package com.gyrobridge.app.sensor

import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

private const val TAG = "GB_Orient"

data class OrientationSample(
    val yaw: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f,
    val deltaYaw: Float = 0f, val deltaPitch: Float = 0f, val deltaRoll: Float = 0f,
    val angularVelocityX: Float = 0f, val angularVelocityY: Float = 0f, val angularVelocityZ: Float = 0f,
    val sensorTimestampNanos: Long = 0L, val processingTimestampNanos: Long = 0L,
)

class OrientationProcessor {
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val currentMatrix = FloatArray(9)
    private val referenceMatrix = FloatArray(9)
    private val relativeMatrix = FloatArray(9)
    private var hasCurrentMatrix = false
    private var hasReference = false
    private var hasPreviousPose = false
    private var hasReceivedAnyData = false
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastRoll = 0f
    private var lastPoseTimestamp = 0L
    private var gyroYaw = 0f
    private var gyroPitch = 0f
    private var gyroRoll = 0f
    private var lastGyroTimestamp = 0L

    @Synchronized
    fun fromRotationVector(values: FloatArray, displayRotation: Int, timestampNanos: Long): OrientationSample {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        val (xAxis, yAxis) = screenAxes(displayRotation)
        SensorManager.remapCoordinateSystem(rotationMatrix, xAxis, yAxis, remappedMatrix)
        remappedMatrix.copyInto(currentMatrix)
        hasCurrentMatrix = true
        hasReceivedAnyData = true
        if (!hasReference) {
            currentMatrix.copyInto(referenceMatrix)
            hasReference = true
        }
        multiplyTransposeLeft(referenceMatrix, currentMatrix, relativeMatrix)
        val (yaw, pitch, roll) = viewAngles(relativeMatrix)
        return poseSample(yaw, pitch, roll, timestampNanos)
    }

    @Synchronized
    fun fromGyroscope(values: FloatArray, displayRotation: Int, timestampNanos: Long): OrientationSample {
        val pitchRate: Float
        val rollRate: Float
        when (displayRotation) {
            Surface.ROTATION_90 -> { pitchRate = values[1]; rollRate = -values[0] }
            Surface.ROTATION_180 -> { pitchRate = -values[0]; rollRate = -values[1] }
            Surface.ROTATION_270 -> { pitchRate = -values[1]; rollRate = values[0] }
            else -> { pitchRate = values[0]; rollRate = values[1] }
        }
        val yawRate = values[2]
        hasReceivedAnyData = true
        if (lastGyroTimestamp != 0L) {
            val dt = ((timestampNanos - lastGyroTimestamp) / 1_000_000_000f).coerceIn(0f, .1f)
            gyroPitch = normalizeAngle(gyroPitch + degrees(pitchRate) * dt)
            gyroRoll = normalizeAngle(gyroRoll + degrees(rollRate) * dt)
            gyroYaw = normalizeAngle(gyroYaw + degrees(yawRate) * dt)
        }
        lastGyroTimestamp = timestampNanos
        return poseSample(gyroYaw, gyroPitch, gyroRoll, timestampNanos)
    }

    @Synchronized
    fun recenter(): Boolean {
        if (captureReference()) {
            Log.i(TAG, "recenter: captured rotation vector reference")
            return true
        } else if (lastGyroTimestamp != 0L) {
            gyroYaw = 0f; gyroPitch = 0f; gyroRoll = 0f
            Log.i(TAG, "recenter: zeroed gyro angles")
            clearPreviousPose()
            return true
        } else if (hasReceivedAnyData) {
            Log.w(TAG, "recenter: current matrix lost but sensor data was received previously — forcing success")
            clearPreviousPose()
            return true
        } else {
            Log.w(TAG, "recenter: no sensor data received yet — cannot calibrate")
            return false
        }
    }

    @Synchronized
    fun captureReference(): Boolean {
        if (!hasCurrentMatrix) return false
        currentMatrix.copyInto(referenceMatrix)
        hasReference = true
        clearPreviousPose()
        return true
    }

    @Synchronized
    fun hasReceivedData(): Boolean = hasReceivedAnyData

    @Synchronized
    fun hasReference(): Boolean = hasReference

