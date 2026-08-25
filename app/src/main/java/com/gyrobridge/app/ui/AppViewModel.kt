package com.gyrobridge.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.data.profile.ProfileJsonCodec
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.profile.InstalledApp
import com.gyrobridge.app.sensor.SensorEngine
import com.gyrobridge.app.service.GyroForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val profiles = AppGraph.repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runtime = AppGraph.runtime
    private val _editingProfile = MutableStateFlow<ControlProfile?>(null); val editingProfile = _editingProfile.asStateFlow()
    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList()); val apps = _apps.asStateFlow()
    private val previewEngine = SensorEngine(application)
    val previewSample = previewEngine.samples; val previewInfo = previewEngine.info

    init { AppGraph.runtime.setSensorInfo(previewEngine.detectedInfo()) }

    fun edit(profile: ControlProfile?) { _editingProfile.value = profile }
    fun createProfile() { _editingProfile.value = ControlProfile() }
    fun updateEditing(transform: (ControlProfile) -> ControlProfile) { _editingProfile.value?.let { _editingProfile.value = transform(it).sanitized() } }
    fun saveEditing(onDone: (() -> Unit)? = null) = viewModelScope.launch { _editingProfile.value?.let { AppGraph.repository.save(it) }; onDone?.invoke() }
    fun delete(profile: ControlProfile) = viewModelScope.launch { AppGraph.repository.delete(profile.id) }

    fun loadApps() = viewModelScope.launch { _apps.value = AppGraph.profileManager.launchableApps() }
    fun selectApp(app: InstalledApp) = updateEditing { it.copy(packageName = app.packageName, appLabel = app.label) }

    fun startProfile(profile: ControlProfile, launchApp: Boolean) = viewModelScope.launch {
        AppGraph.repository.save(profile); AppGraph.repository.setActive(profile.id); GyroForegroundService.start(getApplication(), profile.id)
        if (launchApp) { delay(600); AppGraph.profileManager.launch(profile) }
    }
    fun stop() = GyroForegroundService.action(getApplication(), GyroForegroundService.ACTION_STOP)
    fun pause() = GyroForegroundService.action(getApplication(), GyroForegroundService.ACTION_PAUSE)
    fun calibrate() = GyroForegroundService.action(getApplication(), GyroForegroundService.ACTION_CALIBRATE)

    fun startPreview(profile: ControlProfile = _editingProfile.value ?: ControlProfile()) { if (!runtime.sessionActive.value) previewEngine.start(profile.sensorConfig) }
    fun stopPreview() { if (!runtime.sessionActive.value) previewEngine.stop() }
    fun calibrateEditingFromPreview() = updateEditing { p ->
        val s = if (runtime.sessionActive.value) runtime.orientation.value else previewSample.value
        p.copy(calibrationConfig = p.calibrationConfig.copy(zeroYaw = s.yaw, zeroPitch = s.pitch, zeroRoll = s.roll))
    }

    fun exportProfile(context: Context, uri: Uri, profile: ControlProfile) = viewModelScope.launch(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ProfileJsonCodec.encode(profile).toString(2)) }
    }
    fun importProfile(context: Context, uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val profile = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { ProfileJsonCodec.decode(it.readText()) } }.getOrNull() }
        if (profile != null) { AppGraph.repository.save(profile.copy(id = java.util.UUID.randomUUID().toString())); onResult(true) } else onResult(false)
    }

    override fun onCleared() { previewEngine.stop(); super.onCleared() }
}
