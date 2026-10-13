package com.violinstudio.domain.feature.link.repository

import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import kotlinx.coroutines.flow.Flow

/**
 * Los fallos de las operaciones llegan como `Result.failure(LinkFailure)`; los de los flujos, como excepción
 * `LinkFailure` al recoger (p. ej. `PermissionDenied` si cae el vínculo).
 */
interface LinkRepository {
    /** Vínculos en los que [uid] es el alumno. */
    fun observeAsStudent(uid: String): Flow<List<TeacherLink>>

    /** Vínculos en los que [uid] es el profesor. */
    fun observeAsTeacher(uid: String): Flow<List<TeacherLink>>

    /** Canjea [code] (ya normalizado) aceptando el aviso [ackVersion]; devuelve el uid del profesor. */
    suspend fun redeem(code: String, ackVersion: Int): Result<String>

    suspend fun unlink(teacherUid: String, studentUid: String): Result<Unit>

    /** El código en claro solo viene en este resultado. */
    suspend fun createCode(): Result<TeacherCode>

    suspend fun listCodes(): Result<List<TeacherCode>>

    suspend fun revokeCode(id: String): Result<Unit>
}