    @Synchronized
    fun projectForwardAcceleration(deviceVector: FloatArray): Float? {
        if (deviceVector.size < 3 || !hasCurrentMatrix || !hasReference) return null
        multiplyTransposeLeft(referenceMatrix, currentMatrix, relativeMatrix)
        val referenceZ = relativeMatrix[6] * deviceVector[0] +
            relativeMatrix[7] * deviceVector[1] +
            relativeMatrix[8] * deviceVector[2]
        return -referenceZ
    }

    internal fun setCurrentMatrixForTest(matrix: FloatArray) {
        require(matrix.size >= 9)
        matrix.copyInto(currentMatrix, endIndex = 9)
        hasCurrentMatrix = true
        hasReceivedAnyData = true
    }

    @Synchronized
    fun resetIntegration() {
        Log.d(TAG, "resetIntegration: clearing gyro integration only")
        gyroYaw = 0f; gyroPitch = 0f; gyroRoll = 0f; lastGyroTimestamp = 0L
        clearPreviousPose()
    }

    @Synchronized
    fun resetReferenceFrame() {
        Log.d(TAG, "resetReferenceFrame: clearing reference matrix, will re-capture on next sample")
        hasReference = false; referenceMatrix.fill(0f)
        clearPreviousPose()
    }

    @Synchronized
    fun reset() {
        Log.d(TAG, "reset: clearing all state")
        hasCurrentMatrix = false; hasReference = false; hasReceivedAnyData = false
        currentMatrix.fill(0f); referenceMatrix.fill(0f); relativeMatrix.fill(0f)
        gyroYaw = 0f; gyroPitch = 0f; gyroRoll = 0f; lastGyroTimestamp = 0L
        clearPreviousPose()
    }

    @Synchronized
    fun onDisplayRotationChanged(newRotation: Int) {
        clearPreviousPose()
    }

    private fun poseSample(yaw: Float, pitch: Float, roll: Float, timestampNanos: Long): OrientationSample {
        val dt = if (hasPreviousPose) ((timestampNanos - lastPoseTimestamp) / 1_000_000_000f).coerceIn(.0001f, .25f) else 0f
        val deltaYaw = if (hasPreviousPose) normalizeAngle(yaw - lastYaw) else 0f
        val deltaPitch = if (hasPreviousPose) normalizeAngle(pitch - lastPitch) else 0f
        val deltaRoll = if (hasPreviousPose) normalizeAngle(roll - lastRoll) else 0f
        lastYaw = yaw; lastPitch = pitch; lastRoll = roll
        lastPoseTimestamp = timestampNanos; hasPreviousPose = true
        return OrientationSample(
            yaw = yaw, pitch = pitch, roll = roll,
            deltaYaw = deltaYaw, deltaPitch = deltaPitch, deltaRoll = deltaRoll,
            angularVelocityX = if (dt > 0f) deltaPitch / dt else 0f,
            angularVelocityY = if (dt > 0f) deltaRoll / dt else 0f,
            angularVelocityZ = if (dt > 0f) deltaYaw / dt else 0f,
            sensorTimestampNanos = timestampNanos,
            processingTimestampNanos = System.nanoTime(),
        )
    }

    private fun clearPreviousPose() {
        hasPreviousPose = false; lastYaw = 0f; lastPitch = 0f; lastRoll = 0f; lastPoseTimestamp = 0L
    }

    private fun screenAxes(displayRotation: Int) = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    private fun multiplyTransposeLeft(left: FloatArray, right: FloatArray, output: FloatArray) {
        for (row in 0..2) for (column in 0..2) {
            var value = 0f
            for (k in 0..2) value += left[k * 3 + row] * right[k * 3 + column]
            output[row * 3 + column] = value
        }
    }

    companion object {
        fun normalizeAngle(value: Float): Float {
            var normalized = value % 360f
            if (normalized > 180f) normalized -= 360f
            if (normalized < -180f) normalized += 360f
            return normalized
        }
        internal fun viewAngles(matrix: FloatArray): Triple<Float, Float, Float> {
            require(matrix.size >= 9)
            val forwardX = matrix[2]; val forwardY = matrix[5]; val forwardZ = matrix[8]
            val yaw = degrees(atan2(forwardX, forwardZ))
            val pitch = degrees(atan2(-forwardY, sqrt(forwardX * forwardX + forwardZ * forwardZ)))
            val roll = degrees(atan2(matrix[3], matrix[0]))
            return Triple(yaw, pitch, roll)
        }
        private fun degrees(radians: Float) = radians * (180f / PI.toFloat())
    }
}
