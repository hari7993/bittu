package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Bittu's thinking for spoken questions. Works with whichever AI service the owner saved a key for. */
object Llm {
    /**
     * @param req provider, model, url (for services that speak the OpenAI format), system, turns [{role: user|assistant, text}]
     * @return {ok, text} or {ok:false, error} in plain words
     */
    fun ask(context: Context, req: JSONObject): JSONObject {
        val provider = req.optString("provider", "gemini")
        val key = context.getSharedPreferences("keys", Context.MODE_PRIVATE).getString(provider, "") ?: ""
        if (key.isEmpty()) return fail("There is no key saved for this AI service. Add one in Brain and keys.")
        val system = req.optString("system")
        val turns = req.optJSONArray("turns") ?: JSONArray()
        return try {
            when (provider) {
                "gemini" -> {
                    val model = req.optString("model").ifEmpty { "gemini-flash-latest" }
                    val contents = JSONArray()
                    for (i in 0 until turns.length()) turns.getJSONObject(i).let { contents.put(JSONObject().put("role", if (it.optString("role") == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", it.optString("text"))))) }
                    val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))).put("contents", contents)
                    val res = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body, mapOf("x-goog-api-key" to key))
                    val parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                    ok((0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") })
                }
                "anthropic" -> {
                    val messages = JSONArray()
                    for (i in 0 until turns.length()) turns.getJSONObject(i).let { messages.put(JSONObject().put("role", it.optString("role")).put("content", it.optString("text"))) }
                    val body = JSONObject().put("model", req.optString("model").ifEmpty { "claude-haiku-5-5" }).put("max_tokens", 500).put("system", system).put("messages", messages)
                    val res = post("https://api.anthropic.com/v1/messages", body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
                    val content = res.getJSONArray("content")
                    ok((0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") })
                }
                else -> { // OpenAI, OpenRouter, Kilo Code and anything else in the OpenAI format
                    val base = req.optString("url").trim().trimEnd('/').ifEmpty { if (provider == "openrouter") "https://openrouter.ai/api/v1" else if (provider == "openai") "https://api.openai.com/v1" else "" }
                    if (base.isEmpty()) return fail("Add the address of this AI service in Brain and keys.")
                    if (req.optString("model").isEmpty()) return fail("Add the model name for this AI service in Brain and keys.")
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    for (i in 0 until turns.length()) turns.getJSONObject(i).let { messages.put(JSONObject().put("role", it.optString("role")).put("content", it.optString("text"))) }
                    val res = post("$base/chat/completions", JSONObject().put("model", req.optString("model")).put("messages", messages), mapOf("Authorization" to "Bearer $key"))
                    ok(res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
                }
            }
        } catch (e: Exception) { fail(e.message ?: e.toString()) }
    }

    private fun ok(text: String) = JSONObject().put("ok", true).put("text", text.trim())
    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why)

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 40000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) {
            val said = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw java.io.IOException("The AI service answered ${c.responseCode}: ${(said ?: text).take(200)}")
        }
        return JSONObject(text)
    }
}
