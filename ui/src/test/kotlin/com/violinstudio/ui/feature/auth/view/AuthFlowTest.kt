package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AuthFlowTest {
    @get:Rule
    val compose = createComposeRule()

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
            reset = { back -> TextButton(back, Modifier.testTag("reset")) { Text("back") } }
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
}
