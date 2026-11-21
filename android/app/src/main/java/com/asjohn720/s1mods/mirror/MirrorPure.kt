package com.asjohn720.s1mods.mirror

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Pure-JVM helpers for live mirroring (no Android imports, so they can be unit tested on a PC).
 */

/**
 * Splits a raw Annex-B H.264 byte stream (what `screenrecord --output-format=h264 -` writes)
 * into access units that MediaCodec can decode. SPS/PPS are glued in front of the next
 * picture NAL, the same way scrcpy does it.
 *
 * A NAL is normally only known to be complete when the *next* start code arrives, which would
 * hold the last frame back until the screen changes again. [flushIfIdle] releases the trailing
 * NAL once the stream has been quiet for a few ms, so a frame is shown immediately.
 */
class AnnexBAssembler(private val onUnit: (data: ByteArray, key: Boolean) -> Unit) {
    private var acc = ByteArray(1 shl 18)
    private var len = 0
    private val prefix = ByteArrayOutputStream()

    @Volatile var lastDataAtMs = 0L
        private set
    @Volatile var unitsEmitted = 0L
        private set

    @Synchronized
    fun feed(src: ByteArray, n: Int, nowMs: Long) {
        if (len + n > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, len + n))
        System.arraycopy(src, 0, acc, len, n)
        len += n
        lastDataAtMs = nowMs
        parse(false)
    }

    @Synchronized
    fun flushIfIdle(nowMs: Long, idleMs: Long) {
        if (len > 0 && nowMs - lastDataAtMs >= idleMs) parse(true)
    }

    @Synchronized
    fun reset() {
        len = 0
        prefix.reset()
    }

    private fun findStart(from: Int): Int {
        var i = from
        while (i + 2 < len) {
            if (acc[i] == 0.toByte() && acc[i + 1] == 0.toByte() && acc[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun parse(force: Boolean) {
        val first = findStart(0)
        if (first < 0) {
            // No start code yet: keep at most 2 bytes (a start code may be split across reads).
            if (len > 2) {
                acc[0] = acc[len - 2]
                acc[1] = acc[len - 1]
                len = 2
            }
            return
        }
        var pos = first
        while (true) {
            val payload = pos + 3
            val next = findStart(payload)
            if (next >= 0) {
                emit(payload, next)
                pos = next
            } else {
                if (force && len - payload > 0) {
                    emit(payload, len)
                    pos = len
                }
                break
            }
        }
        if (pos > 0) {
            System.arraycopy(acc, pos, acc, 0, len - pos)
            len -= pos
        }
    }

    private fun emit(start: Int, end: Int) {
        var e = end
        while (e > start && acc[e - 1] == 0.toByte()) e-- // zero byte of a following 4-byte start code
        if (e <= start) return
        val type = acc[start].toInt() and 0x1F
        when (type) {
            7, 8 -> {
                prefix.write(START4)
                prefix.write(acc, start, e - start)
            }
            1, 5 -> {
                val head = prefix.toByteArray()
                prefix.reset()
                val out = ByteArray(head.size + 4 + (e - start))
                System.arraycopy(head, 0, out, 0, head.size)
                System.arraycopy(START4, 0, out, head.size, 4)
                System.arraycopy(acc, start, out, head.size + 4, e - start)
                unitsEmitted++
                onUnit(out, type == 5)
            }
            else -> Unit // SEI / AUD / etc. are not needed by the decoder
        }
    }

    companion object {
        private val START4 = byteArrayOf(0, 0, 0, 1)
    }
}

/** One touch panel as reported by `getevent -lp`. */
data class TouchDev(
    val path: String,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val slotMax: Int,          // -1 = no ABS_MT_SLOT (protocol A)
    val hasTrackingId: Boolean,
    val hasBtnTouch: Boolean,
    val pressureMax: Int,      // 0 = axis absent
    val touchMajorMax: Int,    // 0 = axis absent
    val hasAbsXY: Boolean,
    val direct: Boolean        // INPUT_PROP_DIRECT (touchscreen, not touchpad)
)

object GetEventParser {
    private val devRe = Regex("""add device \d+:\s*(\S+)""")

    private fun axis(block: String, name: String): Pair<Int, Int>? {
        val m = Regex("""\b$name\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""").find(block) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun parse(text: String): List<TouchDev> {
        val starts = devRe.findAll(text).toList()
        val out = ArrayList<TouchDev>()
        for ((i, m) in starts.withIndex()) {
            val block = text.substring(m.range.first, if (i + 1 < starts.size) starts[i + 1].range.first else text.length)
            val x = axis(block, "ABS_MT_POSITION_X") ?: continue
            val y = axis(block, "ABS_MT_POSITION_Y") ?: continue
            out += TouchDev(
                path = m.groupValues[1],
                minX = x.first, maxX = x.second,
                minY = y.first, maxY = y.second,
                slotMax = axis(block, "ABS_MT_SLOT")?.second ?: -1,
                hasTrackingId = block.contains("ABS_MT_TRACKING_ID"),
                hasBtnTouch = block.contains("BTN_TOUCH"),
                pressureMax = axis(block, "ABS_MT_PRESSURE")?.second ?: 0,
                touchMajorMax = axis(block, "ABS_MT_TOUCH_MAJOR")?.second ?: 0,
                hasAbsXY = axis(block, "ABS_X") != null && axis(block, "ABS_Y") != null,
                direct = block.contains("INPUT_PROP_DIRECT")
            )
        }
        return out
    }

    fun pick(devs: List<TouchDev>): TouchDev? = devs.firstOrNull { it.direct } ?: devs.firstOrNull()
}

object TouchMath {
    /**
     * Maps a normalised point (u,v) in the *displayed* frame to the panel's natural frame.
     * rot follows android.view.Surface.ROTATION_* (0..3).
     */
    fun toNatural(u: Float, v: Float, rot: Int): Pair<Float, Float> = when (rot and 3) {
        0 -> u to v
        1 -> (1f - v) to u
        2 -> (1f - u) to (1f - v)
        else -> v to (1f - u)
    }
}

/** Builds a batch of Linux `struct input_event`s (little endian). eventSize = 24 (64-bit) or 16 (32-bit). */
class EvdevFrame(private val eventSize: Int, maxEvents: Int = 48) {
    private val bb: ByteBuffer = ByteBuffer.allocate(eventSize * maxEvents).order(ByteOrder.LITTLE_ENDIAN)

    fun ev(type: Int, code: Int, value: Int): EvdevFrame {
        bb.position(bb.position() + eventSize - 8) // zeroed timeval; the kernel stamps the event
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(value)
        return this
    }

    fun bytes(): ByteArray = bb.array().copyOf(bb.position())

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val SYN_MT_REPORT = 2
        const val BTN_TOUCH = 0x14A
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_MT_SLOT = 0x2F
        const val ABS_MT_TOUCH_MAJOR = 0x30
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3A
    }
}
