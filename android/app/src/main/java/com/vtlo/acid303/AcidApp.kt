package com.vtlo.acid303

import android.app.Application
import android.content.Context
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets

/**
 * Owns the engine and audio output for the whole process so playback survives
 * configuration changes (rotation, folding, window resizing).
 */
class AcidApp : Application() {
    lateinit var output: AudioOutput
        private set
    lateinit var engine: AcidEngine
        private set

    override fun onCreate() {
        super.onCreate()
        output = AudioOutput(this)
        engine = AcidEngine(output.sampleRate)
        if (!restore()) {
            Presets.all.forEachIndexed { i, p -> Presets.load(engine.bank, i, p) }
            engine.set(Param.TEMPO, Param.bpmToNorm(Presets.all[0].bpm.toFloat()))
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save() {
        prefs().edit().apply {
            putString(KEY_PATTERNS, engine.bank.serialize())
            putInt(KEY_SELECTED, engine.selectedPattern)
            Param.entries.forEach { putFloat(it.key, engine.get(it)) }
        }.apply()
    }

    private fun restore(): Boolean {
        val p = prefs()
        val data = p.getString(KEY_PATTERNS, null) ?: return false
        if (!engine.bank.deserialize(data)) return false
        Param.entries.forEach { engine.set(it, p.getFloat(it.key, it.default)) }
        engine.selectedPattern = p.getInt(KEY_SELECTED, 0).coerceIn(0, PatternBank.PATTERNS - 1)
        return true
    }

    private companion object {
        const val PREFS = "acid303"
        const val KEY_PATTERNS = "patterns"
        const val KEY_SELECTED = "selected"
    }
}
