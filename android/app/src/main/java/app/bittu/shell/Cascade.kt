package app.bittu.shell

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Anything that can hold Bittu's side of a call: Gemini Live, or the backup chain below. */
interface Voice {
    val open: Boolean
    fun sendAudio(pcm16k: ByteArray, len: Int)
    fun sendText(text: String)
    fun close()
}

/**
 * The backup for calls, used when Gemini Live cannot be reached. Three separate steps, with whichever keys the owner
 * has saved:
 *   hear   ElevenLabs Scribe, then Fish Audio, then AssemblyAI
 *   think  Claude (the Anthropic key), then Gemini's text model
 *   speak  ElevenLabs, then Fish Audio
 * Each step falls through to the next service when one fails. It is slower than Gemini Live: the caller waits a
 * second or two for each reply, because a sentence has to be finished before it can be understood.
 */
class Cascade(private val cfg: JSONObject, private val system: String, private val events: LiveSession.Events) : Voice {
    @Volatile override var open = false
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<JSONObject>()
    private val speech = ByteArrayOutputStream()
    private val before = ArrayDeque<ByteArray>() // the moment before speech was noticed, so first syllables are kept
    private var talking = false
    private var loudFrames = 0
    private var quietMs = 0
    private var speechMs = 0
    @Volatile private var turn = 0 // goes up when the caller starts speaking, so an answer to older words is dropped

    private fun k(name: String) = cfg.optString(name)
    private val hearWith = listOf("ElevenLabs" to "elevenKey", "Fish Audio" to "fishKey", "AssemblyAI" to "assemblyKey").filter { k(it.second).isNotEmpty() }
    private val thinkWith = listOf("Claude" to "anthropicKey", "Gemini" to "geminiKey").filter { k(it.second).isNotEmpty() }
    private val speakWith = listOf("ElevenLabs" to "elevenKey", "Fish Audio" to "fishKey").filter { k(it.second).isNotEmpty() }

    fun describe() = "hear with ${hearWith.joinToString(" then ") { it.first }}, think with ${thinkWith.joinToString(" then ") { it.first }}, speak with ${speakWith.joinToString(" then ") { it.first }}"

    fun start() {
        missing(cfg)?.let { throw java.io.IOException(it) }
        open = true
    }

    override fun close() { open = false; turn++; worker.shutdownNow() }

    /** The caller's sound, 20 ms at a time. A sentence is cut out by loudness: it ends after 0.8 s of quiet. */
    override fun sendAudio(pcm16k: ByteArray, len: Int) {
        if (!open || len < 2) return
        var sum = 0.0; var i = 0
        while (i < len - 1) { val v = (pcm16k[i].toInt() and 0xFF) or (pcm16k[i + 1].toInt() shl 8); sum += v.toDouble() * v; i += 2 }
        val loud = Math.sqrt(sum / (len / 2)) > 600
        if (!talking) {
            before.addLast(pcm16k.copyOf(len)); if (before.size > 15) before.removeFirst()
            loudFrames = if (loud) loudFrames + 1 else 0
            if (loudFrames >= 5) {
                talking = true; speech.reset(); for (b in before) speech.write(b); before.clear(); quietMs = 0; speechMs = 100
                turn++; events.interrupted() // the caller is speaking: Bittu stops and listens
            }
        } else {
            speech.write(pcm16k, 0, len); speechMs += 20
            quietMs = if (loud) 0 else quietMs + 20
            if (quietMs >= 800 || speechMs >= 15000) {
                talking = false; loudFrames = 0
                val clip = speech.toByteArray(); val mine = turn
                if (speechMs - quietMs >= 300) worker.execute { runCatching { val text = hear(clip); if (text.isNotBlank() && mine == turn) { events.heard(text); answer(text, mine) } } }
            }
        }
    }

    /** An instruction for Bittu that the caller did not say, such as "greet the caller now". */
    override fun sendText(text: String) { if (open) worker.execute { runCatching { answer("(Instruction for you, not spoken by the caller: $text)", turn) } } }

