package com.vtlo.sampler

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vtlo.core.audio.WavWriter
import com.vtlo.core.dsp.Resampler
import com.vtlo.core.dsp.WavReader
import com.vtlo.core.seq.StepPatternBank
import com.vtlo.sampler.dsp.PadParam
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import java.io.File
import java.util.concurrent.Executors

/**
 * Persistence of the sampler: settings and patterns in preferences, each pad's
 * audio as a 32-bit float WAV in app storage (written off the main thread).
 */
object SamplerStore {
    private const val TAG = "SamplerStore"
    private const val PREFS = "sampler"
    private const val KEY_PATTERNS = "patterns"
    private const val KEY_SELECTED = "selected"
    private val io = Executors.newSingleThreadExecutor()

    private fun dir(context: Context) = File(context.filesDir, "sampler").apply { mkdirs() }
    private fun padFile(context: Context, pad: Int) = File(dir(context), "pad$pad.wav")

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stores (or with null, deletes) a pad's audio in the background. */
    fun saveSample(context: Context, pad: Int, data: FloatArray?, sampleRate: Int) {
        val app = context.applicationContext
        io.execute {
            val f = padFile(app, pad)
            try {
                if (data == null) {
                    f.delete()
                } else {
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { WavWriter.writeMonoFloat(it, data, sampleRate) }
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not save pad $pad", e)
            }
        }
    }

    fun save(context: Context, engine: SamplerEngine) {
        prefs(context).edit().apply {
            putString(KEY_PATTERNS, engine.bank.serialize())
            putInt(KEY_SELECTED, engine.selectedPattern)
            SamplerParam.entries.forEach { putFloat(it.key, engine.get(it)) }
            for (p in 0 until SamplerEngine.PADS) {
                PadParam.entries.forEach { putFloat("p$p.${it.key}", engine.get(p, it)) }
                putBoolean("p$p.mute", engine.isMuted(p))
                putString("p$p.name", engine.padNames[p])
            }
        }.apply()
    }

    /**
     * Restores settings, patterns and pad audio.
     * @return false on first run (nothing saved yet), so the caller can load defaults.
     */
    fun restore(context: Context, engine: SamplerEngine): Boolean {
        val p = prefs(context)
        val data = p.getString(KEY_PATTERNS, null) ?: return false
        if (!engine.bank.deserialize(data)) return false
        SamplerParam.entries.forEach { engine.set(it, p.getFloat(it.key, it.default)) }
        for (pad in 0 until SamplerEngine.PADS) {
            PadParam.entries.forEach { engine.set(pad, it, p.getFloat("p$pad.${it.key}", it.default)) }
            engine.setMuted(pad, p.getBoolean("p$pad.mute", false))
            engine.padNames[pad] = p.getString("p$pad.name", "") ?: ""
            val f = padFile(context, pad)
            if (f.exists()) {
                try {
                    val s = f.inputStream().use(WavReader::read)
                    engine.setSample(pad, Resampler.resample(s.data, s.sampleRate, engine.sampleRate))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load pad $pad", e)
                }
            }
        }
        engine.selectedPattern = p.getInt(KEY_SELECTED, 0).coerceIn(0, StepPatternBank.PATTERNS - 1)
        return true
    }
}
