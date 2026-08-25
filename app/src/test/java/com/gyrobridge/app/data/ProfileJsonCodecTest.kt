package com.gyrobridge.app.data

import com.gyrobridge.app.data.profile.ProfileJsonCodec
import com.gyrobridge.app.domain.model.ControlProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonCodecTest {
    @Test fun `profile survives JSON round trip`() { val p=ControlProfile(name="FPS Profile",packageName="com.example.game");val decoded=ProfileJsonCodec.decode(ProfileJsonCodec.encode(p).toString());assertEquals(p.name,decoded.name);assertEquals(p.packageName,decoded.packageName);assertEquals(p.sensitivityConfig.horizontal,decoded.sensitivityConfig.horizontal,0f) }
    @Test fun `invalid non finite and coordinates are sanitized`() { val json="""{"name":"x","sensitivity":{"horizontal":1e999},"camera":{"centerX":4,"centerY":-2}}""";val p=ProfileJsonCodec.decode(json);assertTrue(p.sensitivityConfig.horizontal.isFinite());assertEquals(1f,p.cameraZone.centerX,0f);assertEquals(0f,p.cameraZone.centerY,0f) }
    @Test fun `version one profiles migrate overlay on`() { val p=ProfileJsonCodec.decode("""{"version":1,"overlay":{"enabled":false}}""");assertTrue(p.overlayConfig.enabled) }
    @Test fun `version two preserves explicit overlay off`() { val p=ProfileJsonCodec.decode("""{"version":2,"overlay":{"enabled":false}}""");assertEquals(false,p.overlayConfig.enabled) }
    @Test fun `old profiles migrate to fluid continuous gestures`() { val p=ProfileJsonCodec.decode("""{"version":2,"gesture":{"mode":"SEGMENTED"}}""");assertEquals(com.gyrobridge.app.domain.model.GestureMode.CONTINUOUS,p.gestureConfig.mode) }
}
