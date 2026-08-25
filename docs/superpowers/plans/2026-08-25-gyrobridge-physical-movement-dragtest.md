# GyroBridge Physical Movement and DragTest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver two Android apps where GyroBridge controls a persistent camera pointer and a persistent physical-movement joystick pointer simultaneously, and DragTest validates both with a local Three.js scene.

**Architecture:** Keep orientation, physical movement, and Android gesture dispatch as separate components. Use one session reference matrix, rotation-aware mapped zones, a pure two-contact gesture planner, and a thin AccessibilityService adapter; create `:dragtest` as an independent WebView application in the same Gradle project.

**Tech Stack:** Kotlin 2.4, Android SDK 37/minSdk 26, Jetpack Compose, coroutines/StateFlow, AccessibilityService `GestureDescription`, Android sensors, DataStore/JSON, Java WebView, local Three.js assets, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-25-gyrobridge-physical-movement-dragtest-design.md`

## Global Constraints

- Produce separate APKs for `com.gyrobridge.app` and `com.gyrobridge.dragtest` from modules `:app` and `:dragtest`.
- Keep the GyroBridge Activity portrait; allow DragTest all display rotations.
- Start every control session paused and dispatch no touch until explicit user action.
- Keep Auto Detect outside the change unless a compile-safe signature adjustment is unavoidable.
- Use no root, private API, process injection, external network, or game-memory access.
- Preserve old profiles with physical movement disabled by default.
- Do not run Gradle, unit tests, Android tests, builds, installs, or device tests until the user explicitly authorizes compilation.
- When compilation is authorized, use `--no-daemon --max-workers=1` and the repository's conservative Gradle memory settings.

---

## File Structure

### New production files

- `app/src/main/java/com/gyrobridge/app/domain/model/DisplayRotation.kt`: validated four-way rotation value.
- `app/src/main/java/com/gyrobridge/app/domain/model/PhysicalMovement.kt`: movement state, zone, and profile configuration.
- `app/src/main/java/com/gyrobridge/app/sensor/CalibrationController.kt`: calibration state machine and stable-capture timing.
- `app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementDetector.kt`: pure filtered physical-intent classifier.
- `app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementSensor.kt`: Android linear-acceleration and optional step-sensor adapter.
- `app/src/main/java/com/gyrobridge/app/gesture/GestureContactPlanner.kt`: pure two-contact state machine with generations.
- `app/src/main/java/com/gyrobridge/app/gesture/GestureDispatchAdapter.kt`: Android stroke construction/dispatch boundary.
- `app/src/main/java/com/gyrobridge/app/telemetry/TestTelemetryPublisher.kt`: low-rate explicit DragTest broadcast.
- `dragtest/...`: independent WebView app and recovered Three.js assets.

### Existing files with focused changes

- `ControlProfile.kt`, `ProfileJsonCodec.kt`: schema v4 and migration.
- `ScreenCoordinateMapper.kt`: all rotation transformations.
- `OrientationProcessor.kt`, `SensorEngine.kt`, `MotionPipeline.kt`: reference, fallback, and rate independence.
- `GestureScheduler.kt`, `GestureDispatcherRegistry.kt`, `MultiTouchGestureComposer.kt`: use the planner/adapter and remove competing joystick logic.
- `GyroForegroundService.kt`, `GyroAccessibilityService.kt`, `AppGraph.kt`: explicit runtime states and integration.
- `GyroBridgeApp.kt`, `AppViewModel.kt`, `OverlayService.kt`: movement controls and dual mapper.
- `.gitignore`, `settings.gradle.kts`, `README.md`: version the module and document actual behavior.

---

### Task 1: Profile Schema, Runtime State, and Safe Migration

**Files:**
- Create: `app/src/main/java/com/gyrobridge/app/domain/model/DisplayRotation.kt`
- Create: `app/src/main/java/com/gyrobridge/app/domain/model/PhysicalMovement.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/domain/model/ControlProfile.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/data/profile/ProfileJsonCodec.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/core/AppGraph.kt`
- Test: `app/src/test/java/com/gyrobridge/app/data/ProfileJsonCodecTest.kt`

**Interfaces:**
- Produces: `DisplayRotation`, `ScreenZone.mappedDisplayRotation`, `MovementZone`, `PhysicalMovementConfig`, `PhysicalMovementState`, `SessionStatus`, `SessionError`.
- Consumes: existing `ControlProfile`, `ScreenZone`, and JSON helpers.

- [ ] **Step 1: Add migration tests before production changes**

```kotlin
@Test fun `v3 profile migrates movement disabled and camera rotation unknown`() {
    val profile = ProfileJsonCodec.decode("""{"version":3,"camera":{"centerX":0.8}}""")
    assertFalse(profile.physicalMovement.enabled)
    assertNull(profile.cameraZone.mappedDisplayRotation)
}

