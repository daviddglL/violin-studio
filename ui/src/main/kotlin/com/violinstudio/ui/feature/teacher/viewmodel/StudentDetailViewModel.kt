package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.domain.feature.curriculum.usecase.ObserveStudentCurriculumUseCase
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.usecase.ObserveMyStudentsUseCase
import com.violinstudio.domain.feature.link.usecase.ObserveStudentPracticeUseCase
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.usecase.weeklyTotalFlow
import com.violinstudio.domain.feature.practice.usecase.weeklyTotalSec
import com.violinstudio.ui.commons.flow.SETTLE_MS
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Registro de un alumno vinculado, solo lectura. El `studentUid` llega por la ruta (`SavedStateHandle`). El registro
 * solo se pide mientras el alumno sigue en la lista de vínculos; si el vínculo desaparece o las reglas deniegan
 * (el caso de uso emite vacío y termina), la vista se vacía y se vuelve atrás. Las lecciones del currículo se leen
 * aparte y nunca bloquean la vista: un fallo o una denegación las deja sin hacer. Sin logs ni datos en errores
 * (lección 8).
 */
@HiltViewModel
class StudentDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val observeStudents: ObserveMyStudentsUseCase,
    private val observePractice: ObserveStudentPracticeUseCase,
    private val clock: Clock,
    /** Sin valor (tests anteriores al progreso en la nube): no se muestran las lecciones. */
    private val observeCurriculum: (String) -> Flow<CurriculumProgress> = { emptyFlow() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : MviViewModel<StudentDetailState, StudentDetailIntent, StudentDetailEffect>(StudentDetailState()) {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        observeStudents: ObserveMyStudentsUseCase,
        observePractice: ObserveStudentPracticeUseCase,
        observeCurriculum: ObserveStudentCurriculumUseCase,
        clock: Clock
    ) : this(savedStateHandle, observeStudents, observePractice, clock, { observeCurriculum(it) })

    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private var linkObserver: Job? = null
    private var practiceObserver: Job? = null
    private var curriculumObserver: Job? = null
    private var seenLink = false
    private var pendingEmpty: Job? = null
    private var weekRollover: Job? = null
    private var shownSessions: List<PracticeSession>? = null
    private var gone = false

    init {
        start()
    }

    private fun start() {
        linkObserver?.cancel()
        practiceObserver?.cancel()
        curriculumObserver?.cancel()
        pendingEmpty?.cancel()
        weekRollover?.cancel()
        shownSessions = null
        seenLink = false
        gone = false
        linkObserver = viewModelScope.launch { observeLink() }
    }

    private suspend fun observeLink() {
        val uid = studentUid
        if (uid == null) {
            setState { copy(isLoading = false, unavailable = true) }
            return
        }
        try {
            observeStudents()
                .map { list -> list.firstOrNull { it.studentUid == uid } }
                .distinctUntilChanged()
                .collectSettled(isEmpty = { it == null }) { link -> onLink(uid, link) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    private fun onLink(uid: String, link: TeacherLink?) {
        if (gone) return
        if (link == null) {
            if (seenLink) onLinkLost() else setState { copy(isLoading = false, unavailable = true) }
            return
        }
        setState { copy(link = link, unavailable = false) }
        if (!seenLink) {
            seenLink = true
            practiceObserver = viewModelScope.launch { collectPractice(uid) }
            curriculumObserver = viewModelScope.launch { collectCurriculum(uid) }
        }
    }

    private suspend fun collectPractice(uid: String) {
        try {
            // Un vacío (el primero, que puede ser solo la caché, o uno que llega tras datos) se retiene hasta el
            // margen: si llega otra emisión o el flujo termina (permission-denied) nunca se aplica.
            observePractice(uid).collect { sessions -> onEmission(sessions) }
            pendingEmpty?.cancel()
            onLinkLost()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    private suspend fun collectCurriculum(uid: String) {
        try {
            observeCurriculum(uid).collect { progress -> if (!gone) setState { copy(curriculum = progress) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // El repositorio ya convierte los fallos en "nada hecho"; si aun asi falla, la linea no se muestra.
        }
    }

    private fun onEmission(sessions: List<PracticeSession>) {
        pendingEmpty?.cancel()
        val shown = shownSessions
        if (sessions.isEmpty() && (shown == null || shown.isNotEmpty())) {
            pendingEmpty = viewModelScope.launch {
                delay(SETTLE_MS)
                onSessions(sessions)
            }
        } else {
            onSessions(sessions)
        }
    }

    private fun onSessions(sessions: List<PracticeSession>) {
        if (gone) return
        shownSessions = sessions
        val total = sessions.weeklyTotalSec(clock.instant(), zone())
        setState { copy(isLoading = false, sessions = sessions, weeklyTotalSec = total) }
        // El total vuelve a calcularse al cruzar el lunes a las 00:00 aunque no llegue otra emisión; la primera
        // emisión del flujo es la que ya se acaba de aplicar.
        weekRollover?.cancel()
        weekRollover = viewModelScope.launch {
            sessions.weeklyTotalFlow(clock, zone).drop(1).collect { setState { copy(weeklyTotalSec = it) } }
        }
    }

    private fun onLinkLost() {
        if (gone) return
        gone = true
        practiceObserver?.cancel()
        curriculumObserver?.cancel()
        pendingEmpty?.cancel()
        weekRollover?.cancel()
        setState {
            copy(
                isLoading = false,
                unavailable = true,
                link = null,
                sessions = emptyList(),
                weeklyTotalSec = 0,
                curriculum = null
            )
        }
        sendEffect(StudentDetailEffect.NavigateBack)
    }

    override suspend fun handleIntent(intent: StudentDetailIntent) = when (intent) {
        StudentDetailIntent.Retry -> retry()
    }

    private fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        start()
    }

    companion object {
        /** Nombre del argumento de `StudentDetailDestination`: `SavedStateHandle` lo recibe por nombre de propiedad. */
        const val STUDENT_UID_KEY = "studentUid"
    }
}
