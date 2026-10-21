package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** Fallos del canje tal y como se muestran: el código inválido es genérico a propósito (no revela el motivo). */
sealed interface RedeemError {
    data object InvalidCode : RedeemError

    /** [minutes] hacia arriba; `null` si el servidor no dijo cuánto esperar. */
    data class RateLimited(val minutes: Int?) : RedeemError
    data object TeacherFull : RedeemError
    data object AlreadyLinked : RedeemError

    /** El aviso que acepta el cliente es más antiguo que el vigente: hay que actualizar la app. */
    data object NoticeOutdated : RedeemError
    data object NotEligible : RedeemError
    data object Offline : RedeemError
    data object TemporarilyUnavailable : RedeemError
    data object Unknown : RedeemError
}

data class RedeemCodeState(
    val code: String = "",
    val accepted: Boolean = false,
    val isLoading: Boolean = false,
    val error: RedeemError? = null,
    val succeeded: Boolean = false
) : UiState {
    /**
     * Tras `RateLimited` o `NoticeOutdated` reenviar no sirve de nada: se rehabilita al editar el código o la casilla.
     * El aviso aceptado y un código escrito; la validez del código la decide el caso de uso sin tocar la red. */
    val canSubmit: Boolean
        get() = accepted && code.isNotBlank() && !isLoading && !succeeded && !error.blocksResubmit()

    // El código es un secreto de un solo uso: no aparece en trazas.
    override fun toString(): String = "RedeemCodeState(accepted=$accepted, isLoading=$isLoading, error=$error)"

    companion object {
        private fun RedeemError?.blocksResubmit() =
            this is RedeemError.RateLimited || this == RedeemError.NoticeOutdated

        /** El servidor rechaza más de 32 caracteres en bruto (espacios y guiones incluidos). */
        const val MAX_INPUT = 32
    }
}

sealed interface RedeemCodeIntent : UiIntent {
    data class CodeChanged(val code: String) : RedeemCodeIntent {
        // El código es un secreto de un solo uso: no aparece en trazas.
        override fun toString(): String = "CodeChanged"
    }
    data class AcceptedChanged(val accepted: Boolean) : RedeemCodeIntent
    data object Submit : RedeemCodeIntent
}

sealed interface RedeemCodeEffect : UiEffect {
    /** Canje hecho: la pantalla vuelve atrás y la sesión (rol `student`) decide el resto. */
    data object Redeemed : RedeemCodeEffect
}
