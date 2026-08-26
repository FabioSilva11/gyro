package com.gyrobridge.app.domain

import com.gyrobridge.app.domain.mapper.ScreenCoordinateMapper
import com.gyrobridge.app.domain.model.DisplayRotation
import com.gyrobridge.app.domain.model.MovementZone
import com.gyrobridge.app.domain.model.ScreenZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCoordinateMapperTest {
    @Test fun `normalized center converts in portrait and landscape`() { val z=ScreenZone(centerX=.75f,centerY=.5f,width=.4f,height=.4f);val portrait=ScreenCoordinateMapper.toPixels(z,1080,2400);val landscape=ScreenCoordinateMapper.toPixels(z,2400,1080);assertEquals(810f,portrait.centerX,.01f);assertEquals(1800f,landscape.centerX,.01f);assertEquals(1200f,portrait.centerY,.01f);assertEquals(540f,landscape.centerY,.01f) }
    @Test fun `zone is clamped to screen`() { val p=ScreenCoordinateMapper.toPixels(ScreenZone(centerX=2f,centerY=-1f,width=2f,height=-1f),100,200);assertTrue(p.left>=0);assertTrue(p.right<=100);assertTrue(p.top>=0);assertTrue(p.bottom<=200) }

    @Test fun `point rotates by the difference between mapped and current display`() {
        val point = ScreenCoordinateMapper.rotatePoint(
            nx = .8f,
            ny = .7f,
            mappedRotation = DisplayRotation.ROTATION_90,
            currentRotation = DisplayRotation.ROTATION_270,
        )

        assertEquals(.2f, point.first, .0001f)
        assertEquals(.3f, point.second, .0001f)
    }

    @Test fun `camera dimensions swap on quarter turn`() {
        val zone = ScreenZone(
            centerX = .75f,
            centerY = .5f,
            width = .4f,
            height = .2f,
            mappedDisplayRotation = DisplayRotation.ROTATION_0,
        )

        val pixels = ScreenCoordinateMapper.cameraToPixels(
            zone = zone,
            width = 2400,
            height = 1080,
            currentRotation = DisplayRotation.ROTATION_90,
        )

        assertEquals(.2f * 2400f, pixels.widthPx, 1f)
        assertEquals(.4f * 1080f, pixels.heightPx, 1f)
        assertEquals(.5f * 2400f, pixels.centerX, 1f)
        assertEquals(.75f * 1080f, pixels.centerY, 1f)
    }

    @Test fun `movement zone rotates with its mapped display`() {
        val circle = ScreenCoordinateMapper.movementToPixels(
            zone = MovementZone(.2f, .72f, .12f, DisplayRotation.ROTATION_0),
            width = 2400,
            height = 1080,
            currentRotation = DisplayRotation.ROTATION_90,
        )

        assertEquals(.28f * 2400f, circle.centerX, 1f)
        assertEquals(.2f * 1080f, circle.centerY, 1f)
        assertEquals(.12f * 1080f, circle.radiusPx, 1f)
    }

    @Test fun `legacy camera without mapped rotation keeps normalized coordinates`() {
        val zone = ScreenZone(centerX = .75f, centerY = .25f, width = .4f, height = .2f)

        val pixels = ScreenCoordinateMapper.cameraToPixels(zone, 2400, 1080, DisplayRotation.ROTATION_270)

        assertEquals(.75f * 2400f, pixels.centerX, 1f)
        assertEquals(.25f * 1080f, pixels.centerY, 1f)
        assertEquals(.4f * 2400f, pixels.widthPx, 1f)
        assertEquals(.2f * 1080f, pixels.heightPx, 1f)
    }
}
