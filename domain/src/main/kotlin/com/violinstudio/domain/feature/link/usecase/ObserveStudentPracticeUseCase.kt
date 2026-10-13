package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Historial de un alumno vinculado (solo lectura; REQ-TVW-04): `startedAt` desc, como mucho 200.
 * Si las reglas deniegan la lectura (vínculo caído) emite una lista vacía y el flujo termina: quien lo recoge debe
 * volver a recogerlo (no hay reintentos aquí).
 */
class ObserveStudentPracticeUseCase @Inject constructor(private val repo: PracticeLogRepository) {
    operator fun invoke(studentUid: String): Flow<List<PracticeSession>> =
        repo.observeHistory(studentUid, PracticeRules.HISTORY_LIMIT)
            .map { list -> list.sortedByDescending { it.startedAt }.take(PracticeRules.HISTORY_LIMIT) }
            .catch { if (it is PracticeFailure.PermissionDenied) emit(emptyList()) else throw it }
}
