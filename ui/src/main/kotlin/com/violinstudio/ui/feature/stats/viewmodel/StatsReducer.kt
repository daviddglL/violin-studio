package com.violinstudio.ui.feature.stats.viewmodel

object StatsReducer {
    fun reduce(state: StatsState, mutation: StatsMutation): StatsState = when (mutation) {
        StatsMutation.Loading -> state.copy(loading = true, failed = false)

        is StatsMutation.Loaded -> StatsState(
            loading = false,
            stats = mutation.stats,
            achievements = mutation.achievements
        )

        StatsMutation.Failed -> StatsState(loading = false, stats = null, failed = true)
    }
}
