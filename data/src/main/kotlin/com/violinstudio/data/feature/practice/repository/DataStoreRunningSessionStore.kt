package com.violinstudio.data.feature.practice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.feature.tuner.utils.UserKeys
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import com.violinstudio.domain.feature.profile.model.Instrument
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Sesión en curso en el DataStore por usuario. Leer nunca falla: un error de E-S o un valor ausente, de otro tipo o
 * desconocido es "sin sesión". Escribir traduce el error de E-S a [PracticeFailure.Unknown].
 */
@Singleton
class DataStoreRunningSessionStore @Inject constructor(private val store: DataStore<Preferences>) :
    RunningSessionStore {
    override fun observe(uid: String): Flow<RunningSession?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it, uid) }
        .distinctUntilChanged()

    override suspend fun start(uid: String, session: RunningSession) = guarded {
        store.edit {
            it[UserKeys.runningId(uid)] = session.id
            it[UserKeys.runningStartedAt(uid)] = session.startedAt.toEpochMilli()
            it[UserKeys.runningInstrument(uid)] = session.instrument.wire
        }
    }

    override suspend fun clear(uid: String) = guarded {
        store.edit { prefs ->
            listOf(UserKeys.runningId(uid), UserKeys.runningStartedAt(uid), UserKeys.runningInstrument(uid))
                .forEach { prefs.remove(it) }
        }
    }

    // `prefs[key]` lanza ClassCastException si el valor guardado es de otro tipo: se lee por nombre y con `as?`.
    private fun read(prefs: Preferences, uid: String): RunningSession? {
        val values = prefs.asMap().mapKeys { it.key.name }
        val id = (values[UserKeys.runningId(uid).name] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val startedAt = values[UserKeys.runningStartedAt(uid).name] as? Long ?: return null
        val instrument = Instrument.fromWire(values[UserKeys.runningInstrument(uid).name] as? String) ?: return null
        return RunningSession(id, Instant.ofEpochMilli(startedAt), instrument)
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            throw PracticeFailure.Unknown
        }
    }
}
