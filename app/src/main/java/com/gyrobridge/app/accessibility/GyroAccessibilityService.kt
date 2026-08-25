package com.gyrobridge.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.gyrobridge.app.MainActivity
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.gesture.GestureDispatcherRegistry
import com.gyrobridge.app.gesture.GestureScheduler
import com.gyrobridge.app.service.GyroForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "GB_A11y"

class GyroAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var scheduler: GestureScheduler

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected: attaching scheduler")
        try {
            scheduler = GestureScheduler(this)
            GestureDispatcherRegistry.attach(scheduler)
            val profile = AppGraph.runtime.activeProfile.value
            if (profile != null) {
                scheduler.configure(profile)
                Log.i(TAG, "onServiceConnected: scheduler configured with profile=${profile.name}")
            } else {
                Log.w(TAG, "onServiceConnected: no active profile to configure")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected: FAILED", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) {
            val openedMainActivity = event.className?.toString() == MainActivity::class.java.name
            if (openedMainActivity && AppGraph.runtime.sessionActive.value && !AppGraph.runtime.sessionPaused.value) {
                Log.d(TAG, "Own MainActivity opened while session active, pausing")
                GyroForegroundService.action(this, GyroForegroundService.ACTION_PAUSE)
            }
            return
        }
        if (packageName.startsWith("com.android.systemui")) return
        Log.d(TAG, "Window changed: pkg=$packageName sessionActive=${AppGraph.runtime.sessionActive.value} paused=${AppGraph.runtime.sessionPaused.value}")
        AppGraph.runtime.setForegroundPackage(packageName)
        if (!AppGraph.runtime.sessionActive.value) return
        if (!::scheduler.isInitialized) {
            Log.w(TAG, "onAccessibilityEvent: scheduler not initialized yet")
            return
        }
        scope.launch {
            try {
                val active = AppGraph.runtime.activeProfile.value
                if (active != null && active.packageName == null) {
                    Log.d(TAG, "Profile has no pkg filter, configuring and resuming")
                    scheduler.configure(active)
                    if (AppGraph.runtime.sessionPaused.value) scheduler.cancelAll() else scheduler.resume()
                    return@launch
                }
                val matching = AppGraph.repository.findByPackage(packageName)
                if (matching != null) {
                    val alreadySelected = AppGraph.runtime.activeProfile.value?.id == matching.id
                    if (!alreadySelected) {
                        Log.i(TAG, "Switching profile to '${matching.name}' for pkg=$packageName")
                        AppGraph.runtime.setProfile(matching); AppGraph.runtime.setPaused(true); scheduler.configure(matching); scheduler.cancelAll()
                        GyroForegroundService.switchProfile(this@GyroAccessibilityService, matching.id)
                        if (matching.calibrationConfig.recalibrateOnAppChange) GyroForegroundService.action(this@GyroAccessibilityService, GyroForegroundService.ACTION_CALIBRATE)
                    } else if (AppGraph.runtime.sessionPaused.value) scheduler.cancelAll() else scheduler.resume()
                } else {
                    Log.d(TAG, "No matching profile for pkg=$packageName, pausing")
                    AppGraph.runtime.setPaused(true); scheduler.cancelAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onAccessibilityEvent error for pkg=$packageName", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt called")
        if (::scheduler.isInitialized) scheduler.cancelAll()
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: detaching scheduler")
        if (::scheduler.isInitialized) { scheduler.cancelAll(); GestureDispatcherRegistry.detach(scheduler) }
        scope.cancel()
        super.onDestroy()
    }
}
