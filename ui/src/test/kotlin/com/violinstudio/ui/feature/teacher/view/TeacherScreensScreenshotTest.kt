package com.violinstudio.ui.feature.teacher.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.teacher.viewmodel.CodesError
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailState
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsState
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesState
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class TeacherScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val originalZone: TimeZone = TimeZone.getDefault()

    // TeacherCodesScreen formatea con la zona del sistema: sin fijarla, el baseline depende de la maquina (Madrid vs UTC en CI).
    @Before fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(originalZone)

    private fun link(uid: String, name: String, instrument: Instrument?) =
        TeacherLink("t1", uid, "Marta", name, instrument, 1, Instant.parse("2026-10-01T12:00:00Z"))

    private val students = listOf(
        link("s1", "Ana", Instrument.VIOLIN),
        link("s2", "Leo", Instrument.CELLO),
        link("s3", "Sara", null)
    )

    private val expiry = Instant.parse("2026-10-21T12:00:00Z")
    private val listed = (1..4).map { TeacherCode("h$it", null, "A$it", expiry.plusSeconds(it * 3_600L)) }
    private val fresh = TeacherCode("h9", "WXYZ2345", "45", expiry)

    private fun students(state: StudentsState, name: String) {
        compose.setContent {
            ViolinStudioTheme {
                StudentsScreen(
                    state,
                    onIntent = {},
                    onOpenCodes = {},
                    onOpenStudent = {},
                    onBack = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val sessions = listOf(
        PracticeSession(
            "a",
            Instant.parse("2026-10-13T09:00:00Z"),
            1_800,
            Instrument.VIOLIN,
            "Escalas en re y vibrato",
            false
        ),
        PracticeSession("b", Instant.parse("2026-10-12T18:30:00Z"), 3_900, Instrument.VIOLIN, null, false),
        PracticeSession("c", Instant.parse("2026-10-05T09:00:00Z"), 600, Instrument.VIOLIN, "Estudio 3", false)
    )

    private fun detail(state: StudentDetailState, name: String) {
        compose.setContent {
            ViolinStudioTheme { StudentDetailScreen(state, onIntent = {}, onBack = {}, zone = ZoneOffset.UTC) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun codes(state: TeacherCodesState, name: String) {
        compose.setContent { ViolinStudioTheme { TeacherCodesScreen(state, onIntent = {}, onBack = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun studentsEmpty() = students(StudentsState(isLoading = false), "teacher_students_empty")

    @Test fun studentsList() = students(StudentsState(isLoading = false, students = students), "teacher_students_list")

    @Test fun codeCreated() = codes(
        TeacherCodesState(isLoading = false, codes = listed + fresh.copy(code = null), created = fresh),
        "teacher_code_created"
    )

    @Test fun codeLimitReached() = codes(
        TeacherCodesState(
            isLoading = false,
            codes = listed + fresh.copy(code = null),
            error = CodesError.LimitReached
        ),
        "teacher_code_limit_reached"
    )

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h1200dp-xxhdpi")
    fun codeCreatedEn() = codes(
        TeacherCodesState(isLoading = false, codes = listOf(fresh.copy(code = null)), created = fresh, copied = true),
        "teacher_code_created_en"
    )

    @Test fun detailWithSessions() = detail(
        StudentDetailState(isLoading = false, link = students[0], sessions = sessions, weeklyTotalSec = 5_700),
        "teacher_student_detail"
    )

    @Test fun detailEmpty() =
        detail(StudentDetailState(isLoading = false, link = students[0]), "teacher_student_detail_empty")

    @Test fun detailUnavailable() =
        detail(StudentDetailState(isLoading = false, unavailable = true), "teacher_student_detail_unavailable")

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h1200dp-xxhdpi")
    fun detailWithSessionsEn() = detail(
        StudentDetailState(isLoading = false, link = students[0], sessions = sessions, weeklyTotalSec = 5_700),
        "teacher_student_detail_en"
    )
}
