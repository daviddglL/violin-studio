package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.tuner.viewmodel.ConfigError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class TunerConfigScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val presets = listOf(
        TuningConfiguration("b1", "Barroco", ReferencePitch(415.0), MaxCents(50)),
        TuningConfiguration("c1", "Orquesta", ReferencePitch(442.0), MaxCents(100))
    )

    private fun capture(config: TunerConfig, error: ConfigError?, name: String) {
        compose.setContent { ViolinStudioTheme { Surface { TunerConfigContent(config, error) {} } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun defaults() = capture(TunerConfig(), null, "tuner_config_defaults")

    @Test fun withPresets() = capture(
        TunerConfig(ReferencePitch(415.0), MaxCents(50), presets, "b1"),
        null,
        "tuner_config_presets"
    )

    @Test fun validationError() = capture(TunerConfig(), ConfigError.REFERENCE_PITCH, "tuner_config_error")
}
