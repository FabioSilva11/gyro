package com.gyrobridge.app.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.domain.mapper.ScreenCoordinateMapper
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.domain.model.DisplayRotation
import com.gyrobridge.app.domain.model.PhysicalMovementState
import com.gyrobridge.app.sensor.OrientationSample
import java.util.ArrayDeque
import kotlin.math.hypot

private const val TAG = "GB_GestSched"

class GestureScheduler(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var profile = ControlProfile(); private var accumulator = GestureAccumulator(profile.gestureConfig.maxSwipeDistance)
    private var dispatching = false; private var stopped = false; private var virtualX = 0f; private var virtualY = 0f
    private var lastDispatchStartedUptimeMs = 0L
    private var continuedStroke: GestureDescription.StrokeDescription? = null
    private var continuedMovementStroke: GestureDescription.StrokeDescription? = null
    private var movementState = PhysicalMovementState.STATIONARY
    private var movementDirty = false
    private var generation = 0L
    private var lastDisplayWidth = 0; private var lastDisplayHeight = 0
    private var metrics = GestureMetrics(); private var lastCompletedNanos = 0L
    private val latencies = ArrayDeque<Float>(256); private var totalDurationMs = 0.0

    fun configure(profile: ControlProfile) {
        generation++
        continuedStroke = null; continuedMovementStroke = null; dispatching = false
        this.profile = profile.sanitized(); accumulator = GestureAccumulator(this.profile.gestureConfig.maxSwipeDistance)
        val (w, h) = displaySize(); val zone = ScreenCoordinateMapper.cameraToPixels(this.profile.cameraZone, w, h, displayRotation())
        lastDisplayWidth = w; lastDisplayHeight = h; virtualX = zone.centerX; virtualY = zone.centerY
        lastDispatchStartedUptimeMs = 0L
        Log.i(TAG, "Configured: profile=${profile.name} enabled=${profile.enabled} zone=center(${zone.centerX.toInt()},${zone.centerY.toInt()}) size=${w}x${h}")
    }

    fun enqueue(request: GestureRequest, orientation: OrientationSample) {
        if (stopped) { Log.d(TAG, "Enqueue: stopped, skipping"); return }
        if (!profile.enabled) { Log.d(TAG, "Enqueue: profile disabled, skipping"); return }
        if (!request.dx.isFinite() || !request.dy.isFinite()) return
        accumulator.add(request)
        handler.removeCallbacks(endContinuousRunnable)
        metrics = metrics.copy(state = GestureState.QUEUED, queued = metrics.queued + 1); publish()
        scheduleNext()
    }

    fun updateMovement(state: PhysicalMovementState) {
        if (movementState == state || stopped || !profile.physicalMovement.enabled) return
        movementState = state
        movementDirty = true
        metrics = metrics.copy(state = GestureState.QUEUED, queued = metrics.queued + 1)
        publish()
        scheduleNext()
    }

    fun cancelAll() {
        Log.d(TAG, "cancelAll")
        generation++
        stopped = true; accumulator.clear(); handler.removeCallbacksAndMessages(null); dispatching = false; continuedStroke = null; continuedMovementStroke = null
        movementState = PhysicalMovementState.STATIONARY; movementDirty = false
        lastDispatchStartedUptimeMs = 0L
        metrics = metrics.copy(state = GestureState.CANCELLED); publish()
    }

    fun resume() {
        Log.d(TAG, "resume: stopped was=$stopped")
        stopped = false; scheduleNext()
    }

    private fun scheduleNext() {
        if (dispatching || stopped) return
        val period = (1000L / profile.gestureConfig.targetRate.coerceAtLeast(1)).coerceAtLeast(1L)
        val now = SystemClock.uptimeMillis()
        val delay = if (lastDispatchStartedUptimeMs == 0L) 0L else (lastDispatchStartedUptimeMs + period - now).coerceAtLeast(0L)
        handler.removeCallbacks(dispatchRunnable)
        handler.postDelayed(dispatchRunnable, delay)
    }

    private val dispatchRunnable = Runnable {
        if (dispatching || stopped) return@Runnable
        val request = accumulator.take(MIN_GESTURE_DISTANCE_PX) ?: if (movementDirty || continuedMovementStroke != null) {
            GestureRequest(0f, 0f)
        } else run {
            metrics = metrics.copy(state = GestureState.IDLE); publish()
            if (continuedStroke != null) {
                handler.removeCallbacks(endContinuousRunnable)
                handler.postDelayed(endContinuousRunnable, CONTINUOUS_IDLE_GRACE_MS)
            }
            return@Runnable
        }
        if (System.nanoTime() - request.queuedAtNanos > 500_000_000L) {
            Log.d(TAG, "Dropping stale request: age=${(System.nanoTime() - request.queuedAtNanos) / 1_000_000}ms")
            metrics = metrics.copy(dropped = metrics.dropped + 1); publish(); scheduleNext(); return@Runnable
        }
        dispatch(request)
    }

    private fun dispatch(request: GestureRequest) {
        handler.removeCallbacks(endContinuousRunnable)
        val (width, height) = displaySize(); val zone = ScreenCoordinateMapper.cameraToPixels(profile.cameraZone, width, height, displayRotation())
        if (width != lastDisplayWidth || height != lastDisplayHeight) {
            continuedStroke = null; continuedMovementStroke = null
            virtualX = zone.centerX; virtualY = zone.centerY
            lastDisplayWidth = width; lastDisplayHeight = height
        }
        if (virtualX == 0f && virtualY == 0f) { virtualX = zone.centerX; virtualY = zone.centerY }
        val startX = virtualX.coerceIn(zone.left, zone.right); val startY = virtualY.coerceIn(zone.top, zone.bottom)
        val endX = (startX + request.dx).coerceIn(zone.left, zone.right); val endY = (startY + request.dy).coerceIn(zone.top, zone.bottom)
        val hasCameraMovement = hypot((endX - startX).toDouble(), (endY - startY).toDouble()) >= .5
        val hasMovementWork = movementDirty || continuedMovementStroke != null
        if (!hasCameraMovement && !hasMovementWork) { scheduleNext(); return }
        virtualX = endX; virtualY = endY
        val reachedBoundary = nearBoundary(endX, endY, zone)
        dispatching = true; metrics = metrics.copy(state = GestureState.DISPATCHING, sent = metrics.sent + 1); publish()
        lastDispatchStartedUptimeMs = SystemClock.uptimeMillis()
        val started = System.nanoTime(); val duration = profile.gestureConfig.durationMs
        run {
            val dispatchGeneration = generation
            val willContinue = hasCameraMovement && !reachedBoundary
            val stroke = if (hasCameraMovement) {
                val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
                continuedStroke?.let { previous -> runCatching { previous.continueStroke(path, 0, duration, willContinue) }.getOrNull() }
                    ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
            } else continuedStroke?.let { previous ->
                runCatching { previous.continueStroke(Path().apply { moveTo(startX, startY) }, 0, duration, false) }.getOrNull()
            }
            val movementResult = movementStroke(width, height, duration)
            val builder = GestureDescription.Builder()
            stroke?.let(builder::addStroke)
            movementResult.stroke?.let(builder::addStroke)
            movementDirty = false
            val accepted = service.dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (dispatchGeneration != generation) return
                        continuedStroke = if (willContinue) stroke else null
                        continuedMovementStroke = if (movementResult.willContinue) movementResult.stroke else null
                        if (reachedBoundary) { virtualX = zone.centerX; virtualY = zone.centerY }
                        finish(true, started, request)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (dispatchGeneration != generation) return
                        continuedStroke = null; continuedMovementStroke = null
                        finish(false, started, request)
                    }
                },
                handler,
            )
            if (!accepted && dispatchGeneration == generation) { continuedStroke = null; continuedMovementStroke = null; finish(false, started, request) }
        }
    }

    private data class MovementStrokeResult(
        val stroke: GestureDescription.StrokeDescription?,
        val willContinue: Boolean,
    )

    private fun movementStroke(width: Int, height: Int, duration: Long): MovementStrokeResult {
        val previous = continuedMovementStroke
        val config = profile.physicalMovement
        if (!config.enabled) return MovementStrokeResult(null, false)
        val rotation = displayRotation()
        val zone = ScreenCoordinateMapper.movementToPixels(config.zone, width, height, rotation)
        if (movementState == PhysicalMovementState.STATIONARY) {
            if (previous == null) return MovementStrokeResult(null, false)
            val end = runCatching {
                previous.continueStroke(Path().apply { moveTo(zone.centerX, zone.centerY) }, 0, duration, false)
            }.getOrNull()
            return MovementStrokeResult(end, false)
        }
        val direction = if (movementState == PhysicalMovementState.FORWARD) -1f else 1f
        val targetY = zone.centerY + direction * zone.radiusPx * config.joystickStrength
        val path = Path().apply {
            if (previous == null) moveTo(zone.centerX, zone.centerY) else moveTo(zone.centerX, targetY)
            lineTo(zone.centerX, targetY)
        }
        val stroke = previous?.let { runCatching { it.continueStroke(path, 0, duration, true) }.getOrNull() }
            ?: GestureDescription.StrokeDescription(path, 0, duration, true)
        return MovementStrokeResult(stroke, true)
    }

    private fun finishContinuousStroke() {
        val previous = continuedStroke ?: return
        continuedStroke = null
        dispatching = true
        val path = Path().apply { moveTo(virtualX, virtualY) }
        val ending = runCatching { previous.continueStroke(path, 0, 1, false) }.getOrElse {
            dispatching = false
            metrics = metrics.copy(state = GestureState.IDLE)
            publish()
            return
        }
        val accepted = service.dispatchGesture(
            GestureDescription.Builder().addStroke(ending).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuousEnded()
                override fun onCancelled(gestureDescription: GestureDescription) = continuousEnded()
            },
            handler,
        )
        if (!accepted) continuousEnded()
    }

    private val endContinuousRunnable = Runnable {
        if (stopped || dispatching) return@Runnable
        if (accumulator.isEmpty()) finishContinuousStroke() else scheduleNext()
    }

    private fun continuousEnded() {
        dispatching = false
        metrics = metrics.copy(state = GestureState.IDLE)
        publish()
        if (!stopped && !accumulator.isEmpty()) scheduleNext()
    }

    private fun finish(success: Boolean, started: Long, request: GestureRequest) {
        val now = System.nanoTime(); dispatching = false; val durationMs = (now - started) / 1_000_000f
        totalDurationMs += durationMs; val latencyMs = (now - request.queuedAtNanos).coerceAtLeast(0L) / 1_000_000f
        if (latencies.size == 256) latencies.removeFirst(); latencies.addLast(latencyMs)
        val completed = metrics.completed + if (success) 1 else 0; val cancelled = metrics.cancelled + if (success) 0 else 1
        val totalCallbacks = completed + cancelled; val effectiveHz = if (lastCompletedNanos == 0L) 0f else 1_000_000_000f / (now - lastCompletedNanos).coerceAtLeast(1L)
        lastCompletedNanos = now
        val sorted = latencies.sorted(); fun percentile(p: Float) = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * p).toInt()]
        metrics = metrics.copy(
            state = if (success) GestureState.COMPLETED else GestureState.CANCELLED, completed = completed, cancelled = cancelled,
            averageDurationMs = (totalDurationMs / totalCallbacks.coerceAtLeast(1)).toFloat(), effectiveHz = effectiveHz,
            cancellationPercent = cancelled * 100f / totalCallbacks.coerceAtLeast(1), averageLatencyMs = sorted.average().toFloat(),
            p50LatencyMs = percentile(.50f), p90LatencyMs = percentile(.90f), p95LatencyMs = percentile(.95f), p99LatencyMs = percentile(.99f),
        ); publish(); scheduleNext()
    }

    private fun nearBoundary(x: Float, y: Float, zone: com.gyrobridge.app.domain.mapper.PixelZone): Boolean {
        val mx = (zone.right - zone.left) * profile.cameraZone.boundaryMargin; val my = (zone.bottom - zone.top) * profile.cameraZone.boundaryMargin
        return x < zone.left + mx || x > zone.right - mx || y < zone.top + my || y > zone.bottom - my
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds; bounds.width() to bounds.height()
    } else {
        val dm = DisplayMetrics(); (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm); dm.widthPixels to dm.heightPixels
    }
    @Suppress("DEPRECATION")
    private fun displayRotation(): DisplayRotation = if (Build.VERSION.SDK_INT >= 30) {
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        DisplayRotation.fromSurface(display?.rotation ?: android.view.Surface.ROTATION_0)
    } else {
        val manager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        DisplayRotation.fromSurface(manager.defaultDisplay.rotation)
    }
    private fun publish() = AppGraph.runtime.setGestureMetrics(metrics)

    private companion object {
        const val MIN_GESTURE_DISTANCE_PX = 0.5f
        const val CONTINUOUS_IDLE_GRACE_MS = 120L
    }
}
