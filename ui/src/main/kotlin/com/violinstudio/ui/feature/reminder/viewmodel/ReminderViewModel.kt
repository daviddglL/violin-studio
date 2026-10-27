package com.violinstudio.ui.feature.reminder.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeReminderUseCase
import com.violinstudio.domain.feature.practice.usecase.SetPracticeReminderUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Recordatorio diario de los ajustes. Solo guarda el ajuste: programar o anular el aviso lo hace la sincronizacion del
 * proceso al verlo cambiar, asi que esta pantalla no toca WorkManager.
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    observe: ObservePracticeReminderUseCase,
    private val set: SetPracticeReminderUseCase
) : MviViewModel<ReminderState, ReminderIntent, ReminderEffect>(ReminderState()) {
    init {
        viewModelScope.launch {
            try {
                observe().collect { setState { copy(reminder = it, loaded = true) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setState { copy(loaded = true) }
            }
        }
    }

    override suspend fun handleIntent(intent: ReminderIntent) {
        val current = state.value.reminder
        when (intent) {
            is ReminderIntent.Toggle -> save(current.copy(enabled = intent.enabled))
            is ReminderIntent.SetTime -> save(current.copy(time = intent.time))
        }
    }

    private suspend fun save(next: PracticeReminder) {
        if (!state.value.loaded) return
        val failed = set(next).isFailure
        setState { copy(saveFailed = failed) }
    }
}
