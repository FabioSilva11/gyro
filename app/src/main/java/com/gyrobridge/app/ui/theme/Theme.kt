package com.gyrobridge.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC), secondary = Color(0xFFA5B4FC), tertiary = Color(0xFF5EEAD4),
    background = Color(0xFF08111F), surface = Color(0xFF101827), surfaceVariant = Color(0xFF1E293B),
)
private val LightColors = lightColorScheme(primary = Color(0xFF0369A1), secondary = Color(0xFF4F46E5), tertiary = Color(0xFF0F766E))

@Composable fun GyroBridgeTheme(dynamicColor: Boolean = true, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
