package com.violinstudio.home

object HomeReducer {
    fun reduce(state: HomeState, mutation: HomeMutation): HomeState = when (mutation) {
        HomeMutation.Loading -> state.copy(status = HealthStatus.Loading)
        is HomeMutation.Loaded -> state.copy(status = HealthStatus.Ok(mutation.version))
        is HomeMutation.Failed -> state.copy(status = HealthStatus.Error(mutation.message))
    }
}
