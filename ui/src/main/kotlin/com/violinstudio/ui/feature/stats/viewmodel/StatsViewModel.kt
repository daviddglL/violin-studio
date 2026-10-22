package com.violinstudio.ui.feature.stats.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.achievement.model.Achievements
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeStatsUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Escucha las estadisticas del historial y el curriculo mientras vive, y calcula los logros con ambos. Un fallo del
 * historial (p. ej. `PermissionDenied` al cerrar sesion) se convierte en estado de error, nunca en crash; `Retry`
 * vuelve a escuchar.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val observeStats: ObservePracticeStatsUseCase,
    private val observeCurriculum: ObserveCurriculumUseCase
) : MviViewModel<StatsState, StatsIntent, StatsEffect>(StatsState()) {
    private var job: Job? = null

    init {
        listen()
    }

    override suspend fun handleIntent(intent: StatsIntent) = when (intent) {
        StatsIntent.Retry -> listen()
    }

    private fun listen() {
        job?.cancel()
        reduce(StatsMutation.Loading)
        job = viewModelScope.launch {
            combine(observeStats(), observeCurriculum()) { stats, curriculum ->
                StatsMutation.Loaded(stats, Achievements.of(stats, curriculum.done))
            }
                .catch { reduce(StatsMutation.Failed) }
                .collect { reduce(it) }
        }
    }

    private fun reduce(mutation: StatsMutation) = setState { StatsReducer.reduce(this, mutation) }
}
