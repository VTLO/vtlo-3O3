package com.vtlo.desktop

import com.vtlo.acid303.StudioEngine
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.core.audio.WavWriter
import com.vtlo.core.dsp.Resampler
import com.vtlo.core.dsp.WavReader
import com.vtlo.core.seq.StepPatternBank
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.drums.dsp.TrackParam
import com.vtlo.sampler.dsp.PadParam
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

/**
 * The desktop studio: the same five instruments as the Android app (two bass lines, two
 * drum machines, a sampler), one audio output, and persistence in the user's data folder (a properties file plus
 * each sampler pad as a 32-bit float WAV).
 */
class Studio(private val dataDir: File, val sampleRate: Int = pickOutputRate()) {
    private val stateFile = File(dataDir, "studio.properties")
    private val padDir = File(dataDir, "sampler").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vtlo-io").apply { isDaemon = true } }
    val props = Properties()

    private val drumSamples: SampleBank = loadKits(sampleRate)
    val bass = AcidEngine(sampleRate)
    val drums = DrumEngine(sampleRate, drumSamples)
    val sampler = SamplerEngine(sampleRate)
    val bass2 = AcidEngine(sampleRate)
    val drums2 = DrumEngine(sampleRate, drumSamples)
    val engine: StudioEngine
    val output: AudioOutput

    init {
        if (stateFile.exists()) runCatching { stateFile.inputStream().use(props::load) }
        if (!restoreBass(bass, "bass")) {
            Presets.all.forEachIndexed { i, p -> Presets.load(bass.bank, i, p) }
            bass.set(Param.TEMPO, Param.bpmToNorm(Presets.all[0].bpm.toFloat()))
        }
        if (!restoreDrums(drums, "drums")) {
            DrumPresets.all.forEachIndexed { i, p -> DrumPresets.load(drums.bank, i, p) }
            for (t in 0 until Kit.TRACKS) drums.setKit(t, DrumPresets.all[0].kit)
        }
        if (!restoreSampler()) seedSampler()
        // The second bass line and drum machine start with empty patterns: the mix is as before until they are programmed.
        restoreBass(bass2, "bass2")
        if (!restoreDrums(drums2, "drums2")) for (t in 0 until Kit.TRACKS) drums2.setKit(t, DrumPresets.all[0].kit)
        engine = StudioEngine(bass, drums, sampler, bass2, drums2)
        engine.parts.forEachIndexed { i, part -> part.on = props.getProperty("part$i", "true").toBoolean() }
        output = AudioOutput(engine, sampleRate)
    }

    /** The kits at [rate], converted once and cached next to the saved state. */
    private fun loadKits(rate: Int): SampleBank {
        val cache = File(dataDir, "kits-$rate-$CACHE_KEY.bin")
        runCatching { cache.inputStream().use { SampleBank.readFrom(it, rate) } }.getOrNull()?.let { return it }
        val bank = SampleBank.load(rate) { path ->
            Studio::class.java.getResourceAsStream("/$path") ?: error("Missing kit sample $path")
        }
        runCatching {
            dataDir.listFiles { f -> f.name.startsWith("kits-") }?.forEach { it.delete() }
            cache.outputStream().use(bank::writeTo)
        }
        return bank
    }

    fun drumSamplesFor(rate: Int): SampleBank = if (rate == sampleRate) drumSamples else loadKits(rate)

    /** First run: fill the sampler pads with sounds from the bundled kits. */
    private fun seedSampler() {
        val seeds = listOf(
            Kit.TR808 to 0, Kit.TR909 to 1, Kit.TR808 to 3, Kit.TR909 to 4,
            Kit.TR606 to 2, Kit.TR808 to 5, Kit.TR909 to 6, Kit.TR606 to 7,
        )
        seeds.forEachIndexed { pad, (kit, track) ->
            val data = drumSamples.get(kit, track)
            sampler.setSample(pad, data)
            sampler.padNames[pad] = "${kit.label} ${kit.trackNames[track]}"
            saveSample(pad, data)
        }
    }

    /** Stores (null deletes) a sampler pad's audio in the background. */
    fun saveSample(pad: Int, data: FloatArray?) {
        io.execute {
            val f = File(padDir, "pad$pad.wav")
            runCatching {
                if (data == null) f.delete() else {
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { WavWriter.writeMonoFloat(it, data, sampleRate) }
                    if (!tmp.renameTo(f)) {
                        f.delete(); tmp.renameTo(f)
                    }
                }
            }
        }
    }

    // ─── Persistence ─────────────────────────────────────────

