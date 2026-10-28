package com.vtlo.acid303.dsp

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Pattern memory shared between the UI thread (editing) and the audio thread
 * (playback). Each step is packed into one int so updates are atomic:
 * bits 0-6 MIDI note, bit 8 gate, bit 9 accent, bit 10 slide.
 *
 * Slide follows TB-303 semantics: a slide on step N holds the gate and glides
 * the pitch into step N+1.
 */
class PatternBank {
    private val steps = AtomicIntegerArray(PATTERNS * STEPS)
    private val lengths = AtomicIntegerArray(PATTERNS)

    init {
        for (p in 0 until PATTERNS) clear(p)
    }

    fun step(pattern: Int, index: Int): Int = steps.get(pattern * STEPS + index)

    fun setStep(pattern: Int, index: Int, value: Int) = steps.set(pattern * STEPS + index, value)

    fun length(pattern: Int): Int = lengths.get(pattern)

    fun setLength(pattern: Int, len: Int) = lengths.set(pattern, len.coerceIn(1, STEPS))

    fun clear(pattern: Int) {
        for (i in 0 until STEPS) setStep(pattern, i, pack(DEFAULT_NOTE, gate = false, accent = false, slide = false))
        setLength(pattern, STEPS)
    }

    fun copy(from: Int, to: Int) {
        for (i in 0 until STEPS) setStep(to, i, step(from, i))
        setLength(to, length(from))
    }

    /** Serializes the whole bank as comma separated ints (length first per pattern). */
    fun serialize(): String = buildString {
        for (p in 0 until PATTERNS) {
            if (p > 0) append(';')
            append(length(p))
            for (i in 0 until STEPS) append(',').append(step(p, i))
        }
    }

    fun deserialize(data: String): Boolean {
        val pats = data.split(';')
        if (pats.size != PATTERNS) return false
        val parsed = pats.map { it.split(',').mapNotNull(String::toIntOrNull) }
        if (parsed.any { it.size != STEPS + 1 }) return false
        parsed.forEachIndexed { p, values ->
            setLength(p, values[0])
            for (i in 0 until STEPS) {
                val v = values[i + 1]
                setStep(p, i, pack(note(v).coerceIn(MIN_NOTE, MAX_NOTE), gate(v), accent(v), slide(v)))
            }
        }
        return true
    }

    companion object {
        const val PATTERNS = 16
        const val STEPS = 16
        const val DEFAULT_NOTE = 36
        const val MIN_NOTE = 24
        const val MAX_NOTE = 72

        private const val GATE = 1 shl 8
        private const val ACCENT = 1 shl 9
        private const val SLIDE = 1 shl 10

        fun pack(note: Int, gate: Boolean, accent: Boolean, slide: Boolean): Int =
            (note and 0x7f) or (if (gate) GATE else 0) or (if (accent) ACCENT else 0) or (if (slide) SLIDE else 0)

        fun note(v: Int) = v and 0x7f
        fun gate(v: Int) = v and GATE != 0
        fun accent(v: Int) = v and ACCENT != 0
        fun slide(v: Int) = v and SLIDE != 0
        fun withNote(v: Int, note: Int) = (v and 0x7f.inv()) or (note and 0x7f)
        fun toggleGate(v: Int) = v xor GATE
        fun toggleAccent(v: Int) = v xor ACCENT
        fun toggleSlide(v: Int) = v xor SLIDE
    }
}
