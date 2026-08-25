package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPipelineTest {
    private fun sample(yaw: Float = 1f, pitch: Float = 2f, timestamp: Long = 1_000_000_000L) = OrientationSample(deltaYaw = yaw, deltaPitch = pitch, sensorTimestampNanos = timestamp)

    @Test fun `deadzone removes only small values`() { assertEquals(0f, MotionPipeline.applyDeadzone(.09f,.1f),0f); assertEquals(.11f,MotionPipeline.applyDeadzone(.11f,.1f),0f) }
    @Test fun `axis inversion changes output sign`() {
        val base = ControlProfile(filterConfig=FilterConfig(xFilter=FilterType.NONE,yFilter=FilterType.NONE,smoothing=0f),sensitivityConfig=SensitivityConfig(horizontal=2f,vertical=2f,horizontalDeadzone=0f,verticalDeadzone=0f),gestureConfig=GestureConfig(maxPixelsPerSecond=100000f))
        val normal=MotionPipeline(base).process(sample()).dx
        val inverted=MotionPipeline(base.copy(axisConfig=base.axisConfig.copy(invertX=true))).process(sample()).dx
        assertTrue(normal>0f);assertEquals(-normal,inverted,.001f)
    }
    @Test fun `camera pitch uses touch camera direction`() {
        val profile = ControlProfile(
            filterConfig = FilterConfig(xFilter=FilterType.NONE,yFilter=FilterType.NONE,smoothing=0f),
            sensitivityConfig = SensitivityConfig(horizontal=1f,vertical=1f,horizontalDeadzone=0f,verticalDeadzone=0f),
            gestureConfig = GestureConfig(maxPixelsPerSecond=100000f),
        )
        val normal = MotionPipeline(profile).process(sample(pitch=2f)).dy
        val inverted = MotionPipeline(profile.copy(axisConfig=profile.axisConfig.copy(invertY=true))).process(sample(pitch=2f)).dy
        assertTrue(normal < 0f)
        assertEquals(-normal, inverted, .001f)
    }
    @Test fun `sensitivity and clamping are applied`() {
        val p=ControlProfile(filterConfig=FilterConfig(xFilter=FilterType.NONE,yFilter=FilterType.NONE,smoothing=0f),sensitivityConfig=SensitivityConfig(horizontal=50f,vertical=50f,horizontalDeadzone=0f,verticalDeadzone=0f),gestureConfig=GestureConfig(maxXPerUpdate=10f,maxYPerUpdate=12f,maxPixelsPerSecond=100000f))
        val out=MotionPipeline(p).process(sample(10f,10f));assertEquals(10f,out.dx,.001f);assertEquals(-12f,out.dy,.001f)
    }
    @Test fun `power curve preserves sign`() { assertEquals(-4f,MotionPipeline.response(-2f,ResponseCurve.POWER,2f),.001f) }
}
