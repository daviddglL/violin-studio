package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SendEmailVerificationUseCaseTest {
    private val auth = FakeAuthRepository()

    @Test
    fun `delega en el repositorio`() = runTest {
        assertEquals(Result.success(Unit), SendEmailVerificationUseCase(auth)())
        assertEquals(listOf("sendEmailVerification"), auth.calls)
    }

    @Test
    fun `propaga TooManyRequests`() = runTest {
        auth.sendVerificationResult = Result.failure(AuthFailure.TooManyRequests)
        assertEquals(AuthFailure.TooManyRequests, SendEmailVerificationUseCase(auth)().exceptionOrNull())
    }
}
