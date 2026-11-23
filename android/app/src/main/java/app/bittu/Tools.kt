package app.bittu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Everything Bittu can do on the phone when asked by voice. This runs in the always-alive part of the app,
 * not in the screen, so it works while another app is in front. Every answer says what really happened.
 */
object Tools {
    private const val NO_HELPER = "My helper is not running and I could not start it just now. It needs Wi-Fi for a moment to start; after that it works without Wi-Fi."
    @Volatile private var app: Context? = null

    /** True when the helper is there. If it has stopped, Bittu starts it by itself first (a few seconds) instead of giving up. */
    private fun ready(): Boolean {
        if (ShellLink.connected) return true
        val c = app ?: return false
        ShellLink.init(c)
        ShellLink.connect()
        Thread.sleep(600)
        if (ShellLink.connected) return true
        return AdbStarter.start(c) == "running"
    }

    private fun sh(cmd: String): String = ShellLink.requestSync("sh", JSONObject().put("cmd", cmd), 9000).optString("out").trim()
    private fun prefs(c: Context) = c.getSharedPreferences("bittu", Context.MODE_PRIVATE)
    private fun did(icon: String, text: String) { TalkService.sink?.invoke(JSONObject().put("ev", "did").put("icon", icon).put("text", text)) }

    fun run(c: Context, name: String, a: JSONObject): String = try {
        app = c.applicationContext
        when (name) {
            "phone_switch" -> switch(c, a.optString("name"), a.optBoolean("on"))
            "set_mode" -> setMode(c, a.optString("mode"))
            "call_person" -> call(c, a.optString("name"))
            "send_message" -> message(c, a.optString("app"), a.optString("name"), a.optString("text"))
            "open_app" -> open(c, a.optString("name"))
            "my_location" -> Where.now(c)
            "remember" -> if (Agent.remembering) Memory.remember(c, a.optString("fact")) else "Remembered."
            "recall" -> Memory.recall(c, a.optString("about"))
            "forget" -> Memory.forget(c, a.optString("about"))
            "phone_info" -> Device.details(c)
            "phone_command" -> if (!ready()) "The helper is not running, so commands cannot be run right now." else sh(a.optString("command")).take(6000).ifEmpty { "Done. The command printed nothing." }
            "set_level" -> level(a.optString("what"), a.optDouble("percent", 50.0).toInt().coerceIn(0, 100))
            "developer_option" -> developer(a.optString("name"), a.optBoolean("on"))
            "read_notifications" -> Notifications.instance?.list(a.optString("app")) ?: "Bittu is not allowed to see notifications yet. The owner must switch on notification access for Bittu in the phone's settings."
            "reply_to_notification" -> Notifications.instance?.reply(a.optInt("number"), a.optString("text"))?.also { did("msg", "Replied to a notification") } ?: "Bittu is not allowed to see notifications yet."
            "read_screen" -> ScreenControl.instance?.let { screen ->
                // An app that has only just opened may have nothing on screen for a moment, so look again a few times.
                var text = screen.read()
                repeat(4) { if (text.lines().count { it.isNotBlank() } < 2) { Thread.sleep(500); text = screen.read() } }
                text.ifBlank { "Nothing readable is on screen." }
            } ?: "Screen control is switched off, so the screen cannot be read."
            "tap_on" -> ScreenControl.instance?.let { if (it.tap(a.optString("words"), 1500)) "Tapped \"${a.optString("words")}\"." else "Nothing with the words \"${a.optString("words")}\" was found on screen." } ?: "Screen control is switched off."
            "type_text" -> ScreenControl.instance?.let { if (it.type(a.optString("text"))) "Typed it." else "No text box is open on screen." } ?: "Screen control is switched off."
            "press_button" -> ScreenControl.instance?.let { if (it.press(a.optString("button"))) "Pressed ${a.optString("button")}." else "That button could not be pressed." } ?: "Screen control is switched off."
            else -> "That action does not exist."
        }
    } catch (e: Exception) { "It failed: ${e.message ?: e}" }

