package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.LinkNotice
import com.violinstudio.domain.feature.link.usecase.RedeemTeacherCodeUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Sin logs: ni el código ni el uid del profesor salen de aquí. El rol lo comprueba la navegación, no este VM. */
@HiltViewModel
class RedeemCodeViewModel @Inject constructor(private val redeem: RedeemTeacherCodeUseCase) :
    MviViewModel<RedeemCodeState, RedeemCodeIntent, RedeemCodeEffect>(RedeemCodeState()) {
    private var submitting = false

    override fun onIntent(intent: RedeemCodeIntent) {
        if (intent == RedeemCodeIntent.Submit) {
            if (submitting) return
            submitting = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: RedeemCodeIntent) = when (intent) {
        is RedeemCodeIntent.CodeChanged -> setState {
            if (isLoading || succeeded) {
                this
            } else {
                copy(code = intent.code.take(RedeemCodeState.MAX_INPUT), error = null)
            }
        }

        is RedeemCodeIntent.AcceptedChanged -> setState {
            if (isLoading || succeeded) this else copy(accepted = intent.accepted, error = null)
        }

        RedeemCodeIntent.Submit -> submit()
    }

    private suspend fun submit() {
        try {
            if (!state.value.canSubmit) return
            val code = state.value.code
            setState { copy(isLoading = true, error = null) }
            val result = try {
                redeem(code, LinkNotice.VERSION)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(LinkFailure.Unknown)
            }
            result.fold(
                onSuccess = {
                    setState { copy(isLoading = false, succeeded = true) }
                    sendEffect(RedeemCodeEffect.Redeemed)
                },
                onFailure = { failure -> setState { copy(isLoading = false, error = failure.toRedeemError()) } }
            )
        } finally {
            submitting = false
        }
    }
}

private fun Throwable.toRedeemError(): RedeemError = when (this) {
    LinkFailure.InvalidCode -> RedeemError.InvalidCode
    is LinkFailure.RateLimited -> RedeemError.RateLimited(retryAfterSeconds?.let(::minutesCeil))
    LinkFailure.TeacherFull -> RedeemError.TeacherFull
    LinkFailure.AlreadyLinked -> RedeemError.AlreadyLinked
    LinkFailure.NoticeOutdated -> RedeemError.NoticeOutdated
    LinkFailure.NotEligible, LinkFailure.NoActiveConsent -> RedeemError.NotEligible
    LinkFailure.Offline -> RedeemError.Offline
    LinkFailure.TemporarilyUnavailable -> RedeemError.TemporarilyUnavailable
    else -> RedeemError.Unknown
}

private fun minutesCeil(seconds: Long): Int = ((seconds + 59) / 60).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
