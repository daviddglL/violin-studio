package com.violinstudio.domain.feature.auth.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.notification.repository.PushTokenRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Antes de cerrar la sesion da de baja el token de avisos de este dispositivo (necesita la sesion para el callable). Es
 * lo mejor posible y acotado a [UNREGISTER_TIMEOUT_MS]: sin conexion se cierra igual (el servidor retira el token
 * cuando FCM lo invalide, y un dispositivo compartido lo quita de la cuenta anterior al registrarse otra).
 */
class SignOutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val push: PushTokenRepository
) {
    suspend operator fun invoke() {
        val uid = auth.authUser.first()?.uid
        if (uid != null) withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) { push.unregister(uid) }
        auth.signOut()
    }

    companion object {
        const val UNREGISTER_TIMEOUT_MS = 5_000L
    }
}
