package com.violinstudio.domain.feature.curriculum.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.domain.feature.curriculum.repository.LessonProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Curriculo con el progreso del usuario con sesion; sin sesion, todo sin hacer. */
class ObserveCurriculumUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: LessonProgressRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<CurriculumProgress> = auth.authUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(CurriculumProgress.of(emptySet()))
        } else {
            repo.observeCompleted(user.uid).map { CurriculumProgress.of(it) }
        }
    }
}

/** Marca o desmarca una leccion como hecha. Fallos como `Result.failure(CurriculumFailure)`. */
class SetLessonCompletedUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: LessonProgressRepository
) {
    suspend operator fun invoke(lessonId: String, done: Boolean): Result<Unit> {
        if (!Curriculum.contains(lessonId)) return Result.failure(CurriculumFailure.UnknownLesson)
        val uid = auth.authUser.first()?.uid ?: return Result.failure(CurriculumFailure.NoSession)
        return try {
            repo.setCompleted(uid, lessonId, done)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CurriculumFailure) {
            Result.failure(e)
        } catch (_: Exception) {
            Result.failure(CurriculumFailure.Storage)
        }
    }
}
