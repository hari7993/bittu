package app.bittu

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import app.bittu.shell.LiveSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "Talk to Bittu": a live voice conversation with the owner through the phone's own microphone and speaker.
 * Bittu can also use tools (switches, modes, calls, messages). The interface carries those out and reports back.
 */
class Talk(private val context: Context, private val event: (JSONObject) -> Unit) {
    @Volatile private var session: LiveSession? = null
    @Volatile private var running = false
    private val toolReplies = ConcurrentHashMap<Int, String>()
    private val toolWaits = ConcurrentHashMap<Int, CountDownLatch>()
    private var toolSeq = 0
    private val lastWord = java.util.concurrent.atomic.AtomicLong(0)

    private fun tool(name: String, about: String, props: JSONObject, required: Array<String>) = JSONObject().put("name", name).put("description", about)
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required)))
    private fun str(about: String) = JSONObject().put("type", "STRING").put("description", about)
    private fun bool(about: String) = JSONObject().put("type", "BOOLEAN").put("description", about)

    private fun tools() = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(tool("phone_switch", "Turn a phone switch on or off.", JSONObject().put("name", str("one of: wifi, bluetooth, mobile_data, torch, silent, hotspot")).put("on", bool("true to turn on")), arrayOf("name", "on")))
        .put(tool("set_mode", "Change Bittu's mode for incoming calls.", JSONObject().put("mode", str("one of: normal, driving, meeting, sleep, strangers, off")), arrayOf("mode")))
        .put(tool("call_person", "Place a phone call to a person by name.", JSONObject().put("name", str("the person's name as the owner said it")), arrayOf("name")))
        .put(tool("send_message", "Send a text message to a person.", JSONObject().put("app", str("whatsapp or sms")).put("name", str("the person's name")).put("text", str("the message, in the owner's words")), arrayOf("app", "name", "text")))
        .put(tool("open_app", "Open an app by name.", JSONObject().put("name", str("the app's name")), arrayOf("name")))
        .put(tool("set_level", "Set screen brightness or media volume.", JSONObject().put("what", str("brightness or volume")).put("percent", JSONObject().put("type", "NUMBER").put("description", "0 to 100")), arrayOf("what", "percent")))
        .put(tool("read_screen", "Read the words and buttons currently on the phone's screen, in whatever app is open. Items marked [tap] can be tapped, [box] can be typed into.", JSONObject(), arrayOf()))
        .put(tool("tap_on", "Tap a button or item on screen by the words shown on it.", JSONObject().put("words", str("the exact words on the item, as read_screen showed them")), arrayOf("words")))
        .put(tool("type_text", "Type text into the text box that is open on screen.", JSONObject().put("text", str("the text to type")), arrayOf("text")))
        .put(tool("press_button", "Press a phone button.", JSONObject().put("button", str("one of: back, home, recents, notifications")), arrayOf("button")))
        .put(tool("developer_option", "Turn a developer option on or off.", JSONObject().put("name", str("one of: developer_options, usb_debugging, wireless_debugging, stay_awake, show_taps, layout_bounds")).put("on", bool("true to turn on")), arrayOf("name", "on")))))

    /** The interface answers a tool request through here. */
    fun toolResult(id: Int, text: String) { toolReplies[id] = text; toolWaits.remove(id)?.countDown() }

    @SuppressLint("MissingPermission")
    fun start(cfg: JSONObject) {
        stop()
        val key = context.getSharedPreferences("keys", Context.MODE_PRIVATE).getString("gemini", "") ?: ""
        if (key.isEmpty()) { event(JSONObject().put("ev", "talk").put("state", "closed").put("error", "Add a Gemini key in Brain and keys to talk with Bittu.")); return }
        running = true
        thread(name = "bittu-talk") {
            var rec: AudioRecord? = null
            var trk: AudioTrack? = null
            val queue = LinkedBlockingQueue<ByteArray>()
            val quiet = java.util.concurrent.atomic.AtomicLong(0) // Bittu's voice must not be heard back as the owner speaking, so the mic is ignored until it finishes
            try {
                val live = LiveSession(key, cfg.optString("model", "gemini-3.8-live"), cfg.optString("voice", "Aoede"), cfg.optString("prompt"), object : LiveSession.Events {
                    override fun audio(pcm24k: ByteArray) { queue.put(pcm24k) }
                    override fun interrupted() { queue.clear(); runCatching { trk?.pause(); trk?.flush(); trk?.play() } }
                    override fun heard(text: String) { lastWord.set(System.currentTimeMillis()); event(JSONObject().put("ev", "talk").put("heard", text)) }
                    override fun said(text: String) { lastWord.set(System.currentTimeMillis()); event(JSONObject().put("ev", "talk").put("said", text)) }
                    override fun turnDone() { event(JSONObject().put("ev", "talk").put("turn", true)) }
                    override fun tool(name: String, args: JSONObject): String {
                        if (cfg.optBoolean("call")) return when (name) { // a practice call has the call's own two actions
                            "take_message" -> { event(JSONObject().put("ev", "talk").put("message", args.optString("message"))); "saved" }
                            "end_call" -> { thread { Thread.sleep(600); while (running && queue.isNotEmpty()) Thread.sleep(100); Thread.sleep(1500); if (running) { event(JSONObject().put("ev", "talk").put("state", "closed").put("ended", true)); stop() } }; "ending the call" }
                            else -> "unknown"
                        }
                        // Actions run here, in the always-alive part of the app, so they work while another app is in front.
                        event(JSONObject().put("ev", "talk-tool").put("name", name))
                        val result = Tools.run(context, name, args)
                        event(JSONObject().put("ev", "talk-result").put("name", name).put("result", result.take(160)))
                        return result
                    }
                    override fun closed(reason: String) { if (running) event(JSONObject().put("ev", "talk").put("state", "closed").put("error", if (reason.isEmpty() || reason == "closed") "" else reason.take(160))) }
                }, if (cfg.optBoolean("call")) null else tools())
                live.start()
                session = live
                val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
                runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
                trk = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 9600)).build()
                val record = rec; val track = trk
                record.startRecording(); track.play()
                event(JSONObject().put("ev", "talk").put("state", "listening"))
                thread(name = "bittu-talk-out") {
                    var speaking = false
                    while (running && live.open) {
                        val chunk = queue.poll(150, TimeUnit.MILLISECONDS)
                        if (chunk == null) { if (speaking && System.currentTimeMillis() > quiet.get()) { speaking = false; event(JSONObject().put("ev", "talk").put("state", "listening")) }; continue }
                        if (!speaking) { speaking = true; event(JSONObject().put("ev", "talk").put("state", "speaking")) }
                        quiet.set(System.currentTimeMillis() + chunk.size / 48 + 350) // 48 bytes per millisecond at 24 kHz
                        runCatching { track.write(chunk, 0, chunk.size) }
                        quiet.set(System.currentTimeMillis() + 350)
                    }
                }
                live.sendText(cfg.optString("hello", "The owner has just opened you. Greet them in one short sentence and ask what they need."))
                val buf = ByteArray(640)
                lastWord.set(System.currentTimeMillis())
                while (running && live.open) {
                    // Nobody has spoken for three minutes: stop listening by itself.
                    if (System.currentTimeMillis() - lastWord.get() > cfg.optInt("idleSecs", 180) * 1000L) { event(JSONObject().put("ev", "talk").put("state", "closed")); break }
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0 && System.currentTimeMillis() > quiet.get() && queue.isEmpty()) live.sendAudio(buf, n)
                }
                live.close()
            } catch (e: Throwable) {
                val cause = generateSequence(e) { it.cause }.last()
                if (running) event(JSONObject().put("ev", "talk").put("state", "closed").put("error", (cause.message ?: cause.toString()).take(200)))
            } finally {
                running = false
                session = null
                runCatching { rec?.stop(); rec?.release() }
                runCatching { trk?.stop(); trk?.release() }
            }
        }
    }

    fun stop() { running = false; session?.close(); session = null; toolWaits.values.forEach { it.countDown() }; toolWaits.clear() }
}
