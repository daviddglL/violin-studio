package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.testing.FakeLinkRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedeemTeacherCodeUseCaseTest {
    private val repo = FakeLinkRepository()
    private val useCase = RedeemTeacherCodeUseCase(repo)

    @Test
    fun `envia el codigo normalizado y el ackVersion y devuelve el uid del profesor`() = runTest {
        repo.redeemResult = Result.success("teacher1")
        assertEquals(Result.success("teacher1"), useCase(" abcd-2345 ", ackVersion = 2))
        assertEquals(listOf("redeem:ABCD2345:2"), repo.calls)
    }

    @Test
    fun `un codigo malformado falla con InvalidCode sin llamar a la red`() = runTest {
        assertEquals(LinkFailure.InvalidCode, useCase("ABCD", ackVersion = 2).exceptionOrNull())
        assertEquals(LinkFailure.InvalidCode, useCase("0BCD2345", ackVersion = 2).exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `propaga los fallos del repositorio`() = runTest {
        repo.redeemResult = Result.failure(LinkFailure.RateLimited(30))
        assertEquals(LinkFailure.RateLimited(30), useCase("ABCD2345", ackVersion = 2).exceptionOrNull())
    }
}
