package com.violinstudio.data.feature.notification.datasource

/** Token de FCM de este dispositivo. */
interface PushTokenSource {
    suspend fun token(): String

    /** Invalida el token en FCM (el siguiente `token()` dara otro). */
    suspend fun delete()
}

/** Callables `registerDevice` / `unregisterDevice`. */
interface DeviceFunctionsDataSource {
    suspend fun register(token: String)

    suspend fun unregister(token: String)
}
