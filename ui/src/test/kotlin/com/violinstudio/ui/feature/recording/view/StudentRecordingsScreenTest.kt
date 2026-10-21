package com.violinstudio.ui.feature.recording.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.recording.viewmodel.ReviewEditor
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsIntent
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsMessage
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class StudentRecordingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<StudentRecordingsIntent>()
    private var back = 0
    private val r0 = RecordingSummary("r0", null, 65_000, ready = true, isDemo = false, objectPath = "p")

    private fun show(state: StudentRecordingsState) {
        compose.setContent { ViolinStudioTheme { StudentRecordingsScreen(state, { sent += it }, { back++ }) } }
    }

    @Test
    fun `lista con escuchar y valorar`() {
        show(StudentRecordingsState(isLoading = false, recordings = listOf(r0)))
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithTag(playTag("r0")).performClick()
        compose.onNodeWithTag(reviewTag("r0")).performClick()
        compose.onNodeWithTag(STUDENT_RECORDINGS_BACK_TAG).performClick()
        assertEquals(listOf(StudentRecordingsIntent.Play("r0"), StudentRecordingsIntent.OpenReview("r0")), sent)
        assertEquals(1, back)
    }

    @Test
    fun `vacio, fallo y mensajes`() {
        show(StudentRecordingsState(isLoading = false, message = StudentRecordingsMessage.SAVE_FAILED))
        compose.onNodeWithTag(STUDENT_RECORDINGS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithText("No se pudo guardar la valoración. Revisa la conexión.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        assertEquals(listOf<StudentRecordingsIntent>(StudentRecordingsIntent.DismissMessage), sent)
    }

    @Test
    fun `si falla la carga se puede reintentar`() {
        show(StudentRecordingsState(isLoading = false, loadFailed = true))
        compose.onNodeWithTag(STUDENT_RECORDINGS_FAILED_TAG).assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(listOf<StudentRecordingsIntent>(StudentRecordingsIntent.Retry), sent)
    }

    @Test
    fun `sonando se puede parar`() {
        show(StudentRecordingsState(isLoading = false, recordings = listOf(r0), playback = PlaybackState.Playing("r0")))
        compose.onNodeWithTag(stopPlaybackTag("r0")).performClick()
        assertEquals(listOf<StudentRecordingsIntent>(StudentRecordingsIntent.Stop), sent)
    }

    @Test
    fun `el editor elige veredicto, escribe y guarda`() {
        val editor = ReviewEditor("r0", loading = false, verdict = Verdict.NEEDS_WORK, text = "Arco")
        show(StudentRecordingsState(isLoading = false, recordings = listOf(r0), editor = editor))
        compose.onNodeWithText("4/1000").assertIsDisplayed()
        compose.onNodeWithTag(verdictTag(Verdict.APPROVED)).performClick()
        compose.onNodeWithTag(REVIEW_TEXT_TAG).performTextReplacement("Bien")
        compose.onNodeWithTag(REVIEW_SAVE_TAG).performClick()
        assertEquals(
            listOf(
                StudentRecordingsIntent.SetVerdict(Verdict.APPROVED),
                StudentRecordingsIntent.SetText("Bien"),
                StudentRecordingsIntent.SaveReview
            ),
            sent
        )
    }

    @Test
    fun `sin veredicto no se puede guardar`() {
        show(
            StudentRecordingsState(
                isLoading = false,
                recordings = listOf(r0),
                editor = ReviewEditor("r0", loading = false)
            )
        )
        compose.onNodeWithTag(REVIEW_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(listOf<StudentRecordingsIntent>(StudentRecordingsIntent.CloseReview), sent)
    }
}