@Test fun `v4 movement survives round trip`() {
    val original = ControlProfile(
        physicalMovement = PhysicalMovementConfig(
            enabled = true,
            zone = MovementZone(.2f, .72f, .12f, DisplayRotation.ROTATION_90),
        ),
    )
    val decoded = ProfileJsonCodec.decode(ProfileJsonCodec.encode(original).toString())
    assertEquals(original.physicalMovement, decoded.physicalMovement)
}
```

- [ ] **Step 2: Record deferred red verification**

Run after compilation authorization:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.data.ProfileJsonCodecTest" --no-daemon --max-workers=1
```

Expected before implementation: compilation failure because the new types and properties do not exist.

- [ ] **Step 3: Add exact domain types**

```kotlin
enum class DisplayRotation(val surfaceValue: Int) {
    ROTATION_0(Surface.ROTATION_0), ROTATION_90(Surface.ROTATION_90),
    ROTATION_180(Surface.ROTATION_180), ROTATION_270(Surface.ROTATION_270);
}

enum class PhysicalMovementState { STATIONARY, FORWARD, BACKWARD }

data class MovementZone(
    val centerX: Float = .2f,
    val centerY: Float = .72f,
    val radius: Float = .12f,
    val mappedDisplayRotation: DisplayRotation? = null,
)

data class PhysicalMovementConfig(
    val enabled: Boolean = false,
    val forwardEnabled: Boolean = true,
    val backwardEnabled: Boolean = true,
    val threshold: Float = .35f,
    val sensitivity: Float = 1f,
    val minimumActiveMs: Long = 180L,
    val stopTimeoutMs: Long = 450L,
    val joystickStrength: Float = .85f,
    val zone: MovementZone = MovementZone(),
)

enum class SessionStatus { STOPPED, PAUSED, WAITING_ACCESSIBILITY, WAITING_SENSOR, CALIBRATING, ACTIVE, ERROR }
enum class SessionError { NO_SENSOR_AVAILABLE, SENSOR_START_FAILED, PHYSICAL_SENSOR_UNAVAILABLE }
```

Extend the existing `ScreenZone` with
`mappedDisplayRotation: DisplayRotation?` and keep the `ScreenZone` name in all
production and test code. Increment JSON schema to 4, decode versions 1-3 with
null mapped rotation, and omit obsolete zero angles and synthetic-tilt fields
from new JSON.

- [ ] **Step 4: Add sanitizers and runtime StateFlows**

Clamp normalized centers to `0f..1f`, radius to `.01f..50f`, thresholds to
finite non-negative ranges, strengths to `0f..1f`, and timeouts to
`50L..10_000L`. Add `sessionStatus`, `sessionError`, and
`physicalMovementState` to `RuntimeState`.

- [ ] **Step 5: Record deferred green verification and commit**

Expected after authorization: the codec test passes.

