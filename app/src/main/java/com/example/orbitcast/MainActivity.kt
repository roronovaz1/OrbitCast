package com.example.orbitcast

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

@Composable
fun Orbit(active: Boolean, th: Th, mod: Modifier) {
    val tr = rememberInfiniteTransition(label = "orbit")
    val t by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 8000 else 26000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(mod) {
        val c = center
        val r = size.minDimension / 2f * 0.95f
        val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 9f))
        val ks = floatArrayOf(1f, 0.78f, 0.58f, 0.38f)
        for (i in ks.indices) {
            val k = ks[i]
            rotate((if (i % 2 == 0) t else -t) * (0.6f + i * 0.35f) + i * 47f, c) {
                drawOval(th.fg.copy(alpha = 0.75f), Offset(c.x - r * k, c.y - r * k * 0.8f), Size(2 * r * k, 2 * r * k * 0.8f), style = Stroke(1.5f, pathEffect = dash))
            }
        }
        val a = Math.toRadians((t * 1.5f).toDouble())
        drawCircle(th.red, radius = 7f, center = Offset(c.x + (r * 0.78f * cos(a)).toFloat(), c.y + (r * 0.78f * 0.8f * sin(a)).toFloat()))
        val b = Math.toRadians((-t).toDouble())
        drawCircle(th.fg, radius = 5f, center = Offset(c.x + (r * 0.58f * cos(b)).toFloat(), c.y + (r * 0.58f * 0.8f * sin(b)).toFloat()))
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
    var internet by remember { mutableStateOf(false) }

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
                val names = listOf("CAST", "RECEIVE", "REC", "SET")
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
                        0 -> CastTab(th, internet) { internet = it }
                        1 -> ReceiveTab(th, internet) { internet = it }
                        2 -> RecTab(th)
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
fun Transport(th: Th, internet: Boolean, set: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("WI-FI", th, !internet) { set(false) }
        Pill("INTERNET", th, internet) { set(true) }
    }
}

@Composable
fun CastTab(th: Th, internet: Boolean, setNet: (Boolean) -> Unit) {
    var codeDlg by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Pair<String, Int>?>(null) }
    DisposableEffect(Unit) { Discovery.discover(); onDispose { Discovery.stopDiscover() } }
    val casting = Session.casting.value
    val status = Session.status.value
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Transport(th, internet, setNet)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orbit(casting || status.endsWith("…"), th, Modifier.fillMaxSize())
        }
        T(status, th, 18, mod = Modifier.fillMaxWidth(), center = true)
        if (casting) {
            T(Session.stats.value, th, 12, th.dim, Modifier.fillMaxWidth(), true)
            Pill("STOP", th, true) { Session.app?.let { CastService.stop(it) } }
        } else if (!internet) {
            T("DEVICES", th, 14, th.dim)
            val list = Discovery.found.entries.toList()
            if (list.isEmpty()) T("Open RECEIVE on the other phone…", th, 13, th.dim)
            list.forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(e.key.uppercase(), th, 18)
                    Pill("PAIR", th) { target = e.value; codeDlg = true }
                }
            }
        } else {
            Pill("ENTER CODE", th) { target = null; codeDlg = true }
        }
    }
    if (codeDlg) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { codeDlg = false },
            title = { T(if (internet) "Code (ROOM-123456)" else "PIN shown on receiver", th, 15) },
            text = { OutlinedTextField(code, { code = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    codeDlg = false
                    val tg = target
                    if (internet) Sender.connectRelay(code) else if (tg != null) Sender.connectLan(tg.first, tg.second, code)
                }) { T("CONNECT", th, 13, th.red) }
            },
            dismissButton = { TextButton({ codeDlg = false }) { T("CANCEL", th, 13) } }
        )
    }
}

@Composable
fun ReceiveTab(th: Th, internet: Boolean, setNet: (Boolean) -> Unit) {
    DisposableEffect(Unit) { Receiver.start(); onDispose { Receiver.stop() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Transport(th, internet, setNet)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Orbit(false, th, Modifier.fillMaxSize()) }
        T("Waiting for a device", th, 18, mod = Modifier.fillMaxWidth(), center = true)
        val code = if (internet) {
            if (Prefs.relay.isBlank()) "SET RELAY URL IN SET" else Receiver.room.value + "-" + Receiver.pin.value
        } else Receiver.pin.value
        T(code, th, 34, th.red, Modifier.fillMaxWidth(), true)
        T("Enter this code on the casting phone · ${Prefs.deviceName}", th, 12, th.dim, Modifier.fillMaxWidth(), true)
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
    val recording = Session.casting.value
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Session.pending.value = "rec" }

    fun begin() {
        val need = (mic || inn) && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
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
    var relay by remember { mutableStateOf(Prefs.relay) }
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
        T("INTERNET RELAY URL", th, 12, th.dim)
        OutlinedTextField(relay, { relay = it.trim(); Prefs.relay = relay }, singleLine = true,
            placeholder = { Text("wss://your-relay.example.com") })
        T("Leave empty to use Wi-Fi only. See server/README for the relay.", th, 12, th.dim)
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
