package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.repository.LinkRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Vínculos del usuario con sesión; vacío sin sesión. Si las reglas deniegan la lectura (vínculo caído) emite una lista
 * vacía y ese flujo termina: se vuelve a suscribir cuando `authUser` emite de nuevo o el colector recoge otra vez
 * (sin bucles de reintento aquí).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun Flow<AuthUser?>.links(observe: (String) -> Flow<List<TeacherLink>>): Flow<List<TeacherLink>> =
    flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            observe(user.uid).catch { if (it is LinkFailure.PermissionDenied) emit(emptyList()) else throw it }
        }
    }

/** El vínculo más antiguo del alumno, o `null` si es independiente. */
class ObserveMyTeacherUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: LinkRepository
) {
    private val oldestFirst = compareBy<TeacherLink> { it.createdAt }.thenBy { it.teacherUid }

    operator fun invoke(): Flow<TeacherLink?> =
        auth.authUser.links(repo::observeAsStudent).map { list -> list.minWithOrNull(oldestFirst) }
}

/** Alumnos del profesor por nombre, sin distinguir mayúsculas. */
class ObserveMyStudentsUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: LinkRepository
) {
    operator fun invoke(): Flow<List<TeacherLink>> = auth.authUser.links(repo::observeAsTeacher).map { list ->
        list.sortedBy { it.studentDisplayName.lowercase() }
    }
}
