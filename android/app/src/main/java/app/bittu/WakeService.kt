package app.bittu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Bittu's ears and mouth for the owner. It runs all the time, apart from the app's screen:
 *  - waits for the name "Bittu", using the phone's own on-device speech recognizer;
 *  - then takes what the owner says, thinks with Claude (Agent), acts on the phone, and answers out loud;
 *  - keeps listening for a follow-up for a few seconds, so the owner need not say "Bittu" again.
 * It never speaks on its own, except to say a new message has arrived when the owner has left that switched on.
 */
class WakeService : Service() {
    private enum class Mode { NAME, COMMAND } // NAME: only "Bittu ..." counts. COMMAND: whatever is said next is for Bittu.

    private var recognizer: SpeechRecognizer? = null   // set while a listen is going on
    private var engine: SpeechRecognizer? = null       // the one recognizer, reused for every listen
    private var engineOnDevice = false
    private var session = 0
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val speaker by lazy { Speaker(this) }
    private var running = false
    private var busy = false // thinking or speaking: the microphone is not listened to
    private var mode = Mode.NAME
    private var commandUntil = 0L
    private var heard = ""
    private var englishOnly = false
    private var onDevice = true
    private var beepOff = false
    private var errors = 0

    private fun prefs() = getSharedPreferences("bittu", Context.MODE_PRIVATE)
    private fun cfg() = runCatching { JSONObject(prefs().getString("talkCfg", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun tell(ev: JSONObject) { TalkService.sink?.invoke(ev.put("ev", ev.optString("ev", "talk"))) }
    private fun state(s: String) = tell(JSONObject().put("state", s))

    private val wakeUps = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // A cable or charger going in or out restarts the phone's debugging service, which stops the helper. Wait for it to settle.
            val power = intent.action == Intent.ACTION_POWER_CONNECTED || intent.action == Intent.ACTION_POWER_DISCONNECTED
            Keeper.kick(c, when (intent.action) { Intent.ACTION_SCREEN_ON -> "screen on"; Intent.ACTION_USER_PRESENT -> "unlocked"; Intent.ACTION_POWER_CONNECTED -> "power connected"; else -> "power disconnected" }, if (power) 6000 else 0)
            if (!busy && recognizer == null) main.post { listen() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { prefs().edit().putBoolean("wake", false).apply(); stopSelf(); return START_NOT_STICKY }
            "quiet" -> { if (running) { speaker.stop(); busy = false; backToName() }; return START_STICKY }
        }
        if (!running) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("wake", "Listening for \"Bittu\"", NotificationManager.IMPORTANCE_MIN))
            val off = PendingIntent.getService(this, 4, Intent(this, WakeService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "wake").setSmallIcon(R.drawable.ic_bittu).setContentTitle("Say \"Bittu\"").setContentText("Bittu is waiting for its name.")
                .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Turn off", off).build()).build()
            try { startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY } // Android refuses this from the background: it starts next time the app is opened
            running = true
            instance = this
            ShellLink.init(applicationContext)
            ShellLink.connect()
            // Moments when the helper may have just been stopped, or when the owner is about to need it.
            runCatching { registerReceiver(wakeUps, android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }) }
            Keeper.schedule(applicationContext)
            Keeper.kick(applicationContext, "Bittu's listener started", 2000)
            Models.daily(applicationContext) { }
        }
        when (intent?.action) {
            "listen" -> if (!busy) command() // the Talk button: listen now, no name needed
            "say" -> intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { speaker.stop(); handle(it) } // a typed request interrupts whatever Bittu was saying
            else -> if (!busy && recognizer == null) listen()
        }
        return START_STICKY
    }

    /** Lets a phone call or another recorder have the microphone. Listening resumes by itself afterwards. */
    fun pause() = main.post { session++; recognizer?.cancel(); recognizer = null; main.postDelayed({ listen() }, 1500) }

