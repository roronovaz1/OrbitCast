package com.example.orbitcast

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Session.app = applicationContext
        Discovery.init(this)
        enableEdgeToEdge()
        setContent { App() }
    }
}

class Th(val bg: Color, val fg: Color, val dim: Color, val red: Color, val font: FontFamily)

/** Drop a dot-matrix font (e.g. Doto / DotGothic16, OFL) at app/src/main/res/font/dotmatrix.ttf to enable it. */
@Composable
fun dotFont(): FontFamily {
    val c = LocalContext.current
    val id = remember { c.resources.getIdentifier("dotmatrix", "font", c.packageName) }
    return if (id != 0) FontFamily(Font(id)) else FontFamily.Monospace
}

@Composable
fun T(s: String, th: Th, size: Int = 14, color: Color = th.fg, mod: Modifier = Modifier, center: Boolean = false) {
    Text(s, color = color, fontFamily = th.font, fontSize = size.sp, modifier = mod, textAlign = if (center) TextAlign.Center else null)
}

@Composable
fun Pill(s: String, th: Th, on: Boolean = false, click: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (on) th.fg else Color.Transparent)
            .border(1.dp, if (on) th.fg else th.dim, shape)
            .clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp)
    ) { T(s, th, 13, if (on) th.bg else th.fg) }
}

/** Layered orbit logo: dial ticks, five tilted dashed orbits, satellites with trails, a dot-matrix core. */
@Composable
fun Orbit(active: Boolean, th: Th, mod: Modifier) {
    val tr = rememberInfiniteTransition(label = "orbit")
    val t by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 16000 else 48000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(mod) {
        val c = center
        val r = size.minDimension / 2f * 0.96f
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 9f))
        val dim = th.fg.copy(alpha = 0.6f)
        // slow rotating dial
        rotate(t, c) {
            for (i in 0 until 72) {
                val a = Math.toRadians((i * 5).toDouble())
                val long = i % 6 == 0
                val r2 = r - (if (long) 14f else 6f)
                drawLine(
                    dim.copy(alpha = if (long) 0.95f else 0.35f),
                    Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                    strokeWidth = if (long) 2f else 1f
                )
            }
        }
        // radius factor, flatness, base tilt, speed (whole numbers so the loop is seamless)
        val rings = arrayOf(
            floatArrayOf(0.84f, 0.80f, 12f, 1f),
            floatArrayOf(0.72f, 0.62f, 70f, -2f),
            floatArrayOf(0.60f, 0.78f, 130f, 2f),
            floatArrayOf(0.46f, 0.70f, 30f, -3f),
            floatArrayOf(0.33f, 0.90f, 95f, 3f),
        )
        for (g in rings) {
            val a = r * g[0]
            val b = a * g[1]
            rotate(g[2] + t * g[3], c) {
                drawOval(dim, Offset(c.x - a, c.y - b), Size(2 * a, 2 * b), style = Stroke(1.3f, pathEffect = dash))
            }
        }
        fun pos(g: FloatArray, deg: Float): Offset {
            val a = r * g[0]
            val b = a * g[1]
            val ang = Math.toRadians(deg.toDouble())
            val x = a * cos(ang)
            val y = b * sin(ang)
            val rot = Math.toRadians((g[2] + t * g[3]).toDouble())
            return Offset(c.x + (x * cos(rot) - y * sin(rot)).toFloat(), c.y + (x * sin(rot) + y * cos(rot)).toFloat())
        }
        // red satellite with a fading trail
        for (k in 4 downTo 1) {
            drawCircle(th.red.copy(alpha = 0.7f - k * 0.15f), radius = 6f - k, center = pos(rings[1], t * 4 - k * 9))
        }
        drawCircle(th.red, radius = 7f, center = pos(rings[1], t * 4))
        // white satellite
        drawCircle(th.fg, radius = 5f, center = pos(rings[0], -t * 2 + 40))
        // hollow satellite
        drawCircle(th.fg, radius = 7f, center = pos(rings[2], t * 3), style = Stroke(1.5f))
        // plus marker
        val pm = pos(rings[3], -t * 3 + 120)
        drawLine(th.fg, Offset(pm.x - 6, pm.y), Offset(pm.x + 6, pm.y), strokeWidth = 1.5f)
        drawLine(th.fg, Offset(pm.x, pm.y - 6), Offset(pm.x, pm.y + 6), strokeWidth = 1.5f)
        // small dot on the inner orbit
        drawCircle(dim, radius = 3f, center = pos(rings[4], t * 5))
        // dot-matrix core
        val counts = intArrayOf(8, 14, 22)
        for (j in counts.indices) {
            val rad = r * (0.09f + 0.07f * j)
            for (i in 0 until counts[j]) {
                val a = Math.toRadians(i * 360.0 / counts[j] + j * 12)
                val al = 0.25f + 0.75f * (0.5f + 0.5f * sin(2 * Math.PI * (p - j * 0.15)).toFloat())
                drawCircle(th.fg.copy(alpha = al), radius = 1.8f, center = Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat()))
            }
        }
        drawCircle(th.red, radius = 4f, center = c)
        if (active) {
            drawCircle(th.red.copy(alpha = (1f - p) * 0.7f), radius = r * (0.1f + 0.5f * p), center = c, style = Stroke(2f))
        }
    }
}

