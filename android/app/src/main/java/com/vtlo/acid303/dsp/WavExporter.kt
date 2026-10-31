package com.vtlo.acid303.dsp

import com.vtlo.core.audio.WavWriter
import java.io.OutputStream

/** Offline renderer: plays a pattern through a private engine and writes a 24-bit WAV. */
object WavExporter {
    const val SAMPLE_RATE = 48000
    private const val TAIL_SECONDS = 2.0

    /**
     * @param configure copies the live parameters and pattern into the offline engine.
     * @return number of frames written.
     */
    fun export(
        out: OutputStream,
        loops: Int,
        patternLength: Int,
        configure: (AcidEngine) -> Unit,
    ): Int {
        val engine = AcidEngine(SAMPLE_RATE)
        configure(engine)
        val stepSeconds = 60.0 / Param.normToBpm(engine.get(Param.TEMPO)) / 4.0
        val playFrames = (loops * patternLength * stepSeconds * SAMPLE_RATE).toInt()
        return WavWriter.render(out, engine, SAMPLE_RATE, playFrames, (TAIL_SECONDS * SAMPLE_RATE).toInt())
    }
}
