package app.bittu.shell

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A small WebSocket client: enough for one JSON stream over TLS. No library needed. */
class WebSocket(private val host: String, private val path: String) {
    private lateinit var socket: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val random = SecureRandom()
    @Volatile var open = false
        private set
    var closeReason = ""
        private set

    fun connect() {
        // A weak connection must fail quickly, while the phone is still ringing, instead of hanging.
        val plain = java.net.Socket().apply { connect(java.net.InetSocketAddress(host, 443), 6000) }
        socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket
        socket.soTimeout = 8000
        socket.startHandshake()
        input = DataInputStream(socket.inputStream)
        output = socket.outputStream
        val key = Base64.encodeToString(ByteArray(16).also { random.nextBytes(it) }, Base64.NO_WRAP)
        output.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
        output.flush()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()); if (head.length > 16000) break }
        val status = head.lineSequence().firstOrNull() ?: ""
        if (!status.contains(" 101")) {
            val body = ByteArray(minOf(input.available(), 2000)).also { input.read(it) }
            closeReason = "$status ${String(body)}".trim()
            close(); throw java.io.IOException(closeReason)
        }
        socket.soTimeout = 0
        open = true
    }

    @Synchronized fun send(text: String) = frame(0x1, text.toByteArray())

    @Synchronized private fun frame(op: Int, data: ByteArray) {
        if (!::output.isInitialized) return
        val out = ByteArrayOutputStream(data.size + 14)
        out.write(0x80 or op)
        when {
            data.size < 126 -> out.write(0x80 or data.size)
            data.size < 65536 -> { out.write(0x80 or 126); out.write(data.size shr 8); out.write(data.size and 0xFF) }
            else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        out.write(mask)
        for (i in data.indices) out.write(data[i].toInt() xor mask[i % 4].toInt())
        output.write(out.toByteArray()); output.flush()
    }

    /** Blocks for the next whole message. Returns null when the connection has closed. */
    fun read(): String? {
        val message = ByteArrayOutputStream()
        try {
            while (true) {
                val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte()
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                val payload = ByteArray(len.toInt()); input.readFully(payload)
                when (b0 and 0x0F) {
                    0x8 -> { closeReason = if (payload.size > 2) String(payload, 2, payload.size - 2) else "closed"; open = false; return null }
                    0x9 -> frame(0xA, payload)
                    0xA -> {}
                    else -> { message.write(payload); if (b0 and 0x80 != 0) return message.toString("UTF-8") }
                }
            }
        } catch (e: Exception) { if (closeReason.isEmpty()) closeReason = e.toString(); open = false; return null }
    }

    fun close() { open = false; try { socket.close() } catch (_: Exception) {} }
    /** Closes and keeps the reason, so the diary can say why a call's conversation stopped. */
    fun fail(why: String) { if (closeReason.isEmpty()) closeReason = why; close() }
}

/** One Gemini Live conversation: caller audio goes in, Bittu's voice comes out. */
class LiveSession(private val key: String, private val model: String, private val voice: String, private val prompt: String, private val events: Events, private val toolList: JSONArray? = null) : Voice {
    interface Events {
        fun audio(pcm24k: ByteArray)
        /** Voice that is already at the call's own 16 kHz (the backup chain's voices). */
        fun audio16k(pcm: ByteArray) {}
        fun interrupted()
        fun heard(text: String)      // what the caller said
        fun said(text: String)       // what Bittu said
        fun turnDone()
        fun tool(name: String, args: JSONObject): String
        fun closed(reason: String)
    }

    private val ws = WebSocket("generativelanguage.googleapis.com", "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key")
    override val open get() = ws.open

    /** Connects and waits until Gemini accepts the setup. Throws with Gemini's own words if it refuses. */
    fun start() {
        ws.connect()
        val tools = toolList ?: JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(fn("end_call", "End the phone call. Use it after saying goodbye, or when the caller is abusive or silent.", "reason"))
            .put(fn("take_message", "Save a message or request the caller wants passed on to the owner.", "message"))
            .put(fn("alert_owner", "Buzz the owner's phone right now with who is calling and why, so the owner can take over the call. Use it for close family, or when the caller says it is urgent.", "reason"))))
        ws.send(JSONObject().put("setup", JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            .put("tools", tools)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())).toString())
        val first = ws.read() ?: throw java.io.IOException(ws.closeReason.ifEmpty { "Gemini closed the connection" })
        if (!JSONObject(first).has("setupComplete")) throw java.io.IOException(first.take(300))
        Thread({ loop() }, "bittu-live").start()
    }

    private fun fn(name: String, about: String, arg: String) = JSONObject().put("name", name).put("description", about)
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put(arg, JSONObject().put("type", "STRING"))))

    override fun sendAudio(pcm16k: ByteArray, len: Int) {
        if (!ws.open) return
        try {
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                .put("mimeType", "audio/pcm;rate=16000").put("data", Base64.encodeToString(pcm16k, 0, len, Base64.NO_WRAP)))).toString())
        } catch (e: Exception) { ws.fail("sending to Gemini failed: $e") }
    }

    /** Text from the owner or the app, spoken to as an instruction for the next reply. */
    override fun sendText(text: String) {
        if (!ws.open) return
        try {
            ws.send(JSONObject().put("clientContent", JSONObject().put("turnComplete", true).put("turns", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString())
        } catch (e: Exception) { ws.fail("sending to Gemini failed: $e") }
    }

    private fun loop() {
        while (true) {
            val raw = ws.read() ?: break
            try {
                val msg = JSONObject(raw)
                if (!raw.contains("\"inlineData\"")) debug?.invoke(raw.take(220))
                msg.optJSONObject("serverContent")?.let { sc ->
                    if (sc.optBoolean("interrupted")) events.interrupted()
                    sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(events::heard)
                    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(events::said)
                    sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                        for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")
                            ?.takeIf { it.isNotEmpty() }?.let { events.audio(Base64.decode(it, Base64.DEFAULT)) }
                    }
                    if (sc.optBoolean("turnComplete")) events.turnDone()
                }
                msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                    val replies = JSONArray()
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val result = events.tool(c.optString("name"), c.optJSONObject("args") ?: JSONObject())
                        replies.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name")).put("response", JSONObject().put("result", result)))
                    }
                    ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", replies)).toString())
                }
            } catch (_: Exception) { /* one bad message must not end the call */ }
        }
        events.closed(ws.closeReason)
    }

    override fun close() = ws.fail("closed by Bittu")

    /** For fault-finding: receives the start of every message from Gemini that is not sound. */
    @Volatile var debug: ((String) -> Unit)? = null

    companion object {
        /** One-shot text request, used for the short summary after a call. Returns "" on any failure. */
        fun ask(key: String, model: String, prompt: String): String = try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 15000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))).toString().toByteArray()) }
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
        } catch (e: Exception) { "" }
    }
}
