package com.vtlo.acid303

import android.content.Context
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.core.audio.InstrumentApp
import com.vtlo.drums.DrumStore
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.sampler.SamplerStore
import com.vtlo.sampler.dsp.SamplerEngine
import kotlin.math.roundToInt

/**
 * The studio: TB-303 bass line, drum machine and sampler sharing one transport
 * and one audio output. Owns engine creation, persistence and the notification text.
 */
class StudioApp : InstrumentApp() {
    override lateinit var engine: StudioEngine
        private set
    val bass: AcidEngine get() = engine.bass
    val drums: DrumEngine get() = engine.drums
    val sampler: SamplerEngine get() = engine.sampler
    private lateinit var drumSamples: SampleBank

    override val mainActivity get() = MainActivity::class.java

    override fun playbackTitle(): String =
        getString(
            R.string.notification_title,
            patternLabel(bass.selectedPattern), patternLabel(drums.selectedPattern), patternLabel(sampler.selectedPattern)
        )

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
        val sampler = SamplerEngine(sampleRate)
        if (!SamplerStore.restore(this, sampler)) seedSampler(sampler)
        // The bass line's tempo and swing win at start-up; StudioEngine keeps them in step after.
        engine = StudioEngine(bass, drums, sampler)
        engine.parts.forEachIndexed { i, part -> part.on = prefs().getBoolean(PART_KEYS[i], true) }
    }

    /** First run: fill the pads with sounds from the bundled kits so the sampler plays at once. */
    private fun seedSampler(sampler: SamplerEngine) {
        val seeds = listOf(
            Kit.TR808 to 0, Kit.TR909 to 1, Kit.TR808 to 3, Kit.TR909 to 4,
            Kit.TR606 to 2, Kit.TR808 to 5, Kit.TR909 to 6, Kit.TR606 to 7,
        )
        seeds.forEachIndexed { pad, (kit, track) ->
            val data = drumSamples.get(kit, track)
            sampler.setSample(pad, data)
            sampler.padNames[pad] = "${kit.label} ${kit.trackNames[track]}"
            SamplerStore.saveSample(this, pad, data, sampler.sampleRate)
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
            engine.parts.forEachIndexed { i, part -> putBoolean(PART_KEYS[i], part.on) }
        }.apply()
        DrumStore.save(this, drums)
        SamplerStore.save(this, sampler)
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
        val PART_KEYS = listOf("bass_on", "drums_on", "sampler_on")
    }
}