```powershell
git add app/src/main/java/com/gyrobridge/app/domain app/src/main/java/com/gyrobridge/app/data/profile/ProfileJsonCodec.kt app/src/main/java/com/gyrobridge/app/core/AppGraph.kt app/src/test/java/com/gyrobridge/app/data/ProfileJsonCodecTest.kt
git commit -m "feat: add movement profile schema and session states"
```

---

### Task 2: Rotation-Aware Coordinate Mapping

**Files:**
- Modify: `app/src/main/java/com/gyrobridge/app/domain/mapper/ScreenCoordinateMapper.kt`
- Test: `app/src/test/java/com/gyrobridge/app/domain/ScreenCoordinateMapperTest.kt`

**Interfaces:**
- Consumes: `DisplayRotation`, `ScreenZone`, `MovementZone` from Task 1.
- Produces: `toCurrentRotation(point, mapped, current)`, `cameraToPixels(...)`, `movementToPixels(...)`, `PixelCircle`.

- [ ] **Step 1: Add exhaustive rotation tests**

```kotlin
@Test fun `point rotates from 90 to 270`() {
    assertEquals(
        .2f to .3f,
        ScreenCoordinateMapper.rotatePoint(.8f, .7f, DisplayRotation.ROTATION_90, DisplayRotation.ROTATION_270),
    )
}

@Test fun `camera width and height swap on quarter turn`() {
    val zone = ScreenZone(.75f, .5f, .4f, .2f, mappedDisplayRotation = DisplayRotation.ROTATION_0)
    val pixels = ScreenCoordinateMapper.cameraToPixels(zone, 2400, 1080, DisplayRotation.ROTATION_90)
    assertEquals(.2f * 2400, pixels.widthPx, 1f)
    assertEquals(.4f * 1080, pixels.heightPx, 1f)
}
```

Cover `0->90`, `90->180`, `180->270`, `270->0`, `90->270`, legacy null
rotation, and both zone types.

- [ ] **Step 2: Record deferred red verification**

Run after authorization:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.domain.ScreenCoordinateMapperTest" --no-daemon --max-workers=1
```

Expected before implementation: missing rotation-aware mapper APIs.

- [ ] **Step 3: Implement one delta-rotation transform**

Normalize the quarter-turn difference into `0..3` and apply exactly one of:

```text
0: (x, y)
1: (1-y, x)
2: (1-x, 1-y)
3: (y, 1-x)
```

For camera zones, swap normalized width/height on odd differences. For legacy
null rotation, preserve coordinates and dimensions exactly as the existing app.

- [ ] **Step 4: Replace callers of raw `toPixels`**

All scheduler and mapper callers must pass the current `DisplayRotation`.
Remove `CoordinateMapper.rotatedNormalized` after every caller uses the central
API.

- [ ] **Step 5: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/domain/mapper/ScreenCoordinateMapper.kt app/src/test/java/com/gyrobridge/app/domain/ScreenCoordinateMapperTest.kt
git commit -m "fix: map control zones across display rotations"
```

---

### Task 3: Single-Reference Orientation and Calibration

**Files:**
- Modify: `app/src/main/java/com/gyrobridge/app/sensor/OrientationProcessor.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/sensor/SensorEngine.kt`
- Replace: `app/src/main/java/com/gyrobridge/app/sensor/SensorCalibration.kt`
- Create: `app/src/main/java/com/gyrobridge/app/sensor/CalibrationController.kt`
- Test: `app/src/test/java/com/gyrobridge/app/sensor/OrientationProcessorTest.kt`
- Test: `app/src/test/java/com/gyrobridge/app/sensor/CalibrationControllerTest.kt`

**Interfaces:**
- Produces: `OrientationProcessor.captureReference(): Boolean`, `hasReference()`, `onDisplayRotationChanged(...)`, `CalibrationController.onSample(...)`.
- Consumes: `DisplayRotation` and sensor vectors.

- [ ] **Step 1: Add matrix/reference and settling tests**

Tests must feed deterministic rotation matrices through an internal
`processRotationMatrix(matrix, rotation, timestamp)` seam so JVM tests do not
depend on `SensorManager` native behavior.

