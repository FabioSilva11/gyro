package com.gyrobridge.app.telemetry

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.gyrobridge.app.domain.model.SessionStatus
import com.gyrobridge.app.sensor.OrientationSample
import com.gyrobridge.app.sensor.PhysicalMovementOutput

class TestTelemetryPublisher(private val context: Context) {
    private var lastPublishNanos = 0L

    fun publish(
        orientation: OrientationSample,
        movement: PhysicalMovementOutput,
        sensorName: String,
        status: SessionStatus,
        accessibilityAvailable: Boolean,
    ) {
        val now = System.nanoTime()
        if (now - lastPublishNanos < MIN_INTERVAL_NANOS) return
        lastPublishNanos = now
        val intent = Intent(ACTION_TELEMETRY)
            .setPackage(DRAG_TEST_PACKAGE)
            .putExtra("sensor", sensorName.take(80))
            .putExtra("rotation", displayRotation())
            .putExtra("yaw", orientation.yaw)
            .putExtra("pitch", orientation.pitch)
            .putExtra("status", status.name)
            .putExtra("accessibility", accessibilityAvailable)
            .putExtra("movement", movement.state.name)
            .putExtra("forwardAcceleration", movement.forwardSignal)
        context.sendBroadcast(intent, TELEMETRY_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

    companion object {
        const val ACTION_TELEMETRY = "com.gyrobridge.action.TEST_TELEMETRY"
        const val TELEMETRY_PERMISSION = "com.gyrobridge.permission.TEST_TELEMETRY"
        private const val DRAG_TEST_PACKAGE = "com.gyrobridge.dragtest"
        private const val MIN_INTERVAL_NANOS = 200_000_000L
    }
}
