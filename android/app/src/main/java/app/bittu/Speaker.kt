package app.bittu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bittu's voice when it talks to the owner.
 * First choice: the phone's own voice (free, nothing to download, works offline).
 * Second choice: ElevenLabs, when the owner picked it and saved a key. If that fails, the phone's voice is used.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var pending: (() -> Unit)? = null

    fun speak(text: String, cfg: JSONObject, done: () -> Unit) {
        val keys = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
        val eleven = keys.getString("elevenlabs", "") ?: ""; val fish = keys.getString("fish", "") ?: ""
        // The chosen cloud voice is tried first, then the other one if its key is saved, then the phone's own voice.
        val order = when (cfg.optString("tts")) { "cloud" -> listOf("eleven", "fish"); "fish" -> listOf("fish", "eleven"); else -> emptyList() }
            .filter { (it == "eleven" && eleven.isNotEmpty()) || (it == "fish" && fish.isNotEmpty()) }
        if (order.isEmpty()) { local(text, cfg.optString("lang", "en-IN"), done); return }
        thread(name = "bittu-voice") {
            val ok = order.any { if (it == "eleven") eleven(text, eleven, cfg.optString("elevenVoice"), cfg.optString("elevenModel").ifEmpty { "eleven_flash_v2_5" }) else fish(text, fish, cfg.optString("fishVoice"), cfg.optString("fishModel").ifEmpty { "s2.1-pro-free" }) }
            if (ok) done() else local(text, cfg.optString("lang", "en-IN"), done)
        }
    }

    private fun local(text: String, lang: String, done: () -> Unit) {
        pending = done
        fun say() {
            tts?.language = Locale.forLanguageTag(lang)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "bittu")
        }
        if (ready) { say(); return }
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) { finish(); return@TextToSpeech }
            ready = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finish()
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = finish()
                override fun onStop(id: String?, interrupted: Boolean) = finish()
            })
            say()
        }
    }

    private fun finish() { val f = pending; pending = null; f?.invoke() }

    /** Streams the voice from ElevenLabs straight to the speaker. Returns false on any failure. */
    private fun eleven(text: String, key: String, voice: String, model: String): Boolean = try {
        val id = voice.trim().ifEmpty { "21m00Tcm4TlvDq8ikWAM" }
        val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$id/stream?output_format=pcm_16000").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("xi-api-key", key)
        c.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", model).toString().toByteArray()) }
        play(c)
    } catch (e: Exception) { false }

    /** The same with Fish Audio. Its s2.1-pro-free model costs nothing within fair-use limits. */
    private fun fish(text: String, key: String, voice: String, model: String): Boolean = try {
        val c = URL("https://api.fish.audio/v1/tts").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("model", model)
        val body = JSONObject().put("text", text).put("format", "pcm").put("sample_rate", 16000).put("latency", "low")
        voice.trim().takeIf { it.isNotEmpty() }?.let { body.put("reference_id", it) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        play(c)
    } catch (e: Exception) { false }

    /** Plays 16 kHz sound from a service as it arrives. False when the service refused or sent nothing. */
    private fun play(c: HttpURLConnection): Boolean {
        if (c.responseCode !in 200..299) { android.util.Log.w("BittuVoice", "voice service answered ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(160)}"); return false }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(16000).build()
        track = t
        t.play()
        var total = 0
        val buf = ByteArray(3200); var kept = 0
        c.inputStream.use { input -> while (true) {
            val n = input.read(buf, kept, buf.size - kept); if (n < 0 || track == null) break
            val have = kept + n; val even = have - have % 2 // a sample is two bytes: never split one
            if (even > 0) { t.write(buf, 0, even); total += even }
            kept = have - even; if (kept == 1) buf[0] = buf[even]
        } }
        Thread.sleep(250)
        runCatching { t.stop(); t.release() }
        track = null
        return total > 3200
    }

    fun stop() { runCatching { tts?.stop() }; track?.let { track = null; runCatching { it.pause(); it.flush() } } }
    fun release() { stop(); runCatching { tts?.shutdown() } }
}
