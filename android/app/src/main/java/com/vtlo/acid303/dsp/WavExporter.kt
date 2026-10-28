package com.vtlo.acid303.dsp

import java.io.BufferedOutputStream
import java.io.OutputStream
import kotlin.random.Random

/** Offline renderer: plays a pattern through a private engine and writes a 24-bit WAV. */
object WavExporter {
    const val SAMPLE_RATE = 48000
    private const val BLOCK = 512
    private const val TAIL_SECONDS = 2.0

    /**
     * @param configure copies the live parameters and pattern into the offline engine.
     * @return number of frames written.
     */
    fun export(
        out: OutputStream,
        loops: Int,
        patternLength: Int,
        configure: (AcidEngine) -> Unit,
    ): Int {
        val engine = AcidEngine(SAMPLE_RATE)
        configure(engine)
        val bpm = Param.normToBpm(engine.get(Param.TEMPO)).toDouble()
        val stepSeconds = 60.0 / bpm / 4.0
        val frames = ((loops * patternLength * stepSeconds + TAIL_SECONDS) * SAMPLE_RATE).toInt()
        val playFrames = (loops * patternLength * stepSeconds * SAMPLE_RATE).toInt()

        val dataBytes = frames * 2 * 3
        val stream = BufferedOutputStream(out, 1 shl 16)
        writeHeader(stream, dataBytes)

        val buf = FloatArray(BLOCK * 2)
        val bytes = ByteArray(BLOCK * 2 * 3)
        val rnd = Random(303)
        engine.play()
        var done = 0
        while (done < frames) {
            if (done >= playFrames && engine.isPlaying) engine.stop()
            val n = minOf(BLOCK, frames - done, if (done < playFrames) playFrames - done else BLOCK)
            engine.render(buf, n)
            var b = 0
            for (i in 0 until n * 2) {
                val dither = (rnd.nextDouble() - rnd.nextDouble())
                val v = (buf[i] * 8388607.0 + dither).toLong().coerceIn(-8388608L, 8388607L).toInt()
                bytes[b++] = v.toByte()
                bytes[b++] = (v shr 8).toByte()
                bytes[b++] = (v shr 16).toByte()
            }
            stream.write(bytes, 0, b)
            done += n
        }
        stream.flush()
        return frames
    }

    private fun writeHeader(s: OutputStream, dataBytes: Int) {
        fun str(v: String) = s.write(v.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) {
            s.write(v); s.write(v shr 8); s.write(v shr 16); s.write(v shr 24)
        }
        fun i16(v: Int) {
            s.write(v); s.write(v shr 8)
        }
        str("RIFF"); i32(36 + dataBytes); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(2); i32(SAMPLE_RATE)
        i32(SAMPLE_RATE * 2 * 3); i16(2 * 3); i16(24)
        str("data"); i32(dataBytes)
    }
}
