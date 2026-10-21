package com.violinstudio.ui.feature.curriculum.viewmodel

import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class CurriculumMessage { SAVE_FAILED }

/** [progress] `null` mientras carga. [expandedId]: la leccion abierta (como mucho una). */
data class CurriculumState(
    val progress: CurriculumProgress? = null,
    val expandedId: String? = null,
    val message: CurriculumMessage? = null
) : UiState

sealed interface CurriculumIntent : UiIntent {
    /** Abre la leccion [id] o la cierra si ya estaba abierta. */
    data class Toggle(val id: String) : CurriculumIntent

    data class SetDone(val id: String, val done: Boolean) : CurriculumIntent

    data object DismissMessage : CurriculumIntent
}

/** Sin efectos de un solo uso: todo pasa por el estado. */
sealed interface CurriculumEffect : UiEffect

sealed interface CurriculumMutation {
    data class Loaded(val progress: CurriculumProgress) : CurriculumMutation

    data class Toggled(val id: String) : CurriculumMutation

    data object SaveFailed : CurriculumMutation

    data object MessageDismissed : CurriculumMutation
}
