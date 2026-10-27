package com.violinstudio.ui.feature.intonation.viewmodel

import com.violinstudio.domain.feature.intonation.model.IntonationExercise
import com.violinstudio.domain.feature.intonation.model.IntonationSummary
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import com.violinstudio.ui.feature.practice.viewmodel.ToolPracticeUi

enum class IntonationMic { OK, DENIED, BLOCKED }

enum class IntonationError { MIC_BUSY, MIC_UNAVAILABLE, UNKNOWN }

/**
 * [exercise] `null` si la ruta trae un id desconocido. [expected] es la nota que toca (`null` al acabar) y [cents] la
 * desviacion de la ultima lectura que era esa nota. [summary] solo al acabar; [isNewBest] si supero a [best] (la
 * mejor previa del dispositivo, que se actualiza al guardar).
 */
data class IntonationState(
    val exercise: IntonationExercise? = null,
    val isListening: Boolean = false,
    val index: Int = 0,
    val expected: Note? = null,
    val cents: Double? = null,
    val summary: IntonationSummary? = null,
    val best: Int? = null,
    val isNewBest: Boolean = false,
    val mic: IntonationMic = IntonationMic.OK,
    val error: IntonationError? = null,
    /** Al pausar o acabar con 1 min o mas escuchando, la oferta de guardarlo como practica. */
    val toolPractice: ToolPracticeUi = ToolPracticeUi()
) : UiState {
    val total: Int get() = exercise?.notes?.size ?: 0
}

sealed interface IntonationIntent : UiIntent {
    /** Empezar o continuar; la pantalla lee el permiso en la Activity. */
    data class Start(val granted: Boolean) : IntonationIntent

    data class PermissionResult(val granted: Boolean, val rationale: Boolean) : IntonationIntent

    /** Pausa (boton o `ON_STOP`): suelta el micro y conserva lo hecho. Idempotente. */
    data object Pause : IntonationIntent

    data object Skip : IntonationIntent

    data object Restart : IntonationIntent

    data object OpenAppSettings : IntonationIntent

    /** Guarda como practica el tiempo escuchando (solo si hay oferta). */
    data object SaveToolPractice : IntonationIntent

    data object DismissToolPractice : IntonationIntent
}

sealed interface IntonationEffect : UiEffect {
    data object RequestMicPermission : IntonationEffect

    data object OpenAppSettings : IntonationEffect
}
