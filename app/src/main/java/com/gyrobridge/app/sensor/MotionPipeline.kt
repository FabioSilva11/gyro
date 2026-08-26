package com.gyrobridge.app.sensor

import android.util.Log
import com.gyrobridge.app.domain.model.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign

private const val TAG = "GB_Motion"

data class MotionOutput(
    val rawX: Float = 0f, val rawY: Float = 0f, val filteredX: Float = 0f, val filteredY: Float = 0f,
    val dx: Float = 0f, val dy: Float = 0f, val sensorTimestampNanos: Long = 0L,
    val processingTimestampNanos: Long = 0L, val processingLatencyNanos: Long = 0L,
)

class MotionPipeline(profile: ControlProfile) {
    var profile: ControlProfile = profile.sanitized(); private set
    private var xFilter = MotionFilterFactory.create(this.profile.filterConfig.xFilter, this.profile.filterConfig)
    private var yFilter = MotionFilterFactory.create(this.profile.filterConfig.yFilter, this.profile.filterConfig)
    private var smoothX = 0f; private var smoothY = 0f
    private var pendingDegreesX = 0f; private var pendingDegreesY = 0f
    private var lastTimestampNanos = 0L

    fun updateProfile(value: ControlProfile) {
        profile = value.sanitized(); xFilter = MotionFilterFactory.create(profile.filterConfig.xFilter, profile.filterConfig)
        yFilter = MotionFilterFactory.create(profile.filterConfig.yFilter, profile.filterConfig); reset()
    }

    fun process(sample: OrientationSample): MotionOutput {
        val axis = profile.axisConfig; val sensitivity = profile.sensitivityConfig; val gesture = profile.gestureConfig
        var rawX = axisValue(axis.xSource, sample, sensitivity.deadzoneUnit); var rawY = axisValue(axis.ySource, sample, sensitivity.deadzoneUnit)
        if (axis.invertX) rawX = -rawX; if (axis.invertY) rawY = -rawY
        val deadX = if (sensitivity.deadzoneUnit == DeadzoneUnit.DEGREES) accumulateDegrees(rawX, sensitivity.horizontalDeadzone, true) else applyDeadzone(rawX, sensitivity.horizontalDeadzone)
        val deadY = if (sensitivity.deadzoneUnit == DeadzoneUnit.DEGREES) accumulateDegrees(rawY, sensitivity.verticalDeadzone, false) else applyDeadzone(rawY, sensitivity.verticalDeadzone)
        val filteredX = xFilter.apply(deadX, sample.sensorTimestampNanos)
        val filteredY = yFilter.apply(deadY, sample.sensorTimestampNanos)
        val curvedX = response(filteredX, sensitivity.curve, sensitivity.gamma)
        val curvedY = response(filteredY, sensitivity.curve, sensitivity.gamma)
        val velocity = maxOf(abs(sample.angularVelocityX), abs(sample.angularVelocityY), abs(sample.angularVelocityZ))
        if (velocity > gesture.maxAngularVelocity) {
            Log.d(TAG, "Gesture suppressed: velocity=${"%.1f".format(velocity)} > max=${"%.1f".format(gesture.maxAngularVelocity)}")
            return MotionOutput(rawX, rawY, filteredX, filteredY, 0f, 0f, sample.sensorTimestampNanos, System.nanoTime(), 0L)
        }
        val acceleration = accelerationMultiplier(velocity, sensitivity)
        var dx = curvedX * sensitivity.horizontal * sensitivity.pixelsPerDegree * sensitivity.globalMultiplier * acceleration
        var dy = curvedY * sensitivity.vertical * sensitivity.pixelsPerDegree * sensitivity.globalMultiplier * acceleration
        val smoothing = profile.filterConfig.smoothing.coerceIn(0f, .99f)
        smoothX = smoothX * smoothing + dx * (1f - smoothing); smoothY = smoothY * smoothing + dy * (1f - smoothing)
        dx = smoothX.coerceIn(-gesture.maxXPerUpdate, gesture.maxXPerUpdate)
        dy = smoothY.coerceIn(-gesture.maxYPerUpdate, gesture.maxYPerUpdate)
        if (lastTimestampNanos != 0L) {
            val dt = ((sample.sensorTimestampNanos-lastTimestampNanos)/1_000_000_000f).coerceIn(.0001f,.1f)
            val perFrame = gesture.maxPixelsPerSecond * dt
            dx = dx.coerceIn(-perFrame, perFrame); dy = dy.coerceIn(-perFrame, perFrame)
        }
        lastTimestampNanos = sample.sensorTimestampNanos
        val processed = System.nanoTime()
        return MotionOutput(rawX, rawY, filteredX, filteredY, dx, dy, sample.sensorTimestampNanos, processed, (processed - sample.sensorTimestampNanos).coerceAtLeast(0L))
    }

