package com.gyrobridge.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    fun start(config: PhysicalMovementConfig): Boolean {
        stop()
        if (!config.enabled) return true
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return false
        detector = PhysicalMovementDetector(config)
        linearAcceleration = linear
        stepDetector = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val linearRegistered = manager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        stepDetector?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        return linearRegistered
    }

    fun stop() {
        manager.unregisterListener(this)
        detector?.reset()
        detector = null
        linearAcceleration = null
        stepDetector = null
        stepPending = false
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
            ),
        ) ?: return
        stepPending = false
        onOutput(output)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
