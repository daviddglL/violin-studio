package com.violinstudio.data.commons.erasure

import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject

/**
 * `clear` borra toda clave `u.{uid}.*` del DataStore (configuracion, presets y la sesion de practica en curso), asi que
 * un solo eraser cubre los datos de todas las features. Un uid con punto no puede tener claves: no hay nada que borrar.
 */
class DataStoreUserDataEraser @Inject constructor(
    private val tunerConfig: TunerConfigRepository
) : LocalUserDataEraser {
    override suspend fun erase(uid: String) {
        try {
            tunerConfig.clear(uid)
        } catch (_: IllegalArgumentException) {
            // uid invalido para el prefijo de claves
        }
    }
}
