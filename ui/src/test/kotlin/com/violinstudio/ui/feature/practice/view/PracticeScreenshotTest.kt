package com.violinstudio.ui.feature.practice.view

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.practice.viewmodel.PracticeState
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class PracticeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val running = RunningSession("r1", t0, Instrument.VIOLIN)
    private val history = listOf(
        PracticeSession("s1", t0, 3725, Instrument.VIOLIN, "Escalas y arpegios en re mayor", false),
        PracticeSession("s2", t0.minusSeconds(86_400), 600, Instrument.CELLO, null, true)
    )

    /** Con diálogo hay animaciones infinitas (cursor, ventana): se congela el reloj para poder llegar a idle. */
    private fun capture(state: PracticeState, name: String, frozen: Boolean = false) {
        compose.mainClock.autoAdvance = !frozen
        compose.setContent {
            ViolinStudioTheme { PracticeScreen(state, {}, {}, zone = ZoneOffset.UTC) }
        }
        if (frozen) compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun empty() = capture(PracticeState(ready = true), "practice_empty")

    @Test fun running() = capture(
        PracticeState(ready = true, running = running, elapsedSec = 3725, history = history, weeklyTotalSec = 4325),
        "practice_running"
    )

    @Test fun historyWithPending() =
        capture(PracticeState(ready = true, history = history, weeklyTotalSec = 4325), "practice_history")

    @Test fun saveDialog() {
        compose.setContent {
            ViolinStudioTheme {
                Surface {
                    Column(Modifier.padding(24.dp)) {
                        SaveDialogContent(
                            PracticeState(ready = true, running = running, showSave = true, draftNotes = "Buen día"),
                            {}
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/practice_save_dialog.png")
    }

    @Test fun deleteConfirmation() =
        capture(PracticeState(ready = true, history = history, confirmDeleteId = "s1"), "practice_delete_confirm", frozen = true)
}
