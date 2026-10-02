package com.violinstudio.domain.feature.account.usecase

import com.violinstudio.domain.feature.account.repository.AccountRepository
import javax.inject.Inject

class DeleteAccountUseCase @Inject constructor(private val account: AccountRepository) {
    suspend operator fun invoke(): Result<Unit> = throw NotImplementedError()
}
