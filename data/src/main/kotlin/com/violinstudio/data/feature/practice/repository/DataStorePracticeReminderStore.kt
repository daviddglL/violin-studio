package com.violinstudio.data.feature.practice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.domain.feature.practice.repository.PracticeReminderStore
import java.io.IOException
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Recordatorio en el DataStore bajo `u.{uid}.practice.reminder.*` (el eraser de datos locales lo borra con el resto del
 * usuario). Leer nunca falla: un error de E-S o un valor ausente, de otro tipo o fuera de rango es el de por defecto.
 */
@Singleton
class DataStorePracticeReminderStore @Inject constructor(private val store: DataStore<Preferences>) :
    PracticeReminderStore {
    override fun observe(uid: String): Flow<PracticeReminder> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it, uid) }
        .distinctUntilChanged()

    override suspend fun set(uid: String, reminder: PracticeReminder) {
        try {
            store.edit {
                it[UserKeys.reminderEnabled(uid)] = reminder.enabled
                it[UserKeys.reminderMinute(uid)] = reminder.time.toSecondOfDay() / SECONDS_PER_MINUTE
            }
        } catch (_: IOException) {
            throw PracticeFailure.Unknown
        }
    }

    // Como en la sesion en curso: se lee por nombre y con `as?` para no lanzar con un valor de otro tipo.
    private fun read(prefs: Preferences, uid: String): PracticeReminder {
        val values = prefs.asMap().mapKeys { it.key.name }
        val enabled = values[UserKeys.reminderEnabled(uid).name] as? Boolean ?: false
        val minute = (values[UserKeys.reminderMinute(uid).name] as? Int)?.takeIf { it in 0 until MINUTES_PER_DAY }
        val time = minute?.let { LocalTime.ofSecondOfDay(it.toLong() * SECONDS_PER_MINUTE) }
        return PracticeReminder(enabled, time ?: PracticeReminder.DEFAULT_TIME)
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val MINUTES_PER_DAY = 1_440
    }
}
