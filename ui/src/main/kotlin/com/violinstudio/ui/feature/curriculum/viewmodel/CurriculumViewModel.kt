package com.violinstudio.ui.feature.curriculum.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.domain.feature.curriculum.usecase.ObserveCurriculumUseCase
import com.violinstudio.domain.feature.curriculum.usecase.SetLessonCompletedUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Progreso en la nube (con cache sin conexion); si la lectura falla se muestra todo sin hacer (nunca bloquea). */
@HiltViewModel
class CurriculumViewModel @Inject constructor(
    observeCurriculum: ObserveCurriculumUseCase,
    private val setLessonCompleted: SetLessonCompletedUseCase
) : MviViewModel<CurriculumState, CurriculumIntent, CurriculumEffect>(CurriculumState()) {
    init {
        viewModelScope.launch {
            observeCurriculum()
                .catch { emit(CurriculumProgress.of(emptySet())) }
                .collect { reduce(CurriculumMutation.Loaded(it)) }
        }
    }

    override suspend fun handleIntent(intent: CurriculumIntent) = when (intent) {
        is CurriculumIntent.Toggle -> reduce(CurriculumMutation.Toggled(intent.id))

        is CurriculumIntent.SetDone -> setLessonCompleted(intent.id, intent.done).fold(
            onSuccess = {},
            onFailure = { reduce(CurriculumMutation.SaveFailed) }
        )

        CurriculumIntent.DismissMessage -> reduce(CurriculumMutation.MessageDismissed)
    }

    private fun reduce(mutation: CurriculumMutation) = setState { CurriculumReducer.reduce(this, mutation) }
}
