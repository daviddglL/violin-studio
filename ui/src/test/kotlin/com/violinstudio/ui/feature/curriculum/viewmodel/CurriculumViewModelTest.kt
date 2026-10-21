package com.violinstudio.ui.feature.curriculum.viewmodel

import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.curriculum.usecase.SetLessonCompletedUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class CurriculumViewModelTest {
    private val progress = MutableStateFlow(CurriculumProgress.of(emptySet()))
    private val observe = mockk<ObserveCurriculumUseCase> { every { this@mockk.invoke() } returns progress }
    private val setDone = mockk<SetLessonCompletedUseCase>()

    @Test
    fun `muestra el progreso y lo actualiza al marcar una leccion`() = runTest {
        coEvery { setDone("b1_posture", true) } coAnswers {
            progress.value = CurriculumProgress.of(setOf("b1_posture"))
            Result.success(Unit)
        }
        CurriculumViewModel(observe, setDone).testMvi {
            assertState { it.progress?.done == 0 }
            intent(CurriculumIntent.SetDone("b1_posture", true))
            assertState { it.progress?.done == 1 && it.message == null }
        }
        coVerify(exactly = 1) { setDone("b1_posture", true) }
    }

    @Test
    fun `un fallo al guardar muestra el aviso y se puede cerrar`() = runTest {
        coEvery { setDone(any(), any()) } returns Result.failure(CurriculumFailure.Storage)
        CurriculumViewModel(observe, setDone).testMvi {
            assertState { it.progress != null }
            intent(CurriculumIntent.SetDone("b1_posture", true))
            assertState { it.message == CurriculumMessage.SAVE_FAILED }
            intent(CurriculumIntent.DismissMessage)
            assertState { it.message == null }
        }
    }

    @Test
    fun `abrir y cerrar una leccion`() = runTest {
        CurriculumViewModel(observe, setDone).testMvi {
            assertState { it.progress != null }
            intent(CurriculumIntent.Toggle("b1_posture"))
            assertState { it.expandedId == "b1_posture" }
            intent(CurriculumIntent.Toggle("b2_bow_hold"))
            assertState { it.expandedId == "b2_bow_hold" }
            intent(CurriculumIntent.Toggle("b2_bow_hold"))
            assertState { it.expandedId == null }
        }
    }

    @Test
    fun `si la lectura falla se muestra el curriculo sin progreso`() = runTest {
        val failing = mockk<ObserveCurriculumUseCase> {
            every { this@mockk.invoke() } returns flow { throw IllegalStateException("io") }
        }
        CurriculumViewModel(failing, setDone).testMvi {
            assertState { it.progress?.done == 0 && it.progress?.total == 12 }
        }
    }

    @Test
    fun `el reductor`() {
        val loaded = CurriculumReducer.reduce(CurriculumState(), CurriculumMutation.Loaded(progress.value))
        assertEquals(progress.value, loaded.progress)
        val open = CurriculumReducer.reduce(loaded, CurriculumMutation.Toggled("a"))
        assertEquals("a", open.expandedId)
        assertNull(CurriculumReducer.reduce(open, CurriculumMutation.Toggled("a")).expandedId)
        val failed = CurriculumReducer.reduce(open, CurriculumMutation.SaveFailed)
        assertEquals(CurriculumMessage.SAVE_FAILED, failed.message)
        assertNull(CurriculumReducer.reduce(failed, CurriculumMutation.MessageDismissed).message)
    }
}
