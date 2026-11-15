package com.dpjha.picprep.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.picPrepStore by preferencesDataStore("picprep_settings")

data class ImagePreset(val name: String, val maxKb: Int, val maxDimension: Int)

class PresetStore(private val context: Context) {
    private val key = stringPreferencesKey("custom_presets_v1")
    val presets: Flow<List<ImagePreset>> = context.picPrepStore.data.map { prefs ->
        prefs[key].orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('|'); if (p.size != 3) null else runCatching { ImagePreset(p[0], p[1].toInt(), p[2].toInt()) }.getOrNull()
        }
    }
    suspend fun save(preset: ImagePreset) {
        context.picPrepStore.edit { prefs ->
            val existing = prefs[key].orEmpty().lines().filter { it.isNotBlank() }.filterNot { it.substringBefore('|').equals(preset.name, true) }
            prefs[key] = (existing + "${preset.name.replace('|',' ')}|${preset.maxKb}|${preset.maxDimension}").joinToString("\n")
        }
    }
    suspend fun delete(name: String) { context.picPrepStore.edit { prefs -> prefs[key] = prefs[key].orEmpty().lines().filter { it.substringBefore('|') != name }.joinToString("\n") } }
}