    fun save() {
        val p = props
        saveBass(bass, "bass")
        saveBass(bass2, "bass2")
        saveDrums(drums, "drums")
        saveDrums(drums2, "drums2")

        p["sampler.patterns"] = sampler.bank.serialize()
        p["sampler.selected"] = sampler.selectedPattern.toString()
        SamplerParam.entries.forEach { p["sampler.${it.key}"] = sampler.get(it).toString() }
        for (pad in 0 until SamplerEngine.PADS) {
            PadParam.entries.forEach { p["sampler.p$pad.${it.key}"] = sampler.get(pad, it).toString() }
            p["sampler.p$pad.mute"] = sampler.isMuted(pad).toString()
            p["sampler.p$pad.name"] = sampler.padNames[pad]
        }
        engine.parts.forEachIndexed { i, part -> p["part$i"] = part.on.toString() }

        dataDir.mkdirs()
        val tmp = File(dataDir, "studio.properties.tmp")
        tmp.outputStream().use { p.store(it, "VTLO 3O3 desktop state") }
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete(); tmp.renameTo(stateFile)
        }
    }

    private fun f(key: String, default: Float) = props.getProperty(key)?.toFloatOrNull() ?: default

    // The first bass line / drum machine keep the original keys ("bass.", "drums."); the second ones use "bass2.", "drums2.".
    private fun saveBass(e: AcidEngine, k: String) {
        props["$k.patterns"] = e.bank.serialize()
        props["$k.selected"] = e.selectedPattern.toString()
        Param.entries.forEach { props["$k.${it.key}"] = e.get(it).toString() }
    }

    private fun saveDrums(e: DrumEngine, k: String) {
        props["$k.patterns"] = e.bank.serialize()
        props["$k.selected"] = e.selectedPattern.toString()
        DrumParam.entries.forEach { props["$k.${it.key}"] = e.get(it).toString() }
        for (t in 0 until Kit.TRACKS) {
            TrackParam.entries.forEach { props["$k.t$t.${it.key}"] = e.get(t, it).toString() }
            props["$k.t$t.kit"] = e.kit(t).name
            props["$k.t$t.mute"] = e.isMuted(t).toString()
        }
    }

    private fun restoreBass(e: AcidEngine, k: String): Boolean {
        val data = props.getProperty("$k.patterns") ?: return false
        if (!e.bank.deserialize(data)) return false
        Param.entries.forEach { e.set(it, f("$k.${it.key}", it.default)) }
        e.selectedPattern = (props.getProperty("$k.selected")?.toIntOrNull() ?: 0).coerceIn(0, PatternBank.PATTERNS - 1)
        return true
    }

    private fun restoreDrums(e: DrumEngine, k: String): Boolean {
        val data = props.getProperty("$k.patterns") ?: return false
        if (!e.bank.deserialize(data)) return false
        DrumParam.entries.forEach { e.set(it, f("$k.${it.key}", it.default)) }
        for (t in 0 until Kit.TRACKS) {
            TrackParam.entries.forEach { e.set(t, it, f("$k.t$t.${it.key}", it.default)) }
            e.setKit(t, Kit.entries.firstOrNull { it.name == props.getProperty("$k.t$t.kit") } ?: Kit.TR909)
            e.setMuted(t, props.getProperty("$k.t$t.mute").toBoolean())
        }
        e.selectedPattern = (props.getProperty("$k.selected")?.toIntOrNull() ?: 0).coerceIn(0, StepPatternBank.PATTERNS - 1)
        return true
    }

    private fun restoreSampler(): Boolean {
        val data = props.getProperty("sampler.patterns") ?: return false
        if (!sampler.bank.deserialize(data)) return false
        SamplerParam.entries.forEach { sampler.set(it, f("sampler.${it.key}", it.default)) }
        for (pad in 0 until SamplerEngine.PADS) {
            PadParam.entries.forEach { sampler.set(pad, it, f("sampler.p$pad.${it.key}", it.default)) }
            sampler.setMuted(pad, props.getProperty("sampler.p$pad.mute").toBoolean())
            sampler.padNames[pad] = props.getProperty("sampler.p$pad.name", "")
            val file = File(padDir, "pad$pad.wav")
            if (file.exists()) runCatching {
                val s = file.inputStream().use(WavReader::read)
                sampler.setSample(pad, Resampler.resample(s.data, s.sampleRate, sampleRate))
            }
        }
        sampler.selectedPattern = (props.getProperty("sampler.selected")?.toIntOrNull() ?: 0).coerceIn(0, StepPatternBank.PATTERNS - 1)
        return true
    }

    companion object {
        /** Bump when the bundled kits change so the converted cache is rebuilt. */
        private const val CACHE_KEY = "v1"

        /** %APPDATA%\VTLO 3O3 on Windows, ~/.vtlo-3o3 elsewhere. */
        fun defaultDataDir(): File {
            val appData = System.getenv("APPDATA")
            return if (appData != null) File(appData, "VTLO 3O3") else File(System.getProperty("user.home"), ".vtlo-3o3")
        }
    }
}
