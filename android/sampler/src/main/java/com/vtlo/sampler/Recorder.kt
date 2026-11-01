package com.vtlo.sampler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.abs

/**
 * Microphone capture into memory: mono float PCM at the engine rate, up to
 * [MAX_SECONDS]. Uses the unprocessed source (no AGC or noise suppression) where
 * the device offers it. Keeps a running peak overview so the UI can draw the
 * waveform while recording. Start and stop on the main thread.
 */
class Recorder(private val context: Context, val sampleRate: Int) {
    private val buffer = FloatArray(MAX_SECONDS * sampleRate)

    /** Peak per [OVERVIEW_BLOCK] frames recorded so far. */
    val overview = FloatArray(buffer.size / OVERVIEW_BLOCK + 1)
    @Volatile var overviewCount = 0
        private set
    @Volatile var frames = 0
        private set
    /** Input level for the meter, 0..1, with a short decay. */
    @Volatile var level = 0f
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRecording get() = running

    /** @return false if the permission is missing or no microphone could be opened. */
    fun start(): Boolean {
        if (running) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val unprocessed = context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBytes <= 0) return false
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBytes * 2, sampleRate / 5 * 4))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open microphone", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        frames = 0
        overviewCount = 0
        level = 0f
        running = true
        thread = Thread({ capture(record) }, "vtlo-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Stops and returns the captured audio (empty if nothing was recorded). */
    fun stop(): FloatArray {
        running = false
        thread?.join(1000)
        thread = null
        level = 0f
        return buffer.copyOf(frames)
    }

    private fun capture(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = FloatArray(OVERVIEW_BLOCK)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                val room = buffer.size - frames
                val take = minOf(n, room)
                System.arraycopy(chunk, 0, buffer, frames, take)
                var peak = 0f
                for (i in 0 until take) peak = maxOf(peak, abs(chunk[i]))
                if (overviewCount < overview.size) overview[overviewCount++] = peak
                level = maxOf(peak, level * 0.8f)
                frames += take
                if (frames >= buffer.size) break // full: the UI sees running stay true until stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recording failed", e)
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
    }

    val isFull get() = frames >= buffer.size

    companion object {
        const val MAX_SECONDS = 30
        const val OVERVIEW_BLOCK = 512
        private const val TAG = "Recorder"
    }
}
