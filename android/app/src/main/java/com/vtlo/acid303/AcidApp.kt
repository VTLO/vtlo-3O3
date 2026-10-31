package com.vtlo.acid303

import android.content.Context
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.core.audio.InstrumentApp
import kotlin.math.roundToInt

/**
 * The TB-303 instrument: engine creation, persistence and the playback notification text.
 */
class AcidApp : InstrumentApp() {
    override lateinit var engine: AcidEngine
        private set

    override val mainActivity get() = MainActivity::class.java

    override fun playbackTitle(): String {
        val p = engine.selectedPattern
        return getString(R.string.notification_title, if (p < 8) 'A' else 'B', p % 8 + 1)
    }

    override fun playbackText(): String =
        getString(R.string.notification_text, Param.normToBpm(engine.get(Param.TEMPO)).roundToInt())

    override fun createEngine(sampleRate: Int) {
        engine = AcidEngine(sampleRate)
        if (!restore()) {
            Presets.all.forEachIndexed { i, p -> Presets.load(engine.bank, i, p) }
            engine.set(Param.TEMPO, Param.bpmToNorm(Presets.all[0].bpm.toFloat()))
        }
    }

    fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
