package com.violinstudio.ui.feature.session.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.SessionNavHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Root states drawn through the real host: they must have the theme background, not the window default. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SessionRootScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(session: SessionState, name: String) {
        compose.setContent { ViolinStudioTheme { SessionNavHost(session = session, onSignOut = {}) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun splash() = capture(SessionState.Loading, "session_splash")

    @Test fun offline() = capture(SessionState.Unavailable, "session_offline")
}
