package app.bittu

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Everything the interface can ask the phone to do. Each method is called from the web page. */
class Bridge(private val activity: Activity, private val send: (String, String) -> Unit) {
    private val perms = mapOf(
        "mic" to arrayOf(Manifest.permission.RECORD_AUDIO),
        "contacts" to arrayOf(Manifest.permission.READ_CONTACTS),
        "notif" to arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        "location" to arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "phone" to arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE),
        "sms" to arrayOf(Manifest.permission.SEND_SMS),
    )
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    init { TalkService.sink = { ev -> event(ev) } }

    private fun has(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun event(json: JSONObject) = send("bittuEvent", json.toString())

    @JavascriptInterface
    fun version(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    /** What is really allowed on this phone right now. */
    @JavascriptInterface
    fun status(): String {
        val o = JSONObject()
        for ((k, v) in perms) o.put(k, has(v[0]))
        val services = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        o.put("access", services.contains(activity.packageName + "/"))
        o.put("helper", ShellLink.connected)
        o.put("paired", AdbStarter.paired(activity))
        o.put("notifAccess", (Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: "").contains(activity.packageName + "/"))
        val apk = activity.applicationInfo.sourceDir
        o.put("startCommand", "adb shell \"CLASSPATH=$apk setsid app_process /system/bin --nice-name=bittu_helper app.bittu.shell.Daemon >/dev/null 2>&1 &\"")
        return o.toString()
    }

    /** Shows Android's own permission prompt. If the owner already refused twice, Android no longer shows it, so we open the app's settings page. */
    @JavascriptInterface
    fun ask(name: String) = activity.runOnUiThread {
        val list = perms[name] ?: return@runOnUiThread
        if (has(list[0])) { event(JSONObject().put("ev", "perm")); return@runOnUiThread }
        val p = activity.getSharedPreferences("bittu", Context.MODE_PRIVATE)
        if (p.getBoolean("asked_$name", false) && !activity.shouldShowRequestPermissionRationale(list[0])) open("app")
        else { p.edit().putBoolean("asked_$name", true).apply(); activity.requestPermissions(list, 7) }
    }

    @JavascriptInterface
    fun open(which: String) = activity.runOnUiThread {
        val intent = when (which) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
        }
        try { activity.startActivity(intent) } catch (e: Exception) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))) }
        }
    }

    /** A request to the helper. The answer comes back to the page as bittuReply(id, json). */
    @JavascriptInterface
    fun shell(id: Int, op: String, json: String) {
        ShellLink.request(op, runCatching { JSONObject(json) }.getOrDefault(JSONObject())) { res -> send("bittuReply", JSONObject().put("id", id).put("res", res).toString()) }
    }

    /** The interface saves the owner's settings here; they are forwarded to the helper for calls. */
    @JavascriptInterface
    fun config(json: String) {
        activity.getSharedPreferences("bittu", Context.MODE_PRIVATE).edit().putString("config", json).apply()
        ShellLink.pushConfig()
    }

    /** Keys stay in the app's private storage. The page only ever learns whether one is saved. */
    @JavascriptInterface
    fun saveKey(provider: String, key: String) {
        activity.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().apply { if (key.isBlank()) remove(provider) else putString(provider, key.trim()) }.apply()
        ShellLink.pushConfig()
        // A new key means new lists: fetch the model lists that this key unlocks.
        for (kind in when (provider) { "gemini" -> listOf("gemini", "gemini-live"); "elevenlabs" -> listOf("eleven-tts", "eleven-voices"); "fish" -> listOf("fish-voices"); "anthropic", "openai", "openrouter" -> listOf(provider); else -> emptyList() })
            Models.refresh(activity.applicationContext, kind) { event(JSONObject().put("ev", "models").put("kind", kind)) }
    }

    @JavascriptInterface
    fun hasKey(provider: String): Boolean = !activity.getSharedPreferences("keys", Context.MODE_PRIVATE).getString(provider, "").isNullOrEmpty()

    @JavascriptInterface
    fun takeNotes(): String {
        val p = activity.getSharedPreferences("bittu", Context.MODE_PRIVATE)
        val notes = p.getString("pendingNotes", "[]") ?: "[]"
        p.edit().remove("pendingNotes").apply()
        return notes
    }

    @JavascriptInterface
    fun contacts(): String {
        val list = JSONArray()
        if (!has(Manifest.permission.READ_CONTACTS)) return list.toString()
        val seen = HashSet<String>()
        activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")?.use { c ->
            while (c.moveToNext() && list.length() < 2000) {
                val name = c.getString(0) ?: continue
                val number = (c.getString(1) ?: continue).filter { it.isDigit() || it == '+' }
                if (number.length >= 7 && seen.add(name + number.takeLast(10))) list.put(JSONObject().put("name", name).put("number", number))
            }
        }
        return list.toString()
    }

    @JavascriptInterface
    fun torch(on: Boolean): Boolean = try {
        val cm = activity.getSystemService(CameraManager::class.java)
        cm.setTorchMode(cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }, on); true
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun sms(number: String, text: String): Boolean {
        if (!has(Manifest.permission.SEND_SMS)) { ask("sms"); return false }
        return try { val m = activity.getSystemService(SmsManager::class.java); m.sendMultipartTextMessage(number, null, m.divideMessage(text), null, null); true } catch (e: Exception) { false }
    }

    /** Switches the always-on "Bittu" wake word on or off. */
    @JavascriptInterface
    fun wake(on: Boolean) {
        activity.getSharedPreferences("bittu", Context.MODE_PRIVATE).edit().putBoolean("wake", on).apply()
        if (on) { if (!has(Manifest.permission.RECORD_AUDIO)) ask("mic") else WakeService.startIfWanted(activity) }
        else activity.stopService(Intent(activity, WakeService::class.java))
    }

    /** The conversation settings, kept so a conversation can start even when the interface is not open. */
    @JavascriptInterface
    fun talkConfig(json: String) { activity.getSharedPreferences("bittu", Context.MODE_PRIVATE).edit().putString("talkCfg", json).apply() }

    /** What the owner said right after "Bittu", when the wake word had to open the app first. Read once. */
    @JavascriptInterface
    fun wakeText(): String {
        val p = activity.getSharedPreferences("bittu", Context.MODE_PRIVATE)
        val t = p.getString("wakeText", null) ?: return ""
        p.edit().remove("wakeText").apply()
        return t
    }

    /** The brightness and volume sliders. Returns what really happened. */
    @JavascriptInterface
    fun level(what: String, percent: Int): String = Tools.run(activity.applicationContext, "set_level", JSONObject().put("what", what).put("percent", percent))

    private val testBuild get() = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Test builds only: runs one of Bittu's actions directly and returns its plain answer, without any speech. */
    @JavascriptInterface
    fun testTool(name: String, json: String): String = if (testBuild) Tools.run(activity.applicationContext, name, runCatching { JSONObject(json) }.getOrDefault(JSONObject())) else ""

    /** The owner's switch for restarting the helper without Wi-Fi (keeps debugging port 5555 open). */
    @JavascriptInterface
    fun keepPort(on: Boolean) { AdbStarter.setKeepPort(activity.applicationContext, on) }

    @JavascriptInterface
    fun keepPortOn(): Boolean = AdbStarter.keepPort(activity.applicationContext)

    /** Wakes Bittu when a timed mode is due to end, even if the app is closed and the phone asleep. 0 cancels. */
    @JavascriptInterface
    fun modeEnds(at: Double) { Modes.arm(activity.applicationContext, at.toLong()) }

    /** The diary of helper checks and restarts, for the Phone access page. */
    @JavascriptInterface
    fun keeperDiary(): String = Keeper.diary(activity.applicationContext)

    /** A model list for a picker (see Models). Returns what is stored at once; a list older than a day is fetched again in the background. */
    @JavascriptInterface
    fun models(kind: String): String = Models.get(activity.applicationContext, kind) { event(JSONObject().put("ev", "models").put("kind", kind)) }.toString()

    /** Fetches a model list again right now. */
    @JavascriptInterface
    fun modelsRefresh(kind: String) { Models.refresh(activity.applicationContext, kind) { event(JSONObject().put("ev", "models").put("kind", kind)) } }

    /** What Bittu remembers about the owner, for the memory page. */
    @JavascriptInterface
    fun memory(): String = Memory.show(activity.applicationContext).toString()

    /** Erases everything Bittu remembers about the owner. */
    @JavascriptInterface
    fun memoryClear() { Memory.clear(activity.applicationContext) }

    /** Erases one thing. Runs off the screen thread because it may ask the AI to rewrite the profile. */
    @JavascriptInterface
    fun memoryForget(what: String) { Thread { Memory.forget(activity.applicationContext, what) }.start() }

    /** The tasks Bittu has learned, for the Skills page. */
    @JavascriptInterface
    fun skills(): String = Skills.list(activity.applicationContext)

    @JavascriptInterface
    fun skillDelete(id: String) { Skills.delete(activity.applicationContext, id) }

    /** Test builds only: one request through the same stages as a spoken one (fast path, saved skill, AI with recording). */
    @JavascriptInterface
    fun testSay(text: String): String { if (!testBuild) return ""; val c = activity.applicationContext; val t0 = System.currentTimeMillis()
        Quick.tryHandle(c, text)?.let { return "fast path | ${System.currentTimeMillis() - t0} ms | ${it.ifEmpty { "(done)" }}" }
        Skills.tryReplay(c, text)?.let { return "saved skill | ${System.currentTimeMillis() - t0} ms | $it" }
        Agent.reset(); Skills.begin(c, text); val r = Agent.ask(c, text) { }; Skills.end(c, r)
        return "AI | ${System.currentTimeMillis() - t0} ms | $r" }

    /** Test builds only: how alike a spoken name and a name on screen are, 0 to 1. */
    @JavascriptInterface
    fun testAlike(said: String, shown: String): String = if (testBuild) "%.2f".format(Tools.alike(said, shown)) else ""

    /** Test builds only: who a spoken name would reach. Nothing is called or sent. */
    @JavascriptInterface
    fun testWho(name: String): String = if (testBuild) Tools.who(activity.applicationContext, name) else ""

    /** Test builds only: tries the fast path on a sentence. Returns its answer and how long it took, or says the AI would be needed. */
    @JavascriptInterface
    fun testQuick(text: String): String { if (!testBuild) return ""; val t0 = System.currentTimeMillis(); val r = Quick.tryHandle(activity.applicationContext, text); return if (r == null) "NEEDS-AI" else "${System.currentTimeMillis() - t0} ms | ${r.ifEmpty { "(done, nothing said)" }}" }

    /** Test builds only: asks Bittu's brain one thing and returns the words it would say, without saying them. */
    @JavascriptInterface
    fun testThink(text: String): String = if (testBuild) { Agent.reset(); Agent.remembering = false; try { Agent.ask(activity.applicationContext, text) { } } finally { Agent.remembering = true } } else ""

    /** Test builds only: like a real conversation, memory included. keep=true carries on the same conversation. */
    @JavascriptInterface
    fun testChat(text: String, keep: Boolean): String = if (testBuild) { if (!keep) Agent.reset(); Agent.ask(activity.applicationContext, text) { } } else ""

    /** Begins pairing with the phone's Wireless debugging: shows the code notification and opens Developer options. */
    @JavascriptInterface
    fun pairStart() = activity.runOnUiThread {
        if (!has(Manifest.permission.POST_NOTIFICATIONS)) { ask("notif"); return@runOnUiThread }
        AdbStarter.askForCode(activity)
        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    /** Starts the helper through Wireless debugging. The answer returns as bittuReply(id, json). */
    @JavascriptInterface
    fun helperStart(id: Int) {
        kotlin.concurrent.thread { val r = AdbStarter.start(activity.applicationContext); send("bittuReply", JSONObject().put("id", id).put("res", JSONObject().put("ok", r == "running").put("text", r)).toString()) }
    }

    /**
     * Talk to Bittu from the screen: "listen" (hear me now, no name needed), "say" (a typed request), "quiet" (stop).
     * The conversation runs in the always-on service, so it carries on when this screen is closed.
     */
    @JavascriptInterface
    fun assist(action: String, text: String) {
        if (!has(Manifest.permission.RECORD_AUDIO) && action == "listen") { ask("mic"); return }
        runCatching { activity.startForegroundService(Intent(activity, WakeService::class.java).setAction(action).putExtra("text", text)) }
    }

    /** Live voice conversation with the owner (Gemini Live). */
    @JavascriptInterface
    fun talkStart(json: String) {
        if (!has(Manifest.permission.RECORD_AUDIO)) { ask("mic"); event(JSONObject().put("ev", "talk").put("state", "closed").put("error", "Bittu needs the microphone.")); return }
        activity.startForegroundService(android.content.Intent(activity, TalkService::class.java).putExtra("cfg", json))
    }

    /**
     * Practice call: the owner plays the caller and talks out loud to the same Gemini voice, with the same
     * instructions, that answers real calls. Uses the phone's microphone and speaker; nothing is dialled.
     */
    @JavascriptInterface
    fun practiceStart(who: String) {
        val cfg = runCatching { JSONObject(activity.getSharedPreferences("bittu", Context.MODE_PRIVATE).getString("config", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val people = cfg.optJSONArray("people") ?: org.json.JSONArray()
        val person = (0 until people.length()).map { people.getJSONObject(it) }.firstOrNull { it.optString("name").equals(who, true) }
        val prompt = app.bittu.shell.Prompt.forCall(cfg, person?.optString("name") ?: "Unknown number", person) +
            "\n\nThis is a practice call. The owner is playing the caller to hear how you answer. Behave exactly as you would on a real call."
        talkStart(JSONObject().put("prompt", prompt).put("model", cfg.optString("liveModel", "gemini-3.8-live")).put("voice", cfg.optString("liveVoice", "Aoede"))
            .put("hello", "The call has just connected. Greet the caller now.").put("call", true).put("idleSecs", 40).toString())
    }

    @JavascriptInterface
    fun talkStop() { activity.stopService(android.content.Intent(activity, TalkService::class.java)) }

    @JavascriptInterface
    fun toolResult(id: Int, text: String) { TalkService.instance?.talk?.toolResult(id, text) }

    /** One typed or recognised question to the owner's chosen AI service. The answer returns as bittuReply(id, json). */
    @JavascriptInterface
    fun think(id: Int, json: String) {
        kotlin.concurrent.thread { val res = Llm.ask(activity, runCatching { JSONObject(json) }.getOrDefault(JSONObject())); send("bittuReply", JSONObject().put("id", id).put("res", res).toString()) }
    }

    /**
     * Finds an installed app by the name people call it ("Instagram", "Camera", "Play Store").
     * @return "package/activity|Label" for the best match, or "" when nothing fits
     */
    @JavascriptInterface
    fun findApp(name: String): String = Tools.findApp(activity.applicationContext, name) // one search, the same one Bittu uses when it opens an app

    /** Screen control. Each returns false when it is switched off or the thing was not found. */
    @JavascriptInterface
    fun screenTap(words: String, waitMs: Int): Boolean = ScreenControl.instance?.tap(words, waitMs) ?: false

    @JavascriptInterface
    fun screenType(text: String): Boolean = ScreenControl.instance?.type(text) ?: false

    @JavascriptInterface
    fun screenRead(): String = ScreenControl.instance?.read() ?: ""

    @JavascriptInterface
    fun screenPress(what: String): Boolean = ScreenControl.instance?.press(what) ?: false

    /** Listens once with the phone's own recognizer and reports words as they are heard. */
    @JavascriptInterface
    fun listen(language: String) = activity.runOnUiThread {
        if (!has(Manifest.permission.RECORD_AUDIO)) { ask("mic"); event(JSONObject().put("ev", "stt").put("error", "Bittu needs the microphone.")); return@runOnUiThread }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) { event(JSONObject().put("ev", "stt").put("error", "This phone has no speech recognizer switched on.")); return@runOnUiThread }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private var last = "" // the phone sometimes ends with nothing even though it heard words on the way
            private fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            override fun onPartialResults(b: Bundle?) { words(b).takeIf { it.isNotEmpty() }?.let { last = it; event(JSONObject().put("ev", "stt").put("partial", it)) } }
            override fun onResults(b: Bundle?) { event(JSONObject().put("ev", "stt").put("final", words(b).ifEmpty { last })) }
            override fun onError(code: Int) {
                if (last.isNotEmpty()) { event(JSONObject().put("ev", "stt").put("final", last)); return }
                val why = when (code) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."; SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs internet for this language."; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Bittu needs the microphone."; else -> "Listening stopped (code $code)." }
                event(JSONObject().put("ev", "stt").put("error", why))
            }
            override fun onReadyForSpeech(b: Bundle?) { event(JSONObject().put("ev", "stt").put("ready", true)) }
            override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}; override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language))
    }

    @JavascriptInterface
    fun stopListening() = activity.runOnUiThread { recognizer?.cancel() }

    @JavascriptInterface
    fun speak(text: String, language: String) = activity.runOnUiThread {
        fun say() { tts?.language = Locale.forLanguageTag(language); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bittu") }
        if (ttsReady) say() else tts = TextToSpeech(activity) { ok -> if (ok == TextToSpeech.SUCCESS) { ttsReady = true; say() } }
    }

    fun release() { TalkService.sink = null; talkStop(); recognizer?.destroy(); tts?.shutdown() }
}
