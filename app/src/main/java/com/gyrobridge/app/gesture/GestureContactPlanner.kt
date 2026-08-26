package com.gyrobridge.app.gesture

import com.gyrobridge.app.domain.model.PhysicalMovementState

enum class ContactId { CAMERA, MOVEMENT }
enum class ContactPhase { START, CONTINUE, END }

data class CameraMotion(val dx: Float = 0f, val dy: Float = 0f) {
    val active: Boolean get() = dx != 0f || dy != 0f
}

data class PlannedContact(
    val id: ContactId,
    val phase: ContactPhase,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val normalizedY: Float = 0f,
)

data class GestureFrame(
    val generation: Long,
    val dispatchId: Long,
    val contacts: List<PlannedContact>,
)

data class GesturePlannerSnapshot(
    val generation: Long = 0L,
    val dispatchId: Long = 0L,
    val cameraActive: Boolean = false,
    val movementActive: Boolean = false,
    val dispatchInFlight: Boolean = false,
)

class GestureContactPlanner {
    var snapshot = GesturePlannerSnapshot()
        private set

    fun next(
        camera: CameraMotion,
        movement: PhysicalMovementState,
        movementStrength: Float,
    ): GestureFrame {
        val contacts = buildList {
            if (camera.active) {
                add(
                    PlannedContact(
                        id = ContactId.CAMERA,
                        phase = if (snapshot.cameraActive) ContactPhase.CONTINUE else ContactPhase.START,
                        dx = camera.dx,
                        dy = camera.dy,
                    ),
                )
            } else if (snapshot.cameraActive) {
                add(PlannedContact(ContactId.CAMERA, ContactPhase.END))
            }

            val movementActive = movement != PhysicalMovementState.STATIONARY
            val signedStrength = when (movement) {
                PhysicalMovementState.FORWARD -> movementStrength
                PhysicalMovementState.BACKWARD -> -movementStrength
                PhysicalMovementState.STATIONARY -> 0f
            }.coerceIn(-1f, 1f)
            if (movementActive) {
                add(
                    PlannedContact(
                        id = ContactId.MOVEMENT,
                        phase = if (snapshot.movementActive) ContactPhase.CONTINUE else ContactPhase.START,
                        normalizedY = signedStrength,
                    ),
                )
            } else if (snapshot.movementActive) {
                add(PlannedContact(ContactId.MOVEMENT, ContactPhase.END))
            }
        }
        val dispatchId = snapshot.dispatchId + 1L
        snapshot = snapshot.copy(
            dispatchId = dispatchId,
            cameraActive = camera.active,
            movementActive = movement != PhysicalMovementState.STATIONARY,
            dispatchInFlight = true,
        )
        return GestureFrame(snapshot.generation, dispatchId, contacts)
    }

    fun onDispatchCompleted(generation: Long, dispatchId: Long): Boolean {
        if (generation != snapshot.generation || dispatchId != snapshot.dispatchId) return false
        snapshot = snapshot.copy(dispatchInFlight = false)
        return true
    }

    fun cancelAll() {
        snapshot = GesturePlannerSnapshot(generation = snapshot.generation + 1L)
    }
}
