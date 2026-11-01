package com.vtlo.acid303.dsp

import kotlin.random.Random

class Preset(val name: String, val bpm: Int, val steps: IntArray)

/**
 * Built-in patterns, ported from the web version. The web sequencer treated
 * "slide" as "glide into this step"; here slide uses hardware semantics
 * (glide out of this step), so each flag is moved one step earlier.
 */
object Presets {
    private const val REST = -1

    // Each entry: note, or REST. Flags: a = accent, s = slide-into (web semantics).
    private fun pat(vararg cells: Any): IntArray {
        require(cells.size == 16)
        val notes = IntArray(16)
        val acc = BooleanArray(16)
        val slideInto = BooleanArray(16)
        cells.forEachIndexed { i, c ->
            when (c) {
                is Int -> notes[i] = c
                is String -> {
                    notes[i] = c.takeWhile { it.isDigit() || it == '-' }.toInt()
                    acc[i] = 'a' in c
                    slideInto[i] = 's' in c
                }
            }
        }
        return IntArray(16) { i ->
            val gate = notes[i] != REST
            val slide = slideInto[(i + 1) % 16]
            PatternBank.pack(if (gate) notes[i] else PatternBank.DEFAULT_NOTE, gate, acc[i], slide && gate)
        }
    }

    val all = listOf(
        Preset("Acid Classic", 138, pat(
            "36a", 36, REST, "48s", 39, "36a", REST, 36,
            "48as", 36, REST, 41, "36a", REST, 36, "43s")),
        Preset("Minimal Techno", 126, pat(
            "36a", REST, REST, 36, REST, 36, REST, REST,
            "36a", REST, 36, REST, REST, REST, 36, REST)),
        Preset("Accent Arp", 140, pat(
            "36a", 39, 41, "43a", 46, "48a", "46s", 43,
            "41a", 39, 36, "39a", "41s", 43, "46a", 48)),
        Preset("Deep House", 122, pat(
            "36a", REST, 36, "38s", REST, 36, REST, 43,
            "36a", REST, 36, REST, "41s", 38, REST, 36)),
        Preset("Acid House", 132, pat(
            "36a", 36, "48as", 36, REST, "39a", "36s", 36,
            "41a", 36, REST, "48a", "36s", 36, "43a", REST)),
        Preset("Slow Melodic", 108, pat(
            48, "46s", 43, "41s", 39, REST, 36, "38s",
            41, "43s", 46, "48a", REST, "46s", 43, REST)),
        Preset("Aggressive", 145, pat(
            "36a", "48a", "36a", "48a", REST, "36a", "51as", 48,
            "36a", 36, "48a", "36a", "51as", "48a", REST, "36a")),
        Preset("Experimental", 135, pat(
            38, REST, "48as", 41, "46a", REST, "36s", REST,
            "53a", "43s", REST, 39, REST, "48a", "36s", 44)),
    )

    fun load(bank: PatternBank, pattern: Int, preset: Preset) {
        preset.steps.forEachIndexed { i, v -> bank.setStep(pattern, i, v) }
        bank.setLength(pattern, PatternBank.STEPS)
    }

    /** Musically constrained random line in C minor pentatonic-ish scale. */
    fun randomize(bank: PatternBank, pattern: Int, random: Random = Random.Default) {
        val notes = intArrayOf(36, 36, 36, 38, 39, 41, 43, 46, 48, 48, 51)
        for (i in 0 until PatternBank.STEPS) {
            val gate = random.nextFloat() > 0.28f
            bank.setStep(
                pattern, i,
                PatternBank.pack(
                    notes[random.nextInt(notes.size)], gate,
                    accent = gate && random.nextFloat() > 0.7f,
                    slide = gate && random.nextFloat() > 0.78f
                )
            )
        }
    }
}
