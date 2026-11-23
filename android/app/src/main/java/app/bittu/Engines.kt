package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The same tool-using assistant as Agent, for the other AI services:
 *   - anything that speaks the OpenAI format: OpenAI, OpenRouter, Kilo Code, and any service the owner gives an address for
 *   - Google Gemini
 * The tools, the instructions, the memory and the way a conversation is kept are shared with Agent; only the wire
 * format differs.
 */
object Engines {
    private val open = ArrayList<JSONObject>()   // the conversation in OpenAI's format
    private val gem = ArrayList<JSONObject>()    // the conversation in Gemini's format
    private val log = ArrayList<String>()        // the same conversation as plain lines, for the memory
    private var lastAt = 0L
    private var settledAt = 0L
    private val later = Executors.newSingleThreadScheduledExecutor()

    @Synchronized fun reset() { open.clear(); gem.clear(); log.clear() }

    /** Writes the finished conversation into long-term memory, once. */
    @Synchronized private fun settle(c: Context) {
        if (log.isEmpty() || settledAt >= lastAt || !Agent.remembering) return
        settledAt = lastAt
        Memory.reflect(c, log.joinToString("\n").takeLast(8000))
    }

    private val names = mapOf("gemini" to "Gemini", "openai" to "OpenAI", "openrouter" to "OpenRouter", "kilo" to "Kilo Code", "other" to "this AI service")

    @Synchronized
    fun ask(c: Context, cfg: JSONObject, provider: String, text: String, onTool: (String) -> Unit): String {
        val key = c.getSharedPreferences("keys", Context.MODE_PRIVATE).getString(provider, "") ?: ""
        if (key.isEmpty()) return "I need your ${names[provider] ?: provider} key to think. Add it in Settings, under Brain and keys."
        // A new subject after three quiet minutes: the old talk goes into long-term memory and the answer starts fresh.
        if (System.currentTimeMillis() - lastAt > 180_000) { settle(c); reset() }
        lastAt = System.currentTimeMillis()
        val stamp = lastAt
        later.schedule({ if (lastAt == stamp) settle(c) }, 185, TimeUnit.SECONDS)
        log.add("Owner: $text")
        val reply = try {
            if (provider == "gemini") gemini(c, cfg, key, text, onTool) else openFormat(c, cfg, provider, key, text, onTool)
        } catch (e: Exception) {
            open.clear(); gem.clear()
            "My brain did not answer: ${(e.message ?: e.toString()).take(140)}"
        }
        if (reply.isNotBlank()) log.add("Bittu: $reply")
        return reply
    }

    /** A long conversation drops its oldest part (after its lessons are saved), keeping a clean start. */
    private fun trim(c: Context, list: ArrayList<JSONObject>, startsTurn: (JSONObject) -> Boolean) {
        if (list.size <= 30) return
        if (Agent.remembering) Memory.reflect(c, log.joinToString("\n").takeLast(8000))
        while (list.size > 14) list.removeAt(0)
        while (list.isNotEmpty() && !startsTurn(list[0])) list.removeAt(0)
        while (log.size > 40) log.removeAt(0)
    }

    private fun act(c: Context, name: String, args: JSONObject, onTool: (String) -> Unit): String {
        onTool(name)
        if (name !in setOf("remember", "recall", "forget")) log.add("(Bittu did: $name ${args.toString().take(120)})")
        return if (name == "web_search") search(c, args.optString("query")) else Agent.runTool(c, name, args)
    }

    private const val SEARCH_ABOUT = "Look something up on the internet: weather, news, scores, prices, facts that change. Returns a short answer with what was found."

