package com.violinstudio.home

import com.violinstudio.core.data.health.HealthRepository
import com.violinstudio.core.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val health: HealthRepository
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {

    override suspend fun handleIntent(intent: HomeIntent) = when (intent) {
        HomeIntent.CheckHealth -> checkHealth()
    }

    private suspend fun checkHealth() {
        reduce(HomeMutation.Loading)
        health.check().fold(
            onSuccess = { reduce(HomeMutation.Loaded(it.version)) },
            onFailure = { error ->
                reduce(HomeMutation.Failed(error.message))
                sendEffect(HomeEffect.ShowError(error.message))
            }
        )
    }

    private fun reduce(mutation: HomeMutation) = setState { HomeReducer.reduce(this, mutation) }
}
