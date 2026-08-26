package com.gyrobridge.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class GyroBridgeUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun androidBackReturnsToHome() { rule.onNodeWithText("Permissões").performClick();rule.onNodeWithText("AccessibilityService").assertIsDisplayed();rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() };rule.onNodeWithText("Orientação ao vivo").assertIsDisplayed() }

    @Test fun movementControlsAreAvailableInNewProfile() {
        rule.onNodeWithText("Perfis").performClick()
        rule.onNodeWithText("Novo perfil").performClick()
        rule.onNodeWithText("Movimento físico").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Região da câmera").performScrollTo().assertIsDisplayed()
    }
}
