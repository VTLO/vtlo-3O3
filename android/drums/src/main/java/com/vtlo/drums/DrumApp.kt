package com.vtlo.drums

import android.content.Context
import android.util.Log
import com.vtlo.core.audio.InstrumentApp
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPatternBank
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.drums.dsp.TrackParam
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** The drum machine: sample loading, engine creation, persistence and the playback notification. */
class DrumApp : InstrumentApp() {
    override lateinit var engine: DrumEngine
        private set
    private lateinit var samples: SampleBank

    override val mainActivity get() = MainActivity::class.java

    override fun playbackTitle(): String {
        val p = engine.selectedPattern
        return getString(R.string.notification_title, if (p < 8) 'A' else 'B', p % 8 + 1, engine.kit(0).label)
    }

    override fun playbackText(): String =
        getString(R.string.notification_text, DrumParam.normToBpm(engine.get(DrumParam.TEMPO)).roundToInt())

    override fun createEngine(sampleRate: Int) {
        samples = loadSamples(sampleRate)
        engine = DrumEngine(sampleRate, samples)
        if (!restore()) {
            DrumPresets.all.forEachIndexed { i, p -> DrumPresets.load(engine.bank, i, p) }
            val first = DrumPresets.all[0]
            for (t in 0 until Kit.TRACKS) engine.setKit(t, first.kit)
            engine.set(DrumParam.TEMPO, DrumParam.bpmToNorm(first.bpm.toFloat()))
        }
    }

    /** Samples at [rate]: the live bank when it matches, otherwise loaded for that rate (WAV export). */
    fun samplesFor(rate: Int): SampleBank = if (rate == samples.sampleRate) samples else loadSamples(rate)

    /**
     * The kits converted to [rate]. Conversion runs once per rate; the result is
     * cached in the app's no-backup storage and rebuilt if the cache is unreadable.
     */
    private fun loadSamples(rate: Int): SampleBank {
        // Keyed by install time so an app update with new samples rebuilds it.
        val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val cache = File(noBackupFilesDir, "kits-$rate-$installed.bin")
        try {
            if (cache.exists()) cache.inputStream().use { SampleBank.readFrom(it, rate) }?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Sample cache unreadable, rebuilding", e)
        }
        val bank = SampleBank.load(rate) { assets.open(it) }
        try {
            noBackupFilesDir.listFiles { f -> f.name.startsWith("kits-$rate-") }?.forEach { it.delete() }
            val tmp = File(cache.path + ".tmp")
            tmp.outputStream().use(bank::writeTo)
            tmp.renameTo(cache)
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache samples", e)
        }
        return bank
    }

    fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save() {
        prefs().edit().apply {
            putString(KEY_PATTERNS, engine.bank.serialize())
            putInt(KEY_SELECTED, engine.selectedPattern)
            DrumParam.entries.forEach { putFloat(it.key, engine.get(it)) }
            for (t in 0 until Kit.TRACKS) {
                TrackParam.entries.forEach { putFloat("t$t.${it.key}", engine.get(t, it)) }
                putString("t$t.kit", engine.kit(t).name)
                putBoolean("t$t.mute", engine.isMuted(t))
            }
        }.apply()
    }

    private fun restore(): Boolean {
        val p = prefs()
        val data = p.getString(KEY_PATTERNS, null) ?: return false
        if (!engine.bank.deserialize(data)) return false
        DrumParam.entries.forEach { engine.set(it, p.getFloat(it.key, it.default)) }
        for (t in 0 until Kit.TRACKS) {
            TrackParam.entries.forEach { engine.set(t, it, p.getFloat("t$t.${it.key}", it.default)) }
            engine.setKit(t, Kit.entries.firstOrNull { it.name == p.getString("t$t.kit", null) } ?: Kit.TR909)
            engine.setMuted(t, p.getBoolean("t$t.mute", false))
        }
        engine.selectedPattern = p.getInt(KEY_SELECTED, 0).coerceIn(0, DrumPatternBank.PATTERNS - 1)
        return true
    }

    private companion object {
        const val TAG = "DrumApp"
        const val PREFS = "drums"
        const val KEY_PATTERNS = "patterns"
        const val KEY_SELECTED = "selected"
    }
}
