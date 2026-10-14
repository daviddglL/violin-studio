package com.violinstudio.ui.feature.teacher.view

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.practice.view.PRACTICE_NOTES_FIELD_TAG
import com.violinstudio.ui.feature.practice.view.PRACTICE_START_TAG
import com.violinstudio.ui.feature.practice.view.practiceItemDeleteTag
import com.violinstudio.ui.feature.practice.view.practiceItemEditTag
import com.violinstudio.ui.feature.practice.view.practiceItemTag
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailIntent
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StudentDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val intents = mutableListOf<StudentDetailIntent>()
    private var back = 0
    private val ana =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T12:00:00Z"))
    private val sessions = listOf(
        PracticeSession("a", Instant.parse("2026-10-13T09:00:00Z"), 1_800, Instrument.VIOLIN, "escalas en re", false),
        PracticeSession("b", Instant.parse("2026-10-05T09:00:00Z"), 600, Instrument.VIOLIN, null, false)
    )

    private fun show(state: StudentDetailState) = compose.setContent {
        ViolinStudioTheme {
            StudentDetailScreen(state, { intents += it }, onBack = { back++ }, zone = ZoneOffset.UTC)
        }
    }

    @Test
    fun `muestra nombre, total semanal, sesiones y notas`() {
        show(StudentDetailState(isLoading = false, link = ana, sessions = sessions, weeklyTotalSec = 1_800))
        compose.onNodeWithTag(STUDENT_DETAIL_NAME_TAG).assertTextContains("Ana")
        compose.onNodeWithTag(STUDENT_DETAIL_WEEKLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(practiceItemTag("a")).assertIsDisplayed()
        compose.onNodeWithTag(practiceItemTag("b")).assertIsDisplayed()
        compose.onAllNodesWithText("escalas en re").assertCountEquals(1)
    }

    @Test
    fun `no ofrece editar, borrar ni crear sesiones`() {
        show(StudentDetailState(isLoading = false, link = ana, sessions = sessions, weeklyTotalSec = 1_800))
        for (id in listOf("a", "b")) {
            compose.onAllNodesWithTag(practiceItemEditTag(id)).assertCountEquals(0)
            compose.onAllNodesWithTag(practiceItemDeleteTag(id)).assertCountEquals(0)
        }
        compose.onAllNodesWithTag(PRACTICE_START_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(PRACTICE_NOTES_FIELD_TAG).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.practice_edit)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.practice_delete)).assertCountEquals(0)
    }

    @Test
    fun `sin sesiones se muestra el vacio`() {
        show(StudentDetailState(isLoading = false, link = ana))
        compose.onNodeWithTag(STUDENT_DETAIL_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `un alumno sin vinculo muestra no disponible y ningun dato`() {
        show(StudentDetailState(isLoading = false, unavailable = true))
        compose.onNodeWithTag(STUDENT_DETAIL_UNAVAILABLE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(STUDENT_DETAIL_EMPTY_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(STUDENT_DETAIL_WEEKLY_TAG).assertCountEquals(0)
        compose.onNodeWithTag(STUDENT_DETAIL_UNAVAILABLE_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `mientras carga no se afirma que no hay sesiones`() {
        show(StudentDetailState())
        compose.onNodeWithTag(STUDENT_DETAIL_LOADING_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(STUDENT_DETAIL_EMPTY_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(STUDENT_DETAIL_UNAVAILABLE_TAG).assertCountEquals(0)
    }

    @Test
    fun `un fallo de carga ofrece reintentar y no el vacio`() {
        show(StudentDetailState(isLoading = false, link = ana, loadFailed = true))
        compose.onAllNodesWithTag(STUDENT_DETAIL_EMPTY_TAG).assertCountEquals(0)
        compose.onNodeWithTag(STUDENT_DETAIL_RETRY_TAG).performClick()
        assertEquals(listOf<StudentDetailIntent>(StudentDetailIntent.Retry), intents)
    }

    @Test
    fun `atras llama a onBack`() {
        show(StudentDetailState(isLoading = false, link = ana))
        compose.onNodeWithTag(STUDENT_DETAIL_BACK_TAG).performClick()
        assertEquals(1, back)
    }
}
