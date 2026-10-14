package com.violinstudio.ui.feature.teacher.viewmodel

import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** [Unavailable] es genérico a propósito: no distingue "no eres profesor" de "sin consentimiento". */
enum class CodesError { LimitReached, Unavailable, TryAgain, RevokeFailed }

data class TeacherCodesState(
    val isLoading: Boolean = true,

    /** Códigos activos tal como los lista el servidor: solo [TeacherCode.hint]; nunca el código en claro. */
    val codes: List<TeacherCode> = emptyList(),
    val loadFailed: Boolean = false,
    val isCreating: Boolean = false,

    /** El único sitio donde vive el código en claro: solo memoria del ViewModel, sin `SavedStateHandle`. */
    val created: TeacherCode? = null,
    val copied: Boolean = false,
    val revokeTarget: TeacherCode? = null,
    val isRevoking: Boolean = false,
    val error: CodesError? = null
) : UiState {
    companion object {
        const val MAX_ACTIVE_CODES = 5
    }

    /** Un código sin cerrar ocupa un hueco y su texto en claro no se recupera: no se crea otro hasta pulsar "Hecho". */
    val canCreate: Boolean
        get() = !isLoading && !loadFailed && !isCreating && created == null && codes.size < MAX_ACTIVE_CODES
}

sealed interface TeacherCodesIntent : UiIntent {
    data object Create : TeacherCodesIntent
    data object DismissCreated : TeacherCodesIntent
    data object Copy : TeacherCodesIntent
    data object Share : TeacherCodesIntent
    data class RequestRevoke(val code: TeacherCode) : TeacherCodesIntent
    data object CancelRevoke : TeacherCodesIntent
    data object ConfirmRevoke : TeacherCodesIntent
    data object Retry : TeacherCodesIntent

    /** Recarga silenciosa (al volver a la pantalla o tras un fallo que sugiere lista desactualizada). */
    data object Refresh : TeacherCodesIntent
}

/** Efectos de un solo uso; llevan el código en claro, así que su `toString` lo oculta. */
sealed interface TeacherCodesEffect : UiEffect {
    data class CopyToClipboard(val code: String) : TeacherCodesEffect {
        override fun toString() = "CopyToClipboard"
    }

    data class ClearClipboard(val code: String) : TeacherCodesEffect {
        override fun toString() = "ClearClipboard"
    }

    data class ShareCode(val code: String) : TeacherCodesEffect {
        override fun toString() = "ShareCode"
    }
}
