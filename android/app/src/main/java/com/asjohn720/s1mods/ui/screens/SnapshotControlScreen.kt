package com.asjohn720.s1mods.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asjohn720.s1mods.model.ScooterManager
import com.asjohn720.s1mods.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mirrors the head unit's screen as a live screenshot and turns a tap/long-press
 * on that image into a real `input tap` / `input swipe` event sent over ADB.
 * Because these are injected input events rather than physical touches, they
 * land correctly even in areas where the digitizer itself doesn't respond —
 * this is the "use the bottom half" workaround for a partially dead touchscreen.
 */
@Composable
fun SnapshotRemoteControl(manager: ScooterManager, onBackToLive: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    var resolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var imagePx by remember { mutableStateOf(IntSize.Zero) }
    var isBusy by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(false) }
    var lastTap by remember { mutableStateOf<Offset?>(null) }
    var statusText by remember { mutableStateOf("Loading...") }

    suspend fun refresh() {
        isBusy = true
        try {
            if (resolution == null) {
                resolution = manager.fetchScreenResolution()
            }
            screenshot = manager.captureScreenshot()
            statusText = "Live — tap or long-press the image to send a real touch"
        } catch (e: Exception) {
            statusText = "Capture failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    fun handleTouch(pos: Offset, longPress: Boolean) {
        val res = resolution ?: return
        if (imagePx.width == 0 || imagePx.height == 0) return
        val x = (pos.x / imagePx.width * res.first).toInt().coerceIn(0, res.first - 1)
        val y = (pos.y / imagePx.height * res.second).toInt().coerceIn(0, res.second - 1)
        lastTap = pos
        coroutineScope.launch {
            if (longPress) manager.sendLongPress(x, y) else manager.sendTap(x, y)
            delay(400)
            if (!isBusy) refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(1500)
            if (!isBusy) refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Snapshot mode", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(
                    text = resolution?.let { "${it.first}\u00D7${it.second}" } ?: "Resolution unknown",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBackToLive) { Text("Live", color = AccentMint, fontSize = 12.sp) }
                Text("Auto-refresh", fontSize = 11.sp, color = TextMuted)
                Switch(
                    checked = autoRefresh,
                    onCheckedChange = { autoRefresh = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentMint, checkedTrackColor = AccentMintSubtle)
                )
                IconButton(onClick = { coroutineScope.launch { refresh() } }, enabled = !isBusy) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                }
            }
        }

        Text(statusText, fontSize = 11.sp, color = TextMuted)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .background(SurfaceVariantDark),
            contentAlignment = Alignment.Center
        ) {
            val bmp = screenshot
            val res = resolution
            if (bmp != null) {
                val aspectMod = if (res != null) Modifier.aspectRatio(res.first.toFloat() / res.second.toFloat()) else Modifier.fillMaxSize()
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Scooter live screen — tap to send a real touch",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(aspectMod)
                        .onSizeChanged { imagePx = it }
                        .pointerInput(res, imagePx) {
                            detectTapGestures(
                                onTap = { pos -> handleTouch(pos, longPress = false) },
                                onLongPress = { pos -> handleTouch(pos, longPress = true) }
                            )
                        }
                )

                lastTap?.let { pos ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = AccentMint, radius = 16f, center = pos, style = Stroke(width = 3f))
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = TextMuted, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isBusy) "Loading..." else "No screenshot yet", color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        Text(
            text = "Tap = regular tap · Long-press (hold ~0.5s) = long-press. These are injected input events sent over ADB, so they land correctly even where the physical touchscreen doesn't respond.",
            fontSize = 11.sp,
            color = TextMuted,
            lineHeight = 15.sp
        )
    }
}
