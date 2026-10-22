package com.violinstudio.domain.feature.consent.repository

import com.violinstudio.domain.feature.consent.model.IdentityConfig

/**
 * Ultima [IdentityConfig] que devolvio el servidor a cada usuario, guardada en el dispositivo para arrancar sin
 * conexion (afinador, metronomo, registro). Nunca falla: sin dato o con un error de E/S, [lastKnown] es `null` y
 * [remember] no hace nada. Se borra con el resto de datos locales del usuario.
 */
interface IdentityConfigCache {
    suspend fun lastKnown(uid: String): IdentityConfig?

    suspend fun remember(uid: String, config: IdentityConfig)
}
