package com.vtlo.core.audio

import java.io.BufferedOutputStream
import java.io.OutputStream
import kotlin.random.Random

/** Offline rendering of a [SequencerEngine] to a 24-bit stereo WAV. */
object WavWriter {
    private const val BLOCK = 512

    /**
     * Plays [engine] for [playFrames], stops it and lets effects ring out for
     * [tailFrames]. The engine must already hold the pattern and settings.
     * @return number of frames written.
     */
    fun render(out: OutputStream, engine: SequencerEngine, sampleRate: Int, playFrames: Int, tailFrames: Int): Int {
        val frames = playFrames + tailFrames
        val stream = BufferedOutputStream(out, 1 shl 16)
        writeHeader(stream, sampleRate, frames * 2 * 3)

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

    private fun writeHeader(s: OutputStream, sampleRate: Int, dataBytes: Int) {
        fun str(v: String) = s.write(v.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) {
            s.write(v); s.write(v shr 8); s.write(v shr 16); s.write(v shr 24)
        }
        fun i16(v: Int) {
            s.write(v); s.write(v shr 8)
        }
        str("RIFF"); i32(36 + dataBytes); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(2); i32(sampleRate)
        i32(sampleRate * 2 * 3); i16(2 * 3); i16(24)
        str("data"); i32(dataBytes)
    }
}
