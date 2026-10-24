package com.violinstudio.domain.feature.intonation.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.intonation.failure.IntonationFailure
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.domain.feature.intonation.repository.IntonationScoreRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Mejores puntuaciones del usuario con sesion; sin sesion, ninguna. */
class ObserveIntonationBestUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: IntonationScoreRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<Map<String, Int>> =
        auth.authUser.flatMapLatest { user -> user?.let { repo.observeBest(it.uid) } ?: flowOf(emptyMap()) }
}

/** Guarda la puntuacion de un intento si es la mejor. Fallos como `Result.failure(IntonationFailure)`. */
class SaveIntonationScoreUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: IntonationScoreRepository
) {
    suspend operator fun invoke(exerciseId: String, score: Int): Result<Unit> {
        if (IntonationExercises.byId(exerciseId) == null) return Result.failure(IntonationFailure.UnknownExercise)
        val uid = auth.authUser.first()?.uid ?: return Result.failure(IntonationFailure.NoSession)
        return try {
            repo.saveIfBest(uid, exerciseId, score.coerceIn(0, 100))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IntonationFailure) {
            Result.failure(e)
        } catch (_: Exception) {
            Result.failure(IntonationFailure.Storage)
        }
    }
}