    fun reset() { xFilter.reset(); yFilter.reset(); smoothX = 0f; smoothY = 0f; pendingDegreesX = 0f; pendingDegreesY = 0f; lastTimestampNanos = 0L }

    private fun accumulateDegrees(value: Float, threshold: Float, horizontal: Boolean): Float {
        var pending = if (horizontal) pendingDegreesX else pendingDegreesY
        if (pending != 0f && value != 0f && sign(pending) != sign(value)) pending = 0f
        pending += value
        val output = if (abs(pending) + 0.000001f >= threshold.coerceAtLeast(0f)) pending else 0f
        if (output != 0f) pending = 0f
        if (horizontal) pendingDegreesX = pending else pendingDegreesY = pending
        return output
    }

    private fun axisValue(source: AxisSource, sample: OrientationSample, unit: DeadzoneUnit) = if (unit == DeadzoneUnit.DEGREES) when (source) {
        // YAW and PITCH are both inverted so that a rightward / upward tilt
        // produces the swipe direction that a typical touch-camera expects
        // (swipe left = look right, swipe up = look up).
        AxisSource.YAW -> -sample.deltaYaw
        AxisSource.PITCH -> -sample.deltaPitch
        AxisSource.ROLL -> sample.deltaRoll
    } else when (source) {
        AxisSource.YAW -> -sample.angularVelocityZ
        AxisSource.PITCH -> -sample.angularVelocityX
        AxisSource.ROLL -> sample.angularVelocityY
    }

    companion object {
        fun applyDeadzone(value: Float, threshold: Float): Float = if (abs(value) < threshold.coerceAtLeast(0f)) 0f else value
        fun response(value: Float, curve: ResponseCurve, gamma: Float): Float {
            if (value == 0f) return 0f
            val magnitude = abs(value); val normalized = magnitude.coerceIn(0f, 1f); val signed = sign(value)
            return when (curve) {
                ResponseCurve.LINEAR -> value
                ResponseCurve.EXPONENTIAL -> signed * ((exp(normalized) - 1f) / (kotlin.math.E.toFloat() - 1f)) * maxOf(1f, magnitude)
                ResponseCurve.POWER, ResponseCurve.CUSTOM -> signed * magnitude.pow(gamma.coerceIn(.2f, 4f))
                ResponseCurve.SMOOTH_STEP -> signed * (normalized * normalized * (3f - 2f * normalized)) * maxOf(1f, magnitude)
                ResponseCurve.S_CURVE -> signed * (normalized * normalized * normalized * (normalized * (normalized * 6f - 15f) + 10f)) * maxOf(1f, magnitude)
            }
        }
        private fun accelerationMultiplier(velocity: Float, config: SensitivityConfig): Float {
            if (config.acceleration == AccelerationMode.OFF || velocity <= config.accelerationThreshold) return 1f
            val preset = when (config.acceleration) { AccelerationMode.LIGHT -> 1.25f; AccelerationMode.MEDIUM -> 1.75f; AccelerationMode.STRONG -> 2.5f; AccelerationMode.CUSTOM -> config.accelerationMultiplier; else -> 1f }
            return (1f + (velocity - config.accelerationThreshold) / config.accelerationThreshold.coerceAtLeast(.1f) * (preset - 1f)).coerceAtMost(config.accelerationMaximum)
        }
    }
}
