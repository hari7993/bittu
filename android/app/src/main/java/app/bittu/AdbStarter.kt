package app.bittu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Starts Bittu's helper from inside the phone, with no computer, the way Shizuku does:
 * the app pairs once with the phone's own Wireless debugging, then uses that to start the helper as the shell user.
 * Wi-Fi is needed only at the moment of starting. The helper then keeps running on mobile data or with no network.
 */
object AdbStarter {
    @Volatile private var working = false
    @Volatile private var lastTry = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("bittu", Context.MODE_PRIVATE)
    fun paired(c: Context) = prefs(c).getBoolean("paired", false)
    private fun tell(text: String) { TalkService.sink?.invoke(JSONObject().put("ev", "helper").put("text", text)) }

    /** The identity Bittu shows to Wireless debugging. Made once and kept in the app's private storage. */
    private class Manager(c: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val kf = File(c.filesDir, "adb.key")
            val cf = File(c.filesDir, "adb.crt")
            if (kf.exists() && cf.exists()) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                cert = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                val name = X500Name("CN=Bittu")
                val from = Date()
                val until = Date(System.currentTimeMillis() + 20L * 365 * 86_400_000)
                val ext = CertificateExtensions()
                ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
                ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(from, until))
                val info = X509CertInfo()
                info.set("version", CertificateVersion(2))
                info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get("SHA512withRSA")))
                info.set("subject", CertificateSubjectName(name))
                info.set("key", CertificateX509Key(pair.public))
                info.set("validity", CertificateValidity(from, until))
                info.set("issuer", CertificateIssuerName(name))
                info.set("extensions", ext)
                val made = X509CertImpl(info)
                made.sign(pair.private, "SHA512withRSA")
                key = pair.private
                cert = made
                kf.writeBytes(pair.private.encoded)
                cf.writeBytes(made.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Bittu"
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Finds the port this phone is offering for a Wireless debugging service. Returns 0 if none appears in time. */
    private fun findPort(c: Context, type: String, timeoutMs: Long): Int {
        val nsd = c.getSystemService(NsdManager::class.java)
        val mine = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress } }.getOrDefault(emptyList())
        val done = CountDownLatch(1)
        var port = 0
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) { if (i.host?.hostAddress in mine || i.host?.isLoopbackAddress == true) { port = i.port; done.countDown() } }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return port
    }

    /** Shows a notification with a box for the pairing code. The owner types the six digits shown by the phone. */
    fun askForCode(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pair", "Pairing Bittu", NotificationManager.IMPORTANCE_HIGH))
        val reply = PendingIntent.getBroadcast(c, 6, Intent(c, PairReply::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val box = RemoteInput.Builder("code").setLabel("6-digit pairing code").build()
        nm.notify(43, Notification.Builder(c, "pair").setSmallIcon(R.drawable.ic_bittu).setContentTitle("Pair Bittu with this phone")
            .setContentText("In Wireless debugging, tap \"Pair device with pairing code\", then type that code here.")
            .setStyle(Notification.BigTextStyle().bigText("In Wireless debugging, tap \"Pair device with pairing code\". Keep that window open, pull this notification down and type the six digits here."))
            .addAction(Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(box).build()).build())
    }

    private fun note(c: Context, text: String, done: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(c, "pair").setSmallIcon(R.drawable.ic_bittu).setContentTitle(if (done) "Bittu is paired" else "Pairing Bittu").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setTimeoutAfter(if (done) 8000 else 0)
        if (!done) {
            val reply = PendingIntent.getBroadcast(c, 6, Intent(c, PairReply::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(RemoteInput.Builder("code").setLabel("6-digit pairing code").build()).build())
        }
        nm.notify(43, b.build())
        tell(text)
    }

    /** Pairs using the code the owner typed, then starts the helper straight away. */
    fun pairWith(c: Context, code: String) {
        thread(name = "bittu-pair") {
            val port = findPort(c, "_adb-tls-pairing._tcp", 6000)
            if (port == 0) { note(c, "Bittu could not find the pairing window. Keep \"Pair device with pairing code\" open on Wi-Fi and enter the code again.", false); return@thread }
            val ok = try { Manager(c).use { it.pair("127.0.0.1", port, code.filter(Char::isDigit)) } } catch (e: Throwable) { false }
            if (!ok) { note(c, "That code was not accepted. Check the six digits and enter them again.", false); return@thread }
            prefs(c).edit().putBoolean("paired", true).apply()
            note(c, "Paired. Starting the helper…", true)
            val result = start(c)
            note(c, if (result == "running") "Paired, and the helper is running." else "Paired. $result", true)
        }
    }

    /**
     * Starts the helper through Wireless debugging.
     * @return "running" on success, otherwise a sentence saying what is needed
     */
    @Synchronized
    fun start(c: Context): String {
        ShellLink.init(c)
        if (ShellLink.connected) return "running"
        if (!paired(c)) return "Bittu is not paired with this phone yet."
        val keep = keepPort(c)
        // First choice when the owner allowed it: the always-open debugging port. It needs no Wi-Fi and no network at all.
        if (keep) {
            val viaPort = overPort(c, tries = 2)
            if (viaPort == "running") return "running"
            if (!onWifi(c)) return "The helper could not be started through the always-open port ($viaPort), and there is no Wi-Fi to open it again. It will be tried again."
        }
        if (!onWifi(c)) return "Wi-Fi is needed for a moment to start the helper. Connect to any Wi-Fi."
        val cr = c.contentResolver
        val canSwitch = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        val wasOn = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        if (!wasOn) {
            if (!canSwitch) return "Turn on Wireless debugging in Developer options, then open Bittu again."
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1) // Bittu switches Wireless debugging on by itself
            Thread.sleep(2500)
        }
        val m = Manager(c)
        var openedPort = false
        try {
            m.setTimeout(10, TimeUnit.SECONDS)
            // Wireless debugging takes a few seconds to come up after it is switched on, so a first miss is tried again.
            var reached = false
            for (attempt in 0..2) {
                reached = try { m.connectTls(c, 10_000) } catch (e: AdbPairingRequiredException) { throw e } catch (e: Throwable) { false }
                if (reached) break
                Thread.sleep(3000)
            }
            if (!reached) return "Bittu could not reach Wireless debugging. If the phone asked to allow it on this Wi-Fi network, tap Allow."
            if (keep) {
                // Ask the phone to keep its debugging port open. The phone's debugging service restarts to do that, which ends this
                // session, so the helper is started afterwards through the new port.
                runCatching { m.openStream("tcpip:$PORT").use { Thread.sleep(1500) } }
                openedPort = true
            } else launch(c, m)
        } catch (e: AdbPairingRequiredException) {
            prefs(c).edit().putBoolean("paired", false).apply()
            return "The phone forgot the pairing. Pair Bittu again."
        } catch (e: Throwable) {
            return "Starting the helper failed: ${(e.message ?: e.toString()).take(120)}"
        } finally { runCatching { m.close() } }
        if (openedPort) {
            Thread.sleep(3500)
            if (!wasOn && canSwitch) runCatching { Settings.Global.putInt(cr, "adb_wifi_enabled", 0) }
            val viaPort = overPort(c, tries = 5)
            return if (viaPort == "running") "running" else "The always-open port was requested but the helper could not be started through it: $viaPort"
        }
        if (linked()) {
            // The helper keeps running without Wireless debugging, so it is switched back off if Bittu was the one who switched it on.
            if (!wasOn && canSwitch) runCatching { Settings.Global.putInt(cr, "adb_wifi_enabled", 0) }
            return "running"
        }
        return "The helper was started but has not answered yet."
    }

    private const val PORT = 5555
    fun keepPort(c: Context) = prefs(c).getBoolean("keepPort", false)

    /** Starts the helper in a session that is already connected. */
    private fun launch(c: Context, m: Manager) {
        val apk = c.applicationInfo.sourceDir
        // The helper must outlive this short session. Two things make that certain: it ignores the hang-up signal the phone
        // sends when the session ends, and the session stays open for a few seconds so the helper has time to detach.
        m.openStream("shell:trap '' HUP; CLASSPATH=$apk setsid app_process /system/bin --nice-name=bittu_helper app.bittu.shell.Daemon >/dev/null 2>&1 & sleep 4").use { Thread.sleep(4500) }
    }

    private fun linked(): Boolean { repeat(12) { Thread.sleep(500); ShellLink.connect(); if (ShellLink.connected) return true }; return false }

    /** Starts the helper through the always-open port on the phone itself. Works with no network. Returns "running" or what went wrong. */
    private fun overPort(c: Context, tries: Int): String {
        var last = "the port is closed"
        repeat(tries) { n ->
            val m = Manager(c)
            try {
                m.setTimeout(6, TimeUnit.SECONDS)
                if (m.connect("127.0.0.1", PORT)) { launch(c, m); runCatching { m.close() }; if (linked()) return "running"; last = "the helper did not answer" }
            } catch (e: AdbPairingRequiredException) { last = "the phone asked for approval again"
            } catch (e: Throwable) { last = (e.message ?: e.toString()).take(80)
            } finally { runCatching { m.close() } }
            if (n < tries - 1) Thread.sleep(2000)
        }
        return last
    }

    /**
     * The owner's switch for "restart without Wi-Fi". On: the phone keeps debugging port 5555 open, so the helper can be
     * started again with no network. Off: the port is closed again. Either way the phone's debugging service restarts once,
     * which stops the helper for a few seconds; it is started again straight away.
     */
    fun setKeepPort(c: Context, on: Boolean) {
        val app = c.applicationContext
        prefs(app).edit().putBoolean("keepPort", on).apply()
        thread(name = "bittu-port") {
            if (!on) {
                // Close the port: tell the debugging service to go back to cable-only.
                val m = Manager(app)
                val closed = try { m.setTimeout(6, TimeUnit.SECONDS); if (m.connect("127.0.0.1", PORT)) { runCatching { m.openStream("usb:").use { Thread.sleep(1500) } }; true } else false } catch (e: Throwable) { false } finally { runCatching { m.close() } }
                Keeper.log(app, "restart without Wi-Fi switched off: " + if (closed) "the port was closed" else "the port was not open")
                Thread.sleep(4000)
            } else Keeper.log(app, "restart without Wi-Fi switched on")
            // Opening happens as part of a normal start: stop relying on the current helper and start it the new way.
            if (on && ShellLink.connected) { ShellLink.requestSync("stop", JSONObject(), 3000); Thread.sleep(2500) }
            val r = start(app)
            Keeper.log(app, "after the switch, start gave: $r")
            tell(if (r == "running") (if (on) "Restart without Wi-Fi is on. The helper is running." else "Restart without Wi-Fi is off. The helper is running.") else r)
        }
    }

    fun ensure(c: Context) {
        val app = c.applicationContext
        if (working || ShellLink.connected || !paired(app) || System.currentTimeMillis() - lastTry < 30_000) return
        lastTry = System.currentTimeMillis()
        working = true
        thread(name = "bittu-start") {
            try { val r = start(app); Keeper.log(app, "check: helper was not running, start gave: $r"); if (r == "running") tell("The helper is running.") }
            finally { working = false; if (!ShellLink.connected) lastTry = System.currentTimeMillis() - 15_000 } // a miss is retried after 15 seconds
        }
    }
}

/** Receives the pairing code typed into the notification. */
class PairReply : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("code")?.toString() ?: return
        AdbStarter.pairWith(context.applicationContext, code)
    }
}
