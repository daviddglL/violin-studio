package com.violinstudio.data.feature.account.repository

import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.feature.account.utils.AccountState
import com.violinstudio.data.feature.account.utils.AccountStateClassifier
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.repository.AccountRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class AccountRepositoryImpl @Inject constructor(
    private val functions: IdentityFunctionsDataSource,
    private val auth: AuthRemoteDataSource
) : AccountRepository {
    /**
     * Tras borrar en el servidor el SDK aún cree que hay sesión hasta el siguiente refresco del token: se cierra la
     * sesión local para que `authUser` emita `null`. En cualquier fallo la sesión se mantiene.
     *
     * `UNAUTHENTICATED` NO prueba que la cuenta se borrara (también lo devuelve un ID token o un App Check
     * inválidos): se verifica con `reload()` y solo `GONE` cuenta como "ya borrada" (respuesta perdida en un
     * reintento). Si la cuenta existe es [AccountFailure.Unauthenticated]; si no se puede saber, `Unknown`.
     */
    override suspend fun deleteAccount(): Result<Unit> {
        try {
            functions.deleteAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is FunctionsCallException && e.code == UNAUTHENTICATED) return resolveUnauthenticated(e)
            return Result.failure(FunctionsErrorMapper.toAccountFailure(e))
        }
        return signOutLocally()
    }

    private suspend fun resolveUnauthenticated(cause: Exception): Result<Unit> {
        val reloadError = try {
            auth.reloadCurrentUser()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        return when (AccountStateClassifier.classify(reloadError)) {
            AccountState.GONE -> signOutLocally()
            AccountState.EXISTS -> Result.failure(AccountFailure.Unauthenticated)
            AccountState.UNKNOWN -> Result.failure(AccountFailure.Unknown(cause))
        }
    }

    // El borrado ya ocurrió: un fallo del cierre local no lo invalida (authUser se corrige en el siguiente refresco).
    private fun signOutLocally(): Result<Unit> {
        try {
            auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // intencionadamente ignorado
        }
        return Result.success(Unit)
    }

    private companion object {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
    }
}
