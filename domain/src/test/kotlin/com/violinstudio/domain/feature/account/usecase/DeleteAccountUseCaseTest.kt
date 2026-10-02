package com.violinstudio.domain.feature.account.usecase

import com.violinstudio.domain.feature.FakeAccountRepository
import com.violinstudio.domain.feature.account.failure.AccountFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeleteAccountUseCaseTest {
    private val account = FakeAccountRepository()
    private val useCase = DeleteAccountUseCase(account)

    @Test
    fun `borra la cuenta`() = runTest {
        assertEquals(Result.success(Unit), useCase())
        assertEquals(1, account.calls)
    }

    @Test
    fun `RequiresRecentLogin se propaga para que la UI reautentique y reintente`() = runTest {
        account.result = Result.failure(AccountFailure.RequiresRecentLogin)
        assertEquals(AccountFailure.RequiresRecentLogin, useCase().exceptionOrNull())
    }

    @Test
    fun `ErasureFailed se propaga`() = runTest {
        account.result = Result.failure(AccountFailure.ErasureFailed)
        assertEquals(AccountFailure.ErasureFailed, useCase().exceptionOrNull())
    }
}
