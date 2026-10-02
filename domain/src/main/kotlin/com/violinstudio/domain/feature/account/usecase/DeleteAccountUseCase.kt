package com.violinstudio.domain.feature.account.usecase

import com.violinstudio.domain.feature.account.repository.AccountRepository
import javax.inject.Inject

/** `RequiresRecentLogin` se propaga: la UI reautentica y reintenta. No cierra sesión: el borrado invalida el usuario. */
class DeleteAccountUseCase @Inject constructor(private val account: AccountRepository) {
    suspend operator fun invoke(): Result<Unit> = account.deleteAccount()
}
