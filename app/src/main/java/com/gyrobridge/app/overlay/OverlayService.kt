package com.gyrobridge.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.domain.model.DisplayRotation
import com.gyrobridge.app.domain.model.MovementZone
import com.gyrobridge.app.domain.model.ScreenZone
import com.gyrobridge.app.domain.model.sanitized
import com.gyrobridge.app.service.GyroForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var expanded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AppGraph.runtime.setOverlayStatus(false, "Permissão de sobreposição ausente")
            stopSelf()
            return
        }
        createOrReport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        removeOverlay()
        expanded = false
        createOrReport()
        return START_NOT_STICKY
    }

    private fun createOrReport() {
        runCatching { showCompactOverlay() }
            .onSuccess { AppGraph.runtime.setOverlayStatus(true, "Visível e compacto") }
            .onFailure {
                AppGraph.runtime.setOverlayStatus(false, "Falha: ${it.javaClass.simpleName}")
                stopSelf()
            }
    }

    private fun showCompactOverlay() {
        if (overlay != null) return
        windowManager = getSystemService(WindowManager::class.java)
        val profile = AppGraph.runtime.activeProfile.value
        val density = resources.displayMetrics.density
        val config = profile?.overlayConfig
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 4f * density
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((config?.normalizedX ?: .02f) * resources.displayMetrics.widthPixels).toInt()
            y = ((config?.normalizedY ?: .12f) * resources.displayMetrics.heightPixels).toInt()
        }
        overlay = panel
        renderMenu(panel, params, profile, density)
        windowManager.addView(panel, params)
    }

    private fun renderMenu(
        panel: LinearLayout,
        params: WindowManager.LayoutParams,
        profile: ControlProfile?,
        density: Float,
    ) {
        panel.removeAllViews()
        val opacity = ((profile?.overlayConfig?.opacity ?: .88f) * 255).toInt().coerceIn(40, 230)
        panel.background = GradientDrawable().apply {
            cornerRadius = 18f * density
            setColor(Color.argb(opacity, 10, 18, 32))
            setStroke(density.toInt().coerceAtLeast(1), Color.argb(190, 56, 189, 248))
        }

        val paused = AppGraph.runtime.sessionPaused.value
        val calibrating = AppGraph.runtime.calibrating.value
        val handle = label(if (calibrating) "… CALIBRANDO" else if (paused) "■ GYRO OFF" else "● GYRO ON", 10.5f, density).apply {
            setTextColor(if (paused) Color.rgb(251, 191, 36) else Color.rgb(134, 239, 172))
        }
        panel.addView(handle)

        if (expanded && !calibrating) {
            if (paused) {
                panel.addView(action("CALIBRAR + INICIAR", density) {
                    GyroForegroundService.action(this, GyroForegroundService.ACTION_PAUSE)
                })
                panel.addView(action("MAPEAR", density) { profile?.let(::showMapper) })
            } else {
                panel.addView(action("CAL", density) {
                    GyroForegroundService.action(this, GyroForegroundService.ACTION_CALIBRATE)
                })
                panel.addView(action("PAUSAR", density) {
                    GyroForegroundService.action(this, GyroForegroundService.ACTION_PAUSE)
                })
            }
            panel.addView(action("PARAR", density) {
                GyroForegroundService.action(this, GyroForegroundService.ACTION_STOP)
            })
            panel.addView(action("‹", density) {
                expanded = false
                renderMenu(panel, params, profile, density)
                windowManager.updateViewLayout(panel, params)
            })
        }

        val onHandleTap = {
            if (!AppGraph.runtime.calibrating.value) {
                expanded = !expanded
                renderMenu(panel, params, profile, density)
                windowManager.updateViewLayout(panel, params)
            }
        }
        if (profile?.overlayConfig?.locked == true) {
            handle.isClickable = true
            handle.setOnClickListener { onHandleTap() }
        } else {
            makeDraggable(handle, panel, params, onHandleTap)
        }
    }

    private fun showMapper(profile: ControlProfile) {
        if (!AppGraph.runtime.sessionPaused.value) return
        removeOverlay()
        val mapper = MappingOverlayView(
            context = this,
            initialCamera = profile.cameraZone,
            initialMovement = profile.physicalMovement.zone,
            onSave = { camera, movement -> saveMappedZones(profile, camera, movement) },
            onCancel = {
                expanded = false
                createOrReport()
            },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        overlay = mapper
        windowManager.addView(mapper, params)
        AppGraph.runtime.setOverlayStatus(true, "Mapeando região da câmera")
    }

    @Suppress("DEPRECATION")
    private fun saveMappedZones(profile: ControlProfile, camera: ScreenZone, movement: MovementZone) {
        val rotation = DisplayRotation.fromSurface(windowManager.defaultDisplay.rotation)
        val updated = profile.copy(
            cameraZone = camera.copy(mappedDisplayRotation = rotation).sanitized(),
            physicalMovement = profile.physicalMovement.copy(
                zone = movement.copy(mappedDisplayRotation = rotation).sanitized(),
            ),
        )
        scope.launch {
            AppGraph.repository.save(updated)
            AppGraph.runtime.setProfile(updated)
            GyroForegroundService.switchProfile(this@OverlayService, updated.id)
        }
    }

    private fun label(textValue: String, sizeSp: Float, density: Float) = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(Color.WHITE)
        setPadding(
            (8 * density).toInt(),
            (5 * density).toInt(),
            (8 * density).toInt(),
            (5 * density).toInt(),
        )
    }

    private fun action(textValue: String, density: Float, onClick: () -> Unit) =
        label(textValue, 9.5f, density).apply {
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun makeDraggable(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 10 || abs(event.rawY - downY) > 10) {
                        moved = true
                        params.x = originX + (event.rawX - downX).toInt()
                        params.y = originY + (event.rawY - downY).toInt()
                        windowManager.updateViewLayout(panel, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { view -> if (::windowManager.isInitialized) runCatching { windowManager.removeView(view) } }
        overlay = null
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        AppGraph.runtime.setOverlayStatus(false, "Oculto")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeOverlay()
        expanded = false
        createOrReport()
    }

    companion object {
        fun start(context: Context) = context.startService(Intent(context, OverlayService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, OverlayService::class.java))
    }
}

private class MappingOverlayView(
    context: Context,
    initialCamera: ScreenZone,
    initialMovement: MovementZone,
    private val onSave: (ScreenZone, MovementZone) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cameraZone = initialCamera.sanitized()
    private var movementZone = initialMovement.sanitized()
    private var target = Target.CAMERA
    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var pressedButton: Button? = null
    private val buttons = linkedMapOf<Button, RectF>()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = cameraRect()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(34, 56, 189, 248)
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (target == Target.CAMERA) 3f else 2f) * density
        paint.color = Color.rgb(56, 189, 248)
        canvas.drawRoundRect(rect, 18f * density, 18f * density, paint)
        paint.strokeWidth = 3f * density
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
        canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, paint)

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 22f * density
        paint.color = Color.WHITE
        canvas.drawText("↑", rect.centerX(), rect.top + 30f * density, paint)
        canvas.drawText("↓", rect.centerX(), rect.bottom - 10f * density, paint)
        canvas.drawText("←", rect.left + 24f * density, rect.centerY() + 8f * density, paint)
        canvas.drawText("→", rect.right - 24f * density, rect.centerY() + 8f * density, paint)

        val circle = movementCircle()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(38, 74, 222, 128)
        canvas.drawCircle(circle.first, circle.second, circle.third, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (target == Target.MOVEMENT) 3f else 2f) * density
        paint.color = Color.rgb(74, 222, 128)
        canvas.drawCircle(circle.first, circle.second, circle.third, paint)
        canvas.drawLine(circle.first, circle.second - circle.third * .85f, circle.first, circle.second + circle.third * .85f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 12f * density
        paint.color = Color.WHITE
        canvas.drawText("Frente", circle.first, circle.second - circle.third * .48f, paint)
        canvas.drawText("Trás", circle.first, circle.second + circle.third * .62f, paint)

        drawToolbar(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        val margin = 12f * density
        val height = 42f * density
        val gap = 6f * density
        val cellWidth = ((width - margin * 2f - gap * (Button.entries.size - 1)) / Button.entries.size).coerceAtLeast(42f * density)
        var left = margin
        buttons.clear()
        Button.entries.forEachIndexed { index, button ->
            val rect = RectF(left, margin, left + cellWidth, margin + height)
            buttons[button] = rect
            paint.style = Paint.Style.FILL
            paint.color = when {
                button == Button.SAVE -> Color.argb(235, 29, 78, 216)
                button == Button.CAMERA && target == Target.CAMERA -> Color.argb(235, 3, 105, 161)
                button == Button.MOVEMENT && target == Target.MOVEMENT -> Color.argb(235, 22, 101, 52)
                else -> Color.argb(225, 10, 18, 32)
            }
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.rgb(56, 189, 248)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f * density
            paint.color = Color.WHITE
            canvas.drawText(button.label, rect.centerX(), rect.centerY() + 4f * density, paint)
            left = rect.right + gap
        }
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 12f * density
        paint.color = Color.WHITE
        canvas.drawText("Selecione CÂMERA ou MOV. e arraste a área", margin, margin + height + 22f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = buttons.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                if (pressedButton == null) {
                    val circle = movementCircle()
                    val inMovement = kotlin.math.hypot((event.x-circle.first).toDouble(), (event.y-circle.second).toDouble()) <= circle.third
                    if (inMovement) target = Target.MOVEMENT else if (cameraRect().contains(event.x, event.y)) target = Target.CAMERA
                    dragging = true
                    val centerX = if (target == Target.CAMERA) cameraZone.centerX else movementZone.centerX
                    val centerY = if (target == Target.CAMERA) cameraZone.centerY else movementZone.centerY
                    dragOffsetX = event.x - centerX * width
                    dragOffsetY = event.y - centerY * height
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && width > 0 && height > 0) {
                    if (target == Target.CAMERA) {
                        val halfW = cameraZone.width / 2f
                        val halfH = cameraZone.height / 2f
                        cameraZone = cameraZone.copy(
                            centerX = ((event.x - dragOffsetX) / width).coerceIn(halfW, 1f - halfW),
                            centerY = ((event.y - dragOffsetY) / height).coerceIn(halfH, 1f - halfH),
                        ).sanitized()
                    } else {
                        val nxRadius = movementZone.radius * minOf(width, height) / width
                        val nyRadius = movementZone.radius * minOf(width, height) / height
                        movementZone = movementZone.copy(
                            centerX = ((event.x - dragOffsetX) / width).coerceIn(nxRadius, 1f - nxRadius),
                            centerY = ((event.y - dragOffsetY) / height).coerceIn(nyRadius, 1f - nyRadius),
                        ).sanitized()
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val button = pressedButton
                if (button != null && buttons[button]?.contains(event.x, event.y) == true) perform(button)
                dragging = false
                pressedButton = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressedButton = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun perform(button: Button) {
        when (button) {
            Button.CAMERA -> { target = Target.CAMERA; invalidate() }
            Button.MOVEMENT -> { target = Target.MOVEMENT; invalidate() }
            Button.CANCEL -> onCancel()
            Button.SMALLER -> resize(-.05f)
            Button.LARGER -> resize(.05f)
            Button.SAVE -> onSave(cameraZone.sanitized(), movementZone.sanitized())
        }
    }

    private fun resize(delta: Float) {
        if (target == Target.CAMERA) {
            cameraZone = cameraZone.copy(
                width = (cameraZone.width + delta).coerceIn(.10f, .90f),
                height = (cameraZone.height + delta).coerceIn(.10f, .90f),
            ).sanitized()
            val halfW = cameraZone.width / 2f
            val halfH = cameraZone.height / 2f
            cameraZone = cameraZone.copy(
                centerX = cameraZone.centerX.coerceIn(halfW, 1f - halfW),
                centerY = cameraZone.centerY.coerceIn(halfH, 1f - halfH),
            )
        } else {
            movementZone = movementZone.copy(radius = (movementZone.radius + delta / 2f).coerceIn(.03f, .40f)).sanitized()
        }
        invalidate()
    }

    private fun cameraRect(): RectF {
        val centerX = cameraZone.centerX * width
        val centerY = cameraZone.centerY * height
        val halfW = cameraZone.width * width / 2f
        val halfH = cameraZone.height * height / 2f
        return RectF(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    private fun movementCircle(): Triple<Float, Float, Float> = Triple(
        movementZone.centerX * width,
        movementZone.centerY * height,
        movementZone.radius * minOf(width, height),
    )

    private enum class Target { CAMERA, MOVEMENT }

    private enum class Button(val label: String) {
        CAMERA("CÂMERA"),
        MOVEMENT("MOV."),
        CANCEL("CANCELAR"),
        SMALLER("−"),
        LARGER("+"),
        SAVE("SALVAR"),
    }
}
