package app.bittu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The one screen of the app. It shows the interface from the bundled web folder (bittu/ui).
 * The always-on work (calls, wake word, screen control) does not live here: it runs in services,
 * so it keeps going when this screen is closed.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var loaded = false
    private var talkWhenReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test builds can be inspected from a computer. Release builds cannot.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.setBackgroundColor(getColor(R.color.paper))
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // the interface keeps its settings in local storage
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.webViewClient = Pages()
        ShellLink.init(this)
        bridge = Bridge(this) { fn, json -> toPage(fn, json) }
        web.addJavascriptInterface(bridge, "BittuNative")
        web.addJavascriptInterface(Bars(), "BittuBars")
        ShellLink.onEvent = { ev -> toPage("bittuEvent", ev.toString()) }
        setContentView(web)
        setBars(isNight())
        wantsTalk(intent)
        if (savedInstanceState == null) web.loadUrl(HOME) else web.restoreState(savedInstanceState)
    }

    /** Opened by the assistant gesture, the Quick Settings tile or a voice-command button: go straight to talking. */
    private fun wantsTalk(intent: android.content.Intent?) {
        if (intent?.action !in setOf(android.content.Intent.ACTION_ASSIST, android.content.Intent.ACTION_VOICE_COMMAND, TALK)) return
        if (loaded) toPage("bittuTalk", "{}") else talkWhenReady = true
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        wantsTalk(intent)
    }

    /** Calls a function in the page with one JSON value. */
    private fun toPage(fn: String, json: String) = runOnUiThread {
        web.evaluateJavascript("window.$fn && window.$fn(${org.json.JSONObject.quote(json)})", null)
    }

    override fun onResume() {
        super.onResume()
        ShellLink.connect()
        web.postDelayed({ AdbStarter.ensure(this) }, 2500) // if the helper is not there, start it without a cable
        WakeService.startIfWanted(this)
        toPage("bittuEvent", "{\"ev\":\"resume\"}")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        toPage("bittuEvent", "{\"ev\":\"perm\"}")
    }

    override fun onDestroy() {
        ShellLink.onEvent = null
        bridge.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** The phone's back button: the interface closes a sheet or steps back; at Home the app goes to the background. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.bittuBack ? window.bittuBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    private fun isNight() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Status and navigation bars take the page colour, with dark icons on the light theme. */
    private fun setBars(dark: Boolean) {
        val color = if (dark) 0xFF121210.toInt() else 0xFFF6F4EE.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    /** Serves the bundled interface under one private address, so its scripts load as normal web modules. */
    private inner class Pages : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            val path = (url.path ?: "").trimStart('/').ifEmpty { "index.html" }
            if (path.contains("..")) return missing()
            return try {
                val type = TYPES[path.substringAfterLast('.', "")] ?: "application/octet-stream"
                val text = type.startsWith("text/") || type == "application/json" || type == "image/svg+xml"
                WebResourceResponse(type, if (text) "utf-8" else null, assets.open(path))
            } catch (e: IOException) {
                missing()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            loaded = true
            if (talkWhenReady) { talkWhenReady = false; view.postDelayed({ toPage("bittuTalk", "{}") }, 700) }
        }

        // The interface never leaves its own pages.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST

        private fun missing() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /** The interface tells the app which theme it is showing, so the system bars match. */
    private inner class Bars {
        @JavascriptInterface
        fun theme(dark: Boolean) = runOnUiThread { setBars(dark) }
    }

    companion object {
        const val TALK = "app.bittu.TALK"
        private const val HOST = "bittu.localhost"
        private const val HOME = "https://$HOST/index.html"
        private val TYPES = mapOf(
            "html" to "text/html", "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
            "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "ttf" to "font/ttf",
        )
    }
}