```kotlin
@Test fun `display change preserves reference and emits zero transition delta`() {
    val processor = OrientationProcessor()
    processor.processRotationMatrix(identity, ROTATION_0, 1_000_000)
    assertTrue(processor.captureReference())
    processor.processRotationMatrix(yaw10, ROTATION_0, 2_000_000)
    val transition = processor.processRotationMatrix(yaw10, ROTATION_90, 3_000_000)
    assertTrue(processor.hasReference())
    assertEquals(0f, transition.deltaYaw, .001f)
    assertEquals(0f, transition.deltaPitch, .001f)
}

@Test fun `movement restarts settling clock`() {
    val controller = CalibrationController(stableForNanos = 300_000_000)
    controller.begin(0)
    controller.onSample(stable, 200_000_000)
    controller.onSample(moving, 250_000_000)
    assertFalse(controller.onSample(stable, 400_000_000).captureReference)
    assertTrue(controller.onSample(stable, 560_000_000).captureReference)
}
```

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.sensor.OrientationProcessorTest" --tests "com.gyrobridge.app.sensor.CalibrationControllerTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Make the device-to-world matrix canonical**

Store the latest unremapped device matrix and the captured reference matrix.
Apply the same current display basis when extracting relative view axes. On
display change, preserve both matrices and clear only the previous output pose
so the transition sample emits no delta.

- [ ] **Step 4: Repair gyroscope fallback**

Integrate device-axis angular velocity into a normalized quaternion, convert it
to the canonical matrix, and feed the same relative-pose path. Estimate bias
only while stationary and only when migrated bias correction is enabled.

- [ ] **Step 5: Replace angle subtraction with calibration state**

Delete `zeroYaw/Pitch/Roll`, `centered(sample)`, and persisted center behavior.
`CalibrationController` returns immutable decisions:

```kotlin
data class CalibrationDecision(
    val phase: CalibrationPhase,
    val captureReference: Boolean = false,
    val error: CalibrationError? = null,
)
```

Lock display only from `begin()` until capture/abort. Unlocking must not reset
the reference.

- [ ] **Step 6: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/sensor app/src/test/java/com/gyrobridge/app/sensor
git commit -m "fix: preserve calibration across display rotation"
```

---

### Task 4: Rate-Independent Camera Motion

**Files:**
- Modify: `app/src/main/java/com/gyrobridge/app/sensor/MotionPipeline.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/sensor/MotionFilter.kt`
- Test: `app/src/test/java/com/gyrobridge/app/sensor/MotionPipelineTest.kt`

**Interfaces:**
- Consumes: `OrientationSample` delta and angular velocity.
- Produces: frequency-stable `MotionOutput` and `reset()`.

- [ ] **Step 1: Add frequency-equivalence tests**

Generate the same 20-degree movement over one second at 50, 100, and 200 Hz,
sum `dx`, and assert each result differs from the 100 Hz baseline by at most
10 percent. Add direction tests for yaw/pitch and explicit inversion.

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.sensor.MotionPipelineTest" --no-daemon --max-workers=1
```

Expected before implementation: current per-sample degree deadzone produces
frequency-dependent totals.

- [ ] **Step 3: Implement per-window angular accumulation**

Accumulate signed degree deltas until the configured deadzone is crossed, emit
the excess while movement continues, and reset hysteresis after direction
change/rest. Apply non-linear response to
`abs(degrees)/degreesForMaxMovement`, then restore the degree scale before
pixels-per-degree and sensitivity.

- [ ] **Step 4: Keep time-based output caps**

Use sensor timestamp `dt` for `maxPixelsPerSecond`; never infer rate from sample
count. Reset accumulators and filters together.

