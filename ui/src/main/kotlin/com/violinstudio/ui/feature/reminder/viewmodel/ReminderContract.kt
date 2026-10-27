package com.violinstudio.ui.feature.reminder.viewmodel

import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import java.time.LocalTime

/** [loaded]: ya se leyo el ajuste guardado. [saveFailed]: el ultimo cambio no se pudo guardar y se ve lo anterior. */
data class ReminderState(
    val reminder: PracticeReminder = PracticeReminder(),
    val loaded: Boolean = false,
    val saveFailed: Boolean = false
) : UiState

sealed interface ReminderIntent : UiIntent {
    data class Toggle(val enabled: Boolean) : ReminderIntent

    data class SetTime(val time: LocalTime) : ReminderIntent
}

sealed interface ReminderEffect : UiEffect
