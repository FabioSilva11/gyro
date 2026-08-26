package com.gyrobridge.app.gesture

import com.gyrobridge.app.domain.model.PhysicalMovementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureContactPlannerTest {
    @Test fun `camera and movement are active in the same frame`() {
        val planner = GestureContactPlanner()
        val frame = planner.next(CameraMotion(12f, -4f), PhysicalMovementState.FORWARD, .8f)

        assertEquals(setOf(ContactId.CAMERA, ContactId.MOVEMENT), frame.contacts.map { it.id }.toSet())
        assertTrue(frame.contacts.all { it.phase == ContactPhase.START })
    }

    @Test fun `movement remains pressed while camera continues`() {
        val planner = GestureContactPlanner()
        val first = planner.next(CameraMotion(2f, 0f), PhysicalMovementState.FORWARD, .8f)
        planner.onDispatchCompleted(first.generation, first.dispatchId)
        val second = planner.next(CameraMotion(3f, 0f), PhysicalMovementState.FORWARD, .8f)

        assertTrue(second.contacts.all { it.phase == ContactPhase.CONTINUE })
        assertEquals(.8f, second.contacts.first { it.id == ContactId.MOVEMENT }.normalizedY, .001f)
    }

    @Test fun `stationary ends only movement contact`() {
        val planner = GestureContactPlanner()
        val first = planner.next(CameraMotion(2f, 0f), PhysicalMovementState.FORWARD, .8f)
        planner.onDispatchCompleted(first.generation, first.dispatchId)
        val next = planner.next(CameraMotion(2f, 0f), PhysicalMovementState.STATIONARY, 0f)

        assertEquals(ContactPhase.CONTINUE, next.contacts.first { it.id == ContactId.CAMERA }.phase)
        assertEquals(ContactPhase.END, next.contacts.first { it.id == ContactId.MOVEMENT }.phase)
    }

    @Test fun `stale callback cannot clear newer generation`() {
        val planner = GestureContactPlanner()
        val old = planner.next(CameraMotion(2f, 0f), PhysicalMovementState.FORWARD, .8f)
        planner.cancelAll()
        val current = planner.next(CameraMotion(3f, 0f), PhysicalMovementState.FORWARD, .8f)

        assertFalse(planner.onDispatchCompleted(old.generation, old.dispatchId))
        assertEquals(current.generation, planner.snapshot.generation)
        assertTrue(planner.snapshot.cameraActive)
        assertTrue(planner.snapshot.movementActive)
    }
}
