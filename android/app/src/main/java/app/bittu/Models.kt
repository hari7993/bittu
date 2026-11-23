package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The lists the model pickers show. Each list comes from the service itself, using the owner's saved key, so a
 * newly released model appears without an app update. Lists are kept on the phone and fetched again once a day.
 *
 * Kinds: anthropic, gemini, gemini-live, openai, openrouter, eleven-tts (ElevenLabs voice models),
 * eleven-voices (ElevenLabs voices), fish-models.
 */
object Models {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun prefs(c: Context) = c.getSharedPreferences("models", Context.MODE_PRIVATE)
    private fun key(c: Context, name: String) = c.getSharedPreferences("keys", Context.MODE_PRIVATE).getString(name, "") ?: ""

    /** What is stored right now: {"at": time, "items": [{"id","name"}], "error": ""}. Starts a refresh when it is older than a day. */
    fun get(c: Context, kind: String, onFresh: () -> Unit): JSONObject {
        val saved = runCatching { JSONObject(prefs(c).getString(kind, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (System.currentTimeMillis() - saved.optLong("at") > DAY) refresh(c, kind, onFresh)
        return saved
    }

    /** Fetches a list now, in the background. Used by the daily check and by the Refresh button. */
    fun refresh(c: Context, kind: String, onFresh: () -> Unit) {
        if (!busy.add(kind)) return
        worker.execute {
            try {
                val items = fetch(c, kind)
                val old = runCatching { JSONObject(prefs(c).getString(kind, "{}") ?: "{}") }.getOrDefault(JSONObject())
                // A failed fetch keeps the old list, so the picker never goes empty because the internet was down.
                val out = if (items != null) JSONObject().put("at", System.currentTimeMillis()).put("items", items).put("error", "")
                          else old.put("at", System.currentTimeMillis() - DAY + 30 * 60 * 1000L).put("error", lastError) // try again in half an hour
                prefs(c).edit().putString(kind, out.toString()).apply()
                if (items != null && items.toString() != old.optJSONArray("items")?.toString()) onFresh()
            } finally { busy.remove(kind) }
        }
    }

    /** Refreshes every list that has a key, when it is due. Called when the app starts and when Bittu's background service starts. */
    fun daily(c: Context, onFresh: () -> Unit) { for (k in listOf("anthropic", "gemini", "gemini-live", "openai", "openrouter", "eleven-tts", "eleven-voices", "fish-voices")) get(c, k, onFresh) }

    @Volatile private var lastError = ""

    private fun http(url: String, headers: Map<String, String>): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (ok) text else { lastError = "the service answered ${c.responseCode}"; null }
    } catch (e: Exception) { lastError = "no connection"; null }

    private fun item(id: String, name: String = id) = JSONObject().put("id", id).put("name", name.ifBlank { id })

    private fun fetch(c: Context, kind: String): JSONArray? {
        val out = JSONArray()
        when (kind) {
            "anthropic" -> {
                val k = key(c, "anthropic"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                val d = JSONObject(http("https://api.anthropic.com/v1/models?limit=100", mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01")) ?: return null).optJSONArray("data") ?: return null
                for (i in 0 until d.length()) d.getJSONObject(i).let { out.put(item(it.optString("id"), it.optString("display_name"))) } // the service lists the newest first
            }
            "gemini", "gemini-live" -> {
                val k = key(c, "gemini"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                val d = JSONObject(http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to k)) ?: return null).optJSONArray("models") ?: return null
                val want = if (kind == "gemini-live") "bidiGenerateContent" else "generateContent"
                val found = ArrayList<JSONObject>()
                for (i in 0 until d.length()) {
                    val m = d.getJSONObject(i)
                    val can = m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == want } } ?: false
                    val id = m.optString("name").removePrefix("models/")
                    if (can && id.startsWith("gemini") && !id.contains("embedding") && !id.contains("image") && !id.contains("tts") && !Regex("robotics|banana|transcribe|translate").containsMatchIn(id)) found.add(item(id, m.optString("displayName")))
                }
                found.sortedByDescending { it.optString("id") }.forEach { out.put(it) } // higher version numbers first
            }
            "openai" -> {
                val k = key(c, "openai"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                val d = JSONObject(http("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $k")) ?: return null).optJSONArray("data") ?: return null
                val found = ArrayList<Pair<Long, JSONObject>>()
                for (i in 0 until d.length()) d.getJSONObject(i).let { m -> val id = m.optString("id"); if (Regex("^(gpt|o\\d|chatgpt)").containsMatchIn(id) && !Regex("audio|realtime|transcribe|tts|image|embedding|search|instruct").containsMatchIn(id)) found.add(m.optLong("created") to item(id)) }
                found.sortedByDescending { it.first }.forEach { out.put(it.second) }
            }
            "openrouter" -> {
                val d = JSONObject(http("https://openrouter.ai/api/v1/models", emptyMap()) ?: return null).optJSONArray("data") ?: return null
                val found = ArrayList<Pair<Long, JSONObject>>()
                for (i in 0 until d.length()) d.getJSONObject(i).let { m -> found.add(m.optLong("created") to item(m.optString("id"), m.optString("name"))) }
                found.sortedByDescending { it.first }.take(150).forEach { out.put(it.second) }
            }
            "eleven-tts" -> {
                val k = key(c, "elevenlabs"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                val d = JSONArray(http("https://api.elevenlabs.io/v1/models", mapOf("xi-api-key" to k)) ?: return null)
                for (i in 0 until d.length()) d.getJSONObject(i).let { if (it.optBoolean("can_do_text_to_speech", true)) out.put(item(it.optString("model_id"), it.optString("name"))) }
            }
            "eleven-voices" -> {
                val k = key(c, "elevenlabs"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                val d = JSONObject(http("https://api.elevenlabs.io/v2/voices?page_size=100", mapOf("xi-api-key" to k)) ?: return null).optJSONArray("voices") ?: return null
                for (i in 0 until d.length()) d.getJSONObject(i).let { v ->
                    val about = v.optJSONObject("labels")?.let { l -> listOf(l.optString("gender"), l.optString("accent")).filter { it.isNotBlank() }.joinToString(", ") }.orEmpty()
                    out.put(item(v.optString("voice_id"), v.optString("name") + if (about.isNotEmpty()) " ($about)" else ""))
                }
            }
            "fish-voices" -> {
                val k = key(c, "fish"); if (k.isEmpty()) { lastError = "no key saved"; return null }
                // The owner's own voices first, then well-known public ones.
                for (q in listOf("self=true&page_size=30", "page_size=40&sort_by=task_count")) {
                    val d = runCatching { JSONObject(http("https://api.fish.audio/model?$q", mapOf("Authorization" to "Bearer $k")) ?: "{}").optJSONArray("items") }.getOrNull() ?: continue
                    for (i in 0 until d.length()) d.getJSONObject(i).let { v ->
                        val langs = v.optJSONArray("languages")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
                        if (v.optString("_id").isNotEmpty() && (0 until out.length()).none { out.getJSONObject(it).optString("id") == v.optString("_id") }) out.put(item(v.optString("_id"), v.optString("title") + if (langs.isNotEmpty()) " ($langs)" else ""))
                    }
                }
            }
            else -> { lastError = "unknown list"; return null }
        }
        if (out.length() == 0) { lastError = "the service sent an empty list"; return null }
        return out
    }
}
