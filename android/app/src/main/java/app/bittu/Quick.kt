package app.bittu

import android.content.Context
import org.json.JSONObject

/**
 * The fast path. Plain commands ("open YouTube", "torch on", "volume 40", "go home") are understood right here
 * and done at once, with no AI call. Anything else returns null and goes to Bittu's brain.
 */
object Quick {
    /** What Bittu should say ("" = say nothing, the result is visible on screen), or null when this is not a plain command. */
    fun tryHandle(c: Context, heard: String): String? {
        val t = heard.lowercase().trim().trim('.', '!', '?', ',')
            .replace(Regex("^(hey |hi |ok |okay )?(bittu|bitu|beetu)[, ]*"), "")
            .replace(Regex("^(please |can you |could you |will you |just )+"), "")
            .replace(Regex("( please| now| for me)+$"), "").trim()
        if (t.isEmpty()) return null
        fun run(name: String, args: JSONObject) = Tools.run(c, name, args)
        fun quiet(result: String, okWords: String) = if (result.contains(okWords)) "" else result // success shows on screen; only problems are spoken

        if (t in setOf("stop", "cancel", "never mind", "nevermind", "nothing", "be quiet", "quiet", "shut up", "that's all", "thanks", "thank you")) return ""

        // "open YouTube", "YouTube open", "open up maps", "take me to settings", and the Telugu and Hindi ways: "YouTube open chey", "YouTube kholo"
        (Regex("^(open up|open|launch|start|go to|show me|show|take me to|bring up|run) (the |my )?(.+?)( app| application)?$").find(t)?.groupValues?.get(3)
            ?: Regex("^(the |my )?(.+?)( app)? (open|open chey|open cheyyi|open cheyi|open chei|open karo|open kar|kholo|khol do|theruvu|teruvu|launch|start)$").find(t)?.groupValues?.get(2))?.let { said ->
            val app = said.trim()
            if (app.split(' ').size <= 3 && Tools.findApp(c, app).isNotEmpty()) return quiet(run("open_app", JSONObject().put("name", app)), "is open")
        }

        // ---- people: call and WhatsApp, done directly when the name is someone Bittu can find ----
        (Regex("^(call|ring|phone|dial|make a call to|call to) (.+)$").find(t)?.groupValues?.get(2)
            ?: Regex("^(.+?) (ki|ku|ko|ni) (call|phone|ring) (chey|cheyyi|cheyi|chei|karo|kar|kar do|lagao)$").find(t)?.groupValues?.get(1))?.let { who ->
            val name = who.replace(Regex("( on (the )?(phone|mobile)| now)$"), "").trim()
            if (name.split(' ').size <= 4 && Tools.knows(c, name)) return run("call_person", JSONObject().put("name", name))
        }
        // "send hi to amma on whatsapp", "send a hi message to amma on whatsapp", "whatsapp amma saying I'm late", "message amma that I'm late"
        (Regex("^send (?:a |an )?(.+?)(?: message| msg| text)? to (.+?) (?:on|in|via|through) whatsapp$").find(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: Regex("^(?:send (?:a )?)?(?:whatsapp|whatsapp message|message|text|msg)(?: to)? (.+?) (?:saying|that|as|:) (.+)$").find(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^(?:tell|inform) (.+?) (?:on whatsapp )?(?:that |saying )(.+)$").find(t)?.let { it.groupValues[1] to it.groupValues[2] })?.let { (who, words) ->
            val name = who.replace(Regex("( on whatsapp| in whatsapp)$"), "").trim()
            if (words.isNotBlank() && name.split(' ').size <= 4 && Tools.knows(c, name)) return quiet(run("send_message", JSONObject().put("app", "whatsapp").put("name", name).put("text", words.trim().replaceFirstChar { it.uppercase() })), "Sent to")
        }

        // ---- places and videos: one link, no tapping ----
        Regex("^(navigate to|navigate me to|directions to|get directions to|route to|drive to|take me to|how do i get to) (.+)$").find(t)?.let { m ->
            return quiet(run("navigate_to", JSONObject().put("place", m.groupValues[2].trim())), "are open in Maps")
        }
        (Regex("^(?:play|search|search for|find|show|show me|put on) (.+?) (?:on|in) youtube$").find(t)?.groupValues?.get(1)
            ?: Regex("^youtube (?:lo |me |mein |on )?(.+?) (?:play|search|pettu|chey|chalao|lagao|veyyi)$").find(t)?.groupValues?.get(1)
            ?: Regex("^(?:search youtube for|youtube search) (.+)$").find(t)?.groupValues?.get(1))?.let { q ->
            return quiet(run("youtube_search", JSONObject().put("query", q.trim())), "is showing results")
        }

        // ---- clock ----
        Regex("^(?:set |start |put )?(?:a |an )?timer (?:for |of )?(\\d{1,3}) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").find(t)?.let { m ->
            val n = m.groupValues[1].toInt(); val unit = m.groupValues[2]
            val minutes = when { unit.startsWith("s") -> n / 60.0; unit.startsWith("h") -> n * 60.0; else -> n.toDouble() }
            return run("set_timer", JSONObject().put("minutes", minutes).put("label", ""))
        }
        Regex("^(?:set |put |keep )?(?:an |a )?alarm (?:for |at )?(\\d{1,2})(?:[:. ](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)?$").find(t)?.let { m ->
            var hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].ifEmpty { "0" }.toInt(); val half = m.groupValues[3].replace(".", "")
            if (half == "pm" && hour < 12) hour += 12
            if (half == "am" && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59 && (half.isNotEmpty() || hour > 12 || m.groupValues[2].isNotEmpty())) return run("set_alarm", JSONObject().put("hour", hour).put("minute", minute).put("label", ""))
        }
        Regex("^wake me (?:up )?at (\\d{1,2})(?:[:. ](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)?$").find(t)?.let { m ->
            var hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].ifEmpty { "0" }.toInt(); val half = m.groupValues[3].replace(".", "")
            if (half == "pm" && hour < 12) hour += 12
            if (half == "am" && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59) return run("set_alarm", JSONObject().put("hour", hour).put("minute", minute).put("label", "Wake up"))
        }

        val switches = listOf("wi-fi" to "wifi", "wifi" to "wifi", "wi fi" to "wifi", "bluetooth" to "bluetooth", "mobile data" to "mobile_data", "data" to "mobile_data", "internet" to "mobile_data",
            "torch" to "torch", "flashlight" to "torch", "flash light" to "torch", "flash" to "torch", "silent mode" to "silent", "silent" to "silent", "do not disturb" to "silent", "dnd" to "silent", "hotspot" to "hotspot")
        for ((word, name) in switches) {
            val on = Regex("^(turn on|switch on|enable|start) (the |my )?$word$|^(the |my )?$word on$|^turn (the |my )?$word on$|^switch (the |my )?$word on$").matches(t)
            val off = Regex("^(turn off|switch off|disable|stop) (the |my )?$word$|^(the |my )?$word off$|^turn (the |my )?$word off$|^switch (the |my )?$word off$").matches(t)
            if (on || off) return run("phone_switch", JSONObject().put("name", name).put("on", on))
        }

        Regex("^(set |make |put )?(the )?(volume|brightness)( to| at)? (\\d{1,3})( percent| %)?$").find(t)?.let { m ->
            return run("set_level", JSONObject().put("what", m.groupValues[3]).put("percent", m.groupValues[5].toInt().coerceIn(0, 100)))
        }
        if (t in setOf("mute", "mute the volume", "volume off")) return run("set_level", JSONObject().put("what", "volume").put("percent", 0))
        if (t in setOf("full volume", "max volume", "maximum volume", "volume full")) return run("set_level", JSONObject().put("what", "volume").put("percent", 100))

        when (t) {
            "go home", "home", "home screen", "go to home", "go to home screen", "close this", "close it", "close the app" -> return quiet(run("press_button", JSONObject().put("button", "home")), "Pressed")
            "go back", "back", "previous screen" -> return quiet(run("press_button", JSONObject().put("button", "back")), "Pressed")
            "recent apps", "recents", "show recent apps" -> return quiet(run("press_button", JSONObject().put("button", "recents")), "Pressed")
            "scroll down", "next", "next one", "next reel", "next video", "scroll" -> return if (ScreenControl.instance?.scroll(true) == true) "" else "I can't scroll here."
            "scroll up", "previous", "previous one", "previous reel", "previous video" -> return if (ScreenControl.instance?.scroll(false) == true) "" else "I can't scroll here."
        }

        for (mode in listOf("driving", "meeting", "sleep") + Tools.myModes(c).map { it.optString("name").lowercase() }.filter { it.isNotBlank() }) {
            val rx = Regex.escape(mode)
            if (Regex("^(start |turn on |switch on |enable )?$rx mode( on)?$|^i('m| am) $rx$").matches(t)) return run("set_mode", JSONObject().put("mode", mode))
            if (Regex("^(stop |turn off |switch off |end |disable )$rx mode$|^$rx mode off$").matches(t)) return run("set_mode", JSONObject().put("mode", "normal"))
        }
        return null
    }
}
