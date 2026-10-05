package com.violinstudio.ui.feature.metronome.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeError
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class MetronomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(state: MetronomeState, name: String) {
        compose.setContent { ViolinStudioTheme { MetronomeScreen(state, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(MetronomeState(), "metronome_idle")

    @Test fun playingAccentBeat() =
        capture(MetronomeState(isPlaying = true, tick = BeatTick(4, 0, true)), "metronome_playing_accent")

    @Test fun sixEight() = capture(
        MetronomeState(signature = TimeSignature.SIX_EIGHT, isPlaying = true, tick = BeatTick(2, 2, false)),
        "metronome_six_eight"
    )

    @Test fun error() = capture(MetronomeState(error = MetronomeError.OUTPUT_UNAVAILABLE), "metronome_error")
}
