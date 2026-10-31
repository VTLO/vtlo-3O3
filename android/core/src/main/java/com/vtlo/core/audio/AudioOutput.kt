package com.vtlo.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Drives a [SequencerEngine] from a dedicated real-time priority thread writing float
 * PCM into a low-latency AudioTrack at the device's native rate. The buffer
 * starts at two bursts and grows by one burst whenever an underrun is detected.
 */
class AudioOutput(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    val sampleRate: Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()?.takeIf { it in 22050..192000 } ?: 48000

    private val burstFrames: Int = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        ?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(engine: SequencerEngine) {
        if (running) return
        running = true
        thread = Thread({ loop(engine) }, "vtlo-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(engine: SequencerEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, burstFrames * 8 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed", e)
            running = false
            return
        }

        val capacity = track.bufferCapacityInFrames
        var bufferFrames = minOf(capacity, burstFrames * 2)
        track.bufferSizeInFrames = bufferFrames
        var underruns = track.underrunCount

        val block = FloatArray(burstFrames * 2)
        track.play()
        try {
            while (running) {
                engine.render(block, burstFrames)
                var off = 0
                while (off < block.size && running) {
                    val n = track.write(block, off, block.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) {
                        Log.e(TAG, "AudioTrack write error $n")
                        running = false
                        break
                    }
                    off += n
                }
                val u = track.underrunCount
                if (u > underruns && bufferFrames < capacity) {
                    bufferFrames = minOf(capacity, bufferFrames + burstFrames)
                    track.bufferSizeInFrames = bufferFrames
                }
                underruns = u
            }
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    private companion object {
        const val TAG = "AudioOutput"
    }
}
