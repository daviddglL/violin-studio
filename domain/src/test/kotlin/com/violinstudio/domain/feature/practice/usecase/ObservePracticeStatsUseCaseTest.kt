package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.PracticeTotals
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakePracticeLogRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObservePracticeStatsUseCaseTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakePracticeLogRepository()

    private fun stats() = ObservePracticeStatsUseCase(
        ObservePracticeHistoryUseCase(auth, repo),
        auth,
        repo,
        Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)
    ) {
        ZoneOffset.UTC
    }

    @Test
    fun `calcula sobre el historial del usuario con sesion`() = runTest {
        repo.seed(
            "u1",
            PracticeSession("a", Instant.parse("2026-10-14T09:00:00Z"), 600, Instrument.VIOLIN, null, false),
            PracticeSession("b", Instant.parse("2026-10-13T09:00:00Z"), 300, Instrument.VIOLIN, null, false)
        )
        val result = stats()().first()
        assertEquals(900, result.weekSec)
        assertEquals(2, result.currentStreakDays)
        assertEquals(listOf("observe:u1"), repo.calls)
    }

    @Test
    fun `sin sesion las estadisticas estan vacias`() = runTest {
        auth.user = null
        val result = stats()().first()
        assertEquals(0, result.sessionCount)
        assertEquals(7, result.last7Days.size)
    }

    @Test
    fun `con el historial lleno emite lo local y despues los totales del servidor`() = runTest {
        val full = (1..PracticeRules.HISTORY_LIMIT).map {
            PracticeSession("s$it", Instant.parse("2026-10-14T09:00:00Z"), 60, Instrument.VIOLIN, null, false)
        }
        repo.seed("u1", *full.toTypedArray())
        repo.totals = PracticeTotals(350, 21_000L)
        val (local, exact) = stats()().take(2).toList()
        assertTrue(local.truncated)
        assertEquals(PracticeRules.HISTORY_LIMIT, local.sessionCount)
        assertFalse(exact.truncated)
        assertEquals(350, exact.sessionCount)
        assertEquals(21_000, exact.totalSec)
    }

    @Test
    fun `sin historial lleno no consulta los totales`() = runTest {
        repo.seed(
            "u1",
            PracticeSession("a", Instant.parse("2026-10-14T09:00:00Z"), 600, Instrument.VIOLIN, null, false)
        )
        stats()().first()
        assertTrue(repo.calls.none { it.startsWith("totals") })
    }
}
