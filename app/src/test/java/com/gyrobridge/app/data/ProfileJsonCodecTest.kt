package com.gyrobridge.app.data

import com.gyrobridge.app.data.profile.ProfileJsonCodec
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.domain.model.DisplayRotation
import com.gyrobridge.app.domain.model.MovementZone
import com.gyrobridge.app.domain.model.PhysicalMovementConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonCodecTest {
    @Test fun `profile survives JSON round trip`() { val p=ControlProfile(name="FPS Profile",packageName="com.example.game");val decoded=ProfileJsonCodec.decode(ProfileJsonCodec.encode(p).toString());assertEquals(p.name,decoded.name);assertEquals(p.packageName,decoded.packageName);assertEquals(p.sensitivityConfig.horizontal,decoded.sensitivityConfig.horizontal,0f) }
    @Test fun `invalid non finite and coordinates are sanitized`() { val json="""{"name":"x","sensitivity":{"horizontal":1e999},"camera":{"centerX":4,"centerY":-2}}""";val p=ProfileJsonCodec.decode(json);assertTrue(p.sensitivityConfig.horizontal.isFinite());assertEquals(1f,p.cameraZone.centerX,0f);assertEquals(0f,p.cameraZone.centerY,0f) }
    @Test fun `version one profiles migrate overlay on`() { val p=ProfileJsonCodec.decode("""{"version":1,"overlay":{"enabled":false}}""");assertTrue(p.overlayConfig.enabled) }
    @Test fun `version two preserves explicit overlay off`() { val p=ProfileJsonCodec.decode("""{"version":2,"overlay":{"enabled":false}}""");assertEquals(false,p.overlayConfig.enabled) }
    @Test fun `old profiles migrate to fluid continuous gestures`() { val p=ProfileJsonCodec.decode("""{"version":2,"gesture":{"mode":"SEGMENTED"}}""");assertEquals(com.gyrobridge.app.domain.model.GestureMode.CONTINUOUS,p.gestureConfig.mode) }

    @Test fun `version three profile migrates with physical movement disabled and unknown camera rotation`() {
        val profile = ProfileJsonCodec.decode("""{"version":3,"camera":{"centerX":0.8}}""")

        assertFalse(profile.physicalMovement.enabled)
        assertNull(profile.cameraZone.mappedDisplayRotation)
    }

    @Test fun `version four physical movement survives JSON round trip`() {
        val original = ControlProfile(
            physicalMovement = PhysicalMovementConfig(
                enabled = true,
                zone = MovementZone(
                    centerX = .2f,
                    centerY = .72f,
                    radius = .12f,
                    mappedDisplayRotation = DisplayRotation.ROTATION_90,
                ),
            ),
        )

        val decoded = ProfileJsonCodec.decode(ProfileJsonCodec.encode(original).toString())

        assertEquals(original.physicalMovement, decoded.physicalMovement)
    }

    @Test fun `version four omits obsolete angle offsets and synthetic joystick`() {
        val json = ProfileJsonCodec.encode(ControlProfile()).toString()

        assertFalse(json.contains("zeroYaw"))
        assertFalse(json.contains("zeroPitch"))
        assertFalse(json.contains("zeroRoll"))
        assertFalse(json.contains("\"joystick\""))
        assertFalse(json.contains("interactionMode"))
    }
}
