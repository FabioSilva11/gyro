package com.gyrobridge.app.domain.model

import kotlin.math.max
import java.util.UUID

enum class SensorRate(val hz: Int?) {
    AUTOMATIC(null), NORMAL(5), UI(16), GAME(50), FASTEST(0), CUSTOM(null), HZ_50(50), HZ_60(60), HZ_90(90), HZ_100(100),
    HZ_120(120), HZ_144(144), HZ_165(165), HZ_200(200), HZ_240(240), HZ_300(300), HZ_400(400), MAXIMUM(0)
}

enum class AxisSource { YAW, PITCH, ROLL }
enum class DeadzoneUnit { DEGREES, DEGREES_PER_SECOND }
enum class ResponseCurve { LINEAR, EXPONENTIAL, POWER, SMOOTH_STEP, S_CURVE, CUSTOM }
enum class FilterType { NONE, LOW_PASS, EMA, ONE_EURO, ADAPTIVE }
enum class AccelerationMode { OFF, LIGHT, MEDIUM, STRONG, CUSTOM }
enum class GestureMode { SEGMENTED, CONTINUOUS }
enum class InteractionMode { GYRO_ONLY, GYRO_TOUCH_EXPERIMENTAL, GYRO_SYNTHETIC_JOYSTICK }
enum class PerformanceMode { BATTERY_SAVER, BALANCED, PERFORMANCE, EXTREME, CUSTOM }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LogLevel { OFF, ERROR, INFO, DEBUG, VERBOSE }

data class SensorConfig(
    val rate: SensorRate = SensorRate.GAME,
    val customHz: Int = 120,
    val preferredSensorType: Int? = null,
)

data class AxisConfig(
    val xSource: AxisSource = AxisSource.YAW,
    val ySource: AxisSource = AxisSource.PITCH,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val invertRoll: Boolean = false,
)

data class CalibrationConfig(
    val zeroYaw: Float = 0f,
    val zeroPitch: Float = 0f,
    val zeroRoll: Float = 0f,
    val autoCalibrate: Boolean = true,
    val recalibrateOnAppChange: Boolean = false,
    val autoRecenter: Boolean = false,
    val autoRecenterSeconds: Int = 30,
    val driftCompensation: Boolean = true,
    val biasCorrection: Boolean = true,
    val restTolerance: Float = 0.08f,
)

data class FilterConfig(
    val xFilter: FilterType = FilterType.ONE_EURO,
    val yFilter: FilterType = FilterType.ONE_EURO,
    val alpha: Float = 0.35f,
    val minCutoff: Float = 1.0f,
    val beta: Float = 0.04f,
    val dCutoff: Float = 1.0f,
    val smoothing: Float = 0.15f,
)

data class SensitivityConfig(
    val horizontal: Float = 8.5f,
    val vertical: Float = 8.5f,
    val linked: Boolean = true,
    val pixelsPerDegree: Float = 1f,
    val degreesForMaxMovement: Float = 45f,
    val globalMultiplier: Float = 1f,
    val horizontalDeadzone: Float = 0.10f,
    val verticalDeadzone: Float = 0.10f,
    val deadzoneUnit: DeadzoneUnit = DeadzoneUnit.DEGREES,
    val curve: ResponseCurve = ResponseCurve.LINEAR,
    val gamma: Float = 1.2f,
    val acceleration: AccelerationMode = AccelerationMode.OFF,
    val accelerationThreshold: Float = 2f,
    val accelerationMultiplier: Float = 1.5f,
    val accelerationMaximum: Float = 3f,
)

data class GestureConfig(
    val mode: GestureMode = GestureMode.CONTINUOUS,
    val interactionMode: InteractionMode = InteractionMode.GYRO_ONLY,
    val targetRate: Int = 45,
    val durationMs: Long = 20,
    val maxXPerUpdate: Float = 80f,
    val maxYPerUpdate: Float = 80f,
    val maxAngularVelocity: Float = 720f,
    val maxPixelsPerSecond: Float = 5000f,
    val maxSwipeDistance: Float = 160f,
    val queueCapacity: Int = 4,
)

data class ScreenZone(
    val centerX: Float = 0.72f,
    val centerY: Float = 0.50f,
    val width: Float = 0.42f,
    val height: Float = 0.58f,
    val boundaryMargin: Float = 0.04f,
    val autoRecenterThreshold: Float = 0.85f,
)

data class JoystickConfig(
    val enabled: Boolean = false,
    val centerX: Float = 0.20f,
    val centerY: Float = 0.72f,
    val radius: Float = 0.12f,
    val tiltThreshold: Float = 3f,
    val maximumTilt: Float = 30f,
    val sensitivity: Float = 1f,
    val deadzone: Float = 2f,
    val curve: ResponseCurve = ResponseCurve.LINEAR,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
)