- [ ] **Step 5: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/sensor/MotionPipeline.kt app/src/main/java/com/gyrobridge/app/sensor/MotionFilter.kt app/src/test/java/com/gyrobridge/app/sensor/MotionPipelineTest.kt
git commit -m "fix: make camera response independent of sensor rate"
```

---

### Task 5: Physical Movement Detection

**Files:**
- Create: `app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementDetector.kt`
- Create: `app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementSensor.kt`
- Test: `app/src/test/java/com/gyrobridge/app/sensor/PhysicalMovementDetectorTest.kt`

**Interfaces:**
- Consumes: `PhysicalMovementConfig`, projected forward acceleration, optional step event, timestamp.
- Produces: `PhysicalMovementOutput(state, confidence, forwardSignal, timestampNanos)`.

- [ ] **Step 1: Add deterministic detector tests**

Provide helper sequences for zero-mean noise, hand tremor, short impulse,
forward walking pulses, backward pulses, and stopping. Assert only sustained
walking reaches active states and timeout returns to stationary.

```kotlin
@Test fun `forward pulses activate then timeout to stationary`() {
    val detector = PhysicalMovementDetector(config)
    forwardWalk.forEach { detector.process(it) }
    assertEquals(FORWARD, detector.output.state)
    detector.process(sample(at = forwardWalk.last().timestampNanos + config.stopTimeoutMs * 1_000_000 + 1))
    assertEquals(STATIONARY, detector.output.state)
}
```

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.sensor.PhysicalMovementDetectorTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement fixed-memory classifier**

Use a fixed-size ring window, low-pass baseline removal, projected signal
energy, signed impulse, minimum-active time, enter/exit hysteresis, confidence,
and stop timeout. Optional step events raise confidence but never choose
direction alone.

```kotlin
data class PhysicalMovementInput(
    val forwardAcceleration: Float,
    val stepDetected: Boolean,
    val timestampNanos: Long,
)

data class PhysicalMovementOutput(
    val state: PhysicalMovementState = PhysicalMovementState.STATIONARY,
    val confidence: Float = 0f,
    val forwardSignal: Float = 0f,
    val timestampNanos: Long = 0L,
)

fun process(input: PhysicalMovementInput): PhysicalMovementOutput
```

- [ ] **Step 4: Implement Android sensor adapter**

Register `TYPE_LINEAR_ACCELERATION` and optionally `TYPE_STEP_DETECTOR` only
when movement is enabled. Transform the linear vector through the current
orientation/reference frame to a calibrated forward component. Expose
availability separately so missing auxiliary sensors do not stop camera input.

- [ ] **Step 5: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementDetector.kt app/src/main/java/com/gyrobridge/app/sensor/PhysicalMovementSensor.kt app/src/test/java/com/gyrobridge/app/sensor/PhysicalMovementDetectorTest.kt
git commit -m "feat: detect forward and backward walking intent"
```

---

### Task 6: Pure Two-Contact Planner and Persistent Android Strokes

**Files:**
- Create: `app/src/main/java/com/gyrobridge/app/gesture/GestureContactPlanner.kt`
- Create: `app/src/main/java/com/gyrobridge/app/gesture/GestureDispatchAdapter.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/gesture/GestureState.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/gesture/GestureScheduler.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/gesture/GestureDispatcherRegistry.kt`
- Remove: `app/src/main/java/com/gyrobridge/app/gesture/MultiTouchGestureComposer.kt`
- Test: `app/src/test/java/com/gyrobridge/app/gesture/GestureContactPlannerTest.kt`

**Interfaces:**
- Consumes: camera delta, movement state/strength, mapped pixel zones, tick time.
- Produces: `GestureFrame(generation, dispatchId, contacts)` and Android callbacks tagged with both identifiers.

- [ ] **Step 1: Add planner state-machine tests**

Cover camera only, movement only, both, camera boundary restart, stationary
release, cancel/resume, and stale callback.