    private fun answer(said: String, mine: Int) {
        history.add(JSONObject().put("role", "user").put("content", said))
        while (history.size > 30) history.removeAt(0)
        val raw = think().ifBlank { "Sorry, I did not catch that. Could you say it again?" }
        history.add(JSONObject().put("role", "assistant").put("content", raw))
        val message = Regex("\\[MESSAGE:(.*?)]", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val end = raw.contains("[END]")
        val alert = Regex("\\[ALERT:(.*?)]", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trim()
        val words = raw.replace(Regex("\\[MESSAGE:.*?]", RegexOption.DOT_MATCHES_ALL), "").replace("[END]", "").replace(Regex("\\[ALERT:.*?]", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("[*_#`]"), "").trim()
        if (message.isNotEmpty()) events.tool("take_message", JSONObject().put("message", message))
        if (alert != null) events.tool("alert_owner", JSONObject().put("reason", alert))
        if (mine != turn || !open) return // the caller started speaking again while Bittu was thinking
        if (words.isNotEmpty()) { events.said(words); speak(words, mine) }
        events.turnDone()
        if (end) events.tool("end_call", JSONObject())
    }

    // ---- hear ----
    private fun hear(pcm: ByteArray): String {
        val wav = wav(pcm)
        for ((name, key) in hearWith) {
            val text = runCatching { when (name) {
                "ElevenLabs" -> JSONObject(multipart("https://api.elevenlabs.io/v1/speech-to-text", mapOf("xi-api-key" to k(key)), mapOf("model_id" to cfg.optString("elevenStt", "scribe_v3")), "file", wav)).optString("text")
                "Fish Audio" -> JSONObject(multipart("https://api.fish.audio/v1/asr", mapOf("Authorization" to "Bearer ${k(key)}", "model" to "transcribe-1-pro"), emptyMap(), "audio", wav)).optString("text")
                // AssemblyAI's fast route knows Hindi and English but not Telugu, so it is tried last.
                else -> JSONObject(multipart("https://sync.assemblyai.com/v1/transcribe", mapOf("Authorization" to k(key), "X-AAI-Model" to "universal-3-6-pro"), emptyMap(), "audio", wav, json = "config" to "{\"language_codes\":[\"en\",\"hi\"]}")).optString("text")
            } }.onFailure { Daemon.note("  backup: hearing with $name failed: ${it.message?.take(120)}") }.getOrNull()
            if (text != null) return text.replace(Regex("<[|][^|>]*[|]>"), "").trim() // Fish Audio marks who is speaking; that mark is not part of the words
        }
        return ""
    }

    // ---- think ----
    private fun think(): String {
        val rules = system + "\n\nYou are speaking through a text-to-speech voice. Reply with only the words to say out loud: no markdown, no lists, no stage directions. One or two short sentences. If the call should end after this reply, put [END] at the very end. If the caller left a message for the owner, add [MESSAGE: the message in one sentence] at the end. To buzz the owner's phone so they can take over, add [ALERT: why] at the end."
        for ((name, key) in thinkWith) {
            val text = runCatching { if (name == "Claude") claude(rules, k(key)) else LiveSession.ask(k(key), cfg.optString("textModel", "gemini-flash-latest"),
                rules + "\n\nThe call so far:\n" + history.joinToString("\n") { (if (it.optString("role") == "user") "Caller: " else "You: ") + it.optString("content") } + "\n\nYour next reply:") }
                .onFailure { Daemon.note("  backup: thinking with $name failed: ${it.message?.take(120)}") }.getOrNull()
            if (!text.isNullOrBlank()) return text.trim()
        }
        return ""
    }

    private fun claude(rules: String, key: String): String {
        val body = JSONObject().put("model", cfg.optString("backupModel").ifEmpty { "claude-haiku-5-5" }).put("max_tokens", 300).put("system", rules)
            .put("messages", JSONArray(history as Collection<*>)).put("thinking", JSONObject().put("type", "disabled"))
        fun post(b: JSONObject): Pair<Int, String> {
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 20000; c.doOutput = true
            c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(b.toString().toByteArray()) }
            return c.responseCode to ((if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
        }
        var (code, text) = post(body)
        if (code == 400) post(body.apply { remove("thinking") }).let { code = it.first; text = it.second } // a model that cannot switch reasoning off
        if (code !in 200..299) throw java.io.IOException("Anthropic answered $code")
        val content = JSONObject(text).optJSONArray("content") ?: JSONArray()
        return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }
    }

    // ---- speak ----
    private fun speak(words: String, mine: Int) {
        for ((name, key) in speakWith) {
            val ok = runCatching {
                val c = if (name == "ElevenLabs") {
                    val voice = cfg.optString("elevenVoice").trim().ifEmpty { "21m00Tcm4TlvDq8ikWAM" }
                    (URL("https://api.elevenlabs.io/v1/text-to-speech/$voice/stream?output_format=pcm_16000").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 6000; readTimeout = 20000; doOutput = true
                        setRequestProperty("content-type", "application/json"); setRequestProperty("xi-api-key", k(key))
                        outputStream.use { it.write(JSONObject().put("text", words).put("model_id", cfg.optString("elevenModel").ifEmpty { "eleven_flash_v2_5" }).toString().toByteArray()) }
                    }
                } else {
                    (URL("https://api.fish.audio/v1/tts").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 6000; readTimeout = 20000; doOutput = true
                        setRequestProperty("content-type", "application/json"); setRequestProperty("Authorization", "Bearer ${k(key)}"); setRequestProperty("model", cfg.optString("fishModel").ifEmpty { "s2.1-pro-free" })
                        val body = JSONObject().put("text", words).put("format", "pcm").put("sample_rate", 16000).put("latency", "low")
                        cfg.optString("fishVoice").trim().takeIf { it.isNotEmpty() }?.let { body.put("reference_id", it) }
                        outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                }
                if (c.responseCode !in 200..299) throw java.io.IOException("$name answered ${c.responseCode}")
                // The voice is passed on as it arrives, so the caller hears the start of the sentence without waiting for the end.
                c.inputStream.use { input ->
                    val buf = ByteArray(3200); var kept = 0
                    while (mine == turn && open) {
                        val n = input.read(buf, kept, buf.size - kept); if (n < 0) break
                        val have = kept + n; val even = have - have % 2
                        if (even > 0) events.audio16k(buf.copyOf(even))
                        kept = have - even; if (kept == 1) buf[0] = buf[even]
                    }
                }
                true
            }.onFailure { Daemon.note("  backup: speaking with $name failed: ${it.message?.take(120)}") }.getOrDefault(false)
            if (ok) return
        }
    }

    // ---- small helpers ----
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(16000); i32(32000); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun multipart(url: String, headers: Map<String, String>, fields: Map<String, String>, fileField: String, wav: ByteArray, json: Pair<String, String>? = null): String {
        val edge = "bittu" + System.nanoTime()
        val body = ByteArrayOutputStream()
        for ((name, value) in fields) body.write("--$edge\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        json?.let { body.write("--$edge\r\nContent-Disposition: form-data; name=\"${it.first}\"\r\nContent-Type: application/json\r\n\r\n${it.second}\r\n".toByteArray()) }
        body.write("--$edge\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav); body.write("\r\n--$edge--\r\n".toByteArray())
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 20000; c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$edge")
        for ((h, v) in headers) c.setRequestProperty(h, v)
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) throw java.io.IOException("answered ${c.responseCode}: ${text.take(100)}")
        return text
    }

    companion object {
        /** Null when the backup can run with the saved keys; otherwise what is missing, in plain words. */
        fun missing(cfg: JSONObject): String? {
            fun has(vararg names: String) = names.any { cfg.optString(it).isNotEmpty() }
            val lack = ArrayList<String>()
            if (!has("elevenKey", "fishKey", "assemblyKey")) lack.add("a key to hear the caller (ElevenLabs, Fish Audio or AssemblyAI)")
            if (!has("anthropicKey", "geminiKey")) lack.add("a key to think (Anthropic or Gemini)")
            if (!has("elevenKey", "fishKey")) lack.add("a key to speak (ElevenLabs or Fish Audio)")
            return if (lack.isEmpty()) null else "the backup needs " + lack.joinToString(" and ")
        }
    }
}
