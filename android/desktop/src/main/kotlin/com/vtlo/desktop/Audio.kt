package com.vtlo.desktop

import com.vtlo.core.audio.SequencerEngine
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.random.Random

private fun pcm16(rate: Int, channels: Int) = AudioFormat(rate.toFloat(), 16, channels, true, false)

/** The output rate: 48 kHz where the sound card takes it, otherwise 44.1 kHz. */
fun pickOutputRate(): Int = intArrayOf(48000, 44100).firstOrNull {
    AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, pcm16(it, 2)))
} ?: 48000

/**
 * Drives the engine from a high-priority thread into a Java Sound line: 16-bit
 * stereo with TPDF dither, small blocks, about 40 ms of buffering, which is
 * what the Windows sound mixer handles without dropouts.
 */
class AudioOutput(private val engine: SequencerEngine, val sampleRate: Int) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var error: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "vtlo-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        val format = pcm16(sampleRate, 2)
        val line = try {
            (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine).apply {
                open(format, BLOCK * 4 * 8)
                start()
            }
        } catch (e: Exception) {
            error = "No audio output: ${e.message}"
            running = false
            return
        }
        val buf = FloatArray(BLOCK * 2)
        val bytes = ByteArray(BLOCK * 4)
        val rnd = Random(303)
        try {
            while (running) {
                engine.render(buf, BLOCK)
                var b = 0
                for (v in buf) {
                    val dither = (rnd.nextFloat() - rnd.nextFloat()) / 32768f
                    val s = ((v + dither).coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[b++] = s.toByte()
                    bytes[b++] = (s shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
        } finally {
            line.drain()
            line.close()
        }
    }

    private companion object {
        const val BLOCK = 256
    }
}

/**
 * Microphone capture into memory: mono at the engine rate, up to [MAX_SECONDS],
 * with a running peak overview for the waveform and a level for the meter.
 */
class Recorder(val sampleRate: Int) {
    private val buffer = FloatArray(MAX_SECONDS * sampleRate)
    val overview = FloatArray(buffer.size / OVERVIEW_BLOCK + 1)
    @Volatile var overviewCount = 0
        private set
    @Volatile var frames = 0
        private set
    @Volatile var level = 0f
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRecording get() = running
    val isFull get() = frames >= buffer.size

    /** @return an error message, or null when recording started. */
    fun start(): String? {
        if (running) return null
        val format = pcm16(sampleRate, 1)
        val line = try {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).apply {
                open(format, sampleRate / 5 * 2)
                start()
            }
        } catch (e: Exception) {
            return "No microphone available (${e.message})"
        }
        frames = 0
        overviewCount = 0
        level = 0f
        running = true
        thread = Thread({ capture(line) }, "vtlo-recorder").apply {
            isDaemon = true
            start()
        }
        return null
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1000)
        thread = null
        level = 0f
        return buffer.copyOf(frames)
    }

    private fun capture(line: TargetDataLine) {
        val bytes = ByteArray(OVERVIEW_BLOCK * 2)
        try {
            while (running && frames < buffer.size) {
                val n = line.read(bytes, 0, bytes.size) / 2
                if (n <= 0) break
                val take = minOf(n, buffer.size - frames)
                var peak = 0f
                for (i in 0 until take) {
                    val v = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)) / 32768f
                    buffer[frames + i] = v
                    peak = maxOf(peak, abs(v))
                }
                if (overviewCount < overview.size) overview[overviewCount++] = peak
                level = maxOf(peak, level * 0.8f)
                frames += take
            }
        } finally {
            line.stop()
            line.close()
        }
    }

    companion object {
        const val MAX_SECONDS = 30
        const val OVERVIEW_BLOCK = 512
    }
}
