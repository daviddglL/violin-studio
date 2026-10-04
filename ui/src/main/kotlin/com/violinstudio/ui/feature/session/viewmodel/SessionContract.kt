package com.violinstudio.ui.feature.session.viewmodel

import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

data class SessionUiState(val session: SessionState = SessionState.Loading) : UiState

sealed interface SessionIntent : UiIntent {
    /** Cerrar sesión: disponible desde cualquier estado con sesión. */
    data object SignOut : SessionIntent
}

/** La sesión no emite efectos: la navegación se deriva del estado. */
sealed interface SessionEffect : UiEffect