```kotlin
@Test fun `stale callback cannot clear newer generation`() {
    val planner = GestureContactPlanner()
    val old = planner.next(cameraRight, FORWARD)
    planner.cancelAll()
    val current = planner.next(cameraRight, FORWARD)
    planner.onDispatchCompleted(old.generation, old.dispatchId)
    assertEquals(current.generation, planner.snapshot.generation)
    assertTrue(planner.snapshot.cameraActive)
    assertTrue(planner.snapshot.movementActive)
}
```

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.gesture.GestureContactPlannerTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement immutable planner outputs**

Define `ContactId.CAMERA` and `ContactId.MOVEMENT`, contact phases `START`,
`CONTINUE`, `END`, and a monotonically increasing generation/dispatch ID.
Keep at most one frame in flight and accumulate camera movement while busy.

- [ ] **Step 4: Implement Android adapter**

For each contact, create a new `StrokeDescription` on `START`, use the previous
stroke's `continueStroke()` on `CONTINUE` and `END`, set `willContinue=false`
only on end, and put every active contact in the same `GestureDescription`.

- [ ] **Step 5: Rebuild scheduler around planner and adapter**

Remove segmented synthetic-tilt joystick behavior. Ensure movement can remain
at one target while camera segments continue. On pause/configure/cancel,
increment generation and ignore callbacks whose generation or dispatch ID does
not match.

- [ ] **Step 6: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/gesture app/src/test/java/com/gyrobridge/app/gesture
git commit -m "feat: dispatch persistent camera and movement contacts"
```

---

### Task 7: Service, Accessibility, and Explicit Session Integration

**Files:**
- Modify: `app/src/main/java/com/gyrobridge/app/service/GyroForegroundService.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/accessibility/GyroAccessibilityService.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/gesture/GestureDispatcherRegistry.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/core/AppGraph.kt`
- Create: `app/src/main/java/com/gyrobridge/app/service/SessionController.kt`
- Create: `app/src/main/java/com/gyrobridge/app/telemetry/TestTelemetryPublisher.kt`
- Test: `app/src/test/java/com/gyrobridge/app/service/SessionControllerTest.kt`

**Interfaces:**
- Consumes: orientation, camera output, physical movement output, connection state.
- Produces: one coherent runtime/session state and `GestureScheduler.update(camera, movement)`.

- [ ] **Step 1: Extract and test pure session decisions**

Create a small `SessionController` whose events include `Start`,
`AccessibilityConnected`, `SensorStarted`, `ExplicitResume`,
`CalibrationCaptured`, `Pause`, and `Failure`. Assert no path reaches `ACTIVE`
without explicit resume, sensor success, accessibility, and required reference.

```kotlin
sealed interface SessionEvent {
    data object Start : SessionEvent
    data class AccessibilityChanged(val available: Boolean) : SessionEvent
    data class SensorStarted(val success: Boolean) : SessionEvent
    data class ExplicitResume(val autoCalibrate: Boolean) : SessionEvent
    data object CalibrationCaptured : SessionEvent
    data object Pause : SessionEvent
    data class Failure(val error: SessionError) : SessionEvent
}

data class SessionSnapshot(
    val status: SessionStatus = SessionStatus.STOPPED,
    val error: SessionError? = null,
    val accessibilityAvailable: Boolean = false,
    val sensorAvailable: Boolean = false,
    val hasReference: Boolean = false,
    val resumeRequested: Boolean = false,
)

