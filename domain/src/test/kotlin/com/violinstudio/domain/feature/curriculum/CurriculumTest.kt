package com.violinstudio.domain.feature.curriculum

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.domain.feature.curriculum.model.LessonTool
import com.violinstudio.domain.feature.curriculum.model.Level
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.curriculum.usecase.SetLessonCompletedUseCase
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeLessonProgressRepository
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurriculumTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakeLessonProgressRepository()

    @Test
    fun `el curriculo tiene 4 lecciones por nivel, ids unicos y tiempos razonables`() {
        val lessons = Curriculum.lessons
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
        Level.entries.forEach { level -> assertEquals(4, lessons.count { it.level == level }) }
        assertTrue(lessons.all { it.minutes in 5..30 })
        assertTrue(lessons.all { it.id.matches(Regex("^[a-z][a-z0-9_]{1,40}$")) })
        assertTrue(lessons.mapNotNull { it.tool as? LessonTool.Metronome }.all { it.bpm in 40..208 })
        // Orden de estudio: los niveles no se mezclan.
        assertEquals(lessons.map { it.level }.sorted(), lessons.map { it.level })
    }

    @Test
    fun `el progreso marca lo hecho, cuenta por nivel e ignora ids desconocidos`() {
        val progress = CurriculumProgress.of(setOf("b1_posture", "b2_bow_hold", "retirada"))
        assertEquals(2, progress.done)
        assertEquals(12, progress.total)
        assertEquals(2, progress.levels.first { it.level == Level.BEGINNER }.done)
        assertEquals("b3_open_strings", progress.next?.id)
    }

    @Test
    fun `sin nada pendiente no hay siguiente leccion`() {
        assertNull(CurriculumProgress.of(Curriculum.lessons.map { it.id }.toSet()).next)
        assertEquals("b1_posture", CurriculumProgress.of(emptySet()).next?.id)
    }

    @Test
    fun `observa el progreso del usuario con sesion y sin sesion todo esta pendiente`() = runTest {
        repo.seed("u1", "b1_posture")
        assertEquals(1, ObserveCurriculumUseCase(auth, repo)().first().done)
        auth.user = null
        assertEquals(0, ObserveCurriculumUseCase(auth, repo)().first().done)
    }

    @Test
    fun `marcar y desmarcar una leccion`() = runTest {
        val set = SetLessonCompletedUseCase(auth, repo)
        assertTrue(set("b1_posture", true).isSuccess)
        assertEquals(1, ObserveCurriculumUseCase(auth, repo)().first().done)
        assertTrue(set("b1_posture", false).isSuccess)
        assertEquals(0, ObserveCurriculumUseCase(auth, repo)().first().done)
        assertEquals(
            listOf("set:u1:b1_posture:true", "observe:u1", "set:u1:b1_posture:false", "observe:u1"),
            repo.calls
        )
    }

    @Test
    fun `rechaza lecciones desconocidas, sin sesion y traduce los errores de E-S`() = runTest {
        val set = SetLessonCompletedUseCase(auth, repo)
        assertEquals(CurriculumFailure.UnknownLesson, set("nope", true).exceptionOrNull())
        repo.writeFailure = IOException("disk")
        assertEquals(CurriculumFailure.Storage, set("b1_posture", true).exceptionOrNull())
        auth.user = null
        assertEquals(CurriculumFailure.NoSession, set("b1_posture", true).exceptionOrNull())
    }
}
