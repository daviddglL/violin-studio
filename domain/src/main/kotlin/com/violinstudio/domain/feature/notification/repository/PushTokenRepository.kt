package com.violinstudio.domain.feature.notification.repository

/**
 * Token de avisos push (FCM) de este dispositivo. Nunca lanza salvo cancelacion: los fallos son `Result.failure`.
 * Solo se pide el token cuando el servidor tiene los avisos encendidos: sin registro, FCM no genera nada.
 */
interface PushTokenRepository {
    /** Pide el token y lo registra en el servidor para [uid]; lo recuerda en el dispositivo para darlo de baja. */
    suspend fun register(uid: String): Result<Unit>

    /** Si [uid] registro un token en este dispositivo, lo da de baja en el servidor y borra el token de FCM. */
    suspend fun unregister(uid: String): Result<Unit>

    /** FCM cambio el token: si [uid] tenia uno registrado aqui, se registra el nuevo (si no, no hace nada). */
    suspend fun onNewToken(uid: String, token: String): Result<Unit>
}
