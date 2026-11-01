package com.vtlo.core.audio

/**
 * An instrument the shared audio stack can drive. [render] is called from a
 * single audio thread; the transport methods are called from the UI thread and
 * must hand their work to the audio thread.
 */
interface SequencerEngine {
    val isPlaying: Boolean

    /** Renders [frames] interleaved stereo frames into [out]. */
    fun render(out: FloatArray, frames: Int)

    fun play()
    fun stop()

    /** Stops and silences everything, including effect tails. */
    fun panic()
}
