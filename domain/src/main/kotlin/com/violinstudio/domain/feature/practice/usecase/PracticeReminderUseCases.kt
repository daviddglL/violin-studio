package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.domain.feature.practice.repository.PracticeReminderScheduler
import com.violinstudio.domain.feature.practice.repository.PracticeReminderStore
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** Recordatorio del usuario con sesion; el de por defecto (apagado) sin sesion. */
class ObservePracticeReminderUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val store: PracticeReminderStore
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<PracticeReminder> = auth.authUser.flatMapLatest { user ->
        if (user == null) flowOf(PracticeReminder()) else store.observe(user.uid)
    }.distinctUntilChanged()
}

/** Guarda el ajuste; [SyncPracticeReminderUseCase] programa o anula el aviso al verlo cambiar. */
class SetPracticeReminderUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val store: PracticeReminderStore
) {
    suspend operator fun invoke(reminder: PracticeReminder): Result<Unit> {
        val uid = auth.authUser.first()?.uid ?: return Result.failure(PracticeFailure.NoSession)
        return guardedStore { Result.success(store.set(uid, reminder)) }
    }
}

/**
 * Mantiene el aviso programado segun el usuario con sesion y su ajuste: al encenderlo, cambiar la hora, entrar con otra
 * cuenta, cerrar sesion o borrar los datos locales. No termina; se lanza una vez por proceso.
 */
class SyncPracticeReminderUseCase(
    private val reminders: ObservePracticeReminderUseCase,
    private val scheduler: PracticeReminderScheduler,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(reminders: ObservePracticeReminderUseCase, scheduler: PracticeReminderScheduler, clock: Clock) :
        this(reminders, scheduler, clock, { ZoneId.systemDefault() })

    suspend operator fun invoke() {
        reminders().collect { reminder ->
            if (reminder.enabled) {
                scheduler.schedule(PracticeReminder.delayUntilNext(clock.instant(), zone(), reminder.time))
            } else {
                scheduler.cancel()
            }
        }
    }
}

/**
 * Lo que hace el aviso programado al saltar: si sigue encendido, deja programado el del dia siguiente y devuelve si hay
 * que mostrarlo (sin una sesion en curso ni practica registrada hoy). Si el historial no llega a tiempo, avisa.
 */
class PracticeReminderDueUseCase(
    private val auth: AuthRepository,
    private val store: PracticeReminderStore,
    private val running: RunningSessionStore,
    private val history: ObservePracticeHistoryUseCase,
    private val scheduler: PracticeReminderScheduler,
    private val clock: Clock,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(
        auth: AuthRepository,
        store: PracticeReminderStore,
        running: RunningSessionStore,
        history: ObservePracticeHistoryUseCase,
        scheduler: PracticeReminderScheduler,
        clock: Clock
    ) : this(auth, store, running, history, scheduler, clock, { ZoneId.systemDefault() })

    suspend operator fun invoke(): Boolean {
        val uid = auth.authUser.first()?.uid ?: return false
        val reminder = store.observe(uid).first()
        if (!reminder.enabled) return false
        val now = clock.instant()
        val today = now.atZone(zone()).toLocalDate()
        val next = PracticeReminder.delayUntilNext(now, zone(), reminder.time, RESCHEDULE_MARGIN)
        scheduler.schedule(next, afterCurrent = true)
        if (running.observe(uid).first() != null) return false
        val sessions = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { history().first() } ?: return true
        return sessions.none { it.startedAt.atZone(zone()).toLocalDate() == today }
    }

    private companion object {
        val RESCHEDULE_MARGIN: Duration = Duration.ofMinutes(1)
        const val HISTORY_TIMEOUT_MS = 20_000L
    }
}
