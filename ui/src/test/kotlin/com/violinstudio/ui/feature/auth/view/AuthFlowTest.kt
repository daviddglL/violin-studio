package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AuthFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val left = mutableListOf<AuthScreen>()

    private fun show() = compose.setContent {
        AuthFlow(
            login = { toRegister, toReset ->
                Column {
                    Text("login", Modifier.testTag("login"))
                    TextButton(toRegister, Modifier.testTag("to_register")) { Text("r") }
                    TextButton(toReset, Modifier.testTag("to_reset")) { Text("p") }
                }
            },
            register = { back -> TextButton(back, Modifier.testTag("register")) { Text("back") } },
            reset = { back -> TextButton(back, Modifier.testTag("reset")) { Text("back") } },
            onLeave = { left += it }
        )
    }

    @Test
    fun startsOnLogin() {
        show()
        compose.onNodeWithTag("login").assertIsDisplayed()
    }

    @Test
    fun opensRegisterAndResetFromLoginAndComesBack() {
        show()
        compose.onNodeWithTag("to_register").performClick()
        compose.onNodeWithTag("register").assertIsDisplayed().performClick()
        compose.onNodeWithTag("login").assertIsDisplayed()
        compose.onNodeWithTag("to_reset").performClick()
        compose.onNodeWithTag("reset").assertIsDisplayed().performClick()
        compose.onNodeWithTag("login").assertIsDisplayed()
    }

    @Test
    fun everyScreenIsToldWhenItIsLeftSoItCanForgetTransientState() {
        show()
        compose.onNodeWithTag("to_register").performClick()
        compose.onNodeWithTag("register").performClick()
        compose.onNodeWithTag("to_reset").performClick()
        compose.onNodeWithTag("reset").performClick()
        assertEquals(listOf(AuthScreen.LOGIN, AuthScreen.REGISTER, AuthScreen.LOGIN, AuthScreen.RESET), left)
    }

    @Test
    fun systemBackFromRegisterReturnsToLoginAndLeavesRegister() {
        show()
        compose.onNodeWithTag("to_register").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("register").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf(AuthScreen.LOGIN, AuthScreen.REGISTER), left)
        compose.onNodeWithTag("login").assertIsDisplayed()
    }
}
