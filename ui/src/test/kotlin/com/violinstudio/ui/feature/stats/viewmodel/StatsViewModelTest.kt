package com.violinstudio.ui.feature.stats.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.practiceStats
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeStatsUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class StatsViewModelTest {
    private val empty = emptyList<PracticeSession>()
        .practiceStats(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `carga las estadisticas del historial`() = runTest {
        val useCase = mockk<ObservePracticeStatsUseCase> { every { this@mockk.invoke() } returns flowOf(empty) }
        StatsViewModel(useCase).testMvi {
            assertState { !it.loading && it.stats == empty && !it.failed }
            assertNoEffects()
        }
    }

    @Test
    fun `un fallo deja el estado de error y Retry vuelve a escuchar`() = runTest {
        var calls = 0
        val useCase = mockk<ObservePracticeStatsUseCase> {
            every { this@mockk.invoke() } answers {
                calls++
                if (calls == 1) {
                    flow { throw PracticeFailure.PermissionDenied }
                } else {
                    // La latencia hace observable el estado de carga (StateFlow descarta estados intermedios).
                    flow {
                        delay(100)
                        emit(empty)
                    }
                }
            }
        }
        StatsViewModel(useCase).testMvi {
            assertState { it.failed && it.stats == null && !it.loading }
            intent(StatsIntent.Retry)
            assertState { it.loading && !it.failed }
            assertState { it.stats == empty && !it.failed }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `el reductor pasa por carga, datos y error`() {
        val loading = StatsReducer.reduce(StatsState(loading = false, failed = true), StatsMutation.Loading)
        assertTrue(loading.loading)
        assertFalse(loading.failed)
        val loaded = StatsReducer.reduce(loading, StatsMutation.Loaded(empty))
        assertEquals(StatsState(loading = false, stats = empty, failed = false), loaded)
        val failed = StatsReducer.reduce(loaded, StatsMutation.Failed)
        assertNull(failed.stats)
        assertTrue(failed.failed)
    }
}