data class OverlayConfig(
    val enabled: Boolean = true,
    val opacity: Float = 0.88f,
    val scale: Float = 1f,
    val normalizedX: Float = 0.02f,
    val normalizedY: Float = 0.12f,
    val locked: Boolean = false,
    val indicatorOnly: Boolean = false,
)

data class AutoDetectConfig(
    val enabled: Boolean = false,
    val scanRegionX: Float = 0.35f,
    val scanRegionY: Float = 0.15f,
    val scanRegionWidth: Float = 0.30f,
    val scanRegionHeight: Float = 0.30f,
    val clickX: Float = 0.50f,
    val clickY: Float = 0.50f,
    val hueMin: Float = 70f,
    val hueMax: Float = 160f,
    val saturationMin: Float = 0.30f,
    val valueMin: Float = 0.20f,
    val pixelThresholdPercent: Float = 2f,
    val cooldownMs: Long = 500L,
    val debounceCount: Int = 3,
    val captureIntervalMs: Long = 200L,
    val edgeMarginPx: Int = 20,
)

data class ControlProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Meu perfil",
    val packageName: String? = null,
    val appLabel: String? = null,
    val enabled: Boolean = true,
    val autoActivate: Boolean = true,
    val sensorConfig: SensorConfig = SensorConfig(),
    val axisConfig: AxisConfig = AxisConfig(),
    val calibrationConfig: CalibrationConfig = CalibrationConfig(),
    val filterConfig: FilterConfig = FilterConfig(),
    val sensitivityConfig: SensitivityConfig = SensitivityConfig(),
    val gestureConfig: GestureConfig = GestureConfig(),
    val cameraZone: ScreenZone = ScreenZone(),
    val joystickConfig: JoystickConfig = JoystickConfig(),
    val overlayConfig: OverlayConfig = OverlayConfig(),
    val autoDetectConfig: AutoDetectConfig = AutoDetectConfig(),
) {
    fun sanitized(): ControlProfile = copy(
        name = name.take(80).ifBlank { "Perfil" },
        packageName = packageName?.take(255)?.ifBlank { null },
        sensorConfig = sensorConfig.copy(customHz = sensorConfig.customHz.coerceIn(1, 1000)),
        calibrationConfig = calibrationConfig.copy(
            zeroYaw = zeroYawSafe(calibrationConfig.zeroYaw),
            zeroPitch = zeroYawSafe(calibrationConfig.zeroPitch),
            zeroRoll = zeroYawSafe(calibrationConfig.zeroRoll),
            autoRecenterSeconds = calibrationConfig.autoRecenterSeconds.coerceIn(1, 3600),
            restTolerance = calibrationConfig.restTolerance.safe(0f, 20f, 0.08f),
        ),
        filterConfig = filterConfig.copy(
            alpha = filterConfig.alpha.safe(0.001f, 1f, 0.35f),
            minCutoff = filterConfig.minCutoff.safe(0.01f, 100f, 1f),
            beta = filterConfig.beta.safe(0f, 10f, 0.04f),
            dCutoff = filterConfig.dCutoff.safe(0.01f, 100f, 1f),
            smoothing = filterConfig.smoothing.safe(0f, 1f, 0.15f),
        ),
        sensitivityConfig = sensitivityConfig.copy(
            horizontal = sensitivityConfig.horizontal.safe(0.01f, 50f, 8.5f),
            vertical = sensitivityConfig.vertical.safe(0.01f, 50f, 8.5f),
            pixelsPerDegree = sensitivityConfig.pixelsPerDegree.safe(0.01f, 1000f, 1f),
            degreesForMaxMovement = sensitivityConfig.degreesForMaxMovement.safe(0.01f, 180f, 45f),
            globalMultiplier = sensitivityConfig.globalMultiplier.safe(0.01f, 50f, 1f),
            horizontalDeadzone = sensitivityConfig.horizontalDeadzone.safe(0f, 180f, 0.1f),
            verticalDeadzone = sensitivityConfig.verticalDeadzone.safe(0f, 180f, 0.1f),
            gamma = sensitivityConfig.gamma.safe(0.2f, 4f, 1.2f),
            accelerationThreshold = sensitivityConfig.accelerationThreshold.safe(0f, 1000f, 2f),
            accelerationMultiplier = sensitivityConfig.accelerationMultiplier.safe(1f, 20f, 1.5f),
            accelerationMaximum = sensitivityConfig.accelerationMaximum.safe(1f, 50f, 3f),
        ),
        gestureConfig = gestureConfig.copy(
            targetRate = gestureConfig.targetRate.coerceIn(1, 120),
            durationMs = gestureConfig.durationMs.coerceIn(1, 10_000),
            maxXPerUpdate = gestureConfig.maxXPerUpdate.safe(1f, 5000f, 80f),
            maxYPerUpdate = gestureConfig.maxYPerUpdate.safe(1f, 5000f, 80f),
            maxAngularVelocity = gestureConfig.maxAngularVelocity.safe(1f, 10_000f, 720f),
            maxPixelsPerSecond = gestureConfig.maxPixelsPerSecond.safe(1f, 100_000f, 5000f),
            maxSwipeDistance = gestureConfig.maxSwipeDistance.safe(1f, 5000f, 160f),
            queueCapacity = gestureConfig.queueCapacity.coerceIn(1, 64),
        ),
        cameraZone = cameraZone.sanitized(),
        joystickConfig = joystickConfig.copy(
            centerX = joystickConfig.centerX.safe(0f, 1f, 0.2f),
            centerY = joystickConfig.centerY.safe(0f, 1f, 0.72f),
            radius = joystickConfig.radius.safe(0.01f, 0.5f, 0.12f),
            tiltThreshold = joystickConfig.tiltThreshold.safe(0f, 90f, 3f),
            maximumTilt = joystickConfig.maximumTilt.safe(0.1f, 180f, 30f),
            sensitivity = joystickConfig.sensitivity.safe(0.01f, 50f, 1f),
            deadzone = joystickConfig.deadzone.safe(0f, 90f, 2f),
        ),
        overlayConfig = overlayConfig.copy(
            opacity = overlayConfig.opacity.safe(0.1f, 1f, 0.88f),
            scale = overlayConfig.scale.safe(0.5f, 2f, 1f),
            normalizedX = overlayConfig.normalizedX.safe(0f, 1f, 0.02f),
            normalizedY = overlayConfig.normalizedY.safe(0f, 1f, 0.12f),
        ),
        autoDetectConfig = autoDetectConfig.copy(
            scanRegionX = autoDetectConfig.scanRegionX.safe(0f, 1f, 0.35f),
            scanRegionY = autoDetectConfig.scanRegionY.safe(0f, 1f, 0.15f),
            scanRegionWidth = autoDetectConfig.scanRegionWidth.safe(0.02f, 1f, 0.30f),
            scanRegionHeight = autoDetectConfig.scanRegionHeight.safe(0.02f, 1f, 0.30f),
            clickX = autoDetectConfig.clickX.safe(0f, 1f, 0.50f),
            clickY = autoDetectConfig.clickY.safe(0f, 1f, 0.50f),
            hueMin = autoDetectConfig.hueMin.safe(0f, 180f, 70f),
            hueMax = autoDetectConfig.hueMax.safe(0f, 180f, 160f),
            saturationMin = autoDetectConfig.saturationMin.safe(0f, 1f, 0.30f),
            valueMin = autoDetectConfig.valueMin.safe(0f, 1f, 0.20f),
            pixelThresholdPercent = autoDetectConfig.pixelThresholdPercent.safe(0.1f, 100f, 2f),
            cooldownMs = autoDetectConfig.cooldownMs.coerceIn(50L, 10_000L),
            debounceCount = autoDetectConfig.debounceCount.coerceIn(1, 30),
            captureIntervalMs = autoDetectConfig.captureIntervalMs.coerceIn(50L, 5_000L),
            edgeMarginPx = autoDetectConfig.edgeMarginPx.coerceIn(0, 200),
        ),
    )
}

fun ScreenZone.sanitized(): ScreenZone = copy(
    centerX = centerX.safe(0f, 1f, 0.72f),
    centerY = centerY.safe(0f, 1f, 0.5f),
    width = width.safe(0.02f, 1f, 0.42f),
    height = height.safe(0.02f, 1f, 0.58f),
    boundaryMargin = boundaryMargin.safe(0f, 0.45f, 0.04f),
    autoRecenterThreshold = autoRecenterThreshold.safe(0.1f, 1f, 0.85f),
)

private fun zeroYawSafe(value: Float) = value.safe(-180f, 180f, 0f)
fun Float.safe(minimum: Float, maximum: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(minimum, maximum) else fallback

data class GlobalSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val startLastProfile: Boolean = false,
    val autoProfile: Boolean = true,
    val showOverlay: Boolean = false,
    val showNotification: Boolean = true,
    val vibrateOnCalibration: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val logLevel: LogLevel = LogLevel.ERROR,
)
