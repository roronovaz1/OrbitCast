package com.example.orbitcast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** State shared between UI and the casting service (sender side). */
object Session {
    var app: Context? = null
    val status = mutableStateOf("Ready to share")
    /** "cast" or "rec": screen-capture permission should be requested for this mode. */
    val pending = mutableStateOf<String?>(null)
    val casting = mutableStateOf(false)
    val stats = mutableStateOf("")
    @Volatile var link: Link? = null
    @Volatile var key: SecretKeySpec? = null
}

object Sender {
    private fun pinOf(code: String) = code.trim().substringAfterLast('-')
    private fun roomOf(code: String) = code.trim().substringBefore('-', "").uppercase()

    fun connectLan(host: String, port: Int, code: String) {
        Session.status.value = "Connecting…"
        thread(isDaemon = true) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                begin(TcpLink(s), pinOf(code))
            } catch (e: Exception) {
                Session.status.value = "Can't reach that device"
            }
        }
    }

    fun connectRelay(code: String) {
        if (Prefs.relay.isBlank()) { Session.status.value = "Set the relay URL in SET first"; return }
        val room = roomOf(code)
        if (room.isEmpty()) { Session.status.value = "Code looks like ROOM-123456"; return }
        Session.status.value = "Connecting…"
        thread(isDaemon = true) { begin(WsLink(Prefs.relay, room, "sender"), pinOf(code)) }
    }

    private fun begin(l: Link, pin: String) {
        val k = Crypto.key(pin)
        Session.key = k
        Session.link = l
        l.onMsg = { t, _ ->
            when (t) {
                5 -> { Session.status.value = "Allowed"; Session.pending.value = "cast" }
                6 -> { Session.status.value = "Denied by receiver"; l.close() }
            }
        }
        l.onClose = {
            if (Session.link === l) {
                Session.link = null
                if (Session.status.value == "Waiting for receiver…") Session.status.value = "Wrong code or receiver closed"
                else if (!Session.casting.value) Session.status.value = "Disconnected"
                Session.app?.let { CastService.stop(it) }
            }
        }
        l.start()
        Session.status.value = "Waiting for receiver…"
        l.send(1, Crypto.enc(k, Prefs.deviceName.toByteArray()))
    }
}

class Decoder {
    private var c: MediaCodec? = null
    private var last: ByteArray? = null
    @Volatile private var run = false
    private var gotKey = false

    @Synchronized
    fun start(s: Surface, w: Int, h: Int, csd: ByteArray) {
        if (run && last?.contentEquals(csd) == true) return
        stop()
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w.coerceAtLeast(16), h.coerceAtLeast(16))
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val m = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            m.configure(f, s, null, 0)
            m.start()
            c = m; last = csd; run = true; gotKey = false
            put(csd, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            thread(isDaemon = true) {
                val bi = MediaCodec.BufferInfo()
                while (run) {
                    try {
                        val i = m.dequeueOutputBuffer(bi, 10000)
                        if (i >= 0) m.releaseOutputBuffer(i, true)
                    } catch (_: Exception) { break }
                }
            }
        } catch (_: Exception) { stop() }
    }

    @Synchronized
    fun feed(d: ByteArray, pts: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) gotKey = true
        if (!gotKey) return // wait for the first key frame
        put(d, pts, flags)
    }

    private fun put(d: ByteArray, pts: Long, flags: Int) {
        val m = c ?: return
        try {
            val i = m.dequeueInputBuffer(20000)
            if (i < 0) return
            val b = m.getInputBuffer(i) ?: return
            b.clear(); b.put(d)
            m.queueInputBuffer(i, 0, d.size, pts, flags)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        run = false
        runCatching { c?.stop() }
        runCatching { c?.release() }
        c = null; last = null
    }
}

object Receiver {
    val viewing = mutableStateOf(false)
    val ask = mutableStateOf<String?>(null)
    val pin = mutableStateOf("")
    val room = mutableStateOf("")
    val vw = mutableIntStateOf(0)
    val vh = mutableIntStateOf(0)
    val stats = mutableStateOf("")
    val addr = mutableStateOf("")
    @Volatile var surface: Surface? = null

