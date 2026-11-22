package com.asjohn720.s1mods.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import dadb.AdbStream
import dadb.Dadb

/**
 * Live H.264 mirror of the head unit screen.
 *
 *   scooter: screenrecord --output-format=h264 -  ->  raw ADB "exec:" stream (no pty, binary safe)
 *   phone:   AnnexBAssembler -> MediaCodec (hardware, low-latency) -> SurfaceView, rendered immediately
 *
 * Frames are never dropped (dropping a P-frame corrupts the picture until the next keyframe,
 * which shows as a green screen). Instead every frame is decoded but only the newest one is
 * drawn, and if the decoder or network falls far behind the stream is restarted to get a fresh
 * keyframe.
 */
class MirrorSession(
    private val dadb: Dadb,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val onSize: (Int, Int) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var stream: AdbStream? = null
    private var codec: MediaCodec? = null
    private var readThread: Thread? = null
    private var drainThread: Thread? = null
    private var flushThread: Thread? = null

    @Volatile private var gotKey = false
    @Volatile private var resync = false
    private var ptsUs = 0L
    private val assembler = AnnexBAssembler { data, key -> onAccessUnit(data, key) }

    fun start() {
        if (running) return
        running = true

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1) // Qualcomm decoders
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c

        drainThread = Thread({ drainLoop(c) }, "mirror-drain").also { it.start() }
        flushThread = Thread({ flushLoop() }, "mirror-flush").also { it.start() }
        readThread = Thread({ readLoop() }, "mirror-read").also { it.start() }
    }

    fun stop() {
        running = false
        try { stream?.close() } catch (_: Exception) {}
        listOf(readThread, flushThread, drainThread).forEach {
            try { it?.interrupt(); it?.join(600) } catch (_: Exception) {}
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        assembler.reset()
    }

    private fun readLoop() {
        val buf = ByteArray(64 * 1024)
        var failures = 0
        while (running) {
            val startedAt = SystemClock.elapsedRealtime()
            val before = assembler.unitsEmitted
            try {
                // --time-limit 180 is screenrecord's hard cap; we simply reconnect when it ends.
                val cmd = "exec:screenrecord --output-format=h264 --size ${width}x$height " +
                    "--bit-rate $bitRate --time-limit 180 -"
                val s = dadb.open(cmd)
                stream = s
                gotKey = false
                assembler.reset()
                onStatus("Live")
                val src = s.source
                while (running) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (n > 0) assembler.feed(buf, n, SystemClock.elapsedRealtime())
                }
                try { s.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                if (!running) return
                if (!resync) onStatus("Stream error: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!running) return

            if (resync) {
                // Deliberate restart to clear a backlog: stop the old capture, then reconnect.
                resync = false
                try { dadb.shell("pkill screenrecord") } catch (_: Exception) {}
                try { Thread.sleep(250) } catch (_: InterruptedException) { return }
                continue
            }

            val gotVideo = assembler.unitsEmitted > before
            if (gotVideo && SystemClock.elapsedRealtime() - startedAt > 5000) {
                failures = 0 // normal 3-minute rollover
                onStatus("Live")
            } else {
                failures++
                if (failures >= 4) {
                    onStatus("Screen capture keeps failing on the scooter (screenrecord returned no video). Use Snapshot mode.")
                    running = false
                    return
                }
                onStatus("Reconnecting ($failures)...")
                try { Thread.sleep(800) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** Releases a trailing NAL once the stream has gone quiet, so the last frame is not held back. */
    private fun flushLoop() {
        while (running) {
            try { Thread.sleep(3) } catch (_: InterruptedException) { return }
            assembler.flushIfIdle(SystemClock.elapsedRealtime(), 5)
        }
    }

    private fun onAccessUnit(data: ByteArray, key: Boolean) {
        if (!gotKey) {
            if (!key) return // wait for the first IDR so we never decode from a broken reference
            gotKey = true
        }
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(250_000)
            if (idx < 0) {
                // Decoder is far behind. Never drop a single frame (that corrupts the picture);
                // restart the stream so the next frame is a fresh keyframe.
                requestResync()
                return
            }
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            if (ib.capacity() < data.size) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return
            }
            ib.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            ptsUs += 16_667
        } catch (_: IllegalStateException) {
            // codec stopped while we were feeding it
        }
    }

    private fun requestResync() {
        if (resync) return
        resync = true
        gotKey = false
        try { stream?.close() } catch (_: Exception) {}
    }

    private fun handleFormat(c: MediaCodec) {
        val f = c.outputFormat
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        if (f.containsKey("crop-left") && f.containsKey("crop-right") &&
            f.containsKey("crop-top") && f.containsKey("crop-bottom")
        ) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        }
        onSize(w, h)
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val i = c.dequeueOutputBuffer(info, 10_000)
                when {
                    i >= 0 -> {
                        // Decode everything, but draw only the newest ready frame.
                        var latest = i
                        while (true) {
                            val next = c.dequeueOutputBuffer(info, 0)
                            if (next == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { handleFormat(c); continue }
                            if (next < 0) break
                            c.releaseOutputBuffer(latest, false)
                            latest = next
                        }
                        c.releaseOutputBuffer(latest, true)
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleFormat(c)
                }
            } catch (_: IllegalStateException) {
                return
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
