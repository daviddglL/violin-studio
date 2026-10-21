package com.violinstudio.data.feature.chat.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow

/**
 * Frontera con Firestore (`chatMessages`). Las escrituras NO esperan al servidor (offline-first): lanzan solo si el
 * SDK rechaza la operacion en local; un rechazo posterior de las reglas retira el doc de la cache.
 */
interface ChatRemoteDataSource {
    /** Las reglas exigen filtrar por los dos uids del par. */
    fun observe(teacherUid: String, studentUid: String): Flow<List<RemoteDoc>>

    fun create(id: String, fields: Map<String, Any>)

    fun delete(id: String)
}
