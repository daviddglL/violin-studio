package com.violinstudio.ui.feature.practice.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.RunningSession
import java.time.Duration
import java.time.Instant

object PracticeReducer {
    /** Avisos de una operacion ya hecha: un fallo de flujo posterior no los pisa. */
    private val INFORMATIVE = setOf(PracticeMessage.CLAMPED, PracticeMessage.TOO_SHORT)

    fun reduce(state: PracticeState, mutation: PracticeMutation): PracticeState = when (mutation) {
        is PracticeMutation.SessionOpened ->
            PracticeState(ready = true, instrument = mutation.instrument)
        PracticeMutation.SessionClosed -> PracticeState()
        is PracticeMutation.RunningChanged -> {
            val running = mutation.running
            state.copy(
                running = running,
                elapsedSec = elapsed(running, mutation.now),
                showSave = state.showSave && running != null,
                draftNotes = if (running == null) "" else state.draftNotes
            )
        }
        is PracticeMutation.Tick -> state.copy(elapsedSec = elapsed(state.running, mutation.now))
        is PracticeMutation.HistoryLoaded -> state.copy(history = mutation.history)
        is PracticeMutation.WeeklyLoaded -> state.copy(weeklyTotalSec = mutation.seconds)
        is PracticeMutation.StreamFailed ->
            state.copy(
                history = if (mutation.clearData) emptyList() else state.history,
                weeklyTotalSec = if (mutation.clearData) 0 else state.weeklyTotalSec,
                message = state.message.takeIf { it in INFORMATIVE } ?: messageOf(mutation.failure)
            )
        is PracticeMutation.InstrumentSelected ->
            state.copy(instrument = mutation.instrument, instrumentChosen = true)
        is PracticeMutation.ProfileInstrumentChanged ->
            if (state.instrumentChosen) state else state.copy(instrument = mutation.instrument)
        PracticeMutation.SaveOpened -> state.copy(showSave = true)
        PracticeMutation.SaveClosed -> state.copy(showSave = false, draftNotes = "")
        is PracticeMutation.NotesEdited -> state.copy(draftNotes = mutation.notes)
        is PracticeMutation.Busy -> state.copy(busy = mutation.busy)
        is PracticeMutation.Failed -> failed(state, mutation.failure)
        is PracticeMutation.Stopped ->
            state.copy(
                running = null,
                elapsedSec = 0,
                showSave = false,
                draftNotes = "",
                message = if (mutation.clamped) PracticeMessage.CLAMPED else state.message
            )
        is PracticeMutation.DeleteRequested -> state.copy(confirmDeleteId = mutation.id)
        PracticeMutation.MessageDismissed -> state.copy(message = null)
    }

    private fun elapsed(running: RunningSession?, now: Instant): Long =
        running?.let { maxOf(0, Duration.between(it.startedAt, now).seconds) } ?: 0

    /** `TooShort` descarta la sesión en curso; `NotRunning` ya no hay nada que guardar. */
    private fun failed(state: PracticeState, failure: Throwable) = when (failure) {
        PracticeFailure.TooShort ->
            state.copy(showSave = false, draftNotes = "", message = PracticeMessage.TOO_SHORT)
        PracticeFailure.NotRunning -> state.copy(showSave = false, draftNotes = "")
        else -> state.copy(message = messageOf(failure))
    }

    private fun messageOf(failure: Throwable) = when (failure) {
        PracticeFailure.NotesTooLong -> PracticeMessage.NOTES_TOO_LONG
        PracticeFailure.PermissionDenied -> PracticeMessage.PERMISSION_DENIED
        else -> PracticeMessage.UNKNOWN
    }
}
