package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.notification.repository.PushTokenRepository

class FakePushTokenRepository : PushTokenRepository {
    val calls = mutableListOf<String>()
    var result: Result<Unit> = Result.success(Unit)

    /** Si es distinto de null, `unregister` se queda colgado este tiempo (para probar el limite). */
    var unregisterDelayMs: Long? = null

    override suspend fun register(uid: String): Result<Unit> {
        calls += "register:$uid"
        return result
    }

    override suspend fun unregister(uid: String): Result<Unit> {
        calls += "unregister:$uid"
        unregisterDelayMs?.let { kotlinx.coroutines.delay(it) }
        return result
    }

    override suspend fun onNewToken(uid: String, token: String): Result<Unit> {
        calls += "onNewToken:$uid:$token"
        return result
    }
}