    // ---------- OpenAI format ----------
    private fun openFormat(c: Context, cfg: JSONObject, provider: String, key: String, text: String, onTool: (String) -> Unit): String {
        val base = cfg.optString("url").trim().trimEnd('/').ifEmpty { when (provider) { "openrouter" -> "https://openrouter.ai/api/v1"; "openai" -> "https://api.openai.com/v1"; else -> "" } }
        if (base.isEmpty()) return "Add the address of this AI service in Brain and keys."
        val model = cfg.optString("model").trim()
        if (model.isEmpty()) return "Choose a model for ${names[provider] ?: provider} in Brain and keys."
        trim(c, open) { it.optString("role") == "user" }
        open.add(JSONObject().put("role", "user").put("content", text))
        val tools = JSONArray()
        for (s in Agent.specs()) tools.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", s.optString("name")).put("description", s.optString("description")).put("parameters", s.optJSONObject("input_schema"))))
        tools.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search").put("description", SEARCH_ABOUT)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "what to look up"))).put("required", JSONArray().put("query")))))
        val system = JSONObject().put("role", "system").put("content", Agent.system(c, cfg))
        repeat(14) {
            val messages = JSONArray().put(system); for (m in open) messages.put(m)
            val res = post("$base/chat/completions", JSONObject().put("model", model).put("messages", messages).put("tools", tools), mapOf("Authorization" to "Bearer $key"))
            val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw java.io.IOException("the service sent no answer")
            val calls = msg.optJSONArray("tool_calls")
            // Only what the format defines is sent back, so one service's extra fields never upset another.
            val mine = JSONObject().put("role", "assistant").put("content", if (msg.isNull("content")) JSONObject.NULL else msg.optString("content"))
            if (calls != null && calls.length() > 0) mine.put("tool_calls", calls)
            open.add(mine)
            if (calls == null || calls.length() == 0) return msg.optString("content").trim().let { if (it == "null") "" else it }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i); val fn = call.optJSONObject("function") ?: JSONObject()
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                open.add(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", act(c, fn.optString("name"), args, onTool)))
            }
        }
        return "That needed too many steps, so I stopped."
    }

    // ---------- Gemini ----------
    /** Gemini wants its own spelling of a tool's inputs: types in capitals and nothing it does not know. */
    private fun geminiSchema(schema: JSONObject?): JSONObject? {
        val props = schema?.optJSONObject("properties") ?: return null
        if (props.length() == 0) return null
        val out = JSONObject()
        for (k in props.keys()) { val p = props.getJSONObject(k); out.put(k, JSONObject().put("type", p.optString("type", "string").uppercase()).put("description", p.optString("description"))) }
        return JSONObject().put("type", "OBJECT").put("properties", out).put("required", schema.optJSONArray("required") ?: JSONArray())
    }

    private fun gemini(c: Context, cfg: JSONObject, key: String, text: String, onTool: (String) -> Unit): String {
        val model = cfg.optString("model").trim().ifEmpty { "gemini-flash-latest" }
        trim(c, gem) { it.optString("role") == "user" && it.optJSONArray("parts")?.optJSONObject(0)?.has("text") == true }
        gem.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        val decls = JSONArray()
        for (s in Agent.specs()) { val d = JSONObject().put("name", s.optString("name")).put("description", s.optString("description")); geminiSchema(s.optJSONObject("input_schema"))?.let { d.put("parameters", it) }; decls.put(d) }
        decls.put(JSONObject().put("name", "web_search").put("description", SEARCH_ABOUT).put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("query", JSONObject().put("type", "STRING").put("description", "what to look up"))).put("required", JSONArray().put("query"))))
        val system = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Agent.system(c, cfg))))
        repeat(14) {
            val res = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                JSONObject().put("systemInstruction", system).put("contents", JSONArray(gem as Collection<*>)).put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls))), mapOf("x-goog-api-key" to key))
            val cand = res.optJSONArray("candidates")?.optJSONObject(0) ?: throw java.io.IOException("Gemini sent no answer")
            val content = cand.optJSONObject("content") ?: throw java.io.IOException("Gemini stopped without an answer (${cand.optString("finishReason")})")
            if (!content.has("role")) content.put("role", "model")
            gem.add(content) // kept exactly as sent, including the marks Gemini needs back with its tool calls
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val replies = JSONArray()
            for (i in 0 until parts.length()) {
                val call = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
                val out = act(c, call.optString("name"), call.optJSONObject("args") ?: JSONObject(), onTool)
                val answer = JSONObject().put("name", call.optString("name")).put("response", JSONObject().put("result", out))
                if (call.has("id")) answer.put("id", call.optString("id"))
                replies.put(JSONObject().put("functionResponse", answer))
            }
            if (replies.length() == 0) return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.has("text") && !it.optBoolean("thought") }.joinToString(" ") { it.optString("text") }.trim()
            gem.add(JSONObject().put("role", "user").put("parts", replies))
        }
        return "That needed too many steps, so I stopped."
    }

    // ---------- internet search for services that have none of their own ----------
    /** Uses Gemini's Google search when a Gemini key is saved, otherwise Claude's web search with the Anthropic key. */
    fun search(c: Context, query: String): String {
        if (query.isBlank()) return "Nothing to search for."
        val keys = c.getSharedPreferences("keys", Context.MODE_PRIVATE)
        val g = keys.getString("gemini", "") ?: ""; val a = keys.getString("anthropic", "") ?: ""
        if (g.isNotEmpty()) runCatching {
            val res = post("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
                JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Search the web and answer in at most three short sentences, with today's facts: $query")))))
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))), mapOf("x-goog-api-key" to g))
            val parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).joinToString(" ") { parts.getJSONObject(it).optString("text") }.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.take(1500) }
        if (a.isNotEmpty()) runCatching {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Search the web and answer in at most three short sentences, with today's facts: $query"))
            var text = ""
            repeat(3) {
                val res = post("https://api.anthropic.com/v1/messages", JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 600).put("messages", messages)
                    .put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2))), mapOf("x-api-key" to a, "anthropic-version" to "2023-06-01"))
                val content = res.optJSONArray("content") ?: JSONArray()
                text = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
                if (res.optString("stop_reason") != "pause_turn") return@runCatching text
                messages.put(JSONObject().put("role", "assistant").put("content", content))
            }
            text
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.take(1500) }
        return "Internet search is not available: it needs a Gemini or an Anthropic key saved in Brain and keys."
    }

    /** A busy service ("overloaded", "try again") is asked twice more before Bittu gives up. */
    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        var last: java.io.IOException? = null
        repeat(3) { n ->
            try { return once(url, body, headers) } catch (e: java.io.IOException) { last = e; if (e.message?.startsWith("busy") != true) throw e; Thread.sleep(1200L * (n + 1)) }
        }
        throw java.io.IOException("the service is overloaded right now, try again in a minute or pick another model")
    }

    private fun once(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) {
            val said = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw java.io.IOException(when (c.responseCode) { 500, 502, 503, 529 -> "busy ${c.responseCode}"; 401, 403 -> "the key was not accepted"; 429 -> "too many requests or no quota left, try again in a moment"; else -> "error ${c.responseCode}: ${(said ?: text).take(160)}" })
        }
        return JSONObject(text)
    }
}
