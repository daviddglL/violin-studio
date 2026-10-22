package com.violinstudio.data.feature.consent.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.feature.consent.dto.IdentityConfigDto
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.repository.IdentityConfigCache
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Ultima config de `identityConfig` en el DataStore por usuario (`u.{uid}.session.identity_config`): el eraser de datos
 * locales la borra con el resto de claves del uid. Un error de E/S, un valor de otro tipo o un JSON roto es "sin dato";
 * escribir con un error de E/S no hace nada (la siguiente config que llegue lo vuelve a intentar).
 */
@Singleton
class DataStoreIdentityConfigCache @Inject constructor(private val store: DataStore<Preferences>) :
    IdentityConfigCache {
    override suspend fun lastKnown(uid: String): IdentityConfig? {
        val prefs = try {
            store.data.first()
        } catch (_: IOException) {
            return null
        }
        val name = UserKeys.identityConfig(uid).name
        // `prefs[key]` lanza ClassCastException con un valor de otro tipo: se busca por nombre y se castea con `as?`.
        val raw = prefs.asMap().entries.firstOrNull { it.key.name == name }?.value as? String ?: return null
        return runCatching { json.decodeFromString(IdentityConfigDto.serializer(), raw).toDomain() }.getOrNull()
    }

    override suspend fun remember(uid: String, config: IdentityConfig) {
        try {
            val raw = json.encodeToString(IdentityConfigDto.serializer(), IdentityConfigDto.from(config))
            store.edit { it[UserKeys.identityConfig(uid)] = raw }
        } catch (_: IOException) {
            // Sin cache no se pierde nada: con conexion se vuelve a pedir.
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
