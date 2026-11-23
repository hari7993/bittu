package app.bittu.shell

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes down what the caller said, properly, after the call.
 *
 * During a call Gemini Live understands the caller's speech directly, but the written lines it sends along come from a
 * quick side transcriber that guesses the language sentence by sentence and often guesses wrong: Telugu came back as
 * Spanish or Hindi. So when the call is over, the recording of the caller's side is transcribed once more, unhurried,
 * by a model that is told which languages to expect. The note the owner reads uses that version.
 */
object Recheck {
    /** One thing the caller said, and when (seconds from the start of the recording). */
    class Said(val at: Double, val text: String)

    /** Returns the caller's sentences in order, or an empty list when no service could do it. */
    fun callerLines(cfg: JSONObject, pcm16k: ByteArray, otherSide: String = ""): List<Said> {
        if (pcm16k.size < 16000) return emptyList() // less than half a second: nothing was said
        val wav = wav(pcm16k)
        if (cfg.optString("geminiKey").isNotEmpty()) for (model in listOf(cfg.optString("textModel", "gemini-flash-latest"), "gemini-flash-lite-latest").distinct()) {
            runCatching { gemini(cfg.optString("geminiKey"), model, wav, otherSide) }.onFailure { Daemon.note("  writing the caller's words again with $model failed: ${it.message?.take(100)}") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (cfg.optString("elevenKey").isNotEmpty())
            runCatching { eleven(cfg.optString("elevenKey"), cfg.optString("elevenStt", "scribe_v3"), wav) }.onFailure { Daemon.note("  writing the caller's words again with ElevenLabs failed: ${it.message?.take(100)}") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun gemini(key: String, model: String, wav: ByteArray, otherSide: String): List<Said> {
        val ask = "This is one side of a phone call in India: only the caller is heard, with silences where the other side spoke. " +
            "The caller speaks Telugu, English or Hindi, and may mix them. Write down exactly what the caller says. " +
            "Write Telugu in Telugu script, Hindi in Devanagari and English in English; never translate, and never guess another language such as Spanish. " +
            (if (otherSide.isNotBlank()) "To help you follow the conversation, this is what the other side (an assistant called Bittu) said, in order: " + otherSide.take(1500) + " Do not include those words; write only the caller's. " else "") +
            "Leave out noise and silence. Reply with JSON only: a list of objects {\"at\": seconds from the start as a number, \"text\": what was said}, one object per sentence, in order."
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
            .put(JSONObject().put("text", ask)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) throw java.io.IOException("answered ${c.responseCode}")
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val raw = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        val list = JSONArray(raw.substring(raw.indexOf('[').coerceAtLeast(0), raw.lastIndexOf(']') + 1))
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("text").isNotBlank() }.map { Said(it.optDouble("at", 0.0), it.optString("text").trim()) }
    }

    /** ElevenLabs Scribe gives every word with its time; words less than a second apart are one sentence. */
    private fun eleven(key: String, model: String, wav: ByteArray): List<Said> {
        val edge = "bittu" + System.nanoTime()
        val body = ByteArrayOutputStream()
        for ((name, value) in listOf("model_id" to model, "timestamps_granularity" to "word", "tag_audio_events" to "false"))
            body.write("--$edge\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        body.write("--$edge\r\nContent-Disposition: form-data; name=\"file\"; filename=\"call.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav); body.write("\r\n--$edge--\r\n".toByteArray())
        val c = URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 60000; c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$edge"); c.setRequestProperty("xi-api-key", key)
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (c.responseCode !in 200..299) throw java.io.IOException("answered ${c.responseCode}")
        val json = JSONObject(text)
        val words = json.optJSONArray("words") ?: return json.optString("text").trim().let { if (it.isEmpty()) emptyList() else listOf(Said(0.0, it)) }
        val out = ArrayList<Said>(); val line = StringBuilder(); var start = 0.0; var lastEnd = -10.0
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("type", "word") == "audio_event") continue
            val s = w.optDouble("start", lastEnd)
            if (line.isNotBlank() && s - lastEnd > 1.0) { out.add(Said(start, line.toString().trim())); line.setLength(0) }
            if (line.isBlank()) start = s
            line.append(w.optString("text"))
            lastEnd = w.optDouble("end", s)
        }
        if (line.isNotBlank()) out.add(Said(start, line.toString().trim()))
        return out
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(16000); i32(32000); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
