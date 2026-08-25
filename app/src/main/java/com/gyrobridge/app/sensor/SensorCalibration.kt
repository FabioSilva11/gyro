package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.CalibrationConfig

class SensorCalibration(config: CalibrationConfig) {
    var zeroYaw = config.zeroYaw; private set
    var zeroPitch = config.zeroPitch; private set
    var zeroRoll = config.zeroRoll; private set

    fun calibrate(sample: OrientationSample) { zeroYaw = sample.yaw; zeroPitch = sample.pitch; zeroRoll = sample.roll }
    fun reset() { zeroYaw = 0f; zeroPitch = 0f; zeroRoll = 0f }
    fun asConfig(base: CalibrationConfig) = base.copy(zeroYaw = zeroYaw, zeroPitch = zeroPitch, zeroRoll = zeroRoll)
    fun centered(sample: OrientationSample) = sample.copy(
        yaw = OrientationProcessor.normalizeAngle(sample.yaw - zeroYaw),
        pitch = OrientationProcessor.normalizeAngle(sample.pitch - zeroPitch),
        roll = OrientationProcessor.normalizeAngle(sample.roll - zeroRoll),
    )
}
