package com.gyrobridge.app.gesture

import android.util.Log
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.domain.model.PhysicalMovementState
import com.gyrobridge.app.sensor.OrientationSample

private const val TAG = "GB_GestReg"

enum class ConnectionState { DISCONNECTED, CONNECTED }

object GestureDispatcherRegistry {
    @Volatile private var scheduler: GestureScheduler? = null
    private var pendingProfile: ControlProfile? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var connectionListeners = mutableListOf<(ConnectionState) -> Unit>()

    fun attach(value: GestureScheduler) {
        scheduler = value; connectionState = ConnectionState.CONNECTED
        Log.i(TAG, "Scheduler attached: ${value}")
        pendingProfile?.let { p ->
            Log.i(TAG, "Applying pending profile: ${p.name}")
            value.configure(p); pendingProfile = null
        }
        notifyListeners()
    }

    fun detach(value: GestureScheduler) {
        if (scheduler === value) {
            scheduler = null; connectionState = ConnectionState.DISCONNECTED
            Log.w(TAG, "Scheduler detached")
            notifyListeners()
        }
    }

    fun configure(profile: ControlProfile) {
        Log.d(TAG, "Configure: profile=${profile.name} state=$connectionState")
        val s = scheduler
        if (s != null) {
            s.configure(profile)
        } else {
            Log.w(TAG, "Scheduler not available, storing profile as pending")
            pendingProfile = profile
        }
    }

    fun enqueue(dx: Float, dy: Float, queuedAt: Long, orientation: OrientationSample): Boolean {
        val s = scheduler
        if (s == null) {
            Log.w(TAG, "Enqueue FAILED: scheduler is null (state=$connectionState)")
            return false
        }
        s.enqueue(GestureRequest(dx, dy, queuedAt), orientation)
        return true
    }

    fun updateMovement(state: PhysicalMovementState): Boolean {
        val current = scheduler ?: return false
        current.updateMovement(state)
        return true
    }

    fun cancelAll() {
        Log.d(TAG, "cancelAll: state=$connectionState")
        scheduler?.cancelAll()
    }

    fun resume() {
        Log.d(TAG, "resume: state=$connectionState")
        scheduler?.resume()
    }

    fun isAvailable() = scheduler != null
    fun getConnectionState() = connectionState

    fun addConnectionListener(listener: (ConnectionState) -> Unit) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: (ConnectionState) -> Unit) {
        connectionListeners.remove(listener)
    }

    private fun notifyListeners() {
        val state = connectionState
        connectionListeners.forEach { it(state) }
    }
}
