package com.gyrobridge.app.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.gyrobridge.app.domain.mapper.PixelZone
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.sensor.OrientationSample
import kotlin.math.abs

object MultiTouchGestureComposer {
    fun joystickStroke(profile: ControlProfile, sample: OrientationSample, width: Int, height: Int, duration: Long): GestureDescription.StrokeDescription? {
        val j = profile.joystickConfig
        if (!j.enabled) return null
        var tiltX = sample.roll; var tiltY = sample.pitch
        if (j.invertX) tiltX = -tiltX; if (j.invertY) tiltY = -tiltY
        if (abs(tiltX) < j.deadzone) tiltX = 0f; if (abs(tiltY) < j.deadzone) tiltY = 0f
        val cx = j.centerX * width; val cy = j.centerY * height; val radius = j.radius * minOf(width, height)
        val x = cx + (tiltX / j.maximumTilt).coerceIn(-1f, 1f) * radius
        val y = cy + (tiltY / j.maximumTilt).coerceIn(-1f, 1f) * radius
        return GestureDescription.StrokeDescription(Path().apply { moveTo(cx, cy); lineTo(x, y) }, 0, duration)
    }
}
