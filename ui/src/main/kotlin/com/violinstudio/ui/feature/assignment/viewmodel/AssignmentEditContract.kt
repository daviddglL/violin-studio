package com.violinstudio.ui.feature.assignment.viewmodel

import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.AssignmentRules
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import java.time.Instant

enum class TitleError { EMPTY, TOO_LONG, INVALID }

enum class DescriptionError { TOO_LONG }

/** Fallo de guardado que la pantalla muestra hasta que se edita o se descarta. */
enum class AssignmentEditError { PERMISSION_DENIED, UNKNOWN }

data class AssignmentEditState(
    val title: String = "",
    val description: String = "",
    val dueAt: Instant? = null,
    /** Se editó el título alguna vez: solo entonces "vacío" es un error visible. */
    val titleTouched: Boolean = false,
    val saveAttempted: Boolean = false,
    /** Edita una tarea existente (ya cargada) en vez de crear una. */
    val isEditing: Boolean = false,

    /** Se espera la primera lectura de la tarea que se edita. */
    val loading: Boolean = false,

    /** La tarea no existe, falta el alumno o el vínculo cayó (también al crear): no se guarda y se vuelve atrás. */
    val notFound: Boolean = false,
    val busy: Boolean = false,
    val error: AssignmentEditError? = null
) : UiState {
    /** Longitud tras recortar, igual que valida el dominio (REQ-ASG-01). */
    val titleCount: Int get() = title.trim().length
    val descriptionCount: Int get() = description.trim().length

    private val rawTitleError: TitleError?
        get() = AssignmentRules.title(title).exceptionOrNull()?.let {
            when (it) {
                AssignmentFailure.TitleTooLong -> TitleError.TOO_LONG
                AssignmentFailure.TitleInvalid -> TitleError.INVALID
                else -> TitleError.EMPTY
            }
        }

    val titleError: TitleError?
        get() = rawTitleError?.takeIf { it != TitleError.EMPTY || titleTouched || saveAttempted }

    val descriptionError: DescriptionError?
        get() = if (AssignmentRules.description(description).isFailure) DescriptionError.TOO_LONG else null

    /** Guardar se bloquea con cualquier error de validación (también el vacío aún no mostrado) o mientras guarda. */
    val canSave: Boolean
        get() = rawTitleError == null && descriptionError == null && !busy && !loading && !notFound

    // El contenido de la tarea no sale en volcados del estado.
    override fun toString(): String =
        "AssignmentEditState(titleCount=$titleCount, descriptionCount=$descriptionCount, dueAt=$dueAt, " +
            "isEditing=$isEditing, busy=$busy, error=$error)"
}

sealed interface AssignmentEditIntent : UiIntent {
    data class TitleChanged(val title: String) : AssignmentEditIntent {
        override fun toString() = "TitleChanged(***)"
    }

    data class DescriptionChanged(val description: String) : AssignmentEditIntent {
        override fun toString() = "DescriptionChanged(***)"
    }

    data class DueAtChanged(val dueAt: Instant?) : AssignmentEditIntent

    data object Save : AssignmentEditIntent

    data object DismissError : AssignmentEditIntent
}

sealed interface AssignmentEditEffect : UiEffect {
    /** Guardado hecho (la escritura es offline-first: no espera al servidor): la pantalla se cierra. */
    data object Saved : AssignmentEditEffect
}

sealed interface AssignmentEditMutation {
    data class TitleEdited(val title: String) : AssignmentEditMutation {
        override fun toString() = "TitleEdited(***)"
    }

    data class DescriptionEdited(val description: String) : AssignmentEditMutation {
        override fun toString() = "DescriptionEdited(***)"
    }

    data class DueAtChanged(val dueAt: Instant?) : AssignmentEditMutation

    data object SaveAttempted : AssignmentEditMutation

    /** Datos de la tarea que se edita (primera lectura). */
    data class Loaded(val title: String, val description: String?, val dueAt: Instant?) : AssignmentEditMutation {
        override fun toString() = "Loaded(dueAt=$dueAt)"
    }

    data class Busy(val busy: Boolean) : AssignmentEditMutation

    data class Failed(val error: AssignmentEditError) : AssignmentEditMutation

    data object NotFound : AssignmentEditMutation

    data object ErrorDismissed : AssignmentEditMutation
}
