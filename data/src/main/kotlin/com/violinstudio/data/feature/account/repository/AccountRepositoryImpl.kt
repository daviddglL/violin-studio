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

    /**
     * El borrado ya ocurrio: un fallo del cierre local no lo invalida, pero la pantalla no puede quedar bloqueada con
     * un usuario que ya no existe. Se reintenta [SIGN_OUT_ATTEMPTS] veces y, si sigue fallando, se limpia el estado
     * local de autenticacion para que `authUser` emita `null` igualmente.
     */
    private fun signOutLocally(): Result<Unit> {
        repeat(SIGN_OUT_ATTEMPTS) {
            try {
                auth.signOut()
                return Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // intencionadamente ignorado: se reintenta
            }
        }
        try {
            auth.clearLocalSession()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // ultimo recurso: la UI ofrece ademas cerrar sesion a mano
        }
        return Result.success(Unit)
    }

    private companion object {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val SIGN_OUT_ATTEMPTS = 3
    }
}
