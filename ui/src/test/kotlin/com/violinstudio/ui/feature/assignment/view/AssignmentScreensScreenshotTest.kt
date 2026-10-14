package com.violinstudio.ui.feature.assignment.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditState
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsState
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsNotice
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsState
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
class AssignmentScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun task(
        id: String,
        title: String,
        status: AssignmentStatus = AssignmentStatus.OPEN,
        pending: Boolean = false,
        dueAt: Instant? = Instant.parse("2026-10-20T12:00:00Z")
    ) = Assignment(
        id,
        "t1",
        title,
        "Do mayor, dos octavas",
        dueAt,
        status,
        if (status == AssignmentStatus.DONE) Instant.parse("2026-10-10T10:00:00Z") else null,
        Instant.parse("2026-10-01T10:00:00Z"),
        pending
    )

    private val tasks = listOf(
        task("a", "Escalas en re mayor", pending = true),
        task("b", "Estudio 3 de Kreutzer", dueAt = null),
        task("c", "Vibrato lento", AssignmentStatus.DONE)
    )

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { ViolinStudioTheme { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun teacherList() = shoot("assignment_teacher_list") {
        TeacherAssignmentsScreen(
            TeacherAssignmentsState(isLoading = false, assignments = tasks, notice = TeacherAssignmentsNotice.REJECTED),
            {},
            onCreate = {},
            onEdit = {},
            onBack = {},
            zone = ZoneOffset.UTC
        )
    }

    @Test
    fun teacherEmpty() = shoot("assignment_teacher_empty") {
        TeacherAssignmentsScreen(
            TeacherAssignmentsState(isLoading = false),
            {},
            onCreate = {},
            onEdit = {},
            onBack = {},
            zone = ZoneOffset.UTC
        )
    }

    @Test
    fun editWithErrors() = shoot("assignment_edit_errors") {
        AssignmentEditScreen(
            AssignmentEditState(
                title = "x".repeat(121),
                description = "y".repeat(1_001),
                dueAt = Instant.parse("2026-10-20T12:00:00Z")
            ),
            {},
            onBack = {},
            zone = ZoneOffset.UTC
        )
    }

    @Test
    fun editLoading() = shoot("assignment_edit_loading") {
        AssignmentEditScreen(AssignmentEditState(isEditing = true, loading = true), {}, onBack = {})
    }

    @Test
    fun studentList() = shoot("assignment_student_list") {
        StudentAssignmentsScreen(
            StudentAssignmentsState(isLoading = false, assignments = tasks),
            {},
            onBack = {},
            zone = ZoneOffset.UTC
        )
    }

    @Test
    fun studentEmpty() = shoot("assignment_student_empty") {
        StudentAssignmentsScreen(StudentAssignmentsState(isLoading = false), {}, onBack = {}, zone = ZoneOffset.UTC)
    }
}
