package com.violinstudio.ui.feature.practice.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.usecase.DeletePracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.DiscardRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeHistoryUseCase
import com.violinstudio.domain.feature.practice.usecase.ObserveRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StartPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StopPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.UpdatePracticeNotesUseCase
import com.violinstudio.domain.feature.practice.usecase.WeeklyPracticeTotalUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vive solo con `SessionState.Ready`: sin él cancela los flujos y vacía el estado. Un fallo de historial o total
 * (p. ej. `PermissionDenied` al cerrar sesión) se convierte en mensaje y estado vacío, nunca en crash; `Retry`
 * vuelve a escuchar. El cronómetro se deriva de `startedAt` y el reloj en cada tick de 1 s.
 */
@HiltViewModel
class PracticeViewModel(
    private val observeSession: ObserveSessionStateUseCase,
    private val observeRunning: ObserveRunningSessionUseCase,
    private val observeHistory: ObservePracticeHistoryUseCase,
    private val weeklyTotal: WeeklyPracticeTotalUseCase,
    private val startSession: StartPracticeSessionUseCase,
    private val stopSession: StopPracticeSessionUseCase,
    private val discardSession: DiscardRunningSessionUseCase,
    private val updateNotes: UpdatePracticeNotesUseCase,
    private val deleteSession: DeletePracticeSessionUseCase,
    private val clock: Clock,
    private val zone: () -> ZoneId
) : MviViewModel<PracticeState, PracticeIntent, PracticeEffect>(PracticeState()) {
    @Inject
    constructor(
        observeSession: ObserveSessionStateUseCase,
        observeRunning: ObserveRunningSessionUseCase,
        observeHistory: ObservePracticeHistoryUseCase,
        weeklyTotal: WeeklyPracticeTotalUseCase,
        startSession: StartPracticeSessionUseCase,
        stopSession: StopPracticeSessionUseCase,
        discardSession: DiscardRunningSessionUseCase,
        updateNotes: UpdatePracticeNotesUseCase,
        deleteSession: DeletePracticeSessionUseCase,
        clock: Clock
    ) : this(
        observeSession, observeRunning, observeHistory, weeklyTotal, startSession, stopSession, discardSession,
        updateNotes, deleteSession, clock, { ZoneId.systemDefault() }
    )

    private var uid: String? = null
    private var sessionJob: Job? = null

    /** Cada suscripcion de datos lleva el suyo: las emisiones ya encoladas de una anterior se descartan. */
    private var generation = 0
    private var streamJobs = emptyList<Job>()
    private var tickJob: Job? = null

    init {
        observeSessionState()
    }

    private fun observeSessionState() {
        sessionJob = viewModelScope.launch {
            observeSession()
                .map { (it as? SessionState.Ready)?.profile }
                .distinctUntilChanged { a, b -> a?.uid == b?.uid && a?.instrument == b?.instrument }
                .catch { onIntent(PracticeIntent.SessionFailed(it)) }
                .collect { onIntent(PracticeIntent.SessionChanged(it?.uid, it?.instrument ?: Instrument.OTHER)) }
        }
    }

    override suspend fun handleIntent(intent: PracticeIntent) {
        when {
            intent is PracticeIntent.SessionChanged -> onSessionChanged(intent.uid, intent.instrument)
            intent is PracticeIntent.SessionFailed -> {
                onSessionChanged(null, Instrument.OTHER)
                reduce(PracticeMutation.Failed(intent.failure))
            }
            intent is PracticeIntent.Retry && sessionJob?.isActive != true -> {
                reduce(PracticeMutation.MessageDismissed)
                observeSessionState()
            }
            intent.generationOrNull()?.let { it != generation } == true -> Unit
            state.value.ready -> handleReady(intent)
        }
    }

    private fun PracticeIntent.generationOrNull() = when (this) {
        is PracticeIntent.RunningChanged -> generation
        is PracticeIntent.HistoryLoaded -> generation
        is PracticeIntent.WeeklyLoaded -> generation
        is PracticeIntent.StreamFailed -> generation
        else -> null
    }

    private suspend fun handleReady(intent: PracticeIntent) {
        when (intent) {
            is PracticeIntent.SessionChanged, is PracticeIntent.SessionFailed -> Unit
            PracticeIntent.Start -> onStart()
            PracticeIntent.Stop ->
                if (state.value.running != null && !state.value.busy) reduce(PracticeMutation.SaveOpened)
            is PracticeIntent.EditNotes -> reduce(PracticeMutation.NotesEdited(intent.notes))
            PracticeIntent.Save -> onSave()
            PracticeIntent.Discard -> onDiscard()
            PracticeIntent.DismissSave -> reduce(PracticeMutation.SaveClosed)
            is PracticeIntent.SelectInstrument -> reduce(PracticeMutation.InstrumentSelected(intent.instrument))
            is PracticeIntent.UpdateNotes -> onUpdateNotes(intent.id, intent.notes)
            is PracticeIntent.RequestDelete -> reduce(PracticeMutation.DeleteRequested(intent.id))
            PracticeIntent.CancelDelete -> reduce(PracticeMutation.DeleteRequested(null))
            PracticeIntent.ConfirmDelete -> onConfirmDelete()
            PracticeIntent.DismissMessage -> reduce(PracticeMutation.MessageDismissed)
            PracticeIntent.Retry -> {
                reduce(PracticeMutation.MessageDismissed)
                startStreams()
            }
            is PracticeIntent.RunningChanged -> onRunning(intent.running)
            PracticeIntent.Tick -> reduce(PracticeMutation.Tick(clock.instant()))
            is PracticeIntent.HistoryLoaded -> reduce(PracticeMutation.HistoryLoaded(intent.history))
            is PracticeIntent.WeeklyLoaded -> reduce(PracticeMutation.WeeklyLoaded(intent.seconds))
            is PracticeIntent.StreamFailed ->
                reduce(PracticeMutation.StreamFailed(intent.failure, intent.clearData))
        }
    }

    private fun onSessionChanged(newUid: String?, instrument: Instrument) {
        if (newUid == uid && state.value.ready == (newUid != null)) {
            if (newUid != null) reduce(PracticeMutation.ProfileInstrumentChanged(instrument))
            return
        }
        uid = newUid
        cancelStreams()
        if (newUid == null) {
            reduce(PracticeMutation.SessionClosed)
        } else {
            reduce(PracticeMutation.SessionOpened(instrument))
            startStreams()
        }
    }

    private fun cancelStreams() {
        generation++
        streamJobs.forEach { it.cancel() }
        streamJobs = emptyList()
        tickJob?.cancel()
        tickJob = null
    }

    private fun startStreams() {
        streamJobs.forEach { it.cancel() }
        val gen = ++generation
        streamJobs = listOf(
            stream(clear = false, gen, observeRunning()) { onIntent(PracticeIntent.RunningChanged(it, gen)) },
            stream(clear = true, gen, observeHistory()) { onIntent(PracticeIntent.HistoryLoaded(it, gen)) },
            viewModelScope.launch { collectWeekly(gen) }
        )
    }

    private fun <T> stream(clear: Boolean, gen: Int, flow: Flow<T>, onEach: (T) -> Unit) = viewModelScope.launch {
        flow.catch { onIntent(PracticeIntent.StreamFailed(it, clear, gen)) }.collect { onEach(it) }
    }

    /** El total solo se recalcula al cambiar el historial: se resuscribe al llegar el lunes 00:00 local. */
    private suspend fun collectWeekly(gen: Int) {
        while (true) {
            val zoneId = zone()
            val now = clock.instant()
            val monday = now.atZone(zoneId).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            val wait = Duration.between(now, monday.atStartOfDay(zoneId).toInstant()).toMillis()
            val timedOut = withTimeoutOrNull(wait) {
                weeklyTotal()
                    .catch { onIntent(PracticeIntent.StreamFailed(it, true, gen)) }
                    .collect { onIntent(PracticeIntent.WeeklyLoaded(it, gen)) }
            } == null
            if (!timedOut) return
        }
    }

    private fun onRunning(running: RunningSession?) {
        reduce(PracticeMutation.RunningChanged(running, clock.instant()))
        if (running != null && tickJob?.isActive == true) return
        tickJob?.cancel()
        tickJob = running?.let {
            viewModelScope.launch {
                while (true) {
                    delay(untilNextSecond())
                    onIntent(PracticeIntent.Tick)
                }
            }
        }
    }

    /** Alinea el tick con el cambio de segundo del reloj para que el cronometro no vaya a saltos. */
    private fun untilNextSecond() = TICK_MS - Math.floorMod(clock.millis(), TICK_MS)

    private suspend fun onStart() {
        val current = state.value
        if (current.busy || current.running != null) return
        reduce(PracticeMutation.Busy(true))
        val result = call { startSession(current.instrument.takeIf { current.instrumentChosen }) }
        result.onSuccess { onRunning(it) }.onFailure { reduce(PracticeMutation.Failed(it)) }
        reduce(PracticeMutation.Busy(false))
    }

    private suspend fun onSave() {
        val current = state.value
        if (!current.showSave || current.busy) return
        if (current.notesTooLong) return reduce(PracticeMutation.Failed(PracticeFailure.NotesTooLong))
        reduce(PracticeMutation.Busy(true))
        call { stopSession(current.draftNotes) }
            .onSuccess {
                onRunning(null)
                reduce(PracticeMutation.Stopped(it.clamped))
            }
            .onFailure { reduce(PracticeMutation.Failed(it)) }
        reduce(PracticeMutation.Busy(false))
    }

    private suspend fun onDiscard() {
        if (!state.value.showSave || state.value.busy) return
        reduce(PracticeMutation.Busy(true))
        call { discardSession() }
            .onSuccess {
                onRunning(null)
                reduce(PracticeMutation.SaveClosed)
            }
            .onFailure { reduce(PracticeMutation.Failed(it)) }
        reduce(PracticeMutation.Busy(false))
    }

    private suspend fun onUpdateNotes(id: String, notes: String) {
        if (notes.trim().length > PracticeRules.NOTES_MAX) {
            return reduce(PracticeMutation.Failed(PracticeFailure.NotesTooLong))
        }
        call { updateNotes(id, notes) }.onFailure { reduce(PracticeMutation.Failed(it)) }
    }

    private suspend fun onConfirmDelete() {
        val id = state.value.confirmDeleteId ?: return
        reduce(PracticeMutation.DeleteRequested(null))
        call { deleteSession(id) }.onFailure { reduce(PracticeMutation.Failed(it)) }
    }

    /** Un caso de uso que lanza en vez de devolver `Result` no puede matar el bucle de intents. */
    private suspend fun <T> call(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun reduce(mutation: PracticeMutation) = setState { PracticeReducer.reduce(this, mutation) }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
