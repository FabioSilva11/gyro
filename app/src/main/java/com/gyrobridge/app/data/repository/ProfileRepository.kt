package com.gyrobridge.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gyrobridge.app.data.profile.ProfileJsonCodec
import com.gyrobridge.app.domain.model.ControlProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gyroDataStore by preferencesDataStore("gyrobridge")

class ProfileRepository(private val context: Context) {
    private val profilesKey = stringPreferencesKey("profiles_json_v1")
    private val activeIdKey = stringPreferencesKey("active_profile_id")

    val profiles: Flow<List<ControlProfile>> = context.gyroDataStore.data.map { prefs ->
        ProfileJsonCodec.decodeProfiles(prefs[profilesKey].orEmpty())
    }
    val activeProfileId: Flow<String?> = context.gyroDataStore.data.map { it[activeIdKey] }

    suspend fun save(profile: ControlProfile) {
        context.gyroDataStore.edit { prefs ->
            val current = ProfileJsonCodec.decodeProfiles(prefs[profilesKey].orEmpty()).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile.sanitized() else current.add(profile.sanitized())
            prefs[profilesKey] = ProfileJsonCodec.encodeProfiles(current)
        }
    }

    suspend fun delete(id: String) {
        context.gyroDataStore.edit { prefs ->
            val remaining = ProfileJsonCodec.decodeProfiles(prefs[profilesKey].orEmpty()).filterNot { it.id == id }
            prefs[profilesKey] = ProfileJsonCodec.encodeProfiles(remaining)
            if (prefs[activeIdKey] == id) prefs.remove(activeIdKey)
        }
    }

    suspend fun setActive(id: String?) = context.gyroDataStore.edit { prefs ->
        if (id == null) prefs.remove(activeIdKey) else prefs[activeIdKey] = id
    }

    suspend fun find(id: String): ControlProfile? = profiles.first().firstOrNull { it.id == id }
    suspend fun findByPackage(packageName: String): ControlProfile? = profiles.first().firstOrNull { it.enabled && it.autoActivate && it.packageName == packageName }
}
