package com.violinstudio.domain.feature.account.repository

interface AccountRepository {
    /** Borrado inmediato de la cuenta y sus datos; disponible en cualquier estado con sesión. */
    suspend fun deleteAccount(): Result<Unit>
}
