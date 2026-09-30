package com.violinstudio.ui.feature.home.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

sealed interface HealthStatus {
    data object Idle : HealthStatus
    data object Loading : HealthStatus
    data class Ok(val version: String) : HealthStatus

    /** [message] null → la UI muestra "Error desconocido". */
    data class Error(val message: String?) : HealthStatus
}

data class HomeState(val status: HealthStatus = HealthStatus.Idle) : UiState

sealed interface HomeIntent : UiIntent {
    data object CheckHealth : HomeIntent
}

sealed interface HomeEffect : UiEffect {
    data class ShowError(val message: String?) : HomeEffect
}

sealed interface HomeMutation {
    data object Loading : HomeMutation
    data class Loaded(val version: String) : HomeMutation
    data class Failed(val message: String?) : HomeMutation
}
