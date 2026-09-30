package com.violinstudio.ui.feature.home.viewmodel

import com.violinstudio.domain.feature.health.usecase.CheckHealthUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val checkHealth: CheckHealthUseCase
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {

    override suspend fun handleIntent(intent: HomeIntent) = when (intent) {
        HomeIntent.CheckHealth -> onCheckHealth()
    }

    private suspend fun onCheckHealth() {
        reduce(HomeMutation.Loading)
        checkHealth().fold(
            onSuccess = { reduce(HomeMutation.Loaded(it.version)) },
            onFailure = { error ->
                reduce(HomeMutation.Failed(error.message))
                sendEffect(HomeEffect.ShowError(error.message))
            }
        )
    }

    private fun reduce(mutation: HomeMutation) = setState { HomeReducer.reduce(this, mutation) }
}
