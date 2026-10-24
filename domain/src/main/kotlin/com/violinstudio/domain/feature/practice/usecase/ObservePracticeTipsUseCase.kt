package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.curriculum.model.Lesson
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.domain.feature.practice.model.PracticeTips
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Consejos del usuario con sesion, recalculados con cada cambio del historial o del curriculo. Si el curriculo no se
 * puede leer, los consejos salen sin la siguiente leccion; un fallo del historial se propaga.
 */
class ObservePracticeTipsUseCase @Inject constructor(
    private val stats: ObservePracticeStatsUseCase,
    private val curriculum: ObserveCurriculumUseCase
) {
    operator fun invoke(): Flow<List<PracticeTip>> = combine(
        stats(),
        curriculum().map<_, Lesson?> { it.next }.catch { emit(null) }
    ) { stats, next -> PracticeTips.of(stats, next) }
        .distinctUntilChanged()
}
