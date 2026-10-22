package com.violinstudio.ui.feature.stats.viewmodel

import com.violinstudio.domain.feature.achievement.model.AchievementProgress
import com.violinstudio.domain.feature.practice.model.PracticeStats
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** [stats] `null` mientras carga o tras un fallo ([failed]); [achievements] van con [stats]. */
data class StatsState(
    val loading: Boolean = true,
    val stats: PracticeStats? = null,
    val achievements: List<AchievementProgress> = emptyList(),
    val failed: Boolean = false
) : UiState

sealed interface StatsIntent : UiIntent {
    /** Tras un fallo vuelve a escuchar el historial. */
    data object Retry : StatsIntent
}

/** Sin efectos de un solo uso: todo pasa por el estado. */
sealed interface StatsEffect : UiEffect

sealed interface StatsMutation {
    data object Loading : StatsMutation

    data class Loaded(val stats: PracticeStats, val achievements: List<AchievementProgress>) : StatsMutation

    data object Failed : StatsMutation
}
