package com.vtlo.acid303

import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.core.audio.SequencerEngine
import com.vtlo.core.dsp.SoftLimiter
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import kotlin.math.exp

/**
 * Bass line and drum machine as one instrument: both render in the same audio
 * callback, start on the same sample and share tempo and swing, so they stay
 * locked. Each part can be switched out of the mix (it keeps running in time).
 */
class StudioEngine(val bass: AcidEngine, val drums: DrumEngine) : SequencerEngine {
    @Volatile var bassOn = true
    @Volatile var drumsOn = true

    override val isPlaying: Boolean get() = bass.isPlaying || drums.isPlaying

    private var bufBass = FloatArray(0)
    private var bufDrums = FloatArray(0)
    private var gainBass = Double.NaN // set from the part switches on the first render
    private var gainDrums = Double.NaN
    private val partFade = exp(-1.0 / (0.01 * bass.sampleRate))
    private var tempo = Float.NaN
    private var swing = Float.NaN

    init {
        require(bass.sampleRate == drums.sampleRate)
        syncTiming()
    }

    override fun play() {
        bass.play()
        drums.play()
    }

    override fun stop() {
        bass.stop()
        drums.stop()
    }

    override fun panic() {
        bass.panic()
        drums.panic()
    }

    /** Whichever part's tempo or swing knob moved last sets it for both. */
    private fun syncTiming() {
        val bt = bass.get(Param.TEMPO)
        val dt = drums.get(DrumParam.TEMPO)
        if (bt != tempo) {
            tempo = bt
            drums.set(DrumParam.TEMPO, bt)
        } else if (dt != tempo) {
            tempo = dt
            bass.set(Param.TEMPO, dt)
        }
        val bs = bass.get(Param.SWING)
        val ds = drums.get(DrumParam.SWING)
        if (bs != swing) {
            swing = bs
            drums.set(DrumParam.SWING, bs)
        } else if (ds != swing) {
            swing = ds
            bass.set(Param.SWING, ds)
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        syncTiming()
        val n = frames * 2
        if (bufBass.size < n) {
            bufBass = FloatArray(n)
            bufDrums = FloatArray(n)
        }
        bass.render(bufBass, frames)
        drums.render(bufDrums, frames)
        val tb = if (bassOn) 1.0 else 0.0
        val td = if (drumsOn) 1.0 else 0.0
        if (gainBass.isNaN()) {
            gainBass = tb
            gainDrums = td
        }
        var i = 0
        for (f in 0 until frames) {
            gainBass = tb + partFade * (gainBass - tb)
            gainDrums = td + partFade * (gainDrums - td)
            out[i] = SoftLimiter.process(bufBass[i] * gainBass + bufDrums[i] * gainDrums).toFloat()
            out[i + 1] = SoftLimiter.process(bufBass[i + 1] * gainBass + bufDrums[i + 1] * gainDrums).toFloat()
            i += 2
        }
    }
}
