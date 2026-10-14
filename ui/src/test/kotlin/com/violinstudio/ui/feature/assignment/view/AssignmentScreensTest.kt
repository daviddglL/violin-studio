package com.violinstudio.ui.feature.assignment.view

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditError
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditIntent
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditState
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsError
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsIntent
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsState
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsError
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsIntent
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsNotice
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h1200dp-xxhdpi")
class AssignmentScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var back = 0
    private var created = 0
    private val edited = mutableListOf<String>()

    private fun task(
        id: String,
        status: AssignmentStatus = AssignmentStatus.OPEN,
        pending: Boolean = false,
        dueAt: Instant? = Instant.parse("2026-10-20T12:00:00Z"),
        doneAt: Instant? = Instant.parse("2026-10-10T10:00:00Z")
    ) = Assignment(
        id,
        "t1",
        "Escalas $id",
        "Do mayor",
        dueAt,
        status,
        if (status == AssignmentStatus.DONE) doneAt else null,
        Instant.parse("2026-10-01T10:00:00Z"),
        pending
    )

    private fun hasLiveRegion() = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    // --- Profesor: lista ---

    private val teacherIntents = mutableListOf<TeacherAssignmentsIntent>()

    private fun teacher(state: TeacherAssignmentsState) = compose.setContent {
        ViolinStudioTheme {
            TeacherAssignmentsScreen(
                state,
                { teacherIntents += it },
                onCreate = { created++ },
                onEdit = { edited += it },
                onBack = { back++ },
                zone = ZoneOffset.UTC
            )
        }
    }

    @Test
    fun `lista del profesor muestra titulo, estado y la insignia de pendiente`() {
        teacher(
            TeacherAssignmentsState(
                isLoading = false,
                assignments = listOf(task("a", pending = true), task("b", AssignmentStatus.DONE))
            )
        )
        compose.onNodeWithTag(teacherTaskTag("a")).assertIsDisplayed()
        compose.onNodeWithTag(teacherTaskPendingTag("a")).assertIsDisplayed()
        compose.onAllNodesWithTag(teacherTaskPendingTag("b")).assertCountEquals(0)
        compose.onNodeWithTag(teacherTaskStatusTag("b"))
            .assertTextContains(context.getString(R.string.assignment_status_done))
    }

    @Test
    fun `nueva tarea, editar y volver llaman a la navegacion`() {
        teacher(TeacherAssignmentsState(isLoading = false, assignments = listOf(task("a"))))
        compose.onNodeWithTag(TEACHER_TASKS_CREATE_TAG).performClick()
        compose.onNodeWithTag(teacherTaskEditTag("a")).performClick()
        compose.onNodeWithTag(TEACHER_TASKS_BACK_TAG).performClick()
        assertEquals(1, created)
        assertEquals(listOf("a"), edited)
        assertEquals(1, back)
    }

    @Test
    fun `borrar pide confirmacion y confirmar o cancelar envia el intent`() {
        teacher(
            TeacherAssignmentsState(
                isLoading = false,
                assignments = listOf(task("a")),
                deleteTarget = task("a")
            )
        )
        compose.onNodeWithTag(TEACHER_TASKS_DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TEACHER_TASKS_DELETE_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(TEACHER_TASKS_DELETE_CANCEL_TAG).performClick()
        assertEquals(
            listOf(TeacherAssignmentsIntent.ConfirmDelete, TeacherAssignmentsIntent.CancelDelete),
            teacherIntents
        )
    }

    @Test
    fun `pulsar borrar en una fila solicita el borrado de ese id`() {
        teacher(TeacherAssignmentsState(isLoading = false, assignments = listOf(task("a"))))
        compose.onNodeWithTag(teacherTaskDeleteTag("a")).performClick()
        assertEquals(listOf(TeacherAssignmentsIntent.RequestDelete("a")), teacherIntents)
    }

    @Test
    fun `lista vacia del profesor`() {
        teacher(TeacherAssignmentsState(isLoading = false))
        compose.onNodeWithTag(TEACHER_TASKS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `cargando no muestra vacio`() {
        teacher(TeacherAssignmentsState(isLoading = true))
        compose.onNodeWithTag(TEACHER_TASKS_LOADING_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(TEACHER_TASKS_EMPTY_TAG).assertCountEquals(0)
    }

    @Test
    fun `no disponible es live region y no ofrece crear`() {
        teacher(TeacherAssignmentsState(isLoading = false, unavailable = true))
        compose.onNodeWithTag(TEACHER_TASKS_UNAVAILABLE_TAG).assert(hasLiveRegion())
        compose.onAllNodesWithTag(TEACHER_TASKS_CREATE_TAG).assertCountEquals(0)
    }

    @Test
    fun `fallo de carga ofrece reintentar y nunca el vacio`() {
        teacher(TeacherAssignmentsState(isLoading = false, loadFailed = true))
        compose.onAllNodesWithTag(TEACHER_TASKS_EMPTY_TAG).assertCountEquals(0)
        compose.onNodeWithTag(TEACHER_TASKS_RETRY_TAG).performClick()
        assertEquals(listOf(TeacherAssignmentsIntent.Retry), teacherIntents)
    }

    @Test
    fun `error y aviso rechazado son live region y descartables`() {
        teacher(
            TeacherAssignmentsState(
                isLoading = false,
                assignments = listOf(task("a")),
                error = TeacherAssignmentsError.PERMISSION_DENIED,
                notice = TeacherAssignmentsNotice.REJECTED
            )
        )
        compose.onNodeWithTag(TEACHER_TASKS_ERROR_TAG).assert(hasLiveRegion())
        compose.onNodeWithTag(TEACHER_TASKS_NOTICE_TAG).assert(hasLiveRegion())
        compose.onNodeWithTag(TEACHER_TASKS_NOTICE_DISMISS_TAG).performClick()
        compose.onNodeWithTag(TEACHER_TASKS_ERROR_DISMISS_TAG).performClick()
        assertEquals(
            listOf(TeacherAssignmentsIntent.DismissNotice, TeacherAssignmentsIntent.DismissError),
            teacherIntents
        )
    }

    @Test
    fun `las acciones de fila tienen rol de boton`() {
        teacher(TeacherAssignmentsState(isLoading = false, assignments = listOf(task("a"))))
        compose.onNodeWithTag(teacherTaskEditTag("a"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    // --- Edicion ---

    private val editIntents = mutableListOf<AssignmentEditIntent>()

    private fun edit(state: AssignmentEditState) = compose.setContent {
        ViolinStudioTheme {
            AssignmentEditScreen(state, { editIntents += it }, onBack = { back++ }, zone = ZoneOffset.UTC)
        }
    }

    @Test
    fun `escribir titulo y descripcion envia los intents`() {
        edit(AssignmentEditState())
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_TAG).performTextInput("Escalas")
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DESCRIPTION_TAG).performTextInput("Do mayor")
        assertTrue(AssignmentEditIntent.TitleChanged("Escalas") in editIntents)
        assertTrue(AssignmentEditIntent.DescriptionChanged("Do mayor") in editIntents)
    }

    @Test
    fun `guardar se deshabilita sin titulo valido`() {
        edit(AssignmentEditState())
        compose.onNodeWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `con titulo valido guardar envia Save`() {
        edit(AssignmentEditState(title = "Escalas"))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertIsEnabled().performClick()
        assertEquals(listOf(AssignmentEditIntent.Save), editIntents)
    }

    @Test
    fun `errores de titulo y descripcion se muestran inline y con contador`() {
        edit(AssignmentEditState(title = "x".repeat(121), description = "y".repeat(1_001)))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_ERROR_TAG, useUnmergedTree = true)
            .assertTextContains(context.getString(R.string.assignment_error_title_too_long, 120))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DESCRIPTION_ERROR_TAG, useUnmergedTree = true)
            .assertTextContains(context.getString(R.string.assignment_error_description_too_long, 1_000))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_COUNT_TAG, useUnmergedTree = true).assertTextContains("121/120")
        compose.onNodeWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `titulo vacio solo es error tras tocarlo`() {
        edit(AssignmentEditState())
        compose.onAllNodesWithTag(ASSIGNMENT_EDIT_TITLE_ERROR_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `titulo vacio tras tocarlo muestra el error`() {
        edit(AssignmentEditState(titleTouched = true))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_ERROR_TAG, useUnmergedTree = true)
            .assertTextContains(context.getString(R.string.assignment_error_title_empty))
    }

    @Test
    fun `los campos se deshabilitan mientras carga`() {
        edit(AssignmentEditState(isEditing = true, loading = true))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DESCRIPTION_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DUE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `mientras guarda los campos estan deshabilitados`() {
        edit(AssignmentEditState(title = "Escalas", busy = true))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_TITLE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `no encontrada muestra no disponible como live region y no deja guardar ni editar`() {
        edit(AssignmentEditState(isEditing = true, notFound = true))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_UNAVAILABLE_TAG).assert(hasLiveRegion())
        compose.onAllNodesWithTag(ASSIGNMENT_EDIT_SAVE_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(ASSIGNMENT_EDIT_TITLE_TAG).assertCountEquals(0)
    }

    @Test
    fun `el error de guardado es live region y descartable`() {
        edit(AssignmentEditState(title = "Escalas", error = AssignmentEditError.UNKNOWN))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_ERROR_TAG).assert(hasLiveRegion())
        compose.onNodeWithTag(ASSIGNMENT_EDIT_ERROR_DISMISS_TAG).performClick()
        assertEquals(listOf(AssignmentEditIntent.DismissError), editIntents)
    }

    @Test
    fun `la fecha limite elegida se muestra y se puede quitar`() {
        edit(AssignmentEditState(title = "Escalas", dueAt = Instant.parse("2026-10-20T12:00:00Z")))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DUE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DUE_CLEAR_TAG).performClick()
        assertEquals(listOf(AssignmentEditIntent.DueAtChanged(null)), editIntents)
    }

    @Test
    fun `sin fecha limite no se ofrece quitarla`() {
        edit(AssignmentEditState(title = "Escalas"))
        compose.onAllNodesWithTag(ASSIGNMENT_EDIT_DUE_CLEAR_TAG).assertCountEquals(0)
    }

    @Test
    fun `mientras guarda cancelar esta deshabilitado`() {
        edit(AssignmentEditState(title = "Escalas", busy = true))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_BACK_TAG).assertIsNotEnabled()
    }

    @Test
    fun `el aceptar del selector de fecha solo se habilita con un dia elegido`() {
        edit(AssignmentEditState(title = "Escalas"))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DUE_TAG).performClick()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DATE_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun `el aceptar del selector de fecha esta habilitado si ya hay fecha`() {
        edit(AssignmentEditState(title = "Escalas", dueAt = Instant.parse("2026-10-20T12:00:00Z")))
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DUE_TAG).performClick()
        compose.onNodeWithTag(ASSIGNMENT_EDIT_DATE_CONFIRM_TAG).assertIsEnabled()
    }

    @Test
    fun `la salida de la edicion es idempotente`() {
        var pops = 0
        compose.setContent {
            val pop = rememberPopOnce { pops++ }
            LaunchedEffect(Unit) {
                pop()
                pop()
            }
        }
        compose.waitForIdle()
        assertEquals(1, pops)
    }

    @Test
    fun `cancelar vuelve atras`() {
        edit(AssignmentEditState())
        compose.onNodeWithTag(ASSIGNMENT_EDIT_BACK_TAG).performClick()
        assertEquals(1, back)
    }

    // --- Alumno ---

    private val studentIntents = mutableListOf<StudentAssignmentsIntent>()

    private fun student(state: StudentAssignmentsState) = compose.setContent {
        ViolinStudioTheme {
            StudentAssignmentsScreen(state, { studentIntents += it }, onBack = { back++ }, zone = ZoneOffset.UTC)
        }
    }

    @Test
    fun `alumno marca hecha una abierta y reabre una hecha`() {
        student(
            StudentAssignmentsState(
                isLoading = false,
                assignments = listOf(task("a"), task("b", AssignmentStatus.DONE, pending = true))
            )
        )
        compose.onNodeWithTag(studentTaskToggleTag("a")).performClick()
        compose.onNodeWithTag(studentTaskToggleTag("b")).performClick()
        compose.onNodeWithTag(studentTaskPendingTag("b")).assertIsDisplayed()
        assertEquals(
            listOf(StudentAssignmentsIntent.Complete("a"), StudentAssignmentsIntent.Reopen("b")),
            studentIntents
        )
    }

    @Test
    fun `el boton de la tarea es un boton con descripcion que incluye el titulo`() {
        student(StudentAssignmentsState(isLoading = false, assignments = listOf(task("a"))))
        compose.onNodeWithTag(studentTaskToggleTag("a"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("descripcion con el titulo") {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> "Escalas a" in d } == true
                }
            )
    }

    @Test
    fun `una tarea hecha con fecha muestra Hecha el y la fecha`() {
        student(StudentAssignmentsState(isLoading = false, assignments = listOf(task("b", AssignmentStatus.DONE))))
        val date = formatDueDate(Instant.parse("2026-10-10T10:00:00Z"), java.util.Locale.getDefault(), ZoneOffset.UTC)
        compose.onNodeWithTag(studentTaskDoneTag("b"))
            .assertTextContains(context.getString(R.string.assignment_done_on, date))
    }

    @Test
    fun `una tarea hecha sin fecha todavia no muestra la linea de fecha y una abierta tampoco`() {
        student(
            StudentAssignmentsState(
                isLoading = false,
                assignments = listOf(task("b", AssignmentStatus.DONE, doneAt = null), task("a"))
            )
        )
        compose.onAllNodesWithTag(studentTaskDoneTag("b")).assertCountEquals(0)
        compose.onAllNodesWithTag(studentTaskDoneTag("a")).assertCountEquals(0)
        compose.onNodeWithTag(studentTaskTag("b")).assertIsDisplayed()
    }

    @Test
    fun `una tarea con escritura en curso no se puede volver a tocar`() {
        student(StudentAssignmentsState(isLoading = false, assignments = listOf(task("a")), busyIds = setOf("a")))
        compose.onNodeWithTag(studentTaskToggleTag("a")).assertIsNotEnabled()
    }

    @Test
    fun `alumno sin tareas`() {
        student(StudentAssignmentsState(isLoading = false))
        compose.onNodeWithTag(STUDENT_TASKS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `alumno cargando no muestra vacio`() {
        student(StudentAssignmentsState(isLoading = true))
        compose.onNodeWithTag(STUDENT_TASKS_LOADING_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(STUDENT_TASKS_EMPTY_TAG).assertCountEquals(0)
    }

    @Test
    fun `alumno fallo de carga reintenta`() {
        student(StudentAssignmentsState(isLoading = false, loadFailed = true))
        compose.onAllNodesWithTag(STUDENT_TASKS_EMPTY_TAG).assertCountEquals(0)
        compose.onNodeWithTag(STUDENT_TASKS_RETRY_TAG).performClick()
        assertEquals(listOf(StudentAssignmentsIntent.Retry), studentIntents)
    }

    @Test
    fun `alumno error es live region y descartable`() {
        student(
            StudentAssignmentsState(
                isLoading = false,
                assignments = listOf(task("a")),
                error = StudentAssignmentsError.UNKNOWN
            )
        )
        compose.onNodeWithTag(STUDENT_TASKS_ERROR_TAG).assert(hasLiveRegion())
        compose.onNodeWithTag(STUDENT_TASKS_ERROR_DISMISS_TAG).performClick()
        assertEquals(listOf(StudentAssignmentsIntent.DismissError), studentIntents)
    }

    @Test
    fun `alumno volver`() {
        student(StudentAssignmentsState(isLoading = false))
        compose.onNodeWithTag(STUDENT_TASKS_BACK_TAG).performClick()
        assertEquals(1, back)
    }
}
