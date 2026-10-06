package com.violinstudio.ui.feature.practice.viewmodel

import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import java.time.Instant

/** Aviso o error que la pantalla muestra hasta [PracticeIntent.DismissMessage]. */
enum class PracticeMessage { TOO_SHORT, CLAMPED, NOTES_TOO_LONG, PERMISSION_DENIED, UNKNOWN }

data class PracticeState(
    /** `true` solo con `SessionState.Ready`; sin él el estado está vacío (nada de otro usuario). */
    val ready: Boolean = false,
    /** Arranca en el instrumento del perfil; el selector es local y nunca escribe el perfil. */
    val instrument: Instrument = Instrument.OTHER,
    val instrumentChosen: Boolean = false,
    val running: RunningSession? = null,
    /** Derivado de `running.startedAt` y el reloj en cada tick de 1 s. */
    val elapsedSec: Long = 0,
    val history: List<PracticeSession> = emptyList(),
    val weeklyTotalSec: Int = 0,
    /** Diálogo de parar: guardar con notas, descartar o seguir. */
    val showSave: Boolean = false,
    val draftNotes: String = "",
    val confirmDeleteId: String? = null,
    /** Inicio, parada, edición o borrado en curso: se ignoran más toques. */
    val busy: Boolean = false,
    val message: PracticeMessage? = null
) : UiState {
    /** Longitud tras recortar, igual que valida el dominio. */
    val notesCount: Int get() = draftNotes.trim().length
    val notesTooLong: Boolean get() = notesCount > PracticeRules.NOTES_MAX
}

sealed interface PracticeIntent : UiIntent {
    data object Start : PracticeIntent

    /** Abre el diálogo de guardar; la sesión sigue corriendo hasta [Save] o [Discard]. */
    data object Stop : PracticeIntent

    data class EditNotes(val notes: String) : PracticeIntent

    data object Save : PracticeIntent

    data object Discard : PracticeIntent

    data object DismissSave : PracticeIntent

    data class SelectInstrument(val instrument: Instrument) : PracticeIntent

    /** Único campo editable de una sesión guardada. */
    data class UpdateNotes(val id: String, val notes: String) : PracticeIntent

    data class RequestDelete(val id: String) : PracticeIntent

    data object ConfirmDelete : PracticeIntent

    data object CancelDelete : PracticeIntent

    data object DismissMessage : PracticeIntent

    /** Vuelve a escuchar historial y total tras un fallo. */
    data object Retry : PracticeIntent

    /**
     * Internos: emisiones de los flujos, procesadas en orden con el resto. Las de datos llevan la generacion de
     * suscripcion que las produjo: una emision en cola de otro usuario o de una suscripcion cancelada se descarta.
     */
    data class SessionChanged(val uid: String?, val instrument: Instrument) : PracticeIntent

    data class SessionFailed(val failure: Throwable) : PracticeIntent

    data class RunningChanged(val running: RunningSession?, val generation: Int) : PracticeIntent

    data object Tick : PracticeIntent

    data class HistoryLoaded(val history: List<PracticeSession>, val generation: Int) : PracticeIntent

    data class WeeklyLoaded(val seconds: Int, val generation: Int) : PracticeIntent

    data class StreamFailed(val failure: Throwable, val clearData: Boolean, val generation: Int) : PracticeIntent
}

/** La pantalla no recibe efectos: avisos y diálogos viven en el estado. */
sealed interface PracticeEffect : UiEffect

sealed interface PracticeMutation {
    data class SessionOpened(val instrument: Instrument) : PracticeMutation

    data object SessionClosed : PracticeMutation

    data class RunningChanged(val running: RunningSession?, val now: Instant) : PracticeMutation

    data class Tick(val now: Instant) : PracticeMutation

    data class HistoryLoaded(val history: List<PracticeSession>) : PracticeMutation

    data class WeeklyLoaded(val seconds: Int) : PracticeMutation

    data class StreamFailed(val failure: Throwable, val clearData: Boolean) : PracticeMutation

    data class InstrumentSelected(val instrument: Instrument) : PracticeMutation

    /** Instrumento del perfil: solo cuenta mientras el usuario no haya usado el selector. */
    data class ProfileInstrumentChanged(val instrument: Instrument) : PracticeMutation

    data object SaveOpened : PracticeMutation

    data object SaveClosed : PracticeMutation

    data class NotesEdited(val notes: String) : PracticeMutation

    data class Busy(val busy: Boolean) : PracticeMutation

    data class Failed(val failure: Throwable) : PracticeMutation

    data class Stopped(val clamped: Boolean) : PracticeMutation

    data class DeleteRequested(val id: String?) : PracticeMutation

    data object MessageDismissed : PracticeMutation
}
