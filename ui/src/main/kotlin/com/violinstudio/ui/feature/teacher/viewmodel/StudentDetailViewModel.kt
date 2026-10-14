package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.link.usecase.ObserveMyStudentsUseCase
import com.violinstudio.domain.feature.link.usecase.ObserveStudentPracticeUseCase
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.usecase.weeklyTotalSec
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Registro de un alumno vinculado, solo lectura. El `studentUid` llega por la ruta (`SavedStateHandle`). El registro
 * solo se pide mientras el alumno sigue en la lista de vínculos; si el vínculo desaparece o las reglas deniegan
 * (el caso de uso emite vacío y termina), la vista se vacía y se vuelve atrás. Sin logs ni datos en errores
 * (lección 8).
 */
@HiltViewModel
class StudentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeStudents: ObserveMyStudentsUseCase,
    private val observePractice: ObserveStudentPracticeUseCase,
    private val clock: Clock
) : MviViewModel<StudentDetailState, StudentDetailIntent, StudentDetailEffect>(StudentDetailState()) {
    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private var linkObserver: Job? = null
    private var practiceObserver: Job? = null
    private var seenLink = false
    private var gone = false

    init {
        start()
    }

    private fun start() {
        linkObserver?.cancel()
        practiceObserver?.cancel()
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
        if (link == null) {
            if (seenLink) onLinkLost() else setState { copy(isLoading = false, unavailable = true) }
            return
        }
        setState { copy(link = link, unavailable = false) }
        if (!seenLink) {
            seenLink = true
            practiceObserver = viewModelScope.launch { collectPractice(uid) }
        }
    }

    private suspend fun collectPractice(uid: String) {
        try {
            // La primera lista vacía puede ser solo la caché: se espera al servidor antes de mostrar el vacío.
            observePractice(uid).collectSettled(isEmpty = { it.isEmpty() }) { sessions -> onSessions(sessions) }
            // Un flujo que termina es el vínculo caído (permission-denied): el caso de uso ya emitió el vacío.
            onLinkLost()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    private fun onSessions(sessions: List<PracticeSession>) {
        if (gone) return
        val total = sessions.weeklyTotalSec(clock.instant(), ZoneId.systemDefault())
        setState { copy(isLoading = false, sessions = sessions, weeklyTotalSec = total) }
    }

    private fun onLinkLost() {
        if (gone) return
        gone = true
        practiceObserver?.cancel()
        setState { copy(isLoading = false, link = null, sessions = emptyList(), weeklyTotalSec = 0) }
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
