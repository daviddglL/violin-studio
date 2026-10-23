package com.violinstudio.domain.feature.notification.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.notification.repository.PushTokenRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Registra el token del dispositivo para [uid] (la sesion lo llama con los avisos encendidos). */
class SyncPushTokenUseCase @Inject constructor(private val push: PushTokenRepository) {
    suspend operator fun invoke(uid: String): Result<Unit> = push.register(uid)
}

/** FCM roto el token: se registra el nuevo para quien tenga la sesion abierta (sin sesion no hay nada que hacer). */
class RefreshPushTokenUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val push: PushTokenRepository
) {
    suspend operator fun invoke(token: String): Result<Unit> {
        val uid = auth.authUser.first()?.uid ?: return Result.success(Unit)
        return push.onNewToken(uid, token)
    }
}
