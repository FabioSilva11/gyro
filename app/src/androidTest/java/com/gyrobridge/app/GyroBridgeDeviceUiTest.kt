package com.gyrobridge.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GyroBridgeDeviceUiTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun profilesNavigationIsVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Perfis")), 5_000))
            device.findObject(By.text("Perfis")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Importar JSON")), 5_000))
        }
    }

    @Test fun gyroPlaygroundOpensWithLivePipeline() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Gyro Playground")), 5_000))
            device.findObject(By.text("Gyro Playground")).click()
            assertTrue(device.wait(Until.hasObject(By.text("RECENTRALIZAR MIRA")), 5_000))
        }
    }
}
