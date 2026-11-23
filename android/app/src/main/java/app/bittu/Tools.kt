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
            "navigate_to" -> Links.navigate(c, a.optString("place"))
            "youtube_search" -> Links.youtube(c, a.optString("query"))
            "set_alarm" -> Links.alarm(c, a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"))
            "set_timer" -> Links.timer(c, (a.optDouble("minutes", 0.0) * 60).toInt(), a.optString("label"))
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
            "tap_on" -> ScreenControl.instance?.let { screen ->
                if (screen.tap(a.optString("words"), 1500)) "Tapped \"${a.optString("words")}\"."
                else screen.tapLike(a.optString("words"), 600)?.let { "Tapped \"$it\", the closest match on screen to \"${a.optString("words")}\"." } ?: "Nothing with the words \"${a.optString("words")}\" was found on screen."
            } ?: "Screen control is switched off."
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

    const val WA_PERSONAL = "com.whatsapp"
    const val WA_BUSINESS = "com.whatsapp.w4b"
    fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    /** "whatsapp business", "business whatsapp", "office whatsapp", "work whatsapp", "wa business" all mean the business app. */
    fun isBusiness(said: String) = Regex("business|office|work|w4b|company|shop").containsMatchIn(said.lowercase())

    /** True when the name reaches someone in the family list, Bittu's people or the contacts. */
    fun knows(c: Context, name: String): Boolean = person(c, name) != null

    /**
     * How alike two names are when spoken, from 0 (nothing alike) to 1 (the same). Used to find "Priya Sharma Intern"
     * on screen when the recognizer wrote "Priya sharma in turn".
     */
    fun alike(said: String, shown: String): Double {
        val a = sound(said); val b = sound(shown)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val whole = 1.0 - distance2(a, b).toDouble() / maxOf(a.length, b.length)
        // Also word by word: how many of the spoken words have a close partner on screen.
        val sw = said.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.map { sound(it) }
        val tw = shown.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.map { sound(it) }
        val words = if (sw.isEmpty() || tw.isEmpty()) 0.0 else sw.count { w -> tw.any { t -> t == w || (w.length > 3 && (t.startsWith(w) || w.startsWith(t))) || distance(w, t) <= 1 } }.toDouble() / sw.size
        return maxOf(whole, words * 0.9)
    }

    /** Spelling distance without the length cut-off that distance() uses for app names. */
    private fun distance2(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = x
            for (y in 1..b.length) cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Runs a command through the helper. Null when the helper cannot be started. */
    fun shell(cmd: String): String? = if (!ready()) null else sh(cmd)

    /** For checking the lookup without calling or sending anything: the name found and the number's last two digits. */
    fun who(c: Context, name: String): String = person(c, name)?.let { it.first + " (number ends in " + it.second.filter(Char::isDigit).takeLast(2) + ")" } ?: ("nobody." + nearby(c, name))

    /** Sends a WhatsApp message by opening the right WhatsApp, finding the chat by name (searching if needed), typing and tapping send. */
    private fun viaChatList(c: Context, app: String, name: String, text: String): String {
        if (!ready()) return NO_HELPER
        val screen = ScreenControl.instance ?: return "Nobody called $name is in the contacts, and screen control is off, so the chat list cannot be searched." + nearby(c, name)
        val business = isBusiness(app)
        val pkg = if (business) WA_BUSINESS else WA_PERSONAL
        val label = if (business) "WhatsApp Business" else "WhatsApp"
        if (!installed(c, pkg)) return "$label is not installed on this phone."
        if (screen.front() != pkg) { val main = apps(c).firstOrNull { it.second == pkg } ?: return "$label could not be opened."; sh("am start -n '${main.second}/${main.third}'"); Thread.sleep(1500) }
        // In the chat list already? Tap the chat whose name sounds like what was said.
        var opened = screen.tapLike(name, 2500)
        if (opened == null) {
            // Not on screen: use WhatsApp's own search.
            if (screen.tap("Search", 2500) || screen.tap("Ask Meta AI or Search", 800)) { Thread.sleep(500); screen.type(name); Thread.sleep(1500); opened = screen.tapLike(name, 2500) }
        }
        if (opened == null) return "Nobody called $name is in the contacts, and no chat with a name like that was found in $label." + nearby(c, name)
        Thread.sleep(900)
        var typed = false
        repeat(8) { if (!typed) { typed = screen.type(text); if (!typed) Thread.sleep(300) } }
        if (!typed) return "The chat \"$opened\" is open in $label, but its message box was not found. Nothing was sent."
        if (!screen.tap("Send", 4000)) return "The message is typed in the chat \"$opened\" in $label, but the send button was not found. The owner has to tap send."
        Thread.sleep(600)
        did("msg", "Sent a $label message to $opened")
        return "Sent to $opened on $label."
    }

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
        if (text.isBlank()) return "There is no message text. Ask the owner what to say."
        // Not in the phone's contacts. On WhatsApp the person may still be in the chat list (a customer, a group, a number
        // saved only there), so look for the chat itself before giving up.
        val p = person(c, name) ?: return if (app.lowercase() == "sms") "Nobody called $name is in the contacts." + nearby(c, name) else viaChatList(c, app, name, text)
        if (app.lowercase() == "sms") {
            if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "Bittu is not allowed to send texts yet. The owner must allow SMS in the app."
            val m = c.getSystemService(SmsManager::class.java)
            m.sendMultipartTextMessage(p.second, null, m.divideMessage(text), null, null)
            did("msg", "Sent a text to ${p.first}")
            return "The text to ${p.first} was handed to the phone for sending."
        }
        if (!ready()) return NO_HELPER
        // The owner may have two WhatsApps: the ordinary one for personal chats and WhatsApp Business for the office.
        val business = isBusiness(app)
        val pkg = if (business) WA_BUSINESS else WA_PERSONAL
        val label = if (business) "WhatsApp Business" else "WhatsApp"
        if (!installed(c, pkg)) return "$label is not installed on this phone." + if (installed(c, if (business) WA_PERSONAL else WA_BUSINESS)) " The other WhatsApp is; say which one to use." else ""
        val d = p.second.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
        sh("am start -a android.intent.action.VIEW -d 'https://wa.me/$d?text=${URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("'", "%27")}' -p $pkg")
        val screen = ScreenControl.instance ?: return "$label is open on ${p.first}'s chat with the message typed, but screen control is off, so the owner has to tap send."
        if (!screen.tap("Send", 8000)) return "$label is open on ${p.first}'s chat with the message typed, but the send button was not found. The owner has to tap send."
        Thread.sleep(700)
        did("msg", "Sent a $label message to ${p.first}")
        return "Sent to ${p.first} on $label."
    }

    /** "package/activity|Label" for the app people call by this name, or "". */
    /** Every app that has an icon on the phone: label, package, activity. Read once and kept for a minute. */
    private var appList: List<Triple<String, String, String>> = emptyList()
    private var appListAt = 0L
    private fun apps(c: Context): List<Triple<String, String, String>> {
        if (appList.isEmpty() || System.currentTimeMillis() - appListAt > 60_000) {
            val pm = c.packageManager
            appList = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            appListAt = System.currentTimeMillis()
        }
        return appList
    }

    fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** How a word sounds, roughly, so "phone pay" and "PhonePe" or "watsap" and "WhatsApp" meet. */
    fun sound(s: String): String = squash(s).replace("ph", "f").replace("wh", "w").replace("ck", "k").replace("ee", "i").replace("oo", "u").replace("ay", "e").replace("ey", "e")
        .replace("z", "s").replace("c", "k").replace("q", "k").replace("y", "i").fold(StringBuilder()) { b, ch -> if (b.isEmpty() || b.last() != ch) b.append(ch); b }.toString()

    /** The number of single-letter changes between two words. */
    fun distance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 3) return 9
        var prev = IntArray(b.length + 1) { it }
        for (x in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = x
            for (y in 1..b.length) cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Names people say that are not what the app is called on the phone. */
    private val appNicknames = mapOf("googlepay" to "gpay", "gpe" to "gpay", "googlemaps" to "maps", "googlemap" to "maps", "map" to "maps", "files" to "filemanager", "myfiles" to "filemanager",
        "fb" to "facebook", "insta" to "instagram", "yt" to "youtube", "playstore" to "playstore", "googleplay" to "playstore", "watsapp" to "whatsapp", "whatsup" to "whatsapp", "twitter" to "x",
        "message" to "messages", "sms" to "messages", "dialer" to "phone", "call" to "phone", "browser" to "chrome", "google" to "google", "mail" to "gmail", "email" to "gmail", "photo" to "photos", "pictures" to "gallery")

    /**
     * Finds an app by the name the owner said. Tried in this order: the exact name, a nickname, a name that starts with
     * or contains what was said, the package name, and last a name that sounds like it or is one or two letters off
     * (speech recognizers spell app names loosely). Returns "package/activity|label", or "" when nothing is close.
     */
    fun findApp(c: Context, name: String): String {
        // Recognizers write WhatsApp in many ways: "whats app", "what's app", "whats up", "watsapp".
        val want = name.trim().lowercase().replace(Regex("what'?s ?app|whats ?up|wh?ats ?ap+"), "whatsapp").removePrefix("the ").removePrefix("my ").removeSuffix(" app").removeSuffix(" application").trim()
        if (want.isEmpty()) return ""
        val list = apps(c)
        if (want.contains("whatsapp") || Regex("(^| )wa( |$)").containsMatchIn(want)) {
            val pkg = if (isBusiness(want)) WA_BUSINESS else WA_PERSONAL
            list.firstOrNull { it.second == pkg }?.let { return "${it.second}/${it.third}|${it.first}" }
        }
        fun pick(w: String): Triple<String, String, String>? = list.firstOrNull { squash(it.first) == w }
            ?: list.filter { squash(it.first).startsWith(w) }.minByOrNull { it.first.length }
            ?: list.filter { w.length >= 3 && squash(it.first).contains(w) }.minByOrNull { it.first.length }
        val w = squash(want)
        var hit = pick(w) ?: appNicknames[w]?.let { pick(it) }
            // "google maps" for an app labelled "Maps": what was said ends with the app's whole name
            ?: list.filter { squash(it.first).length >= 4 && w.endsWith(squash(it.first)) }.maxByOrNull { it.first.length }
            ?: list.firstOrNull { it.second.lowercase().split('.').let { parts -> parts.last() == w || (parts.size > 1 && parts[1] == w) } }
        if (hit == null && w.length >= 4) {
            // Nothing matched as written. Compare by sound, then by spelling distance, and accept only a clear winner.
            val s = sound(want)
            hit = list.firstOrNull { sound(it.first) == s }
            if (hit == null) {
                // Only names that begin with the same sound are compared: opening the wrong app is worse than asking again.
                val ranked = list.filter { sound(it.first).take(1) == s.take(1) }.map { it to minOf(distance(w, squash(it.first)), distance(s, sound(it.first))) }.sortedBy { it.second }
                val allow = if (w.length <= 6) 1 else 2
                if (ranked.isNotEmpty() && ranked[0].second <= allow && (ranked.size < 2 || ranked[1].second > ranked[0].second)) hit = ranked[0].first
            }
        }
        return hit?.let { "${it.second}/${it.third}|${it.first}" } ?: ""
    }

    /** App names close to what was said, for "did you mean". */
    fun nearApps(c: Context, name: String): String {
        val w = squash(name); if (w.length < 2) return ""
        val s = sound(name)
        val close = apps(c).map { it.first to minOf(distance(w, squash(it.first)), distance(s, sound(it.first))) }.filter { it.second <= 3 || squash(it.first).take(3) == w.take(3) }.sortedBy { it.second }.map { it.first }.distinct().take(5)
        return if (close.isEmpty()) "" else " Apps with close names: ${close.joinToString(", ")}."
    }

    private fun open(c: Context, name: String): String {
        val found = findApp(c, name)
        if (found.isEmpty()) return "There is no app called $name on this phone." + nearApps(c, name)
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
