package com.vtlo.drums.dsp

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class Sample(val data: FloatArray, val sampleRate: Int)

/** Minimal RIFF/WAVE reader: PCM 8/16/24/32-bit and 32-bit float, mixed down to mono. */
object WavReader {
    fun read(input: InputStream): Sample {
        val buf = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 12 && tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= buf.limit()) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            require(size >= 0 && body + size <= buf.limit() || id == "data") { "Truncated chunk $id" }
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xffff
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    if (format == 0xFFFE && size >= 26) format = buf.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    require(channels in 1..8 && rate > 0) { "Missing fmt chunk" }
                    val bytes = minOf(size, buf.limit() - body)
                    return Sample(decode(buf, body, bytes, format, channels, bits), rate)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("No data chunk")
    }

    private fun tag(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)

    private fun decode(b: ByteBuffer, start: Int, bytes: Int, format: Int, channels: Int, bits: Int): FloatArray {
        val width = bits / 8
        require(format == 1 && width in 1..4 || format == 3 && width == 4) { "Unsupported WAV encoding $format/$bits" }
        val frames = bytes / (width * channels)
        val out = FloatArray(frames)
        var p = start
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += when {
                    format == 3 -> b.getFloat(p)
                    width == 1 -> ((b.get(p).toInt() and 0xff) - 128) / 128f
                    width == 2 -> b.getShort(p) / 32768f
                    width == 3 -> ((b.get(p).toInt() and 0xff) or ((b.get(p + 1).toInt() and 0xff) shl 8) or
                        (b.get(p + 2).toInt() shl 16)) / 8388608f
                    else -> b.getInt(p) / 2147483648f
                }
                p += width
            }
            out[f] = sum / channels
        }
        return out
    }
}

/**
 * Offline sample-rate conversion with a Kaiser-windowed sinc (16 zero crossings,
 * ~-100 dB stop band). Used once per sample at load time, so playback at the
 * device rate needs no interpolation unless a track is retuned.
 */
object Resampler {
    private const val ZERO_CROSSINGS = 16
    private const val BETA = 9.0

    private const val TABLE_RES = 512 // kernel points per input sample

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input.copyOf()
        val ratio = to.toDouble() / from
        val cutoff = minOf(1.0, ratio) * 0.95 // fraction of the input Nyquist
        val half = ZERO_CROSSINGS / cutoff
        // Tabulate the windowed-sinc kernel for x in [0, half].
        val table = DoubleArray((half * TABLE_RES).toInt() + 2)
        val i0Beta = besselI0(BETA)
        for (i in table.indices) {
            val x = i.toDouble() / TABLE_RES
            val w = x / half
            table[i] = if (w >= 1.0) 0.0 else cutoff * sinc(cutoff * x) * besselI0(BETA * sqrt(1.0 - w * w)) / i0Beta
        }
        val outLen = ((input.size.toLong() * to + from - 1) / from).toInt()
        val out = FloatArray(outLen)
        for (n in 0 until outLen) {
            val t = n / ratio
            val first = maxOf(0, ceil(t - half).toInt())
            val last = minOf(input.size - 1, floor(t + half).toInt())
            var acc = 0.0
            for (k in first..last) {
                val pos = abs(t - k) * TABLE_RES
                val i = pos.toInt()
                if (i + 1 >= table.size) continue
                val frac = pos - i
                acc += input[k] * (table[i] + frac * (table[i + 1] - table[i]))
            }
            out[n] = acc.toFloat()
        }
        return out
    }

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= q / (k * k)
            sum += term
            k++
        }
        return sum
    }
}
