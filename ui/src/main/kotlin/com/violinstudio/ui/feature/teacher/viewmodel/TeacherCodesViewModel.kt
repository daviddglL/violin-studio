package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.usecase.CreateTeacherCodeUseCase
import com.violinstudio.domain.feature.link.usecase.ListTeacherCodesUseCase
import com.violinstudio.domain.feature.link.usecase.RevokeTeacherCodeUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * El código en claro vive solo en [TeacherCodesState.created] (memoria del ViewModel: sin `SavedStateHandle`, sin
 * disco) y en efectos de un solo uso. Sin logs (lección 8). El rol lo comprueba la navegación.
 */
@HiltViewModel
class TeacherCodesViewModel internal constructor(
    private val createCode: CreateTeacherCodeUseCase,
    private val listCodes: ListTeacherCodesUseCase,
    private val revokeCode: RevokeTeacherCodeUseCase,
    private val now: () -> Instant
) : MviViewModel<TeacherCodesState, TeacherCodesIntent, TeacherCodesEffect>(TeacherCodesState()) {
    @Inject
    constructor(
        createCode: CreateTeacherCodeUseCase,
        listCodes: ListTeacherCodesUseCase,
        revokeCode: RevokeTeacherCodeUseCase
    ) : this(createCode, listCodes, revokeCode, Instant::now)

    private var creating = false
    private var revoking = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load(silent: Boolean = false) {
        val result = guarded { listCodes() }
        setState {
            result.fold(
                onSuccess = { copy(isLoading = false, loadFailed = false, codes = active(it)) },
                onFailure = { if (silent) this else copy(isLoading = false, loadFailed = true) }
            )
        }
    }

    override fun onIntent(intent: TeacherCodesIntent) {
        when (intent) {
            TeacherCodesIntent.Create -> {
                if (creating || !state.value.canCreate) return
                creating = true
            }

            TeacherCodesIntent.ConfirmRevoke -> {
                if (revoking) return
                revoking = true
            }

            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: TeacherCodesIntent) = when (intent) {
        TeacherCodesIntent.Create -> create()

        TeacherCodesIntent.DismissCreated -> dismissCreated()

        TeacherCodesIntent.Copy -> state.value.created?.code?.let { code ->
            setState { copy(copied = true) }
            sendEffect(TeacherCodesEffect.CopyToClipboard(code))
        } ?: Unit

        TeacherCodesIntent.Share -> state.value.created?.code?.let { sendEffect(TeacherCodesEffect.ShareCode(it)) }
            ?: Unit

        is TeacherCodesIntent.RequestRevoke -> setState {
            if (isRevoking) this else copy(revokeTarget = intent.code.copy(code = null), error = null)
        }

        TeacherCodesIntent.CancelRevoke -> setState { if (isRevoking) this else copy(revokeTarget = null) }

        TeacherCodesIntent.ConfirmRevoke -> revoke()

        TeacherCodesIntent.Retry -> retry()

        TeacherCodesIntent.Refresh -> if (state.value.run { !isLoading && !loadFailed }) load(silent = true) else Unit
    }

    /** Cerrar tras copiar pide borrar el portapapeles (la UI solo lo hace si aún contiene este código). */
    private fun dismissCreated() {
        val current = state.value
        val code = current.created?.code
        setState { copy(created = null, copied = false) }
        if (current.copied && code != null) sendEffect(TeacherCodesEffect.ClearClipboard(code))
    }

    private fun active(list: List<TeacherCode>): List<TeacherCode> =
        list.filter { it.expiresAt.isAfter(now()) }.sortedBy { it.expiresAt }

    private suspend fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        load()
    }

    private suspend fun create() {
        try {
            setState { copy(isCreating = true, error = null) }
            guarded { createCode() }.fold(
                onSuccess = { code ->
                    setState {
                        copy(
                            isCreating = false,
                            created = code,
                            codes = active(codes + code.copy(code = null))
                        )
                    }
                },
                onFailure = { failure ->
                    val error = failure.toCodesError(revoke = false)
                    setState { copy(isCreating = false, error = error) }
                    if (error == CodesError.LimitReached) load(silent = true)
                }
            )
        } finally {
            creating = false
        }
    }

    private suspend fun revoke() {
        try {
            val target = state.value.revokeTarget ?: return
            setState { copy(isRevoking = true) }
            guarded { revokeCode(target.id) }.fold(
                onSuccess = {
                    setState {
                        copy(
                            isRevoking = false,
                            revokeTarget = null,
                            codes = codes.filterNot { it.id == target.id },
                            created = created?.takeIf { it.id != target.id }
                        )
                    }
                },
                onFailure = { failure ->
                    val error = failure.toCodesError(revoke = true)
                    setState { copy(isRevoking = false, revokeTarget = null, error = error) }
                    if (error == CodesError.RevokeFailed) load(silent = true)
                }
            )
        } finally {
            revoking = false
        }
    }

    private suspend fun <T> guarded(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Result.failure(LinkFailure.Unknown)
    }
}

/** `revokeCode` mapea sus fallos a `InvalidCode` (A7b): al revocar el texto es genérico. */
private fun Throwable.toCodesError(revoke: Boolean): CodesError = when (this) {
    LinkFailure.Offline, LinkFailure.TemporarilyUnavailable -> CodesError.TryAgain
    LinkFailure.CodeLimitReached -> if (revoke) CodesError.RevokeFailed else CodesError.LimitReached
    else -> if (revoke) CodesError.RevokeFailed else CodesError.Unavailable
}
