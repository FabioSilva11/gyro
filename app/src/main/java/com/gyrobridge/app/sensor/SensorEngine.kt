package com.gyrobridge.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.gyrobridge.app.domain.model.SensorConfig
import com.gyrobridge.app.domain.model.SensorRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

private const val TAG = "GB_Sensor"

data class SensorInfo(
    val available: Boolean = false, val name: String = "Nenhum sensor compatível", val vendor: String = "—",
    val version: Int = 0, val power: Float = 0f, val resolution: Float = 0f, val minDelayMicros: Int = 0,
    val maxDelayMicros: Int = 0, val requestedHz: Float = 0f, val actualHz: Float = 0f,
)

class SensorEngine(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val processor = OrientationProcessor()
    private val _samples = MutableStateFlow(OrientationSample())
    private val _info = MutableStateFlow(SensorInfo())
    val samples = _samples.asStateFlow(); val info = _info.asStateFlow()
    private var sensor: Sensor? = null; private var config = SensorConfig(); private var lastTimestamp = 0L
    private var frequencyEma = 0f; private var lastInfoPublishNanos = 0L
    private var lastDisplayRotation = -1
    @Volatile private var lockedDisplayRotation: Int? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val currentRotation = displayRotation()
            val effective = lockedDisplayRotation ?: currentRotation
            if (effective != lastDisplayRotation && lastDisplayRotation != -1) {
                Log.i(TAG, "DisplayListener: rotation changed $lastDisplayRotation -> $effective (locked=$lockedDisplayRotation)")
                if (lockedDisplayRotation == null) {
                    processor.onDisplayRotationChanged(effective)
                }
                lastDisplayRotation = effective
            }
        }
    }

    fun availableSensors(): List<Sensor> = listOfNotNull(
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
        manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
    ).distinctBy { it.type }

    fun detectedInfo(): SensorInfo = availableSensors().firstOrNull()?.toInfo(0f) ?: SensorInfo()

    fun start(config: SensorConfig): Boolean {
        stop(); this.config = config
        sensor = config.preferredSensorType?.let(manager::getDefaultSensor) ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val selected = sensor
        if (selected == null) { Log.e(TAG, "start: NO sensor available!"); _info.value = SensorInfo(); return false }
        val requestedHz = requestedHz(config, selected)
        val delay = when (config.rate) {
            SensorRate.NORMAL -> SensorManager.SENSOR_DELAY_NORMAL
            SensorRate.UI -> SensorManager.SENSOR_DELAY_UI
            SensorRate.GAME -> SensorManager.SENSOR_DELAY_GAME
            SensorRate.FASTEST, SensorRate.MAXIMUM -> SensorManager.SENSOR_DELAY_FASTEST
            else -> (1_000_000f / requestedHz.coerceAtLeast(1f)).roundToInt()
        }
        _info.value = selected.toInfo(requestedHz)
        processor.reset(); lastTimestamp = 0L; frequencyEma = 0f; lastInfoPublishNanos = 0L; lastDisplayRotation = -1; lockedDisplayRotation = null
        val registered = manager.registerListener(this, selected, delay)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)
        Log.i(TAG, "start: sensor=${selected.name} type=${selected.type} requestedHz=${"%.1f".format(requestedHz)} delay=$delay registered=$registered")
        return registered
    }

    fun lockReferenceFrameToCurrentDisplay() {
        lockedDisplayRotation = displayRotation()
        processor.resetReferenceFrame()
        lastDisplayRotation = lockedDisplayRotation ?: -1
        Log.i(TAG, "lockReferenceFrame: rotation=$lockedDisplayRotation")
    }

    fun recenter(): Boolean {
        val result = processor.recenter()
        Log.i(TAG, "recenter: result=$result")
        return result
    }

    fun unlockReferenceFrame() {
        lockedDisplayRotation = null
        processor.resetReferenceFrame()
        lastDisplayRotation = -1
        Log.i(TAG, "unlockReferenceFrame")
    }

    fun hasReceivedSensorData(): Boolean = processor.hasReceivedData()

    fun stop() {
        Log.i(TAG, "stop")
        manager.unregisterListener(this)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager.unregisterDisplayListener(displayListener)
        sensor = null; processor.reset(); lastDisplayRotation = -1; lockedDisplayRotation = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != sensor) return
        val rotation = lockedDisplayRotation ?: displayRotation()
        if (rotation != lastDisplayRotation) {
            if (lastDisplayRotation != -1) {
                Log.i(TAG, "onSensorChanged: rotation changed $lastDisplayRotation -> $rotation (preserving sensor state)")
                processor.onDisplayRotationChanged(rotation)
            }
            lastDisplayRotation = rotation
        }
        val sample = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) processor.fromGyroscope(event.values, rotation, event.timestamp)
        else processor.fromRotationVector(event.values, rotation, event.timestamp)
        if (lastTimestamp != 0L) {
            val dt = (event.timestamp - lastTimestamp) / 1_000_000_000f
            if (dt > 0f) frequencyEma = if (frequencyEma == 0f) 1f / dt else frequencyEma * .95f + (1f / dt) * .05f
        }
        lastTimestamp = event.timestamp
        _samples.value = sample
        if (event.timestamp - lastInfoPublishNanos >= 250_000_000L) {
            lastInfoPublishNanos = event.timestamp; _info.value = _info.value.copy(actualHz = frequencyEma)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    private fun requestedHz(config: SensorConfig, sensor: Sensor): Float = when (config.rate) {
        SensorRate.AUTOMATIC -> 120f.coerceAtMost(maxHz(sensor))
        SensorRate.NORMAL -> 5f; SensorRate.UI -> 16f; SensorRate.GAME -> 50f
        SensorRate.FASTEST, SensorRate.MAXIMUM -> maxHz(sensor)
        else -> (config.rate.hz ?: config.customHz).toFloat()
    }
    private fun maxHz(sensor: Sensor) = if (sensor.minDelay > 0) 1_000_000f / sensor.minDelay else 0f
    private fun Sensor.toInfo(requested: Float) = SensorInfo(true, name, vendor, version, power, resolution, minDelay, maxDelay, requested, 0f)
}
