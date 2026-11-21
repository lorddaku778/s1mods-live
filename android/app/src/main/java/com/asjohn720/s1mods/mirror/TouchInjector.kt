package com.asjohn720.s1mods.mirror

import android.view.MotionEvent
import com.asjohn720.s1mods.model.ScooterManager
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.hypot

/**
 * Sends touches from the phone to the scooter.
 *
 *  EVDEV      (fast, multi-touch, real-time): raw Linux input events are streamed straight into the
 *             head unit's touch panel device (/dev/input/eventN) through a binary ADB "exec:" pipe.
 *             Finger down / move / up are forwarded as they happen, so scrolling and swiping follow
 *             your finger with no per-gesture process start-up.
 *  ADB_INPUT  (fallback): `input tap/swipe` over ADB, sent when the finger lifts.
 *  GRPC_INPUT (last resort): same, through the root gRPC daemon.
 */
class TouchInjector(
    private val manager: ScooterManager,
    private val dadb: Dadb,
    /** Size of the frame the user sees, in the orientation currently shown. */
    private val shownW: Int,
    private val shownH: Int,
    /** Natural (portrait/landscape as built) display size from `wm size`. */
    private val naturalW: Int,
    private val naturalH: Int,
    /** Current display rotation, android.view.Surface.ROTATION_* (0..3). */
    private val rotation: Int
) {
    enum class Mode { EVDEV, ADB_INPUT, GRPC_INPUT }

    @Volatile var mode: Mode = Mode.ADB_INPUT
        private set
    /** User adjustable extra rotation (0..3) for panels mounted rotated against the display. */
    @Volatile var extraRotation = 0

    private var dev: TouchDev? = null
    private var eventSize = 24
    private var pipe: AdbStream? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "touch-writer").apply { isDaemon = true } }
    private val cmdExec = Executors.newSingleThreadExecutor { r -> Thread(r, "touch-cmd").apply { isDaemon = true } }
    @Volatile private var closed = false

    private var nextTrackingId = 1
    private val active = HashMap<Int, Int>() // android pointer id -> slot

    /** Detects the touch panel and picks the best mode. Blocking: call from a background thread. */
    fun prepare(): String {
        try {
            val txt = dadb.shell("getevent -lp").allOutput
            val d = GetEventParser.pick(GetEventParser.parse(txt))
            if (d != null) {
                val arch = dadb.shell("uname -m").allOutput.trim()
                eventSize = if (arch.contains("64")) 24 else 16
                // Harmless probe: one all-zero event (SYN_REPORT) to prove the device is writable.
                val probe = dadb.shell("dd if=/dev/zero of=${d.path} bs=$eventSize count=1 2>&1").allOutput
                val ok = probe.contains("1+0 records out") &&
                    !probe.contains("denied", true) && !probe.contains("No such", true) && !probe.contains("error", true)
                if (ok) {
                    dev = d
                    val rawLandscape = (d.maxX - d.minX) >= (d.maxY - d.minY)
                    val naturalLandscape = naturalW >= naturalH
                    extraRotation = if (rawLandscape != naturalLandscape) 1 else 0
                    pipe = dadb.open("exec:cat > ${d.path}")
                    mode = Mode.EVDEV
                    writer.execute { writeLoop() }
                    return "Touch: real-time (${d.path})"
                }
            }
        } catch (_: Exception) {
        }
        mode = Mode.ADB_INPUT
        return "Touch: compatible mode (tap/swipe sent on release)"
    }

    private fun writeLoop() {
        val sink = pipe?.sink ?: return
        try {
            while (!closed) {
                val b = queue.take()
                sink.write(b)
                sink.flush()
            }
        } catch (_: Exception) {
            if (!closed) mode = Mode.ADB_INPUT // pipe died: degrade gracefully
        }
    }

    fun close() {
        closed = true
        queue.clear()
        try { pipe?.close() } catch (_: Exception) {}
        writer.shutdownNow()
        cmdExec.shutdownNow()
    }

    /** Feed raw touch events from the mirror view. */
    fun onTouch(e: MotionEvent, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0 || closed) return
        if (mode == Mode.EVDEV) evdevTouch(e, viewW, viewH) else gestureTouch(e, viewW, viewH)
    }

    // ---------------------------------------------------------------- EVDEV

    private fun rawXY(d: TouchDev, x: Float, y: Float, vw: Int, vh: Int): Pair<Int, Int> {
        val u = (x / vw).coerceIn(0f, 1f)
        val v = (y / vh).coerceIn(0f, 1f)
        val (un, vn) = TouchMath.toNatural(u, v, rotation + extraRotation)
        val rx = d.minX + (un * (d.maxX - d.minX)).toInt()
        val ry = d.minY + (vn * (d.maxY - d.minY)).toInt()
        return rx to ry
    }

    private fun EvdevFrame.point(d: TouchDev, slot: Int, x: Int, y: Int): EvdevFrame {
        if (d.slotMax >= 0) ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_SLOT, slot)
        return this
    }

    private fun EvdevFrame.coords(d: TouchDev, x: Int, y: Int): EvdevFrame {
        ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_POSITION_X, x)
        ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_POSITION_Y, y)
        if (d.pressureMax > 0) ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_PRESSURE, minOf(d.pressureMax, 60))
        if (d.touchMajorMax > 0) ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_TOUCH_MAJOR, minOf(d.touchMajorMax, 8))
        if (d.slotMax < 0) ev(EvdevFrame.EV_SYN, EvdevFrame.SYN_MT_REPORT, 0)
        return this
    }

    private fun evdevTouch(e: MotionEvent, vw: Int, vh: Int) {
        val d = dev ?: return
        val slots = if (d.slotMax >= 0) minOf(d.slotMax + 1, 10) else 1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val pid = e.getPointerId(i)
                val slot = freeSlot(slots) ?: return
                val firstFinger = active.isEmpty()
                active[pid] = slot
                val (rx, ry) = rawXY(d, e.getX(i), e.getY(i), vw, vh)
                val f = EvdevFrame(eventSize).point(d, slot, rx, ry)
                if (d.slotMax >= 0 && d.hasTrackingId) {
                    f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_TRACKING_ID, nextTrackingId)
                    nextTrackingId = if (nextTrackingId >= 60000) 1 else nextTrackingId + 1
                }
                f.coords(d, rx, ry)
                if (firstFinger) {
                    if (d.hasBtnTouch) f.ev(EvdevFrame.EV_KEY, EvdevFrame.BTN_TOUCH, 1)
                    if (d.hasAbsXY) {
                        f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_X, rx)
                        f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_Y, ry)
                    }
                }
                f.ev(EvdevFrame.EV_SYN, EvdevFrame.SYN_REPORT, 0)
                queue.offer(f.bytes())
            }

            MotionEvent.ACTION_MOVE -> {
                if (active.isEmpty()) return
                // Under a slow link keep only the newest move so the finger never lags behind.
                if (queue.size > 6) return
                val f = EvdevFrame(eventSize)
                var first = true
                for (i in 0 until e.pointerCount) {
                    val slot = active[e.getPointerId(i)] ?: continue
                    val (rx, ry) = rawXY(d, e.getX(i), e.getY(i), vw, vh)
                    f.point(d, slot, rx, ry).coords(d, rx, ry)
                    if (first && d.hasAbsXY) {
                        f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_X, rx)
                        f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_Y, ry)
                    }
                    first = false
                }
                f.ev(EvdevFrame.EV_SYN, EvdevFrame.SYN_REPORT, 0)
                queue.offer(f.bytes())
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                val slot = active.remove(pid) ?: return
                queue.offer(liftFrame(d, slot, active.isEmpty()))
            }

            MotionEvent.ACTION_CANCEL -> releaseAll(d)
        }
    }

    private fun liftFrame(d: TouchDev, slot: Int, last: Boolean): ByteArray {
        val f = EvdevFrame(eventSize)
        if (d.slotMax >= 0) {
            f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_SLOT, slot)
            if (d.hasTrackingId) f.ev(EvdevFrame.EV_ABS, EvdevFrame.ABS_MT_TRACKING_ID, -1)
        } else {
            f.ev(EvdevFrame.EV_SYN, EvdevFrame.SYN_MT_REPORT, 0)
        }
        if (last && d.hasBtnTouch) f.ev(EvdevFrame.EV_KEY, EvdevFrame.BTN_TOUCH, 0)
        f.ev(EvdevFrame.EV_SYN, EvdevFrame.SYN_REPORT, 0)
        return f.bytes()
    }

    private fun releaseAll(d: TouchDev) {
        val slotsNow = active.values.toList()
        active.clear()
        slotsNow.forEachIndexed { i, s -> queue.offer(liftFrame(d, s, i == slotsNow.lastIndex)) }
    }

    private fun freeSlot(n: Int): Int? = (0 until n).firstOrNull { it !in active.values }

    // ---------------------------------------------------------------- tap / swipe fallback

    private var gx0 = 0f
    private var gy0 = 0f
    private var gx = 0f
    private var gy = 0f
    private var gt0 = 0L
    private var gActive = false

    private fun gestureTouch(e: MotionEvent, vw: Int, vh: Int) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gx0 = e.x; gy0 = e.y; gx = e.x; gy = e.y
                gt0 = e.eventTime
                gActive = true
            }

            MotionEvent.ACTION_MOVE -> if (gActive) { gx = e.x; gy = e.y }
            MotionEvent.ACTION_CANCEL -> gActive = false
            MotionEvent.ACTION_UP -> if (gActive) {
                gActive = false
                val dur = (e.eventTime - gt0).toInt()
                val x0 = px(gx0, vw, shownW); val y0 = px(gy0, vh, shownH)
                val x1 = px(e.x, vw, shownW); val y1 = px(e.y, vh, shownH)
                val moved = hypot(e.x - gx0, e.y - gy0)
                val slop = 24f * (vw / 600f).coerceAtLeast(1f)
                cmdExec.execute {
                    when {
                        moved < slop && dur >= 500 -> sendCmd("input swipe $x0 $y0 $x0 $y0 ${dur.coerceIn(600, 1500)}") { manager.sendLongPress(x0, y0) }
                        moved < slop -> sendCmd("input tap $x0 $y0") { manager.sendTap(x0, y0) }
                        else -> {
                            val d = dur.coerceIn(80, 700)
                            sendCmd("input swipe $x0 $y0 $x1 $y1 $d") { manager.sendSwipe(x0, y0, x1, y1, d) }
                        }
                    }
                }
            }
        }
    }

    private fun px(v: Float, viewSize: Int, shown: Int) = ((v / viewSize).coerceIn(0f, 1f) * shown).toInt().coerceIn(0, shown - 1)

    private fun sendCmd(adbCmd: String, grpc: suspend () -> Boolean) {
        try {
            if (mode == Mode.GRPC_INPUT) {
                runBlocking { grpc() }
            } else {
                dadb.shell(adbCmd)
            }
        } catch (_: Exception) {
            if (mode == Mode.ADB_INPUT) mode = Mode.GRPC_INPUT
        }
    }

    /** Hardware-style key press on the scooter (3 = HOME, 4 = BACK, 187 = RECENTS). */
    fun key(code: Int) {
        cmdExec.execute {
            try { dadb.shell("input keyevent $code") } catch (_: Exception) {}
        }
    }
}
