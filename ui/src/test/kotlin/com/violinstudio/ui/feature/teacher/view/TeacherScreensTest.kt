package com.violinstudio.ui.feature.teacher.view

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsIntent
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsState
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesIntent
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TeacherScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val studentIntents = mutableListOf<StudentsIntent>()
    private val codeIntents = mutableListOf<TeacherCodesIntent>()
    private var openedCodes = false
    private val ana =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T12:00:00Z"))
    private val listed = TeacherCode("h1", null, "AB", Instant.parse("2026-10-20T10:00:00Z"))
    private val fresh = TeacherCode("h2", "WXYZ2345", "45", Instant.parse("2026-10-21T10:00:00Z"))

    private fun showStudents(state: StudentsState) = compose.setContent {
        ViolinStudioTheme {
            StudentsScreen(state, { studentIntents += it }, onOpenCodes = { openedCodes = true }, onBack = {})
        }
    }

    private fun showCodes(state: TeacherCodesState) = compose.setContent {
        ViolinStudioTheme { TeacherCodesScreen(state, { codeIntents += it }, onBack = {}) }
    }

    @Test
    fun `sin alumnos se ofrece crear un codigo`() {
        showStudents(StudentsState(isLoading = false))
        compose.onNodeWithTag(STUDENTS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STUDENTS_CODES_CTA_TAG).performClick()
        assertEquals(true, openedCodes)
    }

    @Test
    fun `mientras carga no se afirma que no hay alumnos`() {
        showStudents(StudentsState())
        compose.onNodeWithTag(STUDENTS_LOADING_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(STUDENTS_EMPTY_TAG).assertCountEquals(0)
    }

    @Test
    fun `un fallo de carga ofrece reintentar y no el vacio`() {
        showStudents(StudentsState(isLoading = false, loadFailed = true))
        compose.onAllNodesWithTag(STUDENTS_EMPTY_TAG).assertCountEquals(0)
        compose.onNodeWithTag(STUDENTS_RETRY_TAG).performClick()
        assertEquals(listOf(StudentsIntent.Retry), studentIntents)
    }

    @Test
    fun `la lista muestra el contador y desvincular pide confirmacion`() {
        showStudents(StudentsState(isLoading = false, students = listOf(ana)))
        compose.onNodeWithTag(STUDENTS_COUNT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STUDENT_UNLINK_TAG).performClick()
        assertEquals(listOf<StudentsIntent>(StudentsIntent.RequestUnlink(ana)), studentIntents)
    }

    @Test
    fun `el dialogo de desvincular confirma y cancela`() {
        showStudents(StudentsState(isLoading = false, students = listOf(ana), unlinkTarget = ana))
        compose.onNodeWithTag(STUDENT_UNLINK_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STUDENT_UNLINK_CANCEL_TAG).performClick()
        compose.onNodeWithTag(STUDENT_UNLINK_CONFIRM_TAG).performClick()
        assertEquals(listOf(StudentsIntent.CancelUnlink, StudentsIntent.ConfirmUnlink), studentIntents)
    }

    @Test
    fun `crear se bloquea mientras se crea`() {
        showCodes(TeacherCodesState(isLoading = false, isCreating = true))
        compose.onNodeWithTag(CODES_CREATE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `el codigo creado se ve con copiar compartir y hecho`() {
        showCodes(TeacherCodesState(isLoading = false, created = fresh))
        compose.onNodeWithTag(CODE_CREATED_VALUE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CODE_COPY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CODE_SHARE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CODE_DONE_TAG).performScrollTo().performClick()
        assertEquals(
            listOf(TeacherCodesIntent.Copy, TeacherCodesIntent.Share, TeacherCodesIntent.DismissCreated),
            codeIntents
        )
    }

    @Test
    fun `sin codigo creado no hay tarjeta ni se ve el codigo`() {
        showCodes(TeacherCodesState(isLoading = false, codes = listOf(listed)))
        compose.onAllNodesWithTag(CODE_CREATED_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CODE_ROW_TAG).onFirst().assertIsDisplayed()
    }

    @Test
    fun `revocar un codigo de la lista pide confirmacion`() {
        showCodes(TeacherCodesState(isLoading = false, codes = listOf(listed)))
        compose.onNodeWithTag(CODE_REVOKE_TAG).performScrollTo().performClick()
        assertEquals(listOf<TeacherCodesIntent>(TeacherCodesIntent.RequestRevoke(listed)), codeIntents)
    }

    @Test
    fun `el dialogo de revocar confirma y cancela`() {
        showCodes(TeacherCodesState(isLoading = false, codes = listOf(listed), revokeTarget = listed))
        compose.onNodeWithTag(CODE_REVOKE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CODE_REVOKE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(CODE_REVOKE_CONFIRM_TAG).performClick()
        assertEquals(listOf(TeacherCodesIntent.CancelRevoke, TeacherCodesIntent.ConfirmRevoke), codeIntents)
    }

    @Test
    fun `un fallo de carga de codigos ofrece reintentar`() {
        showCodes(TeacherCodesState(isLoading = false, loadFailed = true))
        compose.onNodeWithTag(CODES_RETRY_TAG).performClick()
        assertEquals(listOf(TeacherCodesIntent.Retry), codeIntents)
    }
}
