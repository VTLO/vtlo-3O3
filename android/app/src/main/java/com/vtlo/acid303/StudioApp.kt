package com.vtlo.acid303

import android.content.Context
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.core.audio.InstrumentApp
import com.vtlo.drums.DrumStore
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.SampleBank
import kotlin.math.roundToInt

/**
 * The studio: TB-303 bass line and drum machine sharing one transport and one
 * audio output. Owns engine creation, persistence and the notification text.
 */
class StudioApp : InstrumentApp() {
    override lateinit var engine: StudioEngine
        private set
    val bass: AcidEngine get() = engine.bass
    val drums: DrumEngine get() = engine.drums
    private lateinit var drumSamples: SampleBank

    override val mainActivity get() = MainActivity::class.java

    override fun playbackTitle(): String =
        getString(R.string.notification_title, patternLabel(bass.selectedPattern), patternLabel(drums.selectedPattern))

    override fun playbackText(): String =
        getString(R.string.notification_text, Param.normToBpm(bass.get(Param.TEMPO)).roundToInt())

    private fun patternLabel(p: Int) = "${if (p < 8) 'A' else 'B'}${p % 8 + 1}"

    override fun createEngine(sampleRate: Int) {
        val bass = AcidEngine(sampleRate)
        if (!restoreBass(bass)) {
            Presets.all.forEachIndexed { i, p -> Presets.load(bass.bank, i, p) }
            bass.set(Param.TEMPO, Param.bpmToNorm(Presets.all[0].bpm.toFloat()))
        }
        drumSamples = DrumStore.loadSamples(this, sampleRate)
        val drums = DrumEngine(sampleRate, drumSamples)
        DrumStore.restore(this, drums)
        // The bass line's tempo and swing win at start-up; StudioEngine keeps them in step after.
        engine = StudioEngine(bass, drums).apply {
            bassOn = prefs().getBoolean(KEY_BASS_ON, true)
            drumsOn = prefs().getBoolean(KEY_DRUMS_ON, true)
        }
    }

    /** Drum samples at [rate]: the live bank when it matches, otherwise loaded for that rate (WAV export). */
    fun drumSamplesFor(rate: Int): SampleBank =
        if (rate == drumSamples.sampleRate) drumSamples else DrumStore.loadSamples(this, rate)

    fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save() {
        prefs().edit().apply {
            putString(KEY_PATTERNS, bass.bank.serialize())
            putInt(KEY_SELECTED, bass.selectedPattern)
            Param.entries.forEach { putFloat(it.key, bass.get(it)) }
            putBoolean(KEY_BASS_ON, engine.bassOn)
            putBoolean(KEY_DRUMS_ON, engine.drumsOn)
        }.apply()
        DrumStore.save(this, drums)
    }

    private fun restoreBass(bass: AcidEngine): Boolean {
        val p = prefs()
        val data = p.getString(KEY_PATTERNS, null) ?: return false
        if (!bass.bank.deserialize(data)) return false
        Param.entries.forEach { bass.set(it, p.getFloat(it.key, it.default)) }
        bass.selectedPattern = p.getInt(KEY_SELECTED, 0).coerceIn(0, PatternBank.PATTERNS - 1)
        return true
    }

    private companion object {
        const val PREFS = "acid303"
        const val KEY_PATTERNS = "patterns"
        const val KEY_SELECTED = "selected"
        const val KEY_BASS_ON = "bass_on"
        const val KEY_DRUMS_ON = "drums_on"
    }
}
