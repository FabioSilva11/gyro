package com.gyrobridge.app.overlay

import android.content.Context
import android.provider.Settings

object OverlayController {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun show(context: Context) { if (canDraw(context)) OverlayService.start(context) }
    fun hide(context: Context) = OverlayService.stop(context)
}
