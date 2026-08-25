package com.gyrobridge.app.domain

import com.gyrobridge.app.domain.mapper.ScreenCoordinateMapper
import com.gyrobridge.app.domain.model.ScreenZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCoordinateMapperTest {
    @Test fun `normalized center converts in portrait and landscape`() { val z=ScreenZone(centerX=.75f,centerY=.5f,width=.4f,height=.4f);val portrait=ScreenCoordinateMapper.toPixels(z,1080,2400);val landscape=ScreenCoordinateMapper.toPixels(z,2400,1080);assertEquals(810f,portrait.centerX,.01f);assertEquals(1800f,landscape.centerX,.01f);assertEquals(1200f,portrait.centerY,.01f);assertEquals(540f,landscape.centerY,.01f) }
    @Test fun `zone is clamped to screen`() { val p=ScreenCoordinateMapper.toPixels(ScreenZone(centerX=2f,centerY=-1f,width=2f,height=-1f),100,200);assertTrue(p.left>=0);assertTrue(p.right<=100);assertTrue(p.top>=0);assertTrue(p.bottom<=200) }
}
