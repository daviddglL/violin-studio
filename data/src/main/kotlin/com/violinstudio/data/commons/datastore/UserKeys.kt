package com.violinstudio.data.commons.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** Claves del fichero único: todo lo de un usuario cuelga de `u.{uid}.` (los uid de Firebase no contienen puntos). */
object UserKeys {
    const val SCHEMA_VERSION = 1

    fun prefix(uid: String): String {
        require('.' !in uid) { "uid con punto" }
        return "u.$uid."
    }

    fun tunerVersion(uid: String) = intPreferencesKey("${prefix(uid)}tuner.v")

    fun referenceHz(uid: String) = doublePreferencesKey("${prefix(uid)}tuner.reference_hz")

    fun maxCents(uid: String) = intPreferencesKey("${prefix(uid)}tuner.max_cents")

    fun presets(uid: String) = stringPreferencesKey("${prefix(uid)}tuner.presets")

    fun selectedPreset(uid: String) = stringPreferencesKey("${prefix(uid)}tuner.selected_preset")

    fun runningId(uid: String) = stringPreferencesKey("${prefix(uid)}practice.running.id")

    fun runningStartedAt(uid: String) = longPreferencesKey("${prefix(uid)}practice.running.started_at")

    fun runningInstrument(uid: String) = stringPreferencesKey("${prefix(uid)}practice.running.instrument")

    fun reminderEnabled(uid: String) = booleanPreferencesKey("${prefix(uid)}practice.reminder.enabled")

    /** Minuto del dia (0..1439) del recordatorio diario. */
    fun reminderMinute(uid: String) = intPreferencesKey("${prefix(uid)}practice.reminder.minute")

    fun recQueue(uid: String) = stringPreferencesKey("${prefix(uid)}rec.queue")

    fun identityConfig(uid: String) = stringPreferencesKey("${prefix(uid)}session.identity_config")

    fun completedLessons(uid: String) = stringSetPreferencesKey("${prefix(uid)}curriculum.completed")

    /** Token de FCM que este usuario registro desde este dispositivo (para darlo de baja al cerrar sesion). */
    fun pushToken(uid: String) = stringPreferencesKey("${prefix(uid)}push.token")

    /** Prefijo de las mejores puntuaciones de afinacion: `{prefijo}{exerciseId}` (solo en el dispositivo). */
    fun intonationBestPrefix(uid: String) = "${prefix(uid)}intonation.best."

    fun intonationBest(uid: String, exerciseId: String) = intPreferencesKey(intonationBestPrefix(uid) + exerciseId)

    fun belongsTo(key: Preferences.Key<*>, uid: String) = key.name.startsWith(prefix(uid))
}
