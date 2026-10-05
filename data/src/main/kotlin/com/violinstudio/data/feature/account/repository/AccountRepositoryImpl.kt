package com.violinstudio.data.feature.account.repository

import com.violinstudio.data.commons.erasure.LocalUserDataEraser
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.FunctionsErrorMapper
import com.violinstudio.data.feature.account.utils.AccountState
import com.violinstudio.data.feature.account.utils.AccountStateClassifier
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.repository.AccountRepository
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccountRepositoryImpl @Inject constructor(
    private val functions: IdentityFunctionsDataSource,
    private val auth: AuthRemoteDataSource,
    private val erasers: Set<@JvmSuppressWildcards LocalUserDataEraser>
) : AccountRepository {
    /**
     * Tras borrar en el servidor el SDK aún cree que hay sesión hasta el siguiente refresco del token: se cierra la
     * sesión local para que `authUser` emita `null`. En cualquier fallo la sesión se mantiene.
     *
     * `UNAUTHENTICATED` NO prueba que la cuenta se borrara (también lo devuelve un ID token o un App Check
     * inválidos): se verifica con `reload()` y solo `GONE` cuenta como "ya borrada" (respuesta perdida en un
     * reintento). Si la cuenta existe es [AccountFailure.Unauthenticated]; si no se puede saber, `Unknown`.
     */
    override suspend fun deleteAccount(): Result<Unit> = withContext(NonCancellable) { deleteAccountToCompletion() }

    /**
     * No cancelable: el listener del perfil ve desaparecer `users/{uid}` ANTES de que responda el callable, la sesion
     * cambia, la pantalla (y el ViewModel que borra) se destruye y cancelaria la corrutina antes del cierre local,
     * dejando un usuario fantasma sin Login. Una vez pedido el borrado se termina siempre.
     */
    private suspend fun deleteAccountToCompletion(): Result<Unit> {
        val uid = currentUid()
        try {
            functions.deleteAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is FunctionsCallException && e.code == UNAUTHENTICATED) return resolveUnauthenticated(e, uid)
            return Result.failure(FunctionsErrorMapper.toAccountFailure(e))
        }
        return signOutLocally(uid)
    }

    private suspend fun resolveUnauthenticated(cause: Exception, uid: String?): Result<Unit> {
        val reloadError = try {
            auth.reloadCurrentUser()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        return when (AccountStateClassifier.classify(reloadError)) {
            AccountState.GONE -> signOutLocally(uid)
            AccountState.EXISTS -> Result.failure(AccountFailure.Unauthenticated)
            AccountState.UNKNOWN -> Result.failure(AccountFailure.Unknown(cause))
        }
    }

    /** El uid se toma ANTES de borrar: tras el borrado el SDK puede dejar de emitir al usuario. */
    private suspend fun currentUid(): String? = withTimeoutOrNull(UID_TIMEOUT_MS) { auth.authUser.first()?.uid }

    /** Mejor esfuerzo: un eraser que falla (se registra solo la clase del error, sin PII) no frena a los demas. */
    private suspend fun eraseLocalData(uid: String) {
        erasers.forEach { eraser ->
            try {
                eraser.erase(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.log(Level.WARNING, "Fallo al borrar datos locales: ${e::class.java.simpleName}")
            }
        }
    }

    /**
     * El borrado ya ocurrio: un fallo del cierre local no lo invalida, pero la pantalla no puede quedar bloqueada con
     * un usuario que ya no existe. Se reintenta [SIGN_OUT_ATTEMPTS] veces y, si sigue fallando, se limpia el estado
     * local de autenticacion para que `authUser` emita `null` igualmente.
     */
    private suspend fun signOutLocally(uid: String?): Result<Unit> {
        if (uid != null) eraseLocalData(uid)
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
        const val UID_TIMEOUT_MS = 2_000L
        val LOG: Logger = Logger.getLogger("AccountRepository")
    }
}
