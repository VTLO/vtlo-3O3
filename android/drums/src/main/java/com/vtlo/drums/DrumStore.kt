package com.vtlo.drums

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPatternBank
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.drums.dsp.TrackParam
import java.io.File

/** Loading the bundled kits and persisting the drum machine's state. */
object DrumStore {
    private const val TAG = "DrumStore"
    private const val PREFS = "drums"
    private const val KEY_PATTERNS = "patterns"
    private const val KEY_SELECTED = "selected"

    /**
     * The kits converted to [rate]. Conversion runs once per rate and app install;
     * the result is cached in no-backup storage and rebuilt if unreadable.
     */
    fun loadSamples(context: Context, rate: Int): SampleBank {
        // Keyed by install time so an app update with new samples rebuilds it.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = context.noBackupFilesDir
        val cache = File(dir, "kits-$rate-$installed.bin")
        try {
            if (cache.exists()) cache.inputStream().use { SampleBank.readFrom(it, rate) }?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Sample cache unreadable, rebuilding", e)
        }
        val bank = SampleBank.load(rate) { context.assets.open(it) }
        try {
            dir.listFiles { f -> f.name.startsWith("kits-$rate-") }?.forEach { it.delete() }
            val tmp = File(cache.path + ".tmp")
            tmp.outputStream().use(bank::writeTo)
            tmp.renameTo(cache)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache samples", e)
        }
        return bank
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, engine: DrumEngine) {
        prefs(context).edit().apply {
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

    /** Restores the saved state, or loads the presets on first run. */
    fun restore(context: Context, engine: DrumEngine) {
        val p = prefs(context)
        val data = p.getString(KEY_PATTERNS, null)
        if (data == null || !engine.bank.deserialize(data)) {
            DrumPresets.all.forEachIndexed { i, preset -> DrumPresets.load(engine.bank, i, preset) }
            val first = DrumPresets.all[0]
            for (t in 0 until Kit.TRACKS) engine.setKit(t, first.kit)
            engine.set(DrumParam.TEMPO, DrumParam.bpmToNorm(first.bpm.toFloat()))
            return
        }
        DrumParam.entries.forEach { engine.set(it, p.getFloat(it.key, it.default)) }
        for (t in 0 until Kit.TRACKS) {
            TrackParam.entries.forEach { engine.set(t, it, p.getFloat("t$t.${it.key}", it.default)) }
            engine.setKit(t, Kit.entries.firstOrNull { it.name == p.getString("t$t.kit", null) } ?: Kit.TR909)
            engine.setMuted(t, p.getBoolean("t$t.mute", false))
        }
        engine.selectedPattern = p.getInt(KEY_SELECTED, 0).coerceIn(0, DrumPatternBank.PATTERNS - 1)
    }
}
