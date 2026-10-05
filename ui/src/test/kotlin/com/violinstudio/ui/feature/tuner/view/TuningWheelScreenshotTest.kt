package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h400dp-xxhdpi")
class TuningWheelScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(cents: Double?, maxCents: Int, name: String) {
        compose.setContent {
            ViolinStudioTheme {
                Column {
                    val inTune = cents != null && kotlin.math.abs(cents) <= 2.5
                    TuningWheel(cents, maxCents, inTune, description = "")
                    overflowLabel(cents ?: 0.0, maxCents)?.let { Text(it) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun inTune() = capture(1.0, 50, "tuner_wheel_in_tune")

    @Test fun flatOverflow() = capture(-87.0, 50, "tuner_wheel_overflow")

    @Test fun wideScale() = capture(30.0, 200, "tuner_wheel_wide")
}
