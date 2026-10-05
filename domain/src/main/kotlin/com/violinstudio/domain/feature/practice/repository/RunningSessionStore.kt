package com.violinstudio.domain.feature.practice.repository

import com.violinstudio.domain.feature.practice.model.RunningSession
import kotlinx.coroutines.flow.Flow

/** Sesión en curso persistida en local por uid (nunca en Firestore): sobrevive a la muerte del proceso. */
interface RunningSessionStore {
    fun observe(uid: String): Flow<RunningSession?>

    suspend fun start(uid: String, session: RunningSession)

    suspend fun clear(uid: String)
}
