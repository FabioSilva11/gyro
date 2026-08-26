package com.gyrobridge.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.gyrobridge.app.domain.model.PhysicalMovementConfig
import com.gyrobridge.app.domain.model.PhysicalMovementState

class PhysicalMovementSensor(
    context: Context,
    private val projectForwardAcceleration: (FloatArray) -> Float?,
    private val onOutput: (PhysicalMovementOutput) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var detector: PhysicalMovementDetector? = null
    private var linearAcceleration: Sensor? = null
    private var stepDetector: Sensor? = null
    private var stepPending = false
    private var lastState = PhysicalMovementState.STATIONARY
    private var lastStepCount = -1

    fun start(config: PhysicalMovementConfig): Boolean {
        stop()
        if (!config.enabled) return true
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return false
        detector = PhysicalMovementDetector(config)
        linearAcceleration = linear
        stepDetector = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val linearRegistered = manager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        stepDetector?.let { sensor ->
            runCatching { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
                .onFailure { Log.w(TAG, "step detector indisponível; usando aceleração linear", it) }
        }
        Log.i(TAG, "start: linear=${linear.name} stepDetector=${stepDetector != null} threshold=${config.threshold} stopTimeout=${config.stopTimeoutMs}")
        return linearRegistered
    }

    fun stop() {
        manager.unregisterListener(this)
        detector?.reset()
        detector = null
        linearAcceleration = null
        stepDetector = null
        stepPending = false
        lastState = PhysicalMovementState.STATIONARY
        lastStepCount = -1
        onOutput(PhysicalMovementOutput(state = PhysicalMovementState.STATIONARY))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor == stepDetector) {
            stepPending = true
            return
        }
        if (event.sensor != linearAcceleration || event.values.size < 3) return
        val forwardAcceleration = projectForwardAcceleration(event.values) ?: -event.values[2]
        val output = detector?.process(
            PhysicalMovementInput(
                forwardAcceleration = forwardAcceleration,
                stepDetected = stepPending,
                timestampNanos = event.timestamp,
                stepSensorAvailable = stepDetector != null,
            ),
        ) ?: return
        stepPending = false
        if (output.state != lastState) {
            Log.i(TAG, "movement state=${output.state} signal=${"%.3f".format(output.forwardSignal)} confidence=${"%.2f".format(output.confidence)}")
            lastState = output.state
        }
        if (output.stepCount != lastStepCount) {
            Log.i(TAG, "pdr step=${output.stepCount} positionMeters=${"%.2f".format(output.pdrPositionMeters)}")
            lastStepCount = output.stepCount
        }
        onOutput(output)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object { const val TAG = "GB_Sensor" }
}