@Composable
fun QrCard(text: String, box: Dp) {
    val res = remember(text) {
        runCatching {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
            val n = m.width
            val scale = 10
            val quiet = 4
            val dim = (n + quiet * 2) * scale
            val px = IntArray(dim * dim) { android.graphics.Color.WHITE }
            for (y in 0 until n) for (x in 0 until n) {
                if (m.get(x, y)) {
                    for (dy in 0 until scale) for (dx in 0 until scale) {
                        px[((y + quiet) * scale + dy) * dim + (x + quiet) * scale + dx] = android.graphics.Color.BLACK
                    }
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(dim, dim, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, dim, 0, 0, dim, dim)
            bmp.asImageBitmap()
        }
    }
    val img = res.getOrNull()
    Box(Modifier.size(box).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, "QR code", Modifier.fillMaxSize().padding(6.dp), filterQuality = FilterQuality.None)
        else Text("QR error: ${res.exceptionOrNull()}", color = Color.Black, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
fun App() {
    var dark by remember { mutableStateOf(Prefs.dark) }
    val th = Th(
        bg = if (dark) Color.Black else Color(0xFFF2F2F0),
        fg = if (dark) Color.White else Color(0xFF0B0B0B),
        dim = if (dark) Color(0xFF8A8A8A) else Color(0xFF6B6B6B),
        red = Color(0xFFFF3B1F),
        font = dotFont()
    )
    val scheme = if (dark) darkColorScheme(background = th.bg, surface = th.bg, primary = th.red, onSurface = th.fg)
    else lightColorScheme(background = th.bg, surface = th.bg, primary = th.red, onSurface = th.fg)
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf(0) } // 0 = this phone sends its screen, 1 = this phone plays the cast

    // Screen-capture permission: shared by Cast (after the receiver allows) and Rec.
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val mode = Session.pending.value
        Session.pending.value = null
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null && mode != null) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CastService::class.java).putExtra("mode", mode).putExtra("code", r.resultCode).putExtra("data", data)
            )
        } else {
            Session.link?.close()
            Session.status.value = "Cancelled"
        }
    }
    val pend = Session.pending.value
    LaunchedEffect(pend) {
        if (pend != null) projLauncher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(th.bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                val names = listOf("CAST", "REC", "SET")
                T(names[tab], th, 36)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    names.forEachIndexed { i, s ->
                        T(s, th, 14, if (i == tab) th.red else th.dim, Modifier.clickable { tab = i })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> CastTab(th, role) { role = it }
                        1 -> RecTab(th)
                        else -> SetTab(th, dark) { dark = it; Prefs.dark = it }
                    }
                }
            }
            Receiver.ask.value?.let { name ->
                AlertDialog(
                    onDismissRequest = { Receiver.answer(false, false) },
                    title = { T("$name wants to cast", th, 16) },
                    text = { T("Allow this device to show its screen here?", th, 13, th.dim) },
                    confirmButton = {
                        Row {
                            TextButton({ Receiver.answer(true, true) }) { T("ALWAYS", th, 13, th.dim) }
                            TextButton({ Receiver.answer(true, false) }) { T("ALLOW", th, 13, th.red) }
                        }
                    },
                    dismissButton = { TextButton({ Receiver.answer(false, false) }) { T("DENY", th, 13) } }
                )
            }
            if (Receiver.viewing.value) Viewer(th)
        }
    }
}

