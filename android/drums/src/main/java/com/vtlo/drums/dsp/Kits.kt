package com.vtlo.drums.dsp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The three drum machines. Every kit maps its sounds onto the same eight track
 * roles so patterns keep their meaning when the kit changes:
 * kick, snare, tom, clap (606: high tom), rim, closed hat, open hat, cymbal (909: ride).
 */
enum class Kit(val label: String, val files: List<String>, val trackNames: List<String>) {
    TR606(
        "606",
        listOf("Bass", "Snare", "Tom_Lo", "Tom_Hi", "Rim", "HH_Closed", "HH_Open", "Cymbal"),
        listOf("BD", "SD", "LT", "HT", "RS", "CH", "OH", "CY"),
    ),
    TR808(
        "808",
        listOf("Bass", "Snare", "Tom", "Claps", "Rim", "HH_Closed", "HH_Open", "Cymbal"),
        listOf("BD", "SD", "TOM", "CP", "RS", "CH", "OH", "CY"),
    ),
    TR909(
        "909",
        listOf("Bass", "Snare", "Tom", "Claps", "Rim", "HH_Closed", "HH_Open", "Ride"),
        listOf("BD", "SD", "TOM", "CP", "RS", "CH", "OH", "RD"),
    );

    fun assetPath(track: Int) = "kits/$label/${files[track]}.wav"

    companion object {
        const val TRACKS = 8
        const val CLOSED_HAT = 5
        const val OPEN_HAT = 6
    }
}

/** Every kit's samples, converted to one output rate. Immutable once built. */
class SampleBank private constructor(val sampleRate: Int, private val samples: Array<Array<FloatArray>>) {
    fun get(kit: Kit, track: Int): FloatArray = samples[kit.ordinal][track]

    /** Writes the converted samples so later launches can skip resampling. */
    fun writeTo(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(CACHE_VERSION)
        d.writeInt(sampleRate)
        for (kit in samples) for (data in kit) {
            d.writeInt(data.size)
            for (v in data) d.writeFloat(v)
        }
        d.flush()
    }

    companion object {
        private const val CACHE_VERSION = 1

        /** Reads a bank written by [writeTo]; null if it is for another rate or version. */
        fun readFrom(input: InputStream, sampleRate: Int): SampleBank? {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != CACHE_VERSION || d.readInt() != sampleRate) return null
            return SampleBank(sampleRate, Array(Kit.entries.size) {
                Array(Kit.TRACKS) { FloatArray(d.readInt()).also { a -> for (i in a.indices) a[i] = d.readFloat() } }
            })
        }

        /** @param open opens an asset path such as `kits/909/Bass.wav`. */
        fun load(sampleRate: Int, open: (String) -> InputStream): SampleBank = SampleBank(
            sampleRate,
            Array(Kit.entries.size) { k ->
                val kit = Kit.entries[k]
                Array(Kit.TRACKS) { t ->
                    val s = open(kit.assetPath(t)).use(WavReader::read)
                    Resampler.resample(s.data, s.sampleRate, sampleRate)
                }
            }
        )
    }
}
