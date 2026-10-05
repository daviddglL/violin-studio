package com.violinstudio.ui.feature.tuner.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.tuner.viewmodel.MicState
import com.violinstudio.ui.feature.tuner.viewmodel.TunerError
import com.violinstudio.ui.feature.tuner.viewmodel.TunerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class TunerScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val violin = TunerState(instrument = Instrument.VIOLIN, mic = MicState.GRANTED)

    private fun pitch(cents: Double) = TunerReading.Pitch(440.0, TuningTarget.OpenString(Note(69), 2), cents, 0.9)

    private fun capture(state: TunerState, name: String) {
        compose.setContent { ViolinStudioTheme { TunerScreen(state, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(violin, "tuner_idle")

    @Test fun noPitch() = capture(violin.copy(reading = TunerReading.NoPitch, isListening = true), "tuner_no_pitch")

    @Test fun inTune() = capture(violin.copy(reading = pitch(1.0), isListening = true), "tuner_in_tune")

    @Test fun flatWithOverflow() = capture(
        violin.copy(reading = pitch(-87.0), isListening = true, selectedString = 2),
        "tuner_flat_overflow"
    )

    @Test fun chromatic() = capture(
        TunerState(
            instrument = Instrument.OTHER,
            reading = TunerReading.Pitch(466.2, TuningTarget.Chromatic(Note(70)), 0.5, 0.9),
            isListening = true
        ),
        "tuner_chromatic"
    )

    @Test fun rationale() = capture(
        violin.copy(mic = MicState.DENIED, showRationale = true),
        "tuner_rationale"
    )

    @Test fun permanentlyDenied() = capture(violin.copy(mic = MicState.PERMANENTLY_DENIED), "tuner_blocked")

    @Test fun micBusy() = capture(violin.copy(error = TunerError.MIC_BUSY), "tuner_mic_busy")
}
