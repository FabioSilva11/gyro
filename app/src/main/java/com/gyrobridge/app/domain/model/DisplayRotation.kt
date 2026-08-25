package com.gyrobridge.app.domain.model

import android.view.Surface

enum class DisplayRotation(val surfaceValue: Int, val quarterTurns: Int) {
    ROTATION_0(Surface.ROTATION_0, 0),
    ROTATION_90(Surface.ROTATION_90, 1),
    ROTATION_180(Surface.ROTATION_180, 2),
    ROTATION_270(Surface.ROTATION_270, 3),
    ;

    companion object {
        fun fromSurface(value: Int): DisplayRotation = entries.firstOrNull { it.surfaceValue == value } ?: ROTATION_0
    }
}
