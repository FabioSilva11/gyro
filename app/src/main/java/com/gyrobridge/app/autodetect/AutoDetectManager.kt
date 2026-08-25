package com.gyrobridge.app.autodetect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import com.gyrobridge.app.domain.model.AutoDetectConfig
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "GB_AutoDet"

class AutoDetectManager(private val context: Context) {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var config: AutoDetectConfig = AutoDetectConfig()
    private var accessibilityService: AccessibilityService? = null
    private var running = false
    private var consecutiveDetections = 0
    private var lastClickTimestamp = 0L
    private var lastCaptureTimestamp = 0L
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var displayDpi = 320

    fun configure(config: AutoDetectConfig, service: AccessibilityService) {
        this.config = config
        this.accessibilityService = service
        Log.i(TAG, "configure: enabled=${config.enabled} hue=[${config.hueMin},${config.hueMax}] threshold=${config.pixelThresholdPercent}%")
    }

    fun updateConfig(config: AutoDetectConfig) {
        this.config = config
        Log.d(TAG, "updateConfig: enabled=${config.enabled}")
        if (!config.enabled && running) stop()
        if (config.enabled && !running) start()
    }

    fun startWithProjection(mediaProjection: MediaProjection) {
        if (running) { Log.w(TAG, "Already running"); return }
        val svc = accessibilityService
        if (svc == null) { Log.e(TAG, "No accessibility service available"); return }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stop()
            }
        }, handler)
        start()
    }

    private fun start() {
        if (running) return
        if (!config.enabled) { Log.d(TAG, "Auto detect disabled, skipping start"); return }
        val svc = accessibilityService ?: return
        val proj = projection ?: return

        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        display?.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDpi = metrics.densityDpi

        handlerThread = HandlerThread("AutoDetectCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val captureW = (displayWidth * config.scanRegionWidth).roundToInt().coerceAtLeast(64)
        val captureH = (displayHeight * config.scanRegionHeight).roundToInt().coerceAtLeast(64)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "GyroBridgeAutoDetect",
            captureW, captureH, displayDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler,
        )

        running = true; consecutiveDetections = 0
        Log.i(TAG, "start: capture ${captureW}x${captureH} at ${config.captureIntervalMs}ms interval")
        handler?.postDelayed(captureRunnable, config.captureIntervalMs)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastCaptureTimestamp < config.captureIntervalMs) {
                handler?.postDelayed(this, config.captureIntervalMs - (now - lastCaptureTimestamp))
                return
            }
            lastCaptureTimestamp = now
            captureAndAnalyze()
            handler?.postDelayed(this, config.captureIntervalMs)
        }
    }

    private fun captureAndAnalyze() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val scanX = ((config.scanRegionX - config.scanRegionWidth / 2f) * image.width).roundToInt().coerceIn(0, bitmap.width - 1)
            val scanY = ((config.scanRegionY - config.scanRegionHeight / 2f) * image.height).roundToInt().coerceIn(0, bitmap.height - 1)
            val scanW = (config.scanRegionWidth * image.width).roundToInt().coerceIn(1, bitmap.width - scanX)
            val scanH = (config.scanRegionHeight * image.height).roundToInt().coerceIn(1, bitmap.height - scanY)

            var greenCount = 0
            val totalPixels = scanW * scanH
            var edgeDetections = 0
            val edgeMargin = config.edgeMarginPx.coerceAtMost(scanW / 4).coerceAtMost(scanH / 4)

            for (y in scanY until (scanY + scanH).coerceAtMost(bitmap.height)) {
                for (x in scanX until (scanX + scanW).coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    val hsv = FloatArray(3)
                    Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                    val hue = hsv[0]; val sat = hsv[1]; val value = hsv[2]

                    val isGreen = hue >= config.hueMin && hue <= config.hueMax &&
                            sat >= config.saturationMin && value >= config.valueMin
                    if (isGreen) {
                        greenCount++
                        val localX = x - scanX; val localY = y - scanY
                        if (localX < edgeMargin || localX > scanW - edgeMargin || localY < edgeMargin || localY > scanH - edgeMargin) {
                            edgeDetections++
                        }
                    }
                }
            }
            bitmap.recycle()

            val greenPercent = if (totalPixels > 0) greenCount * 100f / totalPixels else 0f
            if (greenPercent >= config.pixelThresholdPercent) {
                consecutiveDetections++
                Log.d(TAG, "Green detected: ${"%.1f".format(greenPercent)}% ($greenCount px), consecutive=$consecutiveDetections, edge=$edgeDetections")
                if (consecutiveDetections >= config.debounceCount) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTimestamp >= config.cooldownMs) {
                        lastClickTimestamp = now; consecutiveDetections = 0
                        dispatchClick()
                    }
                }
            } else {
                if (consecutiveDetections > 0) {
                    Log.d(TAG, "Green lost: ${"%.1f".format(greenPercent)}%, resetting consecutive count")
                }
                consecutiveDetections = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureAndAnalyze error", e)
        } finally {
            image.close()
        }
    }

    private fun dispatchClick() {
        val svc = accessibilityService ?: run {
            Log.w(TAG, "dispatchClick: no accessibility service"); return
        }
        val clickScreenX = (config.clickX * displayWidth).coerceIn(config.edgeMarginPx.toFloat(), (displayWidth - config.edgeMarginPx).toFloat())
        val clickScreenY = (config.clickY * displayHeight).coerceIn(config.edgeMarginPx.toFloat(), (displayHeight - config.edgeMarginPx).toFloat())
        Log.i(TAG, "dispatchClick: (${"%.0f".format(clickScreenX)}, ${"%.0f".format(clickScreenY)})")
        val path = Path().apply { moveTo(clickScreenX, clickScreenY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Click dispatched successfully")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Click dispatch cancelled")
            }
        }, handler)
    }

    fun stop() {
        running = false; consecutiveDetections = 0
        handler?.removeCallbacksAndMessages(null)
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        handlerThread?.quitSafely(); handlerThread = null; handler = null
        projection?.stop(); projection = null
        Log.i(TAG, "stop")
    }

    fun isRunning() = running
    fun setAccessibilityService(service: AccessibilityService?) { accessibilityService = service }
}
