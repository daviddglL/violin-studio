package com.violinstudio.data.feature.link.datasource

import com.violinstudio.data.commons.firebase.RemoteDoc
import kotlinx.coroutines.flow.Flow

/** Frontera con `teacherLinks` (solo lectura; escribe el servidor). Las reglas exigen filtrar por el propio uid. */
interface LinkRemoteDataSource {
    fun observeAsStudent(uid: String): Flow<List<RemoteDoc>>

    fun observeAsTeacher(uid: String): Flow<List<RemoteDoc>>
}
