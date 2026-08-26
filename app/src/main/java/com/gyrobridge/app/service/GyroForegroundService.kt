package com.gyrobridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gyrobridge.app.MainActivity
import com.gyrobridge.app.R
import com.gyrobridge.app.autodetect.AutoDetectManager
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.gesture.GestureDispatcherRegistry
import com.gyrobridge.app.overlay.OverlayService
import com.gyrobridge.app.sensor.MotionPipeline
import com.gyrobridge.app.sensor.PhysicalMovementSensor
import com.gyrobridge.app.sensor.PhysicalMovementOutput
import com.gyrobridge.app.sensor.SensorEngine
import com.gyrobridge.app.telemetry.TestTelemetryPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG = "GB_FgSvc"

private enum class CalibrationPhase { IDLE, WAITING_SENSOR, SETTLING, ACTIVE }

class GyroForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorEngine: SensorEngine
    private lateinit var physicalMovementSensor: PhysicalMovementSensor
    private lateinit var telemetryPublisher: TestTelemetryPublisher
    @Volatile private var latestPhysicalOutput = PhysicalMovementOutput()
    private var sessionController = SessionController()
    private var sampleJob: Job? = null; private var infoJob: Job? = null; private var calibrationJob: Job? = null
    private var profile: ControlProfile? = null; private var pipeline: MotionPipeline? = null
    private var autoDetectManager: AutoDetectManager? = null
    @Volatile private var latestSample = com.gyrobridge.app.sensor.OrientationSample()
    private var lastAutoRecenterNanos = 0L
    private var sampleCount = 0L
    private var calibrationPhase = CalibrationPhase.IDLE
    private var calibrationStartNanos = 0L
    private var calibrationStableCount = 0
    private var lastCalibrationSample = com.gyrobridge.app.sensor.OrientationSample()
    private var a11yConnectionListener: ((com.gyrobridge.app.gesture.ConnectionState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorEngine = SensorEngine(this)
        telemetryPublisher = TestTelemetryPublisher(this)
        physicalMovementSensor = PhysicalMovementSensor(this, sensorEngine::projectForwardAcceleration) { output ->
            latestPhysicalOutput = output
            AppGraph.runtime.setPhysicalMovementState(output.state)
            GestureDispatcherRegistry.updateMovement(output.state)
        }
        createChannel()
        Log.i(TAG, "onCreate")
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action} flags=$flags startId=$startId a11yAvailable=${GestureDispatcherRegistry.isAvailable()}")
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_PAUSE -> togglePause()
            ACTION_CALIBRATE -> beginCalibration()
            ACTION_AUTODETECT_STOP -> stopAutoDetect()
            ACTION_START, ACTION_SWITCH -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let { id -> scope.launch { AppGraph.repository.find(id)?.let { startSession(it) } } }
            else -> if (!AppGraph.runtime.sessionActive.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopAutoDetect() {
        autoDetectManager?.stop()
        autoDetectManager = null
        AppGraph.runtime.setAutoDetectActive(false)
        Log.i(TAG, "stopAutoDetect")
        updateNotification()
    }

    private fun startSession(value: ControlProfile) {
        Log.i(TAG, "startSession: profile=${value.name} a11yAvailable=${GestureDispatcherRegistry.isAvailable()}")
        sessionController = SessionController()
        sessionController.onEvent(SessionEvent.Start)
        profile = value.sanitized(); pipeline = MotionPipeline(profile!!)
        calibrationJob?.cancel(); calibrationPhase = CalibrationPhase.IDLE
        latestSample = com.gyrobridge.app.sensor.OrientationSample(); lastAutoRecenterNanos = System.nanoTime()
        AppGraph.runtime.setProfile(profile); AppGraph.runtime.setSession(true, paused = true); AppGraph.runtime.setCalibrating(false)
        val accessibilityAvailable = GestureDispatcherRegistry.isAvailable()
        sessionController.onEvent(SessionEvent.AccessibilityChanged(accessibilityAvailable))
        if (!accessibilityAvailable) {
            Log.w(TAG, "startSession: AccessibilityService NOT available — gestures will not be dispatched")
            AppGraph.runtime.setA11yAvailable(false)
        } else {
            AppGraph.runtime.setA11yAvailable(true)
        }
        GestureDispatcherRegistry.configure(profile!!); GestureDispatcherRegistry.cancelAll()
        a11yConnectionListener?.let { GestureDispatcherRegistry.removeConnectionListener(it) }
        a11yConnectionListener = { state: com.gyrobridge.app.gesture.ConnectionState ->
            if (AppGraph.runtime.sessionActive.value) {
                val available = state == com.gyrobridge.app.gesture.ConnectionState.CONNECTED
                val snapshot = sessionController.onEvent(SessionEvent.AccessibilityChanged(available))
                AppGraph.runtime.setA11yAvailable(available)
                AppGraph.runtime.setPaused(true)
                AppGraph.runtime.setSessionStatus(snapshot.status, snapshot.error)
                GestureDispatcherRegistry.cancelAll()
                if (available) profile?.let(GestureDispatcherRegistry::configure)
                refreshOverlay(); updateNotification()
            }
        }.also { GestureDispatcherRegistry.addConnectionListener(it) }
        startForegroundCompat(notification())
        val sensorStarted = sensorEngine.start(profile!!.sensorConfig)
        val sensorSnapshot = sessionController.onEvent(SessionEvent.SensorStarted(sensorStarted))
        Log.i(TAG, "startSession: sensorStarted=$sensorStarted")
        if (!sensorStarted) {
            AppGraph.runtime.setSessionStatus(sensorSnapshot.status, sensorSnapshot.error)
            AppGraph.runtime.setPaused(true)
            updateNotification()
            return
        }
        val movementStarted = physicalMovementSensor.start(profile!!.physicalMovement)
        if (profile!!.physicalMovement.enabled && !movementStarted) {
            AppGraph.runtime.setPhysicalMovementState(com.gyrobridge.app.domain.model.PhysicalMovementState.STATIONARY)
            Log.w(TAG, "Physical movement unavailable; camera remains operational")
        }
        AppGraph.runtime.setSessionStatus(sensorSnapshot.status, sensorSnapshot.error)
        sampleJob?.cancel(); sampleCount = 0L; sampleJob = scope.launch {
            sensorEngine.samples.collect { raw ->
                if (raw.sensorTimestampNanos == 0L) return@collect
                latestSample = raw; sampleCount++
                telemetryPublisher.publish(
                    orientation = raw,
                    movement = latestPhysicalOutput,
                    sensorName = AppGraph.runtime.sensorInfo.value.name,
                    status = AppGraph.runtime.sessionStatus.value,
                    accessibilityAvailable = AppGraph.runtime.a11yAvailable.value,
                )
                if (sampleCount % 200L == 1L) {
                    Log.d(TAG, "sample #$sampleCount: yaw=${"%.2f".format(raw.yaw)} pitch=${"%.2f".format(raw.pitch)} roll=${"%.2f".format(raw.roll)} paused=${AppGraph.runtime.sessionPaused.value} calibrating=${AppGraph.runtime.calibrating.value} phase=$calibrationPhase")
                }
                if (calibrationPhase == CalibrationPhase.WAITING_SENSOR) {
                    calibrationPhase = CalibrationPhase.SETTLING
                    calibrationStartNanos = System.nanoTime()
                    calibrationStableCount = 0
                    Log.i(TAG, "Sensor data received, transitioning to SETTLING phase")
                }
                if (calibrationPhase == CalibrationPhase.SETTLING) {
                    handleSettlingPhase(raw)
                    return@collect
                }
                if (AppGraph.runtime.calibrating.value) {
                    AppGraph.runtime.setOrientation(raw)
                    return@collect
                }
                val calibrationConfig = profile?.calibrationConfig
                if (calibrationConfig?.autoRecenter == true && raw.sensorTimestampNanos - lastAutoRecenterNanos >= calibrationConfig.autoRecenterSeconds * 1_000_000_000L && maxOf(kotlin.math.abs(raw.deltaYaw), kotlin.math.abs(raw.deltaPitch), kotlin.math.abs(raw.deltaRoll)) <= calibrationConfig.restTolerance) {
                    if (sensorEngine.recenter()) {
                        pipeline?.reset()
                        lastAutoRecenterNanos = raw.sensorTimestampNanos
                    }
                }
                AppGraph.runtime.setOrientation(raw)
                if (!AppGraph.runtime.sessionPaused.value) {
                    val output = pipeline?.process(raw) ?: return@collect
                    AppGraph.runtime.setMotion(output)
                    val enqueued = GestureDispatcherRegistry.enqueue(output.dx, output.dy, output.processingTimestampNanos, raw)
                    if (!enqueued && sampleCount % 100L == 1L) {
                        Log.w(TAG, "sample #$sampleCount: enqueue FAILED (a11y scheduler not attached)")
                    }
                }
            }
        }
        infoJob?.cancel(); infoJob = scope.launch { sensorEngine.info.collect { AppGraph.runtime.setSensorInfo(it); updateNotification() } }
        when {
            !profile!!.overlayConfig.enabled -> { OverlayService.stop(this); AppGraph.runtime.setOverlayStatus(false, "Desativado no perfil") }
            !android.provider.Settings.canDrawOverlays(this) -> AppGraph.runtime.setOverlayStatus(false, "Permissão de sobreposição ausente")
            else -> runCatching { OverlayService.start(this) }.onFailure { AppGraph.runtime.setOverlayStatus(false, "Falha ao iniciar: ${it.javaClass.simpleName}") }
        }
        val adConfig = profile!!.autoDetectConfig
        if (adConfig.enabled) {
            val existingManager = autoDetectManager
            if (existingManager != null) {
                existingManager.updateConfig(adConfig)
                AppGraph.runtime.setAutoDetectActive(true)
            } else {
                Log.i(TAG, "AutoDetect enabled but no MediaProjection — waiting for consent")
                AppGraph.runtime.setAutoDetectActive(false)
            }
        } else {
            autoDetectManager?.stop(); autoDetectManager = null
            AppGraph.runtime.setAutoDetectActive(false)
        }
    }

    private fun handleSettlingPhase(raw: com.gyrobridge.app.sensor.OrientationSample) {
        val elapsed = (System.nanoTime() - calibrationStartNanos) / 1_000_000f
        val delta = maxOf(
            kotlin.math.abs(raw.deltaYaw), kotlin.math.abs(raw.deltaPitch), kotlin.math.abs(raw.deltaRoll)
        )
        val tolerance = profile?.calibrationConfig?.restTolerance ?: 0.08f
        if (delta <= tolerance) {
            calibrationStableCount++
        } else {
            calibrationStableCount = 0; calibrationStartNanos = System.nanoTime()
            lastCalibrationSample = raw
            AppGraph.runtime.setOrientation(raw)
            return
        }
        if (calibrationStableCount >= 3 && elapsed >= 300f) {
            Log.i(TAG, "Settling complete: ${"%.0f".format(elapsed)}ms, stable samples=$calibrationStableCount")
            finishCalibration()
        } else if (elapsed >= CALIBRATION_MAX_SETTLE_MS) {
            Log.w(TAG, "Settling timeout after ${"%.0f".format(elapsed)}ms, finishing calibration")
            finishCalibration()
        }
        lastCalibrationSample = raw
        AppGraph.runtime.setOrientation(raw)
    }

    private fun togglePause() {
        val resuming = AppGraph.runtime.sessionPaused.value
        Log.i(TAG, "togglePause: resuming=$resuming")
        if (resuming) {
            val autoCalibrate = profile?.calibrationConfig?.autoCalibrate == true
            if (sensorEngine.hasReference()) sessionController.onEvent(SessionEvent.CalibrationCaptured)
            val decision = sessionController.onEvent(SessionEvent.ExplicitResume(autoCalibrate))
            if (!decision.accessibilityAvailable) {
                AppGraph.runtime.setPaused(true)
                AppGraph.runtime.setSessionStatus(decision.status, decision.error)
                refreshOverlay(); updateNotification()
                return
            }
            physicalMovementSensor.start(profile?.physicalMovement ?: return)
            if (autoCalibrate) beginCalibration()
            else if (!sensorEngine.hasReference()) {
                AppGraph.runtime.setPaused(true)
                AppGraph.runtime.setSessionStatus(com.gyrobridge.app.domain.model.SessionStatus.PAUSED)
                refreshOverlay(); updateNotification()
            }
            else {
                AppGraph.runtime.setCalibrating(false); AppGraph.runtime.setPaused(false)
                AppGraph.runtime.setSessionStatus(decision.status, decision.error)
                GestureDispatcherRegistry.resume(); refreshOverlay(); updateNotification()
            }
        } else {
            val decision = sessionController.onEvent(SessionEvent.Pause)
            calibrationJob?.cancel(); calibrationPhase = CalibrationPhase.IDLE
            AppGraph.runtime.setCalibrating(false); AppGraph.runtime.setPaused(true)
            sensorEngine.unlockReferenceFrame()
            GestureDispatcherRegistry.cancelAll()
            physicalMovementSensor.stop()
            AppGraph.runtime.setSessionStatus(decision.status, decision.error)
            refreshOverlay(); updateNotification()
        }
    }

    private fun beginCalibration() {
        if (!AppGraph.runtime.sessionActive.value) return
        if (AppGraph.runtime.calibrating.value) {
            Log.w(TAG, "beginCalibration: already calibrating, cancelling previous calibration")
            calibrationJob?.cancel(); calibrationPhase = CalibrationPhase.IDLE; AppGraph.runtime.setCalibrating(false)
        }
        Log.i(TAG, "beginCalibration")
        AppGraph.runtime.setPaused(true); AppGraph.runtime.setCalibrating(true)
        AppGraph.runtime.setSessionStatus(com.gyrobridge.app.domain.model.SessionStatus.CALIBRATING)
        calibrationPhase = CalibrationPhase.WAITING_SENSOR
        calibrationStableCount = 0; calibrationStartNanos = 0L
        pipeline?.reset()
        GestureDispatcherRegistry.cancelAll()
        sensorEngine.lockReferenceFrameToCurrentDisplay()
        calibrationJob?.cancel()
        calibrationJob = scope.launch {
            delay(CALIBRATION_MAX_SETTLE_MS + 500L)
            if (calibrationPhase != CalibrationPhase.IDLE) {
                Log.w(TAG, "Calibration safety timeout, finishing")
                finishCalibration()
            }
        }
        refreshOverlay(); updateNotification()
    }

    private fun finishCalibration() {
        if (calibrationPhase == CalibrationPhase.IDLE) {
            if (AppGraph.runtime.sessionPaused.value && AppGraph.runtime.calibrating.value) {
                Log.w(TAG, "finishCalibration: stale callback ignored while phase is IDLE")
                pipeline?.reset()
                AppGraph.runtime.setCalibrating(false); AppGraph.runtime.setPaused(true)
                AppGraph.runtime.setSessionStatus(com.gyrobridge.app.domain.model.SessionStatus.PAUSED)
                GestureDispatcherRegistry.cancelAll()
                refreshOverlay(); updateNotification()
            }
            return
        }
        val recentered = sensorEngine.recenter()
        sensorEngine.unlockReferenceFrame()
        Log.i(TAG, "finishCalibration: recentered=$recentered phase=$calibrationPhase")
        pipeline?.reset()
        calibrationPhase = CalibrationPhase.ACTIVE
        val raw = latestSample
        AppGraph.runtime.setOrientation(
            raw.copy(
                yaw = 0f, pitch = 0f, roll = 0f,
                deltaYaw = 0f, deltaPitch = 0f, deltaRoll = 0f,
                angularVelocityX = 0f, angularVelocityY = 0f, angularVelocityZ = 0f,
                processingTimestampNanos = System.nanoTime(),
            ),
        )
        val decision = if (recentered) sessionController.onEvent(SessionEvent.CalibrationCaptured)
        else sessionController.onEvent(SessionEvent.Failure(com.gyrobridge.app.domain.model.SessionError.SENSOR_START_FAILED))
        AppGraph.runtime.setCalibrating(false); calibrationPhase = CalibrationPhase.IDLE
        AppGraph.runtime.setPaused(decision.status != com.gyrobridge.app.domain.model.SessionStatus.ACTIVE)
        AppGraph.runtime.setSessionStatus(decision.status, decision.error)
        if (decision.status != com.gyrobridge.app.domain.model.SessionStatus.ACTIVE) {
            GestureDispatcherRegistry.cancelAll()
            refreshOverlay(); updateNotification()
            return
        }
        GestureDispatcherRegistry.resume()
        refreshOverlay(); updateNotification()
    }

    private fun refreshOverlay() {
        if (profile?.overlayConfig?.enabled == true && android.provider.Settings.canDrawOverlays(this)) {
            runCatching { OverlayService.start(this) }
        }
    }

    private fun stopSession() {
        Log.i(TAG, "stopSession")
        a11yConnectionListener?.let { GestureDispatcherRegistry.removeConnectionListener(it); a11yConnectionListener = null }
        calibrationJob?.cancel(); sampleJob?.cancel(); infoJob?.cancel(); sensorEngine.stop(); physicalMovementSensor.stop(); GestureDispatcherRegistry.cancelAll()
        autoDetectManager?.stop(); autoDetectManager = null
        OverlayService.stop(this); AppGraph.runtime.reset(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (!AppGraph.runtime.sessionActive.value) stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { Log.w(TAG, "onDestroy"); instance = null; a11yConnectionListener?.let { GestureDispatcherRegistry.removeConnectionListener(it) }; sensorEngine.stop(); physicalMovementSensor.stop(); autoDetectManager?.stop(); autoDetectManager = null; calibrationJob?.cancel(); sampleJob?.cancel(); infoJob?.cancel(); if (AppGraph.runtime.sessionActive.value) AppGraph.runtime.reset(); scope.cancel(); super.onDestroy() }

    private fun notification(): Notification {
        val p = profile; val sensor = AppGraph.runtime.sensorInfo.value; val paused = AppGraph.runtime.sessionPaused.value; val calibrating = AppGraph.runtime.calibrating.value
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, request: Int) = PendingIntent.getService(this, request, Intent(this, GyroForegroundService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_legacy).setContentTitle(if (calibrating) "GyroBridge calibrando" else if (paused) "GyroBridge pausado" else "GyroBridge ativo")
            .setContentText("Perfil: ${p?.name ?: "—"} • ${sensor.name} • ${"%.1f".format(sensor.actualHz)} Hz")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (paused) "RETOMAR" else "PAUSAR", action(ACTION_PAUSE, 2))
            .addAction(0, "CALIBRAR", action(ACTION_CALIBRATE, 3)).addAction(0, "PARAR", action(ACTION_STOP, 4)).build()
    }

    private fun updateNotification() { if (AppGraph.runtime.sessionActive.value) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.notification_channel_description) },
        )
    }

    companion object {
        const val ACTION_START = "com.gyrobridge.START"; const val ACTION_STOP = "com.gyrobridge.STOP"
        const val ACTION_SWITCH = "com.gyrobridge.SWITCH"
        const val ACTION_PAUSE = "com.gyrobridge.PAUSE"; const val ACTION_CALIBRATE = "com.gyrobridge.CALIBRATE"
        const val ACTION_AUTODETECT_STOP = "com.gyrobridge.AUTODETECT_STOP"
        const val EXTRA_PROFILE_ID = "profile_id"; private const val CHANNEL_ID = "gyro_control"; private const val NOTIFICATION_ID = 4101
        private const val CALIBRATION_MAX_SETTLE_MS = 3_000L
        fun start(context: Context, profileId: String) = ContextCompat.startForegroundService(context, Intent(context, GyroForegroundService::class.java).setAction(ACTION_START).putExtra(EXTRA_PROFILE_ID, profileId))
        fun switchProfile(context: Context, profileId: String) = context.startService(Intent(context, GyroForegroundService::class.java).setAction(ACTION_SWITCH).putExtra(EXTRA_PROFILE_ID, profileId))
        fun action(context: Context, action: String) = context.startService(Intent(context, GyroForegroundService::class.java).setAction(action))
        fun getAutoDetectManager(): AutoDetectManager? = instance?.autoDetectManager
        @Volatile private var instance: GyroForegroundService? = null
    }
}
