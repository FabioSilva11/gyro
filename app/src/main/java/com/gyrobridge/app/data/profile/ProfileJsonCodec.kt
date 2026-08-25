package com.gyrobridge.app.data.profile

import com.gyrobridge.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

object ProfileJsonCodec {
    fun encodeProfiles(profiles: List<ControlProfile>): String = JSONArray().apply {
        profiles.forEach { put(encode(it)) }
    }.toString()

    fun decodeProfiles(json: String): List<ControlProfile> = runCatching {
        val array = JSONArray(json)
        buildList { for (i in 0 until array.length()) add(decode(array.getJSONObject(i))) }
    }.getOrDefault(emptyList())

    fun encode(profile: ControlProfile): JSONObject = profile.sanitized().let { p ->
        JSONObject().apply {
            put("version", 3); put("id", p.id); put("name", p.name); put("packageName", p.packageName)
            put("appLabel", p.appLabel); put("enabled", p.enabled); put("autoActivate", p.autoActivate)
            put("sensor", JSONObject().apply { put("rate", p.sensorConfig.rate.name); put("customHz", p.sensorConfig.customHz); put("preferredSensorType", p.sensorConfig.preferredSensorType) })
            put("axis", JSONObject().apply { put("xSource", p.axisConfig.xSource.name); put("ySource", p.axisConfig.ySource.name); put("invertX", p.axisConfig.invertX); put("invertY", p.axisConfig.invertY); put("invertRoll", p.axisConfig.invertRoll) })
            put("calibration", JSONObject().apply { put("zeroYaw", p.calibrationConfig.zeroYaw); put("zeroPitch", p.calibrationConfig.zeroPitch); put("zeroRoll", p.calibrationConfig.zeroRoll); put("autoCalibrate", p.calibrationConfig.autoCalibrate); put("recalibrateOnAppChange", p.calibrationConfig.recalibrateOnAppChange); put("autoRecenter", p.calibrationConfig.autoRecenter); put("autoRecenterSeconds", p.calibrationConfig.autoRecenterSeconds); put("driftCompensation", p.calibrationConfig.driftCompensation); put("biasCorrection", p.calibrationConfig.biasCorrection); put("restTolerance", p.calibrationConfig.restTolerance) })
            put("filter", JSONObject().apply { put("xFilter", p.filterConfig.xFilter.name); put("yFilter", p.filterConfig.yFilter.name); put("alpha", p.filterConfig.alpha); put("minCutoff", p.filterConfig.minCutoff); put("beta", p.filterConfig.beta); put("dCutoff", p.filterConfig.dCutoff); put("smoothing", p.filterConfig.smoothing) })
            put("sensitivity", JSONObject().apply { put("horizontal", p.sensitivityConfig.horizontal); put("vertical", p.sensitivityConfig.vertical); put("linked", p.sensitivityConfig.linked); put("pixelsPerDegree", p.sensitivityConfig.pixelsPerDegree); put("degreesForMaxMovement", p.sensitivityConfig.degreesForMaxMovement); put("globalMultiplier", p.sensitivityConfig.globalMultiplier); put("horizontalDeadzone", p.sensitivityConfig.horizontalDeadzone); put("verticalDeadzone", p.sensitivityConfig.verticalDeadzone); put("deadzoneUnit", p.sensitivityConfig.deadzoneUnit.name); put("curve", p.sensitivityConfig.curve.name); put("gamma", p.sensitivityConfig.gamma); put("acceleration", p.sensitivityConfig.acceleration.name); put("accelerationThreshold", p.sensitivityConfig.accelerationThreshold); put("accelerationMultiplier", p.sensitivityConfig.accelerationMultiplier); put("accelerationMaximum", p.sensitivityConfig.accelerationMaximum) })
            put("gesture", JSONObject().apply { put("mode", p.gestureConfig.mode.name); put("interactionMode", p.gestureConfig.interactionMode.name); put("targetRate", p.gestureConfig.targetRate); put("durationMs", p.gestureConfig.durationMs); put("maxXPerUpdate", p.gestureConfig.maxXPerUpdate); put("maxYPerUpdate", p.gestureConfig.maxYPerUpdate); put("maxAngularVelocity", p.gestureConfig.maxAngularVelocity); put("maxPixelsPerSecond", p.gestureConfig.maxPixelsPerSecond); put("maxSwipeDistance", p.gestureConfig.maxSwipeDistance); put("queueCapacity", p.gestureConfig.queueCapacity) })
            put("camera", zoneJson(p.cameraZone))
            put("joystick", JSONObject().apply { put("enabled", p.joystickConfig.enabled); put("centerX", p.joystickConfig.centerX); put("centerY", p.joystickConfig.centerY); put("radius", p.joystickConfig.radius); put("tiltThreshold", p.joystickConfig.tiltThreshold); put("maximumTilt", p.joystickConfig.maximumTilt); put("sensitivity", p.joystickConfig.sensitivity); put("deadzone", p.joystickConfig.deadzone); put("curve", p.joystickConfig.curve.name); put("invertX", p.joystickConfig.invertX); put("invertY", p.joystickConfig.invertY) })
            put("overlay", JSONObject().apply { put("enabled", p.overlayConfig.enabled); put("opacity", p.overlayConfig.opacity); put("scale", p.overlayConfig.scale); put("normalizedX", p.overlayConfig.normalizedX); put("normalizedY", p.overlayConfig.normalizedY); put("locked", p.overlayConfig.locked); put("indicatorOnly", p.overlayConfig.indicatorOnly) })
            put("autoDetect", JSONObject().apply { put("enabled", p.autoDetectConfig.enabled); put("scanRegionX", p.autoDetectConfig.scanRegionX); put("scanRegionY", p.autoDetectConfig.scanRegionY); put("scanRegionWidth", p.autoDetectConfig.scanRegionWidth); put("scanRegionHeight", p.autoDetectConfig.scanRegionHeight); put("clickX", p.autoDetectConfig.clickX); put("clickY", p.autoDetectConfig.clickY); put("hueMin", p.autoDetectConfig.hueMin); put("hueMax", p.autoDetectConfig.hueMax); put("saturationMin", p.autoDetectConfig.saturationMin); put("valueMin", p.autoDetectConfig.valueMin); put("pixelThresholdPercent", p.autoDetectConfig.pixelThresholdPercent); put("cooldownMs", p.autoDetectConfig.cooldownMs); put("debounceCount", p.autoDetectConfig.debounceCount); put("captureIntervalMs", p.autoDetectConfig.captureIntervalMs); put("edgeMarginPx", p.autoDetectConfig.edgeMarginPx) })
        }
    }

