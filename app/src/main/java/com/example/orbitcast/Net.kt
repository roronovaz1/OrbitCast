package com.example.orbitcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Message types (1 byte) shared by both transports:
 * 1 HELLO(enc name)  2 CONFIG(enc w,h,csd)  3 KEY FRAME(enc pts+data)  4 DELTA FRAME  5 ALLOW  6 DENY
 */
object Crypto {
    private val rnd = SecureRandom()

    fun key(pin: String): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = f.generateSecret(PBEKeySpec(pin.toCharArray(), "orbitcast-v1".toByteArray(), 20000, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun enc(k: SecretKeySpec, d: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return iv + c.doFinal(d)
    }

    fun dec(k: SecretKeySpec, d: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, d, 0, 12))
        return c.doFinal(d, 12, d.size - 12)
    }
}

abstract class Link {
    @Volatile var onMsg: (Int, ByteArray) -> Unit = { _, _ -> }
    @Volatile var onClose: () -> Unit = {}
    abstract fun start()
    abstract fun send(t: Int, d: ByteArray)
    abstract fun close()
}

/** Direct phone-to-phone link on the same Wi-Fi. */
class TcpLink(private val s: Socket) : Link() {
    private val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))

    override fun start() {
        thread(isDaemon = true) {
            try {
                val i = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
                while (true) {
                    val t = i.readUnsignedByte()
                    val n = i.readInt()
                    if (n < 0 || n > 32_000_000) break
                    val b = ByteArray(n)
                    i.readFully(b)
                    onMsg(t, b)
                }
            } catch (_: Exception) {
            }
            onClose()
        }
    }

    @Synchronized
    override fun send(t: Int, d: ByteArray) {
        try { out.writeByte(t); out.writeInt(d.size); out.write(d); out.flush() } catch (_: Exception) { close() }
    }

    override fun close() { runCatching { s.close() } }
}

/** Internet link through the relay server (see /server). Frames stay end-to-end encrypted. */
class WsLink(private val url: String, private val room: String, private val role: String) : Link() {
    private var ws: WebSocket? = null

    override fun start() {
        val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        val req = Request.Builder().url("${url.trimEnd('/')}/$room/$role").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val a = bytes.toByteArray()
                if (a.isNotEmpty()) onMsg(a[0].toInt() and 255, a.copyOfRange(1, a.size))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onClose() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onClose() }
        })
    }

    override fun send(t: Int, d: ByteArray) {
        val w = ws ?: return
        if (t == 4 && w.queueSize() > 3_000_000) return // drop delta frames when the uplink is congested
        w.send((byteArrayOf(t.toByte()) + d).toByteString())
    }

    override fun close() { ws?.close(1000, null) }
}

class LanServer(private val onLink: (Link) -> Unit) {
    private val srv: ServerSocket = try { ServerSocket(7777) } catch (_: Exception) { ServerSocket(0) }
    val port: Int get() = srv.localPort

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val s = srv.accept()
                    s.tcpNoDelay = true
                    onLink(TcpLink(s))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun close() { runCatching { srv.close() } }
}

/** Finds receivers on the local Wi-Fi (mDNS / NSD) and advertises this phone when it is receiving. */
object Discovery {
    val found = mutableStateMapOf<String, Pair<String, Int>>()
    private const val TYPE = "_orbitcast._tcp."
    private lateinit var nsd: NsdManager
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private val h = Handler(Looper.getMainLooper())

    fun init(c: Context) { nsd = c.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager }

    fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        reg = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun unregister() {
        reg?.let { runCatching { nsd.unregisterService(it) } }
        reg = null
    }

    fun discover() {
        if (disc != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { resolve(s, 0) }
            override fun onServiceLost(s: NsdServiceInfo) { found.remove(s.serviceName) }
        }
        disc = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun resolve(s: NsdServiceInfo, n: Int) {
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                if (n < 4) h.postDelayed({ resolve(s, n + 1) }, 400)
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val a = i.host?.hostAddress ?: return
                found[i.serviceName] = a to i.port
            }
        })
    }

    fun stopDiscover() {
        disc?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        disc = null
        found.clear()
    }
}

/** This phone's IPv4 addresses (Wi-Fi or hotspot), for manual connect. */
fun localIps(): List<String> = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<java.net.Inet4Address>()
        .mapNotNull { it.hostAddress }
} catch (_: Exception) { emptyList() }
