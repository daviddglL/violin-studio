package com.violinstudio.data.feature.tuner.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Claves del fichero único: todo lo de un usuario cuelga de `u.{uid}.` (los uid de Firebase no contienen puntos). */
object UserKeys {
    const val SCHEMA_VERSION = 1

    fun prefix(uid: String) = "u.$uid."

    fun tunerVersion(uid: String) = intPreferencesKey("${prefix(uid)}tuner.v")

    fun referenceHz(uid: String) = doublePreferencesKey("${prefix(uid)}tuner.reference_hz")

    fun maxCents(uid: String) = intPreferencesKey("${prefix(uid)}tuner.max_cents")

    fun presets(uid: String) = stringPreferencesKey("${prefix(uid)}tuner.presets")

    fun selectedPreset(uid: String) = stringPreferencesKey("${prefix(uid)}tuner.selected_preset")

    fun belongsTo(key: Preferences.Key<*>, uid: String) = key.name.startsWith(prefix(uid))
}
