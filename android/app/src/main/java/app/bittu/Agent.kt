package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bittu's thinking when the owner talks to it. It sends the owner's words to Claude (the owner's Anthropic key),
 * lets Claude use the phone through tools, and returns the sentence to speak.
 * Calls with other people do not come through here: those use Gemini Live in the helper.
 */
object Agent {
    private val history = ArrayList<JSONObject>()
    private var lastAt = 0L

    private fun tool(name: String, about: String, vararg props: Triple<String, String, String>, required: List<String> = props.map { it.first }): JSONObject {
        val p = JSONObject()
        for ((key, type, what) in props) p.put(key, JSONObject().put("type", type).put("description", what))
        return JSONObject().put("name", name).put("description", about)
            .put("input_schema", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required)).put("additionalProperties", false))
    }

    private val TOOLS: JSONArray by lazy {
        JSONArray()
            .put(tool("phone_switch", "Turn a phone switch on or off.", Triple("name", "string", "one of: wifi, bluetooth, mobile_data, torch, silent, hotspot"), Triple("on", "boolean", "true to turn on")))
            .put(tool("set_mode", "Change how Bittu handles incoming calls.", Triple("mode", "string", "one of: normal, driving, meeting, sleep, strangers, off, or the name of a mode the owner made")))
            .put(tool("call_person", "Place a phone call to a person by name.", Triple("name", "string", "the person's name as the owner said it")))
            .put(tool("send_message", "Send a text message to a person through WhatsApp or SMS.", Triple("app", "string", "whatsapp or sms"), Triple("name", "string", "the person's name"), Triple("text", "string", "the message, in the owner's words")))
            .put(tool("open_app", "Open an installed app by name and bring it to the front.", Triple("name", "string", "the app's name")))
            .put(tool("set_level", "Set screen brightness or media volume.", Triple("what", "string", "brightness or volume"), Triple("percent", "number", "0 to 100")))
            .put(tool("developer_option", "Turn a developer option on or off.", Triple("name", "string", "one of: developer_options, usb_debugging, wireless_debugging, stay_awake, show_taps, layout_bounds"), Triple("on", "boolean", "true to turn on")))
            .put(tool("read_screen", "Read the words and buttons on the phone's screen right now, in whatever app is open. Items marked [tap] can be tapped, [box] can be typed into. Use it before tapping, and again after each step."))
            .put(tool("tap_on", "Tap a button or item on screen by the words shown on it.", Triple("words", "string", "the words on the item, as read_screen showed them")))
            .put(tool("type_text", "Type text into the text box that is open on screen.", Triple("text", "string", "the text to type")))
            .put(tool("press_button", "Press a phone button.", Triple("button", "string", "one of: back, home, recents, notifications")))
            .put(tool("scroll", "Scroll the screen.", Triple("direction", "string", "down or up")))
            .put(tool("phone_info", "This phone's make, model, Android and HyperOS version, battery, storage, memory, internet connection and the time."))
            .put(tool("phone_command", "Run any Android shell command on this phone as the adb shell user and get its output (settings, pm, am, cmd, dumpsys, getprop, input, ls, cat, svc and so on). Use it for anything the other tools cannot do, such as listing installed apps, reading or changing any setting, closing or uninstalling an app, taking a screenshot, or checking what is using the battery.", Triple("command", "string", "the shell command")))
            .put(tool("remember", "Save something about the owner in your long-term memory so you still know it in the coming days and months: who they are, their people, routines, likes, dislikes, plans, worries, how they want you to behave. Use it whenever the owner shares something like that. One fact per call.", Triple("fact", "string", "one short full sentence, for example: The owner's sister is called Priya and lives in Hyderabad.")))
            .put(tool("recall", "Search your long-term memory and your diary of earlier days. Use it when the owner asks what you know or remember, or about something from an earlier day that is not in your notes above.", Triple("about", "string", "key words, a name, or a date such as yesterday or 2026-11-20")))
            .put(tool("forget", "Erase something from your long-term memory because the owner asked you to forget it.", Triple("about", "string", "key words of what to forget")))
            .put(tool("navigate_to", "Start driving directions to a place in Google Maps. One step, no tapping needed.", Triple("place", "string", "the place or address")))
            .put(tool("youtube_search", "Open YouTube on the results for a search. One step, no tapping needed.", Triple("query", "string", "what to look for")))
            .put(tool("set_alarm", "Set an alarm in the phone's clock.", Triple("hour", "number", "0 to 23"), Triple("minute", "number", "0 to 59"), Triple("label", "string", "what the alarm is for, or an empty string"), required = listOf("hour", "minute")))
            .put(tool("set_timer", "Start a countdown timer.", Triple("minutes", "number", "length in minutes; fractions are allowed"), Triple("label", "string", "what the timer is for, or an empty string"), required = listOf("minutes")))
            .put(tool("my_location", "Where the phone is right now: place name and coordinates. Use it for weather, nearby places, distance and 'where am I'."))
            // Anthropic runs this one itself: live information from the internet (weather, news, scores, prices, facts).
            .put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
            .put(tool("read_notifications", "List the phone's current notifications, newest first, with a number for each. Covers WhatsApp, messages, Instagram, Telegram and every other app.", Triple("app", "string", "an app name to filter by, or an empty string for all"), required = emptyList()))
            .put(tool("reply_to_notification", "Reply to a message notification, using its number from read_notifications.", Triple("number", "number", "the notification's number"), Triple("text", "string", "the reply, in the owner's words")))
    }

    @Synchronized fun reset() { history.clear(); earlier = ""; Engines.reset() }

    private var earlier = ""      // a summary of the part of this conversation that no longer fits
    private var reflectedAt = 0L   // the conversation up to this moment has already been written to memory
    private val later = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** The conversation as plain lines, for the memory to read. */
    private fun transcript(items: List<JSONObject>): String = buildString {
        for (m in items) {
            val content = m.opt("content")
            if (m.optString("role") == "user") { if (content is String) append("Owner: ").append(content).append('\n'); continue }
            val blocks = content as? JSONArray ?: continue
            for (i in 0 until blocks.length()) { val b = blocks.getJSONObject(i); when (b.optString("type")) {
                "text" -> if (b.optString("text").isNotBlank()) append("Bittu: ").append(b.optString("text").trim()).append('\n')
                "tool_use" -> if (b.optString("name") !in setOf("remember", "recall", "forget")) append("(Bittu did: ").append(b.optString("name")).append(' ').append(b.optJSONObject("input")?.toString()?.take(120) ?: "").append(")\n")
            } }
        }
    }

    /** Writes the finished conversation into long-term memory, once. */
    @Synchronized private fun settle(c: Context) {
        if (history.isEmpty() || reflectedAt >= lastAt || !remembering) return
        reflectedAt = lastAt
        Memory.reflect(c, (if (earlier.isNotEmpty()) "Earlier: $earlier\n" else "") + transcript(history))
    }

    /** Test runs must not write into the owner's real memory. */
    @Volatile var remembering = true

    /** One plain question to the AI with no tools and no history. Used by the memory. Returns "" on any failure. */
    fun plain(c: Context, system: String, user: String, maxTokens: Int): String = runCatching {
        val cfg = runCatching { JSONObject(c.getSharedPreferences("bittu", Context.MODE_PRIVATE).getString("talkCfg", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val provider = cfg.optString("provider", "anthropic")
        if (provider != "anthropic") {
            // The chosen service answers, in plain text.
            return Llm.ask(c, JSONObject().put("provider", provider).put("model", cfg.optString("model")).put("url", cfg.optString("url")).put("system", system)
                .put("turns", JSONArray().put(JSONObject().put("role", "user").put("text", user)))).optString("text")
        }
        val key = c.getSharedPreferences("keys", Context.MODE_PRIVATE).getString("anthropic", "") ?: ""
        if (key.isEmpty()) return ""
        val res = post(JSONObject().put("model", cfg.optString("model").ifEmpty { "claude-haiku-5-5" }).put("max_tokens", maxTokens).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user))), key, false)
        val content = res.optJSONArray("content") ?: JSONArray()
        (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
    }.getOrDefault("")

    /** The instructions Bittu works under, the same for every AI service. */
    fun system(c: Context, cfg: JSONObject): String = (cfg.optString("prompt", "You are Bittu, a phone assistant.") + "\n\nThis phone: ${Device.summary}. Now: ${Device.now()}. ${Tools.myModes(c).joinToString(", ") { it.optString("name") }.let { if (it.isNotEmpty()) "The owner's own call modes: $it. " else "" }}You have full access to this phone through your tools; for anything they do not cover, use phone_command. Answer fast and short."
                    + "\n\nHOW YOU ARE WITH THE OWNER: you are their own Bittu, a small loyal companion that lives in their phone and grows with them. Talk like a close friend in easy everyday words, warm and a little playful, never like a customer-service bot. Use their name or what they like to be called now and then. Bring up what you remember when it truly fits (how something they mentioned went, their people by name, their habits), the way a friend would. When they tell you anything about themselves, their people, their day, their likes, plans or worries, save it with remember straight away, without announcing it. If they ask what you know about them or about an earlier day, use recall. If they ask you to forget something, use forget. Commands still come first: do the task, then at most one short friendly line. Spoken answers stay short.\n\n" + Memory.inMind(c))

    /** Bittu's own tools, without the internet search that only Anthropic runs on its side. */
    fun specs(): List<JSONObject> = (0 until TOOLS.length()).map { TOOLS.getJSONObject(it) }.filter { it.has("input_schema") }

    /** Carries out one tool. */
    fun runTool(c: Context, name: String, input: JSONObject): String =
        (if (name == "scroll") (ScreenControl.instance?.let { if (it.scroll(input.optString("direction") != "up")) "Scrolled." else "Nothing here can be scrolled." } ?: "Screen control is switched off.")
        else Tools.run(c, name, input)).also { Skills.step(name, input, it) }

    /**
     * @param onTool called with a tool's name each time Bittu acts, so the screen can show progress
     * @return what Bittu should say; "" means say nothing
     */
    @Synchronized
    fun ask(c: Context, text: String, onTool: (String) -> Unit): String {
        val cfg = runCatching { JSONObject(c.getSharedPreferences("bittu", Context.MODE_PRIVATE).getString("talkCfg", "{}") ?: "{}") }.getOrDefault(JSONObject())
        // The owner picks the AI service in Brain and keys. Claude is handled here; the others in Engines.
        val provider = cfg.optString("provider", "anthropic")
        if (provider != "anthropic") return Engines.ask(c, cfg, provider, text, onTool)
        val key = c.getSharedPreferences("keys", Context.MODE_PRIVATE).getString("anthropic", "") ?: ""
        if (key.isEmpty()) return "I need your Anthropic key to think. Add it in Settings, under Brain and keys."
        // A new subject after three quiet minutes: the old talk goes into long-term memory and the answer starts fresh.
        if (System.currentTimeMillis() - lastAt > 180_000) { settle(c); history.clear(); earlier = "" }
        lastAt = System.currentTimeMillis()
        // A long conversation is compacted by itself: the oldest part is summarised and kept as a short note, and
        // what it taught about the owner goes into memory, so the talk can go on without any limit.
        if (history.size > 24) {
            val old = ArrayList<JSONObject>()
            while (history.size > 12) old.add(history.removeAt(0))
            while (history.isNotEmpty() && !(history[0].optString("role") == "user" && history[0].opt("content") is String)) old.add(history.removeAt(0))
            val text = transcript(old)
            if (remembering) Memory.reflect(c, text)
            val before = earlier
            later.execute { plain(c, "Summarise this part of a conversation between a phone's owner and their assistant Bittu in at most five short sentences: what was asked, what was done, what is still open. Reply with the summary only.", (if (before.isNotEmpty()) "Before this: $before\n\n" else "") + text.takeLast(7000), 300).takeIf { it.isNotBlank() }?.let { earlier = it } }
            if (earlier.isEmpty()) earlier = text.takeLast(600) // until the summary arrives
        }
        // If nothing more is said for three minutes, the conversation is written to memory even if Bittu is never asked again.
        val stamp = lastAt
        later.schedule({ if (lastAt == stamp) settle(c) }, 185, java.util.concurrent.TimeUnit.SECONDS)
        history.add(JSONObject().put("role", "user").put("content", text))
        val model = cfg.optString("model").ifEmpty { "claude-haiku-5-5" }
        return try {
            var lean = false // some models refuse the extra settings; the request is then sent again without them
            var noThinking = model !in keepsThinking // the owner wants answers without the reasoning step; dropped only if the model refuses the setting
            repeat(14) {
                val body = JSONObject().put("model", model).put("max_tokens", 2000).put("system", system(c, cfg) + (if (earlier.isNotEmpty()) "\n\nEARLIER IN THIS CONVERSATION: $earlier" else ""))
                    .put("tools", TOOLS).put("messages", JSONArray(history as Collection<*>))
                if (!lean) body.put("output_config", JSONObject().put("effort", "low")).put("fallbacks", "default")
                if (noThinking) body.put("thinking", JSONObject().put("type", "disabled"))
                val res = try { post(body, key, !lean) } catch (e: BadRequest) {
                    android.util.Log.w("BittuAgent", "request refused (thinking off: $noThinking, lean: $lean): ${e.message?.take(200)}")
                    if (noThinking) { noThinking = false; keepsThinking.add(model); body.remove("thinking"); try { post(body, key, !lean) } catch (e2: BadRequest) { if (lean) throw e2; lean = true; post(body.apply { remove("output_config"); remove("fallbacks") }, key, false) } }
                    else { if (lean) throw e; lean = true; post(body.apply { remove("output_config"); remove("fallbacks") }, key, false) }
                }
                val content = res.optJSONArray("content") ?: JSONArray()
                history.add(JSONObject().put("role", "assistant").put("content", content)) // kept exactly as sent back, thinking blocks included
                when (res.optString("stop_reason")) {
                    "tool_use" -> {
                        val results = JSONArray()
                        for (i in 0 until content.length()) {
                            val b = content.getJSONObject(i)
                            if (b.optString("type") != "tool_use") continue
                            onTool(b.optString("name"))
                            val out = if (b.optString("name") == "scroll") (ScreenControl.instance?.let { if (it.scroll(b.optJSONObject("input")?.optString("direction") != "up")) "Scrolled." else "Nothing here can be scrolled." } ?: "Screen control is switched off.")
                                      else Tools.run(c, b.optString("name"), b.optJSONObject("input") ?: JSONObject())
                            Skills.step(b.optString("name"), b.optJSONObject("input") ?: JSONObject(), out)
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", out))
                        }
                        history.add(JSONObject().put("role", "user").put("content", results))
                    }
                    "pause_turn" -> {} // an internet search is still running on Anthropic's side; asking again continues it
                    "refusal" -> { history.clear(); return "I can't help with that one." }
                    else -> return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
                }
            }
            "That needed too many steps, so I stopped."
        } catch (e: Exception) {
            history.clear()
            "My brain did not answer: ${(e.message ?: e.toString()).take(140)}"
        }
    }

    private class BadRequest(message: String) : Exception(message)
    /** Models that refused to have reasoning switched off. They are asked at the lowest effort instead, without trying again each time. */
    private val keepsThinking = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun post(body: JSONObject, key: String, extras: Boolean): JSONObject {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2026-10-21")
        if (extras) c.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode in 200..299) return JSONObject(text)
        val said = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
        if (c.responseCode == 400) throw BadRequest(said)
        throw java.io.IOException(when (c.responseCode) { 401 -> "the Anthropic key was not accepted"; 429 -> "too many requests, try again in a moment"; else -> "error ${c.responseCode}: $said" })
    }
}
