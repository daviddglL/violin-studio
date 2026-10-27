package com.violinstudio.ui.feature.home.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeTipsUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class PracticeTipsState(val tips: List<PracticeTip> = emptyList()) : UiState

/** Sin intenciones: los consejos solo se observan. */
sealed interface PracticeTipsIntent : UiIntent

sealed interface PracticeTipsEffect : UiEffect

/** Consejos de practica de inicio; si el historial no se puede leer no se muestra ninguno (nunca bloquea). */
@HiltViewModel
class PracticeTipsViewModel @Inject constructor(observeTips: ObservePracticeTipsUseCase) :
    MviViewModel<PracticeTipsState, PracticeTipsIntent, PracticeTipsEffect>(PracticeTipsState()) {
    init {
        viewModelScope.launch {
            observeTips().catch { emit(emptyList()) }.collect { setState { copy(tips = it) } }
        }
    }

    override suspend fun handleIntent(intent: PracticeTipsIntent) = Unit
}
