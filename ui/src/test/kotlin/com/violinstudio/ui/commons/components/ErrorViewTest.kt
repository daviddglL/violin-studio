package com.violinstudio.ui.commons.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ErrorViewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun muestraElMensaje() {
        compose.setContent { ViolinStudioTheme { ErrorView(message = "Sin conexión") } }
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithText("Sin conexión").assertIsDisplayed()
    }
}
