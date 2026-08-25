package com.gyrobridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gyrobridge.app.ui.AppViewModel
import com.gyrobridge.app.ui.GyroBridgeApp
import com.gyrobridge.app.ui.theme.GyroBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { GyroBridgeTheme { GyroBridgeApp(viewModel<AppViewModel>()) } }
    }
}