@Composable
fun CastTab(th: Th, role: Int, setRole: (Int) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("SEND", th, role == 0) { setRole(0) }
            Pill("RECEIVE", th, role == 1) { setRole(1) }
        }
        T(
            if (role == 0) "This phone's screen goes to another phone" else "This phone plays another phone's screen",
            th, 12, th.dim
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { if (role == 0) SendSide(th) else ReceiveSide(th) }
    }
}

@Composable
fun SendSide(th: Th) {
    var addrDlg by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    DisposableEffect(Unit) { Discovery.discover(); onDispose { Discovery.stopDiscover() } }
    val ctx = LocalContext.current
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) Sender.connectQr(text) else Session.status.value = "No QR read: try again"
    }
    fun startScan() {
        scan.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR on the receiving phone").setBeepEnabled(false).setOrientationLocked(false)
        )
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startScan() else Session.status.value = "Allow the camera to scan the QR"
    }
    val casting = Session.casting.value
    val status = Session.status.value
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orbit(casting || status.endsWith("…"), th, Modifier.fillMaxSize())
        }
        T(status, th, 16, mod = Modifier.fillMaxWidth(), center = true)
        if (casting) {
            T(Session.stats.value, th, 12, th.dim, Modifier.fillMaxWidth(), true)
            Pill("STOP", th, true) { Session.app?.let { CastService.stop(it) } }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("SCAN QR", th, true) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScan()
                    else camPerm.launch(Manifest.permission.CAMERA)
                }
                Pill("TYPE ADDRESS", th) { addrDlg = true }
            }
            val list = Discovery.found.entries.toList()
            if (list.isNotEmpty()) T("NEARBY", th, 12, th.dim)
            list.forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(e.key.uppercase(), th, 16)
                    Pill("PAIR", th) { pinFor = e.value }
                }
            }
        }
    }
    if (addrDlg) {
        var ip by remember { mutableStateOf("") }
        var pc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addrDlg = false },
            title = { T("Connect by address", th, 15) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ip, { ip = it.trim() }, singleLine = true, label = { Text("Address shown on receiver") })
                    OutlinedTextField(pc, { pc = it.trim() }, singleLine = true, label = { Text("PIN") })
                }
            },
            confirmButton = {
                TextButton({
                    addrDlg = false
                    val host = ip.substringBefore(':')
                    val port = ip.substringAfter(':', "7777").toIntOrNull() ?: 7777
                    Sender.connectLan(listOf(host), port, pc)
                }) { T("CONNECT", th, 13, th.red) }
            },
            dismissButton = { TextButton({ addrDlg = false }) { T("CANCEL", th, 13) } }
        )
    }
    pinFor?.let { hp ->
        var pc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinFor = null },
            title = { T("PIN shown on receiver", th, 15) },
            text = { OutlinedTextField(pc, { pc = it.trim() }, singleLine = true) },
            confirmButton = {
                TextButton({
                    pinFor = null
                    Sender.connectLan(listOf(hp.first), hp.second, pc)
                }) { T("CONNECT", th, 13, th.red) }
            },
            dismissButton = { TextButton({ pinFor = null }) { T("CANCEL", th, 13) } }
        )
    }
}

@Composable
fun ReceiveSide(th: Th) {
    DisposableEffect(Unit) { Receiver.start(); onDispose { Receiver.stop() } }
    val ips = Receiver.ips.value
    val port = Receiver.port.intValue
    val pin = Receiver.pin.value
    val payload = if (port > 0 && ips.isNotEmpty()) Pairing.payload(ips, port, pin, Prefs.deviceName) else ""
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orbit(false, th, Modifier.fillMaxSize())
            if (payload.isNotEmpty()) QrCard(payload, 230.dp)
            else T("Join a Wi-Fi network or turn on your hotspot", th, 14, th.dim, Modifier.padding(40.dp), true)
        }
        T("Waiting for a device", th, 16, mod = Modifier.fillMaxWidth(), center = true)
        T(pin, th, 34, th.red, Modifier.fillMaxWidth(), true)
        T("Scan the QR on the casting phone", th, 12, th.dim, Modifier.fillMaxWidth(), true)
        if (ips.isNotEmpty()) T(ips.joinToString("   ") { "$it:$port" }, th, 11, th.dim, Modifier.fillMaxWidth(), true)
    }
}

