package com.example.orbitcast

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * One pipeline for both jobs: MediaProjection -> VirtualDisplay -> H.264 encoder.
 * mode "cast": encrypted frames go to Session.link.   mode "rec": frames (+ audio) go to an MP4 file.
 */
class CastService : Service() {
    companion object {
        fun stop(c: Context) { c.stopService(Intent(c, CastService::class.java)) }
    }

    private val ht = HandlerThread("orbit-enc").apply { start() }
    private val hd = Handler(ht.looper)
    private val lock = Any()
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var enc: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var outFile: File? = null
    private var audioThread: Thread? = null
    private var vTrack = -1
    private var aTrack = -1
    private var wantAudio = false
    private var started = false
    private var rec = false
    @Volatile private var running = false
    private var baseUs = 0L
    private var csd: ByteArray? = null
    private var w = 0
    private var hh = 0
    @Volatile private var bytes = 0L
    @Volatile private var frames = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        rec = i.getStringExtra("mode") == "rec"
        val perm = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mic = rec && Prefs.mic && perm
        goForeground(mic)
        val data = IntentCompat.getParcelableExtra(i, "data", Intent::class.java)
        if (data == null || (!rec && Session.link == null)) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(i.getIntExtra("code", 0), data)
        proj = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, hd)
        try {
            begin(mic, rec && Prefs.internal && perm && Build.VERSION.SDK_INT >= 29)
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        Session.casting.value = true
        Session.status.value = if (rec) "Recording" else "Casting"
        return START_NOT_STICKY
    }

    private fun goForeground(mic: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("orbit", "Casting", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, CastService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "orbit")
            .setContentTitle(if (rec) "Recording screen" else "Casting screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (mic && Build.VERSION.SDK_INT >= 30) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1, n, t)
        } else startForeground(1, n)
    }

    private fun begin(mic: Boolean, internal: Boolean) {
        wantAudio = mic || internal
        try {
            startVideo(Prefs.shortSide, Prefs.mbps, Prefs.fps)
        } catch (e: Exception) { // device can't do the chosen settings: fall back to safe ones
            releaseVideo()
            Toast.makeText(this, "Quality not supported, using 1080p 30fps", Toast.LENGTH_SHORT).show()
            startVideo(1080, 8, 30)
        }
        running = true
        if (rec && wantAudio) {
            try { startAudio(mic, internal) } catch (e: Exception) {
                synchronized(lock) { wantAudio = false; maybeStartMuxer() }
            }
            // If no audio ever arrives (some phones), still save the video after 3 seconds
            hd.postDelayed({
                synchronized(lock) { if (!started && vTrack >= 0 && aTrack < 0 && wantAudio) { wantAudio = false; maybeStartMuxer() } }
            }, 3000)
        }
        tick()
    }

    @Suppress("DEPRECATION")
    private fun screen(side: Int): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        var sw: Int
        var sh: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val p = Point(); wm.defaultDisplay.getRealSize(p)
            sw = p.x; sh = p.y
        }
        if (side > 0) {
            val s = minOf(sw, sh)
            if (s > side) { val k = side.toFloat() / s; sw = (sw * k).toInt(); sh = (sh * k).toInt() }
        }
        return Triple(sw and 15.inv(), sh and 15.inv(), resources.displayMetrics.densityDpi)
    }

    private fun startVideo(side: Int, mbps: Int, fps: Int) {
        val (sw, sh, dpi) = screen(side)
        w = sw; hh = sh
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, hh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, mbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                if (rec) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L) // keep frames flowing on a static screen
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time priority for smoother capture
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        e.setCallback(cb, hd)
        e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = e.createInputSurface()
        e.start()
        enc = e
        if (rec && muxer == null) muxer = openOutput()
        baseUs = System.nanoTime() / 1000
        vd = proj!!.createVirtualDisplay("orbit", w, hh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null)
    }

    private val cb = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { stopSelf() }
        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
            synchronized(lock) { muxer?.let { vTrack = it.addTrack(f); maybeStartMuxer() } }
        }
        override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
            try {
                val b = c.getOutputBuffer(i)
                if (b != null && info.size > 0) {
                    val d = ByteArray(info.size)
                    b.position(info.offset); b.limit(info.offset + info.size); b.get(d)
                    out(d, info)
                }
            } finally {
                try { c.releaseOutputBuffer(i, false) } catch (_: Exception) {}
            }
        }
    }

    private fun out(d: ByteArray, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { csd = d; sendConfig(); return }
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (rec) {
            synchronized(lock) {
                val m = muxer
                if (started && m != null) {
                    val bi = MediaCodec.BufferInfo()
                    bi.set(0, d.size, (info.presentationTimeUs - baseUs).coerceAtLeast(0), info.flags)
                    m.writeSampleData(vTrack, ByteBuffer.wrap(d), bi)
                    bytes += d.size; frames++
                }
            }
            return
        }
        val l = Session.link
        val k = Session.key
        if (l != null && k != null) {
            if (key) sendConfig() // repeat config on every key frame so a late/restarted receiver can join
            val p = ByteBuffer.allocate(8 + d.size).putLong(info.presentationTimeUs).put(d).array()
            l.send(if (key) 3 else 4, Crypto.enc(k, p))
            bytes += d.size; frames++
        }
    }

    private fun sendConfig() {
        val l = Session.link
        val k = Session.key
        val c = csd
        if (rec || l == null || k == null || c == null) return
        l.send(2, Crypto.enc(k, ByteBuffer.allocate(8 + c.size).putInt(w).putInt(hh).put(c).array()))
    }

    private fun maybeStartMuxer() {
        val m = muxer ?: return
        if (!started && vTrack >= 0 && (!wantAudio || aTrack >= 0)) { m.start(); started = true }
    }

    private fun openOutput(): MediaMuxer {
        val name = "OrbitCast_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OrbitCast")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
            uri = u
            val p = contentResolver.openFileDescriptor(u, "rw")!!
            pfd = p
            return MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        // Android 9: save to the public Movies/OrbitCast folder (needs storage permission), else the app folder
        @Suppress("DEPRECATION")
        var dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OrbitCast")
        if (!dir.exists() && !dir.mkdirs()) dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        val f = File(dir, name)
        outFile = f
        return MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(mic: Boolean, internal: Boolean) {
        val rate = 48000
        val fmt = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4
        var m: AudioRecord? = null
        var s: AudioRecord? = null
        if (mic) m = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt).setBufferSizeInBytes(buf).build()
        if (internal && Build.VERSION.SDK_INT >= 29) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            s = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(buf).setAudioPlaybackCaptureConfig(cfg).build()
        }
        val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ae.start()
        m?.startRecording(); s?.startRecording()
        audioThread = thread(isDaemon = true) { pump(ae, m, s, rate) }
    }

    private fun pump(ae: MediaCodec, m: AudioRecord?, s: AudioRecord?, rate: Int) {
        val n = 4096
        val a = ShortArray(n)
        val b = ShortArray(n)
        val bi = MediaCodec.BufferInfo()
        try {
            while (running) {
                val rm = m?.read(a, 0, n) ?: 0
                val rs = s?.read(b, 0, n) ?: 0
                val cnt = maxOf(rm, rs)
                if (cnt <= 0) { Thread.sleep(5); continue }
                for (j in 0 until cnt) { // mix mic + internal
                    var v = 0
                    if (j < rm) v += a[j]
                    if (j < rs) v += b[j]
                    a[j] = v.coerceIn(-32768, 32767).toShort()
                }
                val ii = ae.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = ae.getInputBuffer(ii)!!
                    ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(a, 0, cnt)
                    val pts = System.nanoTime() / 1000 - baseUs - (cnt / 2) * 1_000_000L / rate
                    ae.queueInputBuffer(ii, 0, cnt * 2, pts.coerceAtLeast(0), 0)
                }
                while (true) {
                    val oi = ae.dequeueOutputBuffer(bi, 0)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) { muxer?.let { if (!started) { aTrack = it.addTrack(ae.outputFormat); maybeStartMuxer() } } }
                    } else if (oi >= 0) {
                        val ob = ae.getOutputBuffer(oi)!!
                        if (bi.size > 0 && (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(lock) {
                                val mx = muxer
                                if (started && mx != null && aTrack >= 0) {
                                    ob.position(bi.offset); ob.limit(bi.offset + bi.size)
                                    mx.writeSampleData(aTrack, ob, bi)
                                }
                            }
                        }
                        ae.releaseOutputBuffer(oi, false)
                    } else break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { m?.stop(); m?.release() }
            runCatching { s?.stop(); s?.release() }
            runCatching { ae.stop(); ae.release() }
        }
    }

    private fun tick() {
        hd.postDelayed({
            if (!running) return@postDelayed
            Session.stats.value = String.format(Locale.US, "%.1f Mbps · %d fps", bytes * 8 / 1e6, frames)
            bytes = 0; frames = 0
            tick()
        }, 1000)
    }

    private fun releaseVideo() {
        runCatching { vd?.release() }
        runCatching { enc?.stop() }
        runCatching { enc?.release() }
        vd = null; enc = null
    }

    override fun onDestroy() {
        running = false
        audioThread?.join(800)
        releaseVideo()
        synchronized(lock) {
            val wasStarted = started
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            runCatching { muxer?.release() }
            started = false; muxer = null
            runCatching { pfd?.close() }
            outFile?.let { f -> if (wasStarted) MediaScannerConnection.scanFile(this, arrayOf(f.path), arrayOf("video/mp4"), null) }
            uri?.let { u ->
                if (!wasStarted) contentResolver.delete(u, null, null)
                else if (Build.VERSION.SDK_INT >= 29) contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        }
        runCatching { proj?.stop() }
        Session.link?.close(); Session.link = null
        Session.casting.value = false
        Session.stats.value = ""
        Session.status.value = if (rec) "Saved to Movies/OrbitCast" else "Ready to share"
        ht.quitSafely()
        super.onDestroy()
    }
}
