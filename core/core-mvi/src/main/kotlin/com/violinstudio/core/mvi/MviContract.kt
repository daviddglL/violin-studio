package com.violinstudio.core.mvi

/** Estado inmutable de una pantalla. Implementar con data classes. */
interface UiState

/** Acción del usuario o del sistema que la pantalla envía al ViewModel. */
interface UiIntent

/** Evento de consumo único (navegar, snackbar). */
interface UiEffect
