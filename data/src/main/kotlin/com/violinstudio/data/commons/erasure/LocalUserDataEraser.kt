package com.violinstudio.data.commons.erasure

/**
 * Borra los datos locales de un uid al eliminar su cuenta (nunca al cerrar sesion, D8). Cada feature que guarde datos
 * locales por usuario registra el suyo con `@Binds @IntoSet` en `ErasureModule`. Deben ser idempotentes; si fallan,
 * `AccountRepositoryImpl` lo registra (sin PII) y sigue con los demas.
 */
interface LocalUserDataEraser {
    suspend fun erase(uid: String)
}
