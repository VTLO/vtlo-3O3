package com.vtlo.acid303

import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.core.audio.SequencerEngine
import com.vtlo.core.dsp.SoftLimiter
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import kotlin.math.exp

/**
 * Two bass lines, two drum machines and a sampler as one instrument: all render in the same
 * audio callback, start on the same sample and share tempo and swing, so they
 * stay locked. Each part can be switched out of the mix (it keeps running in time).
 *
 * [parts] are in page order: bass line, drums, sampler, then the second bass line and second drums.
 */
class StudioEngine(
    val bass: AcidEngine,
    val drums: DrumEngine,
    val sampler: SamplerEngine,
    val bass2: AcidEngine,
    val drums2: DrumEngine,
) : SequencerEngine {

    /** One instrument in the mix, with access to its (normalized) tempo and swing. */
    class Part(
        val engine: SequencerEngine,
        val getTempo: () -> Float, val setTempo: (Float) -> Unit,
        val getSwing: () -> Float, val setSwing: (Float) -> Unit,
    ) {
        @Volatile var on = true
        internal var buf = FloatArray(0)
        internal var gain = Double.NaN // set from [on] on the first render
    }

    val parts = listOf(
        Part(bass, { bass.get(Param.TEMPO) }, { bass.set(Param.TEMPO, it) },
            { bass.get(Param.SWING) }, { bass.set(Param.SWING, it) }),
        Part(drums, { drums.get(DrumParam.TEMPO) }, { drums.set(DrumParam.TEMPO, it) },
            { drums.get(DrumParam.SWING) }, { drums.set(DrumParam.SWING, it) }),
        Part(sampler, { sampler.get(SamplerParam.TEMPO) }, { sampler.set(SamplerParam.TEMPO, it) },
            { sampler.get(SamplerParam.SWING) }, { sampler.set(SamplerParam.SWING, it) }),
        Part(bass2, { bass2.get(Param.TEMPO) }, { bass2.set(Param.TEMPO, it) },
            { bass2.get(Param.SWING) }, { bass2.set(Param.SWING, it) }),
        Part(drums2, { drums2.get(DrumParam.TEMPO) }, { drums2.set(DrumParam.TEMPO, it) },
            { drums2.get(DrumParam.SWING) }, { drums2.set(DrumParam.SWING, it) }),
    )

    override val isPlaying: Boolean get() = parts.any { it.engine.isPlaying }

    private val partFade = exp(-1.0 / (0.01 * bass.sampleRate))
    private var tempo = Float.NaN
    private var swing = Float.NaN

    init {
        val rate = bass.sampleRate
        require(listOf(drums.sampleRate, sampler.sampleRate, bass2.sampleRate, drums2.sampleRate).all { it == rate })
        syncTiming()
    }

    override fun play() = parts.forEach { it.engine.play() }
    override fun stop() = parts.forEach { it.engine.stop() }
    override fun panic() = parts.forEach { it.engine.panic() }

    /**
     * Whichever part's tempo or swing knob moved last sets it for all. All parts
     * use the same normalized ranges (60–200 BPM, swing 0–1).
     */
    private fun syncTiming() {
        tempo = sync(tempo, Part::getTempo, Part::setTempo)
        swing = sync(swing, Part::getSwing, Part::setSwing)
    }

    private inline fun sync(last: Float, get: (Part) -> () -> Float, set: (Part) -> (Float) -> Unit): Float {
        var v = last
        for (i in parts.indices) {
            val x = get(parts[i])()
            if (x != last) {
                v = x
                break
            }
        }
        if (v == last || v.isNaN()) return last
        for (i in parts.indices) if (get(parts[i])() != v) set(parts[i])(v)
        return v
    }

    override fun render(out: FloatArray, frames: Int) {
        syncTiming()
        val n = frames * 2
        for (pi in parts.indices) {
            val p = parts[pi]
            if (p.buf.size < n) p.buf = FloatArray(n)
            p.engine.render(p.buf, frames)
            if (p.gain.isNaN()) p.gain = if (p.on) 1.0 else 0.0
        }
        java.util.Arrays.fill(out, 0, n, 0f)
        for (pi in parts.indices) {
            val p = parts[pi]
            val target = if (p.on) 1.0 else 0.0
            var g = p.gain
            val b = p.buf
            var i = 0
            for (f in 0 until frames) {
                g = target + partFade * (g - target)
                out[i] += (b[i] * g).toFloat()
                out[i + 1] += (b[i + 1] * g).toFloat()
                i += 2
            }
            p.gain = g
        }
        for (i in 0 until n) out[i] = SoftLimiter.process(out[i].toDouble()).toFloat()
    }
}
