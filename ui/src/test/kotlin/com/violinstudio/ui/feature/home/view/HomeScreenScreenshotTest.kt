package com.violinstudio.ui.feature.home.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.home.viewmodel.HealthStatus
import com.violinstudio.ui.feature.home.viewmodel.HomeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(state: HomeState, name: String) {
        compose.setContent { ViolinStudioTheme { HomeScreen(state = state, onIntent = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(HomeState(HealthStatus.Idle), "home_idle")

    @Test fun loading() {
        compose.mainClock.autoAdvance = false
        capture(HomeState(HealthStatus.Loading), "home_loading")
    }

    @Test fun ok() = capture(HomeState(HealthStatus.Ok("0.1.0")), "home_ok")

    @Test fun error() = capture(HomeState(HealthStatus.Error("Sin conexión")), "home_error")
}