fun onEvent(event: SessionEvent): SessionSnapshot
```

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.gyrobridge.app.service.SessionControllerTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Integrate state without hidden calibration**

Start foreground service paused. With `autoCalibrate=true`, explicit resume
enters calibration and activates after capture. With false, explicit resume
activates only when the session already has a reference; otherwise show that
manual calibration is required. Sensor start false becomes
`NO_SENSOR_AVAILABLE` or `SENSOR_START_FAILED`, never active.

- [ ] **Step 4: Integrate physical sensor and scheduler**

Run physical sensor only when enabled, publish its state, and feed both camera
and movement outputs to the scheduler. Missing physical sensors disable only
movement and produce a visible nonfatal state.

- [ ] **Step 5: Harden AccessibilityService lifecycle**

Filter GyroBridge, overlay, DragTest telemetry, and system windows before
automatic package switching. On disconnect, cancel contacts and move to
`WAITING_ACCESSIBILITY`; on reconnect, reconfigure but do not auto-resume.

- [ ] **Step 6: Add protected low-rate telemetry**

Send an explicit broadcast to `com.gyrobridge.dragtest` at no more than 5 Hz
with sensor name, display rotation, yaw, pitch, session status, accessibility,
movement state, and linear acceleration. Protect the receiver using
`com.gyrobridge.permission.TEST_TELEMETRY` with `signature` protection.

- [ ] **Step 7: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/service app/src/main/java/com/gyrobridge/app/accessibility app/src/main/java/com/gyrobridge/app/gesture/GestureDispatcherRegistry.kt app/src/main/java/com/gyrobridge/app/core/AppGraph.kt app/src/main/java/com/gyrobridge/app/telemetry app/src/test/java/com/gyrobridge/app/service
git commit -m "feat: integrate explicit gyro and movement session states"
```

---

### Task 8: Profile UI and Dual Mapping Overlay

**Files:**
- Modify: `app/src/main/java/com/gyrobridge/app/ui/GyroBridgeApp.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/ui/AppViewModel.kt`
- Modify: `app/src/main/java/com/gyrobridge/app/overlay/OverlayService.kt`
- Test: `app/src/androidTest/java/com/gyrobridge/app/GyroBridgeUiTest.kt`

**Interfaces:**
- Consumes: runtime/session state, `PhysicalMovementConfig`, rotation-aware zones.
- Produces: explicit user actions and persisted dual-zone mapper.

- [ ] **Step 1: Add UI assertions**

Add Compose assertions for `Movimento físico`, `Área da câmera`, `Área de
movimento`, `Frente`, `Trás`, and visible waiting/error statuses. Preserve the
existing Android back-navigation regression test.

- [ ] **Step 2: Record deferred red verification**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --max-workers=1
```

- [ ] **Step 3: Add movement settings**

Expose enabled, forward/backward, threshold, sensitivity, stop timeout, and
joystick strength. Remove old interaction-mode and tilt-joystick controls.

- [ ] **Step 4: Build a dual mapper model and view**

Refactor `MappingOverlayView` so selection is `CAMERA` or `MOVEMENT`. Draw the
camera as translucent blue rectangle with cross/arrows and movement as green
circle with center, `Frente`, and `Trás`. Drag/resize only the selected zone and
save both with current display rotation.

- [ ] **Step 5: Render honest session states**

Show `Gyro pronto`, `Calibrando`, `Mantenha o aparelho parado`,
`Aguardando acessibilidade`, `Sensor indisponível`, `Movimento indisponível`,
and `Movimento ativo`. Overlay buttons remain compact and never imply ON while
paused/waiting/error.

- [ ] **Step 6: Record deferred green verification and commit**

```powershell
git add app/src/main/java/com/gyrobridge/app/ui app/src/main/java/com/gyrobridge/app/overlay/OverlayService.kt app/src/androidTest/java/com/gyrobridge/app/GyroBridgeUiTest.kt
git commit -m "feat: map camera and physical movement controls"
```

---

### Task 9: Independent Three.js DragTest Application

**Files:**
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`
- Create: `dragtest/build.gradle.kts`
- Create: `dragtest/src/main/AndroidManifest.xml`
- Create: `dragtest/src/main/java/com/gyrobridge/dragtest/MainActivity.java`
- Create: `dragtest/src/main/java/com/gyrobridge/dragtest/TelemetryReceiver.java`
- Create: `dragtest/src/main/res/values/styles.xml`
- Create: `dragtest/src/main/assets/index.html`
- Create: `dragtest/src/main/assets/main.js`
- Create: `dragtest/src/main/assets/game.bundle.js`
- Create: `dragtest/src/main/assets/three.core.min.js`
- Create: `dragtest/src/main/assets/three.module.min.js`

