package com.violinstudio.data.feature.notification.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.feature.notification.datasource.DeviceFunctionsDataSource
import com.violinstudio.data.feature.notification.datasource.PushTokenSource
import com.violinstudio.domain.feature.notification.repository.PushTokenRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * El token registrado se recuerda en `u.{uid}.push.token` (el eraser de datos locales lo borra con el resto del uid):
 * sin el no se da de baja nada ni se llama a FCM, asi que con los avisos apagados la app nunca genera un token.
 */
@Singleton
class PushTokenRepositoryImpl @Inject constructor(
    private val source: PushTokenSource,
    private val functions: DeviceFunctionsDataSource,
    private val store: DataStore<Preferences>
) : PushTokenRepository {
    override suspend fun register(uid: String): Result<Unit> = attempt {
        val token = source.token()
        functions.register(token)
        store.edit { it[UserKeys.pushToken(uid)] = token }
    }

    override suspend fun unregister(uid: String): Result<Unit> = attempt {
        val token = stored(uid)
        if (token != null) {
            // Cada paso por separado: sin conexion se sigue con el resto (el servidor retira el token cuando FCM lo
            // invalida).
            val server = runSafely { functions.unregister(token) }
            val fcm = runSafely { source.delete() }
            store.edit { it.remove(UserKeys.pushToken(uid)) }
            (server ?: fcm)?.let { throw it }
        }
    }

    override suspend fun onNewToken(uid: String, token: String): Result<Unit> = attempt {
        if (stored(uid) != null) {
            functions.register(token)
            store.edit { it[UserKeys.pushToken(uid)] = token }
        }
    }

    private suspend fun stored(uid: String): String? {
        val name = UserKeys.pushToken(uid).name
        return store.data.first().asMap().entries.firstOrNull { it.key.name == name }?.value as? String
    }

    private suspend fun runSafely(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e
    }

    private inline fun attempt(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