    fun torch(c: Context, on: Boolean): Boolean = try {
        val cm = c.getSystemService(CameraManager::class.java)
        cm.setTorchMode(cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }, on); true
    } catch (e: Exception) { false }

    private fun switch(c: Context, name: String, on: Boolean): String {
        val word = if (on) "on" else "off"
        if (name == "torch") return if (torch(c, on)) "The torch is $word.".also { did("torch", "Turned the torch $word") } else "The torch could not be changed."
        if (!ready()) return NO_HELPER
        val (cmd, check, label) = when (name) {
            "wifi" -> Triple("svc wifi ${if (on) "enable" else "disable"}", "settings get global wifi_on", "Wi-Fi")
            "bluetooth" -> Triple("svc bluetooth ${if (on) "enable" else "disable"}", "settings get global bluetooth_on", "Bluetooth")
            "mobile_data" -> Triple("svc data ${if (on) "enable" else "disable"}", "settings get global mobile_data", "Mobile data")
            "silent" -> Triple("cmd notification set_dnd ${if (on) "priority" else "off"}", "settings get global zen_mode", "Silent mode")
            "hotspot" -> { sh("am start -a android.settings.TETHER_SETTINGS"); return "The hotspot page is open. The owner has to tap the switch; Bittu cannot flip it." }
            else -> return "There is no switch called $name."
        }
        sh(cmd)
        Thread.sleep(1200)
        val now = sh(check) != "0" // read it back from the phone instead of assuming
        did(if (name == "mobile_data") "globe" else if (name == "silent") "bell" else if (name == "bluetooth") "bt" else "wifi", "Turned $label $word")
        return if (now == on) "$label is $word now." else "I asked for $label to turn $word, but the phone still shows it ${if (now) "on" else "off"}."
    }

    /** The modes the owner made in the app: id, name, what to tell callers, and whether to answer or decline. */
    fun myModes(c: Context): List<JSONObject> = runCatching { JSONObject(prefs(c).getString("config", "{}") ?: "{}").optJSONArray("myModes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrNull() ?: emptyList()

    private fun setMode(c: Context, asked: String): String {
        val wanted = asked.lowercase().trim().removeSuffix(" mode").trim()
        val mine = myModes(c).firstOrNull { it.optString("id") == asked || it.optString("name").lowercase() == wanted }
        val mode = mine?.optString("id") ?: wanted
        if (mine == null && mode !in setOf("normal", "driving", "meeting", "sleep", "strangers", "off")) return "There is no mode called $asked." + myModes(c).joinToString(", ") { it.optString("name") }.let { if (it.isNotEmpty()) " The owner's own modes are: $it." else "" }
        val p = prefs(c)
        val cfg = JSONObject(p.getString("config", "{}") ?: "{}").put("mode", mode).put("until", if (mode == "normal" || mode == "off") "" else "Until I turn it off")
            .put("modeSay", mine?.optString("say") ?: "").put("modeAct", mine?.optString("act") ?: "").put("modeEnds", 0)
        p.edit().putString("config", cfg.toString()).apply()
        ShellLink.pushConfig()
        TalkService.sink?.invoke(JSONObject().put("ev", "mode").put("mode", mode).put("until", cfg.optString("until")))
        return "The mode is now ${mine?.optString("name") ?: mode}." + if (!ShellLink.connected && mode != "normal" && mode != "off") " But the helper is not running, so calls will not be answered until it is started." else ""
    }

    /** A saved person first, then the phone's contacts. Returns name and number, or null. */
    /** A name as people say it: lower case, no emoji or dots, no "garu" or "ji", no "my". */
    private fun plain(name: String): String = name.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
        .split(' ').filter { it.isNotBlank() && it !in setOf("garu", "gaaru", "garoo", "ji", "sir", "madam", "mam", "my", "the", "to") }.joinToString(" ")

    /** Other words people use for the same person, tried when the first word finds nobody. */
    private val alsoCalled = mapOf("mom" to "amma", "mother" to "amma", "mummy" to "amma", "mum" to "amma", "dad" to "nanna", "father" to "nanna", "daddy" to "nanna", "sister" to "akka", "brother" to "anna")

    /**
     * Finds who the owner means. Looked up in this order: the family list, Bittu's own people, then the phone's contacts.
     * Contact names are compared the way they sound, so "Amma" finds a contact saved as "Amma" with a heart after it,
     * and "Amma garu" finds the same person.
     */
    private fun person(c: Context, name: String): Pair<String, String>? {
        val first = plain(name)
        if (first.isEmpty()) return null
        // "Mom" means the owner's own mother first (saved as Amma), and only then any contact with "mom" in the name.
        for (q in listOfNotNull(alsoCalled[first], first).distinct()) find(c, q)?.let { return it }
        return null
    }

    private fun find(c: Context, q: String): Pair<String, String>? {
        val cfg = JSONObject(prefs(c).getString("config", "{}") ?: "{}")
        // The family list: "Amma 9000000001, Bava 9000000002"
        for (part in cfg.optString("familyNumbers").split(',', ';')) {
            val label = plain(part.filter { it.isLetter() || it == ' ' }); val number = part.filter { it.isDigit() || it == '+' }
            if (label == q && number.length >= 7) return part.filter { it.isLetter() || it == ' ' }.trim() to number
        }
        val people = cfg.optJSONArray("people")
        if (people != null) for (pass in 0..1) for (i in 0 until people.length()) {
            val p = people.getJSONObject(i); val n = plain(p.optString("name"))
            if (p.optString("number").isNotEmpty() && (if (pass == 0) n == q else n.contains(q))) return p.optString("name") to p.optString("number")
        }
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val words = q.split(' ')
        var best: Pair<String, String>? = null; var bestScore = 0
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            while (it.moveToNext()) {
                val shown = it.getString(0) ?: continue; val number = it.getString(1) ?: continue
                val n = plain(shown); if (n.isEmpty()) continue
                val nameWords = n.split(' ')
                // An exact name beats a name that starts with it, which beats one that only contains the words. Shorter names win ties.
                val score = when {
                    n == q -> 1000
                    n.startsWith("$q ") -> 800
                    words.all { w -> w in nameWords } -> 700
                    nameWords.any { w -> w == q } -> 600
                    n.contains(q) -> 400
                    else -> 0
                } - minOf(n.length, 99)
                if (score > 300 && score > bestScore) { bestScore = score; best = shown to number }
            }
        }
        return best
    }

    /** Names that sound close, so Bittu can ask "did you mean ..." instead of only saying no. */
    private fun nearby(c: Context, name: String): String {
        val q = plain(name); if (q.length < 2 || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val hits = LinkedHashSet<String>()
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext() && hits.size < 5) { val shown = it.getString(0) ?: continue; val n = plain(shown); if (n.isNotEmpty() && (n.take(3) == q.take(3) || q.split(' ').any { w -> w.length > 2 && n.contains(w) })) hits.add(n) }
        }
        return if (hits.isEmpty()) "" else " Close names in the contacts: ${hits.joinToString(", ")}."
    }

    /** For checking the lookup without calling or sending anything: the name found and the number's last two digits. */
    fun who(c: Context, name: String): String = person(c, name)?.let { it.first + " (number ends in " + it.second.filter(Char::isDigit).takeLast(2) + ")" } ?: ("nobody." + nearby(c, name))

    private fun call(c: Context, name: String): String {
        val p = person(c, name) ?: return "Nobody called $name is in the contacts." + nearby(c, name)
        if (!ready()) return NO_HELPER
        sh("am start -a android.intent.action.CALL -d tel:${p.second.filter { it.isDigit() || it == '+' }}")
        Thread.sleep(1500)
        val state = Regex("mCallState=(\\d)").findAll(sh("dumpsys telephony.registry | grep mCallState=")).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        did("phone", "Called ${p.first}")
        return if (state == 2) "Calling ${p.first} now." else "I tried to call ${p.first}, but the phone did not start the call."
    }

    private fun message(c: Context, app: String, name: String, text: String): String {
        val p = person(c, name) ?: return "Nobody called $name is in the contacts." + nearby(c, name)
        if (text.isBlank()) return "There is no message text. Ask the owner what to say."
        if (app.lowercase() == "sms") {
            if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "Bittu is not allowed to send texts yet. The owner must allow SMS in the app."
            val m = c.getSystemService(SmsManager::class.java)
            m.sendMultipartTextMessage(p.second, null, m.divideMessage(text), null, null)
            did("msg", "Sent a text to ${p.first}")
            return "The text to ${p.first} was handed to the phone for sending."
        }
        if (!ready()) return NO_HELPER
        val d = p.second.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
        sh("am start -a android.intent.action.VIEW -d 'https://wa.me/$d?text=${URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("'", "%27")}'")
        val screen = ScreenControl.instance ?: return "WhatsApp is open on ${p.first}'s chat with the message typed, but screen control is off, so the owner has to tap send."
        if (!screen.tap("Send", 8000)) return "WhatsApp is open on ${p.first}'s chat with the message typed, but the send button was not found. The owner has to tap send."
        Thread.sleep(700)
        did("msg", "Sent a WhatsApp message to ${p.first}")
        return "Sent to ${p.first} on WhatsApp."
    }

    /** "package/activity|Label" for the app people call by this name, or "". */
    fun findApp(c: Context, name: String): String {
        val want = name.trim().lowercase().removePrefix("the ").removeSuffix(" app").trim()
        if (want.isEmpty()) return ""
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
        val squash = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val w = squash(want)
        val hit = apps.firstOrNull { squash(it.first) == w } ?: apps.filter { squash(it.first).startsWith(w) }.minByOrNull { it.first.length }
            ?: apps.filter { squash(it.first).contains(w) }.minByOrNull { it.first.length } ?: apps.firstOrNull { it.second.lowercase().split('.').contains(w) } ?: return ""
        return "${hit.second}/${hit.third}|${hit.first}"
    }

    private fun open(c: Context, name: String): String {
        val found = findApp(c, name)
        if (found.isEmpty()) return "There is no app called $name on this phone."
        if (!ready()) return NO_HELPER
        val (component, label) = found.split('|')
        sh("am start -n '$component'")
        repeat(6) {
            Thread.sleep(500)
            val top = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
            if (top.contains(component.substringBefore('/'))) { did("eye", "Opened $label"); return "$label is open on the screen now." }
            // HyperOS App lock stands in front of locked apps until the owner unlocks with a fingerprint or PIN.
            if (top.contains("applicationlock", true) || top.contains("ConfirmAccess")) {
                // The phone's App lock is asking for a fingerprint. Wait a while for the owner to touch the sensor, then carry on.
                repeat(40) {
                    Thread.sleep(500)
                    if (sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").contains(component.substringBefore('/'))) { did("eye", "Opened $label"); return "$label is open on the screen now, after the owner unlocked it." }
                }
                return "$label is protected by the phone's App lock, and it is still waiting for the owner's fingerprint or PIN. Bittu cannot unlock it. Tell the owner to unlock it, or to remove $label from App lock in the phone's Security settings so Bittu can open it directly."
            }
        }
        return "I tried to open $label, but it did not come to the front."
    }

    private fun level(what: String, percent: Int): String {
        if (what != "volume" && !ready()) return NO_HELPER
        return when (what) {
            "brightness" -> { sh("settings put system screen_brightness_mode 0; cmd display set-brightness ${"%.2f".format(java.util.Locale.US, percent / 100.0)}"); "Brightness is set to $percent percent." }
            "volume" -> {
                // Set through Android's own volume control, which this phone obeys, and read back to be sure.
                val am = (app ?: return "Volume could not be set.").getSystemService(android.media.AudioManager::class.java)
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, percent * max / 100, 0)
                val now = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (Math.abs(now - percent * max / 100) <= 1) "Volume is at $percent percent." else "I asked for $percent percent, but the phone shows ${now * 100 / max} percent."
            }
            else -> "Only brightness and volume can be set."
        }
    }

    private fun developer(name: String, on: Boolean): String {
        if (!ready()) return NO_HELPER
        val v = if (on) 1 else 0
        val cmd = when (name) {
            "developer_options" -> "settings put global development_settings_enabled $v"
            "usb_debugging" -> "settings put global adb_enabled $v"
            "wireless_debugging" -> "settings put global adb_wifi_enabled $v"
            "stay_awake" -> "settings put global stay_on_while_plugged_in ${if (on) 3 else 0}"
            "show_taps" -> "settings put system show_touches $v"
            "layout_bounds" -> "setprop debug.layout $on; service call activity 1599295570 >/dev/null"
            else -> return "There is no developer option called $name."
        }
        sh(cmd)
        did("code", "Turned ${name.replace('_', ' ')} ${if (on) "on" else "off"}")
        return "${name.replace('_', ' ')} is ${if (on) "on" else "off"} now."
    }
}
