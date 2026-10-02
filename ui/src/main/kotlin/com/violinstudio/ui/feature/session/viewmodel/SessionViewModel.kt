package com.violinstudio.ui.feature.session.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltViewModel
class SessionViewModel @Inject constructor(
    observeSession: ObserveSessionStateUseCase,
    private val signOut: SignOutUseCase
) : MviViewModel<SessionUiState, SessionIntent, SessionEffect>(SessionUiState()) {

    init {
        // El caso de uso no termina por fallos de repositorio; el catch es la última red de seguridad y
        // nunca deja pasar a una ruta de negocio: Unavailable no da acceso.
        observeSession()
            .onEach { session -> setState { copy(session = session) } }
            .catch { setState { copy(session = SessionState.Unavailable) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: SessionIntent) = when (intent) {
        SessionIntent.SignOut -> signOut()
    }
}