    private val decoder = Decoder()
    private var csd: ByteArray? = null
    private var key: SecretKeySpec? = null
    private var lan: LanServer? = null
    private var relayLink: Link? = null
    private var cur: Link? = null
    private var peer = ""
    private var allowed = false
    private var running = false
    private var fails = 0
    @Volatile private var bytes = 0L
    @Volatile private var frames = 0
    private val h = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()

    fun start() {
        if (running) return
        running = true
        room.value = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[rnd.nextInt(32)] }.joinToString("")
        newPin()
        lan = LanServer { attach(it, false) }
        Discovery.register(Prefs.deviceName, lan!!.port)
        lan?.let { l -> addr.value = localIps().joinToString("  ") { ip -> "$ip:${l.port}" } }
        connectRelay()
        tick()
    }

    private fun newPin() {
        pin.value = "%06d".format(rnd.nextInt(1_000_000))
        key = Crypto.key(pin.value)
    }

    private fun connectRelay() {
        if (!running || Prefs.relay.isBlank()) return
        val l = WsLink(Prefs.relay, room.value, "receiver")
        relayLink = l
        attach(l, true)
    }

    private fun attach(l: Link, relay: Boolean) {
        l.onMsg = { t, d -> handle(l, t, d) }
        l.onClose = {
            h.post {
                if (cur === l) endSession()
                if (relay && running) h.postDelayed({ connectRelay() }, 2000)
            }
        }
        l.start()
    }

    private fun handle(l: Link, t: Int, d: ByteArray) {
        val k = key ?: return
        try {
            when (t) {
                1 -> {
                    val name = String(Crypto.dec(k, d))
                    cur?.takeIf { it !== l }?.close()
                    cur = l; peer = name; allowed = false; fails = 0
                    if (Prefs.trusted.contains(name)) h.post { answer(true, false) } else ask.value = name
                }
                2 -> if (l === cur && allowed) {
                    val p = Crypto.dec(k, d)
                    val bb = ByteBuffer.wrap(p)
                    vw.intValue = bb.int; vh.intValue = bb.int
                    csd = p.copyOfRange(8, p.size)
                    h.post { tryStart() }
                }
                3, 4 -> if (l === cur && allowed) {
                    val p = Crypto.dec(k, d)
                    val pts = ByteBuffer.wrap(p).long
                    decoder.feed(p.copyOfRange(8, p.size), pts, if (t == 3) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    bytes += d.size; frames++
                }
            }
        } catch (e: Exception) {
            if (t == 1) { // wrong PIN
                l.close()
                if (++fails >= 5) { fails = 0; newPin() } // rotate the PIN after repeated failures
            }
        }
    }

    fun answer(ok: Boolean, always: Boolean) {
        val l = cur
        ask.value = null
        if (l == null) return
        if (ok) {
            if (always) Prefs.trusted = Prefs.trusted + peer
            allowed = true
            l.send(5, ByteArray(0))
            viewing.value = true
        } else {
            l.send(6, ByteArray(0))
            l.close()
        }
    }

    fun surfaceGone() {
        surface = null
        decoder.stop()
    }

    fun tryStart() {
        val s = surface ?: return
        val c = csd ?: return
        if (!viewing.value) return
        decoder.start(s, vw.intValue, vh.intValue, c)
    }

    fun endSession() {
        val l = cur
        cur = null; allowed = false; csd = null
        viewing.value = false; ask.value = null
        decoder.stop()
        l?.close()
    }

    fun stop() {
        running = false
        endSession()
        lan?.close(); lan = null
        Discovery.unregister()
        relayLink?.close(); relayLink = null
    }

    private fun tick() {
        h.postDelayed({
            if (!running) return@postDelayed
            stats.value = String.format("%.1f Mbps · %d fps", bytes * 8 / 1e6, frames)
            lan?.let { l -> addr.value = localIps().joinToString("  ") { ip -> "$ip:${l.port}" } }
            bytes = 0; frames = 0
            tick()
        }, 1000)
    }
}
