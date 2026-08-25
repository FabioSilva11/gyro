package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.FilterConfig
import com.gyrobridge.app.domain.model.FilterType
import kotlin.math.PI
import kotlin.math.abs

interface MotionFilter { fun apply(value: Float, timestampNanos: Long): Float; fun reset() }

class PassthroughFilter : MotionFilter { override fun apply(value: Float, timestampNanos: Long) = value; override fun reset() = Unit }

class EmaFilter(private val alpha: Float) : MotionFilter {
    private var previous = Float.NaN
    override fun apply(value: Float, timestampNanos: Long): Float {
        previous = if (previous.isNaN()) value else alpha.coerceIn(.001f, 1f) * value + (1f - alpha.coerceIn(.001f, 1f)) * previous
        return previous
    }
    override fun reset() { previous = Float.NaN }
}

class AdaptiveFilter(private val baseAlpha: Float) : MotionFilter {
    private var previous = Float.NaN
    override fun apply(value: Float, timestampNanos: Long): Float {
        val speed = if (previous.isNaN()) 0f else abs(value - previous)
        val alpha = (baseAlpha + speed / (speed + 2f) * (1f - baseAlpha)).coerceIn(.02f, 1f)
        previous = if (previous.isNaN()) value else previous + alpha * (value - previous)
        return previous
    }
    override fun reset() { previous = Float.NaN }
}

class OneEuroFilter(private val minCutoff: Float, private val beta: Float, private val dCutoff: Float) : MotionFilter {
    private var xPrev = Float.NaN; private var dxPrev = 0f; private var lastTimestamp = 0L
    override fun apply(value: Float, timestampNanos: Long): Float {
        if (xPrev.isNaN() || lastTimestamp == 0L) { xPrev = value; lastTimestamp = timestampNanos; return value }
        val dt = ((timestampNanos - lastTimestamp) / 1_000_000_000f).coerceIn(.0001f, .2f)
        lastTimestamp = timestampNanos
        val derivative = (value - xPrev) / dt
        val dAlpha = smoothingFactor(dt, dCutoff)
        dxPrev += dAlpha * (derivative - dxPrev)
        val cutoff = minCutoff + beta * abs(dxPrev)
        val alpha = smoothingFactor(dt, cutoff)
        xPrev += alpha * (value - xPrev)
        return xPrev
    }
    override fun reset() { xPrev = Float.NaN; dxPrev = 0f; lastTimestamp = 0L }
    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff.coerceAtLeast(.001f))
        return 1f / (1f + tau / dt)
    }
}

object MotionFilterFactory {
    fun create(type: FilterType, config: FilterConfig): MotionFilter = when (type) {
        FilterType.NONE -> PassthroughFilter()
        FilterType.LOW_PASS, FilterType.EMA -> EmaFilter(config.alpha)
        FilterType.ONE_EURO -> OneEuroFilter(config.minCutoff, config.beta, config.dCutoff)
        FilterType.ADAPTIVE -> AdaptiveFilter(config.alpha)
    }
}
