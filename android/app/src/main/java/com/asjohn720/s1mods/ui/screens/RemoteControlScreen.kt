package com.asjohn720.s1mods.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.asjohn720.s1mods.mirror.MirrorSession
import com.asjohn720.s1mods.mirror.TouchInjector
import com.asjohn720.s1mods.model.ScooterManager
import com.asjohn720.s1mods.ui.theme.*
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class MirrorParams(
    val dadb: Dadb,
    val encW: Int,
    val encH: Int,
    val bitRate: Int,
    val shownW: Int,
    val shownH: Int,
    val injector: TouchInjector,
    val touchInfo: String
)

private val QUALITY_NAMES = listOf("Fast", "Balanced", "Sharp")
private val QUALITY_MAX_SIDE = intArrayOf(800, 1024, 1280)
private val QUALITY_BITRATE = intArrayOf(3_000_000, 5_000_000, 8_000_000)

/**
 * Live screen mirror of the scooter display with real-time touch, multi-touch and swipe.
 * Falls back to the old snapshot viewer if live capture cannot be used.
 */
@Composable
fun RemoteControlScreen(manager: ScooterManager) {
    var snapshotMode by remember { mutableStateOf(false) }
    if (snapshotMode) {
        SnapshotRemoteControl(manager, onBackToLive = { snapshotMode = false })
    } else {
        LiveMirror(manager, onSnapshot = { snapshotMode = true })
    }
}

private fun readRotation(dadb: Dadb): Int {
    return try {
        val a = dadb.shell("dumpsys input | grep -m1 SurfaceOrientation").allOutput
        Regex("""SurfaceOrientation:\s*(\d)""").find(a)?.groupValues?.get(1)?.toInt() ?: 0
    } catch (_: Exception) {
        0
    }
}

private suspend fun prepareMirror(manager: ScooterManager, quality: Int): MirrorParams =
    withContext(Dispatchers.IO) {
        val dadb = manager.ensureDadb()
        val natural = manager.fetchScreenResolution()
            ?: throw IllegalStateException("could not read the scooter screen size")
        val rot = readRotation(dadb)
        val odd = rot % 2 == 1
        val shownW = if (odd) natural.second else natural.first
        val shownH = if (odd) natural.first else natural.second

        val scale = minOf(1f, QUALITY_MAX_SIDE[quality] / maxOf(shownW, shownH).toFloat())
        val encW = ((shownW * scale).toInt() / 16 * 16).coerceAtLeast(16)
        val encH = ((shownH * scale).toInt() / 16 * 16).coerceAtLeast(16)

        val injector = TouchInjector(manager, dadb, shownW, shownH, natural.first, natural.second, rot)
        val info = injector.prepare()
        MirrorParams(dadb, encW, encH, QUALITY_BITRATE[quality], shownW, shownH, injector, info)
    }

@Composable
private fun LiveMirror(manager: ScooterManager, onSnapshot: () -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val fullscreen by manager.mirrorFullscreen.collectAsState()

    var quality by remember { mutableIntStateOf(1) }
    var restartKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connecting to scooter...") }
    var params by remember { mutableStateOf<MirrorParams?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var extraRot by remember { mutableIntStateOf(0) }

    // 1) Connect over ADB, read screen size / rotation, detect the touch panel.
    LaunchedEffect(quality, restartKey) {
        params = null
        status = "Connecting to scooter..."
        try {
            val p = prepareMirror(manager, quality)
            extraRot = p.injector.extraRotation
            params = p
            status = "Starting video..."
        } catch (e: Exception) {
            status = "Could not start live view: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Close the touch pipe whenever the params are replaced or the screen leaves.
    val currentParams = params
    DisposableEffect(currentParams) {
        onDispose { currentParams?.injector?.close() }
    }

    // 2) Run the decoder while we have both a surface and params.
    DisposableEffect(currentParams, surface) {
        var session: MirrorSession? = null
        val s = surface
        if (currentParams != null && s != null && s.isValid) {
            session = MirrorSession(
                dadb = currentParams.dadb,
                surface = s,
                width = currentParams.encW,
                height = currentParams.encH,
                bitRate = currentParams.bitRate,
                onSize = { _, _ -> },
                onStatus = { t -> main.post { status = t } }
            )
            try {
                session.start()
            } catch (e: Exception) {
                status = "Video decoder error: ${e.message ?: e.javaClass.simpleName}"
                session = null
            }
        }
        onDispose { session?.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(if (fullscreen) 4.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!fullscreen) {
                Text("Live screen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { currentParams?.injector?.key(4) }, enabled = currentParams != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            IconButton(onClick = { currentParams?.injector?.key(3) }, enabled = currentParams != null) {
                Icon(Icons.Default.Home, contentDescription = "Home", tint = TextPrimary)
            }
            IconButton(onClick = { currentParams?.injector?.key(187) }, enabled = currentParams != null) {
                Icon(Icons.Default.CropSquare, contentDescription = "Recent apps", tint = TextPrimary)
            }
            IconButton(
                onClick = {
                    extraRot = (extraRot + 1) % 4
                    currentParams?.injector?.extraRotation = extraRot
                },
                enabled = currentParams != null
            ) {
                Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate touch mapping", tint = TextSecondary)
            }
            IconButton(onClick = { restartKey++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = TextSecondary)
            }
            IconButton(onClick = { manager.mirrorFullscreen.value = !fullscreen }) {
                Icon(
                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Full screen",
                    tint = TextPrimary
                )
            }
        }

        if (!fullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QUALITY_NAMES.forEachIndexed { i, name ->
                    TextButton(onClick = { quality = i }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = if (quality == i) AccentMint else TextMuted,
                            fontWeight = if (quality == i) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onSnapshot) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Snapshot", fontSize = 12.sp, color = TextMuted)
                }
            }
            Text(
                text = listOfNotNull(status, currentParams?.touchInfo).joinToString("  ·  "),
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val ar = currentParams?.let { it.shownW.toFloat() / it.shownH } ?: (5f / 3f)
            val boxAr = maxWidth / maxHeight
            val vw = if (boxAr > ar) maxHeight * ar else maxWidth
            val vh = if (boxAr > ar) maxHeight else maxWidth / ar

            AndroidView(
                modifier = Modifier
                    .size(vw, vh)
                    .clip(RoundedCornerShape(if (fullscreen) 0.dp else 10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(if (fullscreen) 0.dp else 10.dp)),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        keepScreenOn = true
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                surface = h.surface
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                surface = null
                            }
                        })
                    }
                },
                update = { v ->
                    v.setOnTouchListener { view, ev ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        params?.injector?.onTouch(ev, view.width, view.height)
                        true
                    }
                }
            )
        }

        if (!fullscreen) {
            Text(
                text = "Touch the picture like the scooter screen: tap, drag, swipe, pinch. " +
                    "If touches land in the wrong place, press the rotate-touch button until they match.",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp
            )
        }
    }
}
