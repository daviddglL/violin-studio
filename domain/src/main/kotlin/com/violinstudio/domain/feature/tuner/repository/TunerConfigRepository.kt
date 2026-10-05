package com.violinstudio.domain.feature.tuner.repository

import com.violinstudio.domain.feature.tuner.model.TunerConfig
import kotlinx.coroutines.flow.Flow

/** Configuración y presets del afinador, locales y por uid. */
interface TunerConfigRepository {
    /** Config del uid; sin datos o con datos corruptos emite los valores por defecto. */
    fun observe(uid: String): Flow<TunerConfig>

    /** Lectura-modificación-escritura atómica. Si [transform] lanza, no se escribe nada y la excepción se propaga. */
    suspend fun update(uid: String, transform: (TunerConfig) -> TunerConfig)

    /** Elimina todos los datos locales del uid (solo al borrar la cuenta; cerrar sesión NO la invoca). Idempotente. */
    suspend fun clear(uid: String)
}