    private fun backToName() {
        mode = Mode.NAME
        state("idle")
        if (!prefs().getBoolean("wake", false)) { stopSelf(); return } // started only for one conversation
        listen()
    }

    private fun command() {
        mode = Mode.COMMAND
        commandUntil = System.currentTimeMillis() + 12_000
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)) } // a small buzz, never a sound
        state("listening")
        listen()
    }

    private fun listen() {
        if (!running || busy) return
        main.removeCallbacksAndMessages(null)
        if (getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) { main.postDelayed({ listen() }, 1500); return } // a phone call is on
        if (TalkService.instance != null) { main.postDelayed({ listen() }, 1500); return } // a practice call is using the microphone
        // The phone's recognizer plays a beep every time it starts and stops. While waiting for the name that would
        // beep all day, so the helper silences that one system component first. Without the helper, Bittu does not
        // wait for its name at all rather than beep.
        // Silencing the recognizer's beep is a phone setting that stays set, so it is done once and remembered. Bittu then
        // keeps listening for its name even while the helper is stopped; before, a stopped helper made Bittu deaf.
        if (!beepOff && prefs().getBoolean("beepOff", false)) beepOff = true
        if (!beepOff && ShellLink.connected) { ShellLink.request("sh", JSONObject().put("cmd", "cmd appops set $RECOGNIZER PLAY_AUDIO ignore")); beepOff = true; prefs().edit().putBoolean("beepOff", true).apply() }
        if (!ShellLink.connected) { ShellLink.connect(); AdbStarter.ensure(this) }
        if (mode == Mode.NAME && !beepOff) { state("idle"); main.postDelayed({ listen() }, 5000); return }
        recognizer?.cancel()
        heard = ""
        // ONE recognizer is kept and reused. Making a new one for every listen left a connection open inside Android each
        // time; after a day there were thousands, Android ran out, and screen control and notification access stopped connecting.
        val wantOnDevice = onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (engine == null || engineOnDevice != wantOnDevice) {
            runCatching { engine?.destroy() }
            engine = if (wantOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            engineOnDevice = wantOnDevice
            android.util.Log.i("BittuWake", "recognizer made, on-device=$wantOnDevice")
        }
        val r = engine!!
        val mine = ++session // a late answer from an earlier listen must not be taken for this one
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            override fun onPartialResults(b: Bundle?) { if (mine != session) return; words(b).takeIf { it.isNotEmpty() }?.let {
                if (it == heard) return@let
                heard = it; if (mode == Mode.COMMAND) tell(JSONObject().put("heard", it).put("whole", true))
                // The recognizer takes a second or two to decide the owner has stopped. Bittu does not wait for it:
                // when the words have not changed for a moment, it acts on them. While waiting for the name this only
                // applies once the name has been heard, so other people's talk never triggers it.
                main.removeCallbacks(settle)
                if (mode == Mode.COMMAND || NAME.containsMatchIn(it.lowercase())) main.postDelayed(settle, 900)
            } }
            override fun onResults(b: Bundle?) { if (mine == session) finished(words(b).ifEmpty { heard }, 300) }
            override fun onError(code: Int) {
                if (mine != session) return
                android.util.Log.i("BittuWake", "recognizer error $code")
                if ((code == 12 || code == 13 || code == 5) && onDevice && errors++ > 2) onDevice = false // the on-device recognizer is not usable here
                if (code == 12 || code == 13) englishOnly = true // this language is not on the phone: fall back to English
                finished(heard, if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == SpeechRecognizer.ERROR_CLIENT) 1500 else 300)
            }
            override fun onReadyForSpeech(b: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onEvent(t: Int, b: Bundle?) {}
        })
        val lang = if (mode == Mode.COMMAND && !englishOnly) cfg().optString("lang", "en-IN") else "en-IN"
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Ask the recognizer to decide sooner that the owner has finished speaking.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L))
    }

    /** The owner's words have stopped changing: act now instead of waiting for the recognizer to finish. */
    private val settle = Runnable { if (running && !busy && recognizer != null && heard.isNotEmpty()) { android.util.Log.i("BittuWake", "acting early on settled words"); finished(heard, 300) } }

    private fun finished(text: String, waitMs: Long) {
        if (!running || busy) return
        main.removeCallbacks(settle)
        session++; recognizer?.cancel(); recognizer = null
        val hit = NAME.find(text.lowercase())
        val afterName = if (hit != null) text.substring(minOf(text.length, hit.range.last + 1)).trim(' ', ',', '.', '!', '?') else ""
        if (mode == Mode.NAME) {
            when {
                hit == null -> main.postDelayed({ listen() }, waitMs)
                afterName.isEmpty() -> command()          // just the name: listen for what comes next, silently
                else -> handle(afterName)                 // "Bittu, open Instagram" in one breath
            }
            return
        }
        val said = (if (hit != null && hit.range.first < 3) afterName else text).trim()
        when {
            said.isNotEmpty() -> handle(said)
            System.currentTimeMillis() > commandUntil -> backToName() // nobody spoke: go back to waiting for the name
            else -> main.postDelayed({ listen() }, waitMs)
        }
    }

    /** The owner said something for Bittu. Think, act, answer, then wait for a follow-up. */
    private fun handle(text: String) {
        busy = true
        session++; recognizer?.cancel(); recognizer = null
        tell(JSONObject().put("heard", text).put("whole", true))
        state("thinking")
        worker.execute {
            // Plain commands are done at once, right here. Only the rest is sent to the AI, which takes a few seconds.
            val t0 = System.currentTimeMillis()
            val reply = Quick.tryHandle(this, text) ?: Agent.ask(this, text) { name -> tell(JSONObject().put("ev", "talk-tool").put("name", name)) }
            android.util.Log.i("BittuWake", "answer ready in ${System.currentTimeMillis() - t0} ms")
            main.post {
                if (!running) return@post
                val silent = reply.isBlank() || reply.trim().trim('(', ')', '.').equals("silent", true)
                if (silent) { answered(); return@post }
                tell(JSONObject().put("said", reply).put("whole", true))
                state("speaking")
                speaker.speak(reply, cfg()) { main.post { answered() } }
            }
        }
    }

    private fun answered() {
        if (!running) return
        busy = false
        mode = Mode.COMMAND
        commandUntil = System.currentTimeMillis() + 12_000 // a follow-up needs no "Bittu"
        state("listening")
        main.postDelayed({ listen() }, 250)
    }

    /** A message arrived from someone. Says who it is from, never the content, and only when nothing else is going on. */
    fun announce(app: String, who: String) = main.post {
        if (!running || busy || mode != Mode.NAME || !cfg().optBoolean("announce", true)) return@post
        if (getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) return@post
        busy = true
        session++; recognizer?.cancel(); recognizer = null
        speaker.speak("New $app message from $who.", cfg()) { main.post { answered() } } // then listens, so "read it" works straight away
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        session++; recognizer = null
        runCatching { engine?.destroy() }; engine = null
        speaker.release()
        runCatching { unregisterReceiver(wakeUps) }
        instance = null
        state("closed")
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: WakeService? = null
        private const val RECOGNIZER = "com.google.android.as" // Android's on-device speech recognizer
        // The recognizer writes the name in many ways, so the common spellings all count.
        private val NAME = Regex("\\b(hey |hi |ok |okay )?(bittu|bitu|bittoo|bitoo|bit too|bit to|bit two|beetu|beetoo|bithu|bitto|bidu|pittu|vittu|bitu's|bittu's)\\b")

        /** Starts listening if the owner switched the wake word on and the microphone is allowed. */
        fun startIfWanted(context: Context) {
            if (!context.getSharedPreferences("bittu", Context.MODE_PRIVATE).getBoolean("wake", false)) return
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { context.startForegroundService(Intent(context, WakeService::class.java)) }
        }
    }
}