@Composable
fun RecTab(th: Th) {
    val ctx = LocalContext.current
    var side by remember { mutableStateOf(Prefs.shortSide) }
    var mbps by remember { mutableStateOf(Prefs.mbps) }
    var fps by remember { mutableStateOf(Prefs.fps) }
    var mic by remember { mutableStateOf(Prefs.mic) }
    var inn by remember { mutableStateOf(Prefs.internal) }
    var cd by remember { mutableStateOf(0) }
    val recording = Session.casting.value && Session.status.value == "Recording"
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Session.pending.value = "rec" }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) cd = 1 else Session.status.value = "Allow storage to save recordings"
    }

    fun begin() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val need = (mic || inn) && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (need) micPerm.launch(Manifest.permission.RECORD_AUDIO) else Session.pending.value = "rec"
    }
    LaunchedEffect(cd) {
        if (cd > 0) {
            delay(1000)
            if (cd == 1) { cd = 0; begin() } else cd -= 1
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("QUALITY (also used for casting)", th, 12, th.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (n, v) ->
                Pill(n, th, side == v.first && mbps == v.second && fps == v.third) {
                    side = v.first; mbps = v.second; fps = v.third
                    Prefs.shortSide = side; Prefs.mbps = mbps; Prefs.fps = fps
                }
            }
        }
        T("RESOLUTION", th, 12, th.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(720, 1080, 1440, 0).forEach { s ->
                Pill(if (s == 0) "NATIVE" else "${s}P", th, side == s) { side = s; Prefs.shortSide = s }
            }
        }
        T("BITRATE  $mbps MBPS", th, 12, th.dim)
        Slider(mbps.toFloat(), { mbps = it.toInt(); Prefs.mbps = mbps }, valueRange = 2f..100f)
        T("FRAME RATE", th, 12, th.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 120).forEach { f -> Pill("$f", th, fps == f) { fps = f; Prefs.fps = f } }
        }
        T("AUDIO", th, 12, th.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("MIC", th, mic) { mic = !mic; Prefs.mic = mic }
            if (Build.VERSION.SDK_INT >= 29) Pill("INTERNAL", th, inn) { inn = !inn; Prefs.internal = inn }
        }
        if (Build.VERSION.SDK_INT < 29) T("Internal audio needs Android 10+", th, 12, th.dim)
        T("≈ ${"%.0f".format(mbps * 7.5)} MB per minute", th, 13, th.dim)
        Spacer(Modifier.height(4.dp))
        when {
            recording -> {
                T(Session.stats.value, th, 14, th.red)
                Pill("STOP RECORDING", th, true) { Session.app?.let { CastService.stop(it) } }
            }
            cd > 0 -> T("$cd", th, 48, th.red)
            else -> Pill("START RECORDING", th, true) { cd = 3 }
        }
        T(Session.status.value, th, 12, th.dim)
    }
}

@Composable
fun SetTab(th: Th, dark: Boolean, setDark: (Boolean) -> Unit) {
    var name by remember { mutableStateOf(Prefs.deviceName) }
    var trusted by remember { mutableStateOf(Prefs.trusted) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("THEME", th, 12, th.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("BLACK", th, dark) { setDark(true) }
            Pill("LIGHT", th, !dark) { setDark(false) }
        }
        T("DEVICE NAME", th, 12, th.dim)
        OutlinedTextField(name, { name = it; Prefs.deviceName = it }, singleLine = true)
        T("TRUSTED DEVICES", th, 12, th.dim)
        if (trusted.isEmpty()) T("None", th, 13) else trusted.forEach { T(it, th, 14) }
        if (trusted.isNotEmpty()) Pill("FORGET ALL", th) { Prefs.trusted = emptySet(); trusted = emptySet() }
    }
}

@Composable
fun Viewer(th: Th) {
    val act = LocalContext.current as Activity
    val view = LocalView.current
    BackHandler { Receiver.endSession() }
    DisposableEffect(Unit) {
        val c = WindowCompat.getInsetsController(act.window, view)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.keepScreenOn = true
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()); view.keepScreenOn = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val ar = if (Receiver.vh.intValue > 0) Receiver.vw.intValue.toFloat() / Receiver.vh.intValue else 9f / 19f
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { Receiver.surface = h.surface; Receiver.tryStart() }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { Receiver.surfaceGone() }
                    })
                }
            },
            modifier = Modifier.aspectRatio(ar)
        )
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(Receiver.stats.value, th, 11, Color.White)
            Pill("PIP", th) { act.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
            Pill("STOP", th, true) { Receiver.endSession() }
        }
    }
}
