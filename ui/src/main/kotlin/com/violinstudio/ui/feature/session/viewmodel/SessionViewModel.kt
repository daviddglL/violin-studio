package com.violinstudio.ui.feature.session.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val observeSession: ObserveSessionStateUseCase,
    private val signOut: SignOutUseCase,
    private val backoff: RetryBackoff
) : MviViewModel<SessionUiState, SessionIntent, SessionEffect>(SessionUiState()) {

    init {
        viewModelScope.launch { collectSessionForever() }
    }

    /**
     * El caso de uso no termina por fallos de repositorio; esto es la última red de seguridad: ante un fallo
     * inesperado (o un final inesperado del flujo) emite `Unavailable`, que no da acceso, y vuelve a suscribirse
     * con espera exponencial. Nunca deja un colector muerto.
     */
    private suspend fun collectSessionForever() {
        var attempt = 0
        while (true) {
            try {
                observeSession().collect { session ->
                    attempt = 0
                    setState { copy(session = session) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Sin registrar la causa: puede contener datos personales.
            }
            setState { copy(session = SessionState.Unavailable) }
            delay(backoff.delayFor(attempt++))
        }
    }

    override suspend fun handleIntent(intent: SessionIntent) = when (intent) {
        SessionIntent.SignOut -> signOutSafely()
    }

    // Una excepción aquí mataría el bucle de intents (ver MviViewModel); el fallo se descarta y la sesión
    // sigue su curso: si el cierre no se produjo, el usuario puede reintentarlo.
    private suspend fun signOutSafely() {
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa.
        }
    }
}