**Interfaces:**
- Consumes: Android pointer events through WebView and optional protected telemetry broadcast.
- Produces: visual camera, joystick, pointer lifecycle, and telemetry validation.

- [ ] **Step 1: Version and include the module**

Remove `/dragtest/` from `.gitignore`, add `include(":dragtest")`, and configure
an Android application with compile/target 37, minSdk 26, Java 17,
`applicationId="com.gyrobridge.dragtest"`, version 1/`1.0.0`, and no network
permission.

- [ ] **Step 2: Restore the exact recovered assets**

Copy the five files from
`C:\Users\kirit\Desktop\gyro-dragtest-recovery\apk-unpacked\assets` into the
module before editing the scene. Preserve a SHA-256 manifest in
`dragtest/RECOVERY_SHA256.txt` with the hashes captured during extraction.

- [ ] **Step 3: Create the lightweight WebView host**

Enable JavaScript, local file access, hardware acceleration, fullscreen
immersive layout, local `file:///android_asset/index.html`, console logging,
and teardown. Do not enable content access or network loading.

- [ ] **Step 4: Update the Three.js validation scene**

Classify active pointers by role after initial region: movement circle on the
left and camera elsewhere. Maintain independent pointer IDs and show
`DOWN/MOVE/UP`, active state, movement vector, yaw, pitch, FPS, and maximum move
gap. Ensure simultaneous pointers update both world movement and view angles.

- [ ] **Step 5: Receive optional GyroBridge telemetry**

Register a signature-protected receiver and forward sanitized scalar values to
JavaScript via `evaluateJavascript()` on the UI thread. Show unavailable rather
than fabricating state when GyroBridge is absent.

- [ ] **Step 6: Record deferred build verification and commit**

Run only after authorization:

```powershell
.\gradlew.bat :dragtest:assembleDebug --no-daemon --max-workers=1
```

Expected: `dragtest/build/outputs/apk/debug/dragtest-debug.apk`.

```powershell
git add .gitignore settings.gradle.kts dragtest
git commit -m "feat: add independent Three.js gyro validation app"
```

---

### Task 10: Documentation, Static Review, and Deferred Verification Matrix

**Files:**
- Modify: `README.md`
- Review: all modified production and test files

**Interfaces:**
- Consumes: completed implementation.
- Produces: accurate documentation and a command/evidence checklist.

- [ ] **Step 1: Update README to actual architecture**

Document session calibration, rotation-aware camera/movement mapping, physical
intent limitations, persistent multitouch, DragTest use, and explicitly state
that movement is not 6DoF tracking.

- [ ] **Step 2: Remove only proven dead code**

Use `rg` to verify no callers remain before removing old zero-angle
calibration, synthetic tilt joystick, obsolete config fields, duplicate
rotation formulas, and stale comments.

- [ ] **Step 3: Perform allowed static checks without compilation**

```powershell
git diff --check
git status -sb
rg -n "TO.DO|FIX.ME|SensorCalibration|zeroYaw|zeroPitch|zeroRoll|MultiTouchGestureComposer|rotatedNormalized" app dragtest README.md
```

Interpret matches manually; test method names or migration keys may be valid.

- [ ] **Step 4: Commit documentation/static cleanup**

```powershell
git add README.md app dragtest
git commit -m "docs: explain physical movement and dual-app validation"
```

- [ ] **Step 5: Execute full verification only after user authorization**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1
.\gradlew.bat :dragtest:assembleDebug --no-daemon --max-workers=1
.\gradlew.bat :app:lintDebug :dragtest:lintDebug --no-daemon --max-workers=1
```

Then use `adb install -r` for both APKs, preserving permissions and app data.
Validate session-paused startup, explicit calibration/start, all four rotations,
camera only, movement only, simultaneous pointers, cancellation, and stop.
Record each command and observed result separately; do not equate build success
with working in-game gestures.
