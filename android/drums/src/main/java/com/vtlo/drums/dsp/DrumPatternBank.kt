package com.vtlo.drums.dsp

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Pattern memory shared by the UI (editing) and audio thread (playback).
 * One int per pattern and track: bits 0-15 are the steps that play,
 * bits 16-31 the steps that are accented.
 */
class DrumPatternBank {
    private val rows = AtomicIntegerArray(PATTERNS * Kit.TRACKS)
    private val lengths = AtomicIntegerArray(PATTERNS)

    init {
        for (p in 0 until PATTERNS) clear(p)
    }

    fun row(pattern: Int, track: Int): Int = rows.get(pattern * Kit.TRACKS + track)

    fun setRow(pattern: Int, track: Int, value: Int) = rows.set(pattern * Kit.TRACKS + track, value)

    fun isOn(pattern: Int, track: Int, step: Int) = row(pattern, track) and (1 shl step) != 0

    fun isAccent(pattern: Int, track: Int, step: Int) = row(pattern, track) and (1 shl (step + 16)) != 0

    /** Toggles a step; turning a step off also clears its accent. */
    fun toggle(pattern: Int, track: Int, step: Int) {
        val r = row(pattern, track)
        setRow(pattern, track, if (r and (1 shl step) != 0) r and (1 shl step or (1 shl (step + 16))).inv() else r or (1 shl step))
    }

    /** Toggles a step's accent; accenting a step also turns it on. */
    fun toggleAccent(pattern: Int, track: Int, step: Int) {
        val r = row(pattern, track)
        val acc = 1 shl (step + 16)
        setRow(pattern, track, if (r and acc != 0) r and acc.inv() else r or acc or (1 shl step))
    }

    fun length(pattern: Int): Int = lengths.get(pattern)

    fun setLength(pattern: Int, len: Int) = lengths.set(pattern, len.coerceIn(1, STEPS))

    fun clear(pattern: Int) {
        for (t in 0 until Kit.TRACKS) setRow(pattern, t, 0)
        setLength(pattern, STEPS)
    }

    /** Pattern as length followed by its rows, for copy/paste and undo. */
    fun snapshot(pattern: Int) = IntArray(Kit.TRACKS + 1) { if (it == 0) length(pattern) else row(pattern, it - 1) }

    fun restore(pattern: Int, data: IntArray) {
        setLength(pattern, data[0])
        for (t in 0 until Kit.TRACKS) setRow(pattern, t, data[t + 1])
    }

    fun serialize(): String = (0 until PATTERNS).joinToString(";") { p -> snapshot(p).joinToString(",") }

    fun deserialize(data: String): Boolean {
        val pats = data.split(';').map { it.split(',').mapNotNull(String::toIntOrNull) }
        if (pats.size != PATTERNS || pats.any { it.size != Kit.TRACKS + 1 }) return false
        pats.forEachIndexed { p, values -> restore(p, values.toIntArray()) }
        return true
    }

    companion object {
        const val PATTERNS = 16
        const val STEPS = 16
    }
}