    fun decode(json: String): ControlProfile = decode(JSONObject(json))

    fun decode(root: JSONObject): ControlProfile {
        val schemaVersion = root.optInt("version", 1)
        val s = root.optJSONObject("sensor") ?: JSONObject(); val a = root.optJSONObject("axis") ?: JSONObject()
        val c = root.optJSONObject("calibration") ?: JSONObject(); val f = root.optJSONObject("filter") ?: JSONObject()
        val n = root.optJSONObject("sensitivity") ?: JSONObject(); val g = root.optJSONObject("gesture") ?: JSONObject()
        val z = root.optJSONObject("camera") ?: JSONObject(); val j = root.optJSONObject("joystick") ?: JSONObject()
        val o = root.optJSONObject("overlay") ?: JSONObject(); val ad = root.optJSONObject("autoDetect") ?: JSONObject()
        return ControlProfile(
            id = root.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, name = root.optString("name", "Perfil"),
            packageName = root.optNullableString("packageName"), appLabel = root.optNullableString("appLabel"),
            enabled = root.optBoolean("enabled", true), autoActivate = root.optBoolean("autoActivate", true),
            sensorConfig = SensorConfig(s.enum("rate", SensorRate.GAME), s.optInt("customHz", 120), s.optNullableInt("preferredSensorType")),
            axisConfig = AxisConfig(a.enum("xSource", AxisSource.YAW), a.enum("ySource", AxisSource.PITCH), a.optBoolean("invertX"), a.optBoolean("invertY"), a.optBoolean("invertRoll")),
            calibrationConfig = CalibrationConfig(c.float("zeroYaw"), c.float("zeroPitch"), c.float("zeroRoll"), c.optBoolean("autoCalibrate", true), c.optBoolean("recalibrateOnAppChange"), c.optBoolean("autoRecenter"), c.optInt("autoRecenterSeconds", 30), c.optBoolean("driftCompensation", true), c.optBoolean("biasCorrection", true), c.float("restTolerance", .08f)),
            filterConfig = FilterConfig(f.enum("xFilter", FilterType.ONE_EURO), f.enum("yFilter", FilterType.ONE_EURO), f.float("alpha", .35f), f.float("minCutoff", 1f), f.float("beta", .04f), f.float("dCutoff", 1f), f.float("smoothing", .15f)),
            sensitivityConfig = SensitivityConfig(n.float("horizontal", 8.5f), n.float("vertical", 8.5f), n.optBoolean("linked", true), n.float("pixelsPerDegree", 1f), n.float("degreesForMaxMovement", 45f), n.float("globalMultiplier", 1f), n.float("horizontalDeadzone", .1f), n.float("verticalDeadzone", .1f), n.enum("deadzoneUnit", DeadzoneUnit.DEGREES), n.enum("curve", ResponseCurve.LINEAR), n.float("gamma", 1.2f), n.enum("acceleration", AccelerationMode.OFF), n.float("accelerationThreshold", 2f), n.float("accelerationMultiplier", 1.5f), n.float("accelerationMaximum", 3f)),
            gestureConfig = GestureConfig(if (schemaVersion < 3) GestureMode.CONTINUOUS else g.enum("mode", GestureMode.CONTINUOUS), g.enum("interactionMode", InteractionMode.GYRO_ONLY), g.optInt("targetRate", 45), g.optLong("durationMs", 20), g.float("maxXPerUpdate", 80f), g.float("maxYPerUpdate", 80f), g.float("maxAngularVelocity", 720f), g.float("maxPixelsPerSecond", 5000f), g.float("maxSwipeDistance", 160f), g.optInt("queueCapacity", 4)),
            cameraZone = ScreenZone(z.float("centerX", .72f), z.float("centerY", .5f), z.float("width", .42f), z.float("height", .58f), z.float("boundaryMargin", .04f), z.float("autoRecenterThreshold", .85f)),
            joystickConfig = JoystickConfig(j.optBoolean("enabled"), j.float("centerX", .2f), j.float("centerY", .72f), j.float("radius", .12f), j.float("tiltThreshold", 3f), j.float("maximumTilt", 30f), j.float("sensitivity", 1f), j.float("deadzone", 2f), j.enum("curve", ResponseCurve.LINEAR), j.optBoolean("invertX"), j.optBoolean("invertY")),
            overlayConfig = OverlayConfig(if (schemaVersion < 2) true else o.optBoolean("enabled", true), o.float("opacity", .88f), o.float("scale", 1f), o.float("normalizedX", .02f), o.float("normalizedY", .12f), o.optBoolean("locked"), o.optBoolean("indicatorOnly")),
            autoDetectConfig = AutoDetectConfig(ad.optBoolean("enabled"), ad.float("scanRegionX", .35f), ad.float("scanRegionY", .15f), ad.float("scanRegionWidth", .30f), ad.float("scanRegionHeight", .30f), ad.float("clickX", .50f), ad.float("clickY", .50f), ad.float("hueMin", 70f), ad.float("hueMax", 160f), ad.float("saturationMin", .30f), ad.float("valueMin", .20f), ad.float("pixelThresholdPercent", 2f), ad.optLong("cooldownMs", 500), ad.optInt("debounceCount", 3), ad.optLong("captureIntervalMs", 200), ad.optInt("edgeMarginPx", 20)),
        ).sanitized()
    }

    private fun zoneJson(z: ScreenZone) = JSONObject().apply { put("centerX", z.centerX); put("centerY", z.centerY); put("width", z.width); put("height", z.height); put("boundaryMargin", z.boundaryMargin); put("autoRecenterThreshold", z.autoRecenterThreshold) }
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, fallback: T): T = runCatching { enumValueOf<T>(optString(key, fallback.name)) }.getOrDefault(fallback)
    private fun JSONObject.float(key: String, fallback: Float = 0f): Float = optDouble(key, fallback.toDouble()).toFloat().let { if (it.isFinite()) it else fallback }
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
}
