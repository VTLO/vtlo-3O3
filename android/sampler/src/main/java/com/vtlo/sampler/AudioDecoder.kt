package com.vtlo.sampler

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vtlo.core.dsp.Resampler
import com.vtlo.core.dsp.WavReader
import java.io.IOException
import java.nio.ByteOrder

/**
 * Turns an audio file into mono float samples at the engine rate, at most
 * [maxSeconds] long. WAV is read exactly; every other format the device can
 * decode (MP3, AAC/M4A, OGG, FLAC, …) goes through the platform codecs.
 */
object AudioDecoder {
    fun decode(context: Context, uri: Uri, targetRate: Int, maxSeconds: Int): FloatArray {
        val resolver = context.contentResolver
        val isWav = resolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(12)
            s.read(head) == 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        } ?: throw IOException("Cannot open $uri")
        val (data, rate) = if (isWav) {
            val s = resolver.openInputStream(uri)!!.use(WavReader::read)
            s.data.copyOf(minOf(s.data.size, s.sampleRate * maxSeconds)) to s.sampleRate
        } else {
            decodeWithCodec(context, uri, maxSeconds)
        }
        return Resampler.resample(data, rate, targetRate)
    }

    private fun decodeWithCodec(context: Context, uri: Uri, maxSeconds: Int): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var out = FloatArray(rate * 4)
                var n = 0
                var float = false
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val frames = info.size / ((if (float) 4 else 2) * channels)
                            if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += if (float) buf.float else buf.short / 32768f
                                out[n++] = sum / channels
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n >= rate * maxSeconds) break
                        }
                    }
                }
                return out.copyOf(minOf(n, rate * maxSeconds)) to rate
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
