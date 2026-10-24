package com.violinstudio.ui.commons

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class KeepScreenOnTest {
    @get:Rule
    val compose = createComposeRule()

    private var enabled by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private lateinit var view: View

    private fun show() = compose.setContent {
        view = LocalView.current
        if (shown) KeepScreenOn(enabled)
    }

    @Test
    fun laPantallaSeQuedaEncendidaSoloMientrasEstaActivo() {
        show()
        compose.waitForIdle()
        assertFalse(view.keepScreenOn)

        enabled = true
        compose.waitForIdle()
        assertTrue(view.keepScreenOn)

        enabled = false
        compose.waitForIdle()
        assertFalse(view.keepScreenOn)
    }

    @Test
    fun alSalirDeLaComposicionSeSuelta() {
        enabled = true
        show()
        compose.waitForIdle()
        assertTrue(view.keepScreenOn)

        shown = false
        compose.waitForIdle()
        assertFalse(view.keepScreenOn)
    }
}
