package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Único campo editable de una sesión guardada. Recorta; vacío borra las notas. */
class UpdatePracticeNotesUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: PracticeLogRepository
) {
    suspend operator fun invoke(id: String, notes: String?): Result<Unit> {
        val clean = PracticeRules.notes(notes).getOrElse { return Result.failure(it) }
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        return repo.updateNotes(uid, id, clean)
    }
}
