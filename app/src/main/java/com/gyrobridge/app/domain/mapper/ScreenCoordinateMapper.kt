package com.gyrobridge.app.domain.mapper

import com.gyrobridge.app.domain.model.DisplayRotation
import com.gyrobridge.app.domain.model.MovementZone
import com.gyrobridge.app.domain.model.ScreenZone
import com.gyrobridge.app.domain.model.sanitized

data class PixelZone(
    val centerX: Float, val centerY: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    val widthPx: Float get() = right - left
    val heightPx: Float get() = bottom - top
}

data class PixelCircle(val centerX: Float, val centerY: Float, val radiusPx: Float)

object ScreenCoordinateMapper {
    fun toPixels(zone: ScreenZone, width: Int, height: Int): PixelZone {
        val safe = zone.sanitized()
        val cx = safe.centerX * width; val cy = safe.centerY * height
        val halfW = safe.width * width / 2f; val halfH = safe.height * height / 2f
        return PixelZone(cx, cy, (cx - halfW).coerceIn(0f, width.toFloat()), (cy - halfH).coerceIn(0f, height.toFloat()), (cx + halfW).coerceIn(0f, width.toFloat()), (cy + halfH).coerceIn(0f, height.toFloat()))
    }

    fun rotatePoint(
        nx: Float,
        ny: Float,
        mappedRotation: DisplayRotation,
        currentRotation: DisplayRotation,
    ): Pair<Float, Float> = when (rotationDelta(mappedRotation, currentRotation)) {
        1 -> (1f - ny) to nx
        2 -> (1f - nx) to (1f - ny)
        3 -> ny to (1f - nx)
        else -> nx to ny
    }

    fun cameraToPixels(
        zone: ScreenZone,
        width: Int,
        height: Int,
        currentRotation: DisplayRotation,
    ): PixelZone {
        val safe = zone.sanitized()
        val mapped = safe.mappedDisplayRotation ?: currentRotation
        val (centerX, centerY) = rotatePoint(safe.centerX, safe.centerY, mapped, currentRotation)
        val swapDimensions = rotationDelta(mapped, currentRotation) % 2 != 0
        val normalizedWidth = if (swapDimensions) safe.height else safe.width
        val normalizedHeight = if (swapDimensions) safe.width else safe.height
        val cx = centerX * width
        val cy = centerY * height
        val halfWidth = normalizedWidth * width / 2f
        val halfHeight = normalizedHeight * height / 2f
        return PixelZone(
            centerX = cx,
            centerY = cy,
            left = (cx - halfWidth).coerceIn(0f, width.toFloat()),
            top = (cy - halfHeight).coerceIn(0f, height.toFloat()),
            right = (cx + halfWidth).coerceIn(0f, width.toFloat()),
            bottom = (cy + halfHeight).coerceIn(0f, height.toFloat()),
        )
    }

    fun movementToPixels(
        zone: MovementZone,
        width: Int,
        height: Int,
        currentRotation: DisplayRotation,
    ): PixelCircle {
        val safe = zone.sanitized()
        val mapped = safe.mappedDisplayRotation ?: currentRotation
        val (centerX, centerY) = rotatePoint(safe.centerX, safe.centerY, mapped, currentRotation)
        return PixelCircle(
            centerX = centerX * width,
            centerY = centerY * height,
            radiusPx = safe.radius * minOf(width, height),
        )
    }

    private fun rotationDelta(mapped: DisplayRotation, current: DisplayRotation): Int =
        (current.quarterTurns - mapped.quarterTurns + 4) % 4
}
