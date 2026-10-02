package com.violinstudio.data.feature.account.repository

import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.commons.utils.resultOf
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.repository.AccountRepository
import javax.inject.Inject

class AccountRepositoryImpl @Inject constructor(
    private val functions: IdentityFunctionsDataSource,
    private val auth: AuthRemoteDataSource
) : AccountRepository {
    /**
     * Tras borrar en el servidor el SDK aún cree que hay sesión hasta el siguiente refresco del token: se cierra la
     * sesión local para que `authUser` emita `null`. Si la respuesta se perdió y el reintento llega ya sin usuario
     * (`UNAUTHENTICATED`), la cuenta ya está borrada: se trata como éxito y también se cierra la sesión local.
     * En cualquier otro fallo la sesión se mantiene (el usuario puede reautenticar y reintentar).
     */
    override suspend fun deleteAccount(): Result<Unit> {
        val result = resultOf(FunctionsErrorMapper::toAccountFailure) {
            try {
                functions.deleteAccount()
            } catch (e: FunctionsCallException) {
                if (e.code != UNAUTHENTICATED) throw e
            }
            Unit
        }
        if (result.isSuccess) auth.signOut()
        return result
    }

    private companion object {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
    }
}
