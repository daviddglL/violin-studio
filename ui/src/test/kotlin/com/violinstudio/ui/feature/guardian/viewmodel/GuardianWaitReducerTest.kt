package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardianWaitReducerTest {
    private fun reduce(state: GuardianWaitState, mutation: GuardianWaitMutation) =
        GuardianWaitReducer.reduce(state, mutation)

    private val waiting = GuardianWaitState(emailMasked = "t***@example.com", sends = 1, canResend = true)

    @Test
    fun `the session brings the masked email and the number of sends and nothing else`() {
        val state = reduce(GuardianWaitState(), GuardianWaitMutation.SessionUpdated("t***@example.com", 2))
        assertEquals("t***@example.com", state.emailMasked)
        assertEquals(2, state.sends)
        assertFalse(state.canResend)
        assertFalse(state.toString().contains("example"))
    }

    @Test
    fun `resend is available only when the app remembers the email and nothing is in flight`() {
        assertTrue(waiting.canResendNow)
        assertFalse(waiting.copy(canResend = false).canResendNow)
        assertFalse(waiting.copy(isLoading = true).canResendNow)
        assertFalse(waiting.copy(isDeleting = true).canResendNow)
        assertFalse(waiting.copy(changingEmail = true).canResendNow)
        assertFalse(waiting.copy(notice = GuardianWaitNotice.ALREADY_APPROVED).canResendNow)
    }

    @Test
    fun `resend starts loading and clears the previous result`() {
        val before = waiting.copy(
            error = GuardianRequestError.NETWORK,
            retryAfterSeconds = 60,
            notice = GuardianWaitNotice.RESENT,
            deleteError = ConsentDeleteError.FAILED
        )
        val state = reduce(before, GuardianWaitMutation.ResendRequested)
        assertTrue(state.isLoading)
        assertNull(state.error)
        assertNull(state.retryAfterSeconds)
        assertNull(state.notice)
        assertNull(state.deleteError)
    }

    @Test
    fun `resend is ignored without a remembered email or while busy`() {
        listOf(waiting.copy(canResend = false), waiting.copy(isLoading = true), waiting.copy(isDeleting = true))
            .forEach { assertEquals(it, reduce(it, GuardianWaitMutation.ResendRequested)) }
    }

    @Test
    fun `change email opens an empty field and cancel closes it dropping what was typed`() {
        val opened = reduce(waiting.copy(error = GuardianRequestError.NETWORK), GuardianWaitMutation.ChangeEmailStarted)
        assertTrue(opened.changingEmail)
        assertEquals("", opened.email)
        assertNull(opened.error)
        val typed = reduce(opened, GuardianWaitMutation.EmailChanged("nuevo@example.com"))
        assertEquals("nuevo@example.com", typed.email)
        val withError = typed.copy(emailError = GuardianEmailError.INVALID)
        val closed = reduce(withError, GuardianWaitMutation.ChangeEmailCancelled)
        assertFalse(closed.changingEmail)
        assertEquals("", closed.email)
        assertNull(closed.emailError)
    }

    @Test
    fun `change email cannot open or accept typing while busy`() {
        val busy = waiting.copy(isLoading = true)
        assertEquals(busy, reduce(busy, GuardianWaitMutation.ChangeEmailStarted))
        val typing = waiting.copy(changingEmail = true, isLoading = true)
        assertEquals(typing, reduce(typing, GuardianWaitMutation.EmailChanged("x@y.zz")))
        assertEquals(typing, reduce(typing, GuardianWaitMutation.ChangeEmailCancelled))
    }

    @Test
    fun `typing clears field and general errors`() {
        val before = waiting.copy(
            changingEmail = true,
            emailError = GuardianEmailError.OWN_EMAIL,
            error = GuardianRequestError.RATE_LIMITED,
            retryAfterSeconds = 60
        )
        val state = reduce(before, GuardianWaitMutation.EmailChanged("a@b.cd"))
        assertNull(state.emailError)
        assertNull(state.error)
        assertNull(state.retryAfterSeconds)
    }

    @Test
    fun `a malformed new email is rejected locally without loading`() {
        listOf("sin-arroba", "a@b", "a b@c.de", "@c.de").forEach { bad ->
            val state = reduce(
                waiting.copy(changingEmail = true, email = bad),
                GuardianWaitMutation.SubmitNewEmailRequested
            )
            assertEquals(GuardianEmailError.INVALID, state.emailError, bad)
            assertFalse(state.isLoading, bad)
        }
    }

    @Test
    fun `a valid new email starts loading`() {
        val state = reduce(
            waiting.copy(changingEmail = true, email = " nuevo@example.com "),
            GuardianWaitMutation.SubmitNewEmailRequested
        )
        assertTrue(state.isLoading)
        assertNull(state.emailError)
    }

    @Test
    fun `submitting the new email needs the field open and a non blank value`() {
        assertFalse(waiting.canSubmitNewEmail)
        assertFalse(waiting.copy(changingEmail = true).canSubmitNewEmail)
        assertTrue(waiting.copy(changingEmail = true, email = "a@b.cd").canSubmitNewEmail)
        assertFalse(waiting.copy(changingEmail = true, email = "a@b.cd", isLoading = true).canSubmitNewEmail)
        val ignored = waiting.copy(email = "a@b.cd")
        assertEquals(ignored, reduce(ignored, GuardianWaitMutation.SubmitNewEmailRequested))
    }

    @Test
    fun `success closes the field, enables resend and keeps the notice`() {
        val before = waiting.copy(canResend = false, changingEmail = true, email = "n@e.cd", isLoading = true)
        val state = reduce(before, GuardianWaitMutation.Succeeded(GuardianWaitNotice.EMAIL_CHANGED))
        assertFalse(state.isLoading)
        assertFalse(state.changingEmail)
        assertEquals("", state.email)
        assertTrue(state.canResend)
        assertEquals(GuardianWaitNotice.EMAIL_CHANGED, state.notice)
    }

    @Test
    fun `rate limited keeps the field open and carries the wait`() {
        val before = waiting.copy(changingEmail = true, email = "n@e.cd", isLoading = true)
        val state = reduce(before, GuardianWaitMutation.Failed(GuardianRequestError.RATE_LIMITED, 90))
        assertFalse(state.isLoading)
        assertTrue(state.changingEmail)
        assertEquals("n@e.cd", state.email)
        assertEquals(GuardianRequestError.RATE_LIMITED, state.error)
        assertEquals(90L, state.retryAfterSeconds)
        assertTrue(state.canSubmitNewEmail)
    }

    @Test
    fun `rejected emails become field errors and stop loading`() {
        val before = waiting.copy(changingEmail = true, email = "n@e.cd", isLoading = true)
        assertEquals(GuardianEmailError.INVALID, reduce(before, GuardianWaitMutation.EmailRejected).emailError)
        val own = reduce(before, GuardianWaitMutation.OwnEmailRejected)
        assertEquals(GuardianEmailError.OWN_EMAIL, own.emailError)
        assertFalse(own.isLoading)
    }

    @Test
    fun `already approved blocks every send and shows its notice`() {
        val state = reduce(waiting.copy(isLoading = true), GuardianWaitMutation.AlreadyApproved)
        assertFalse(state.isLoading)
        assertEquals(GuardianWaitNotice.ALREADY_APPROVED, state.notice)
        assertTrue(state.busy)
        assertFalse(state.canResendNow)
    }

    @Test
    fun `can resend follows what the app remembers`() {
        assertTrue(reduce(GuardianWaitState(), GuardianWaitMutation.CanResend(true)).canResend)
        assertFalse(reduce(waiting, GuardianWaitMutation.CanResend(false)).canResend)
    }

    @Test
    fun `delete started, succeeded and failed`() {
        val started = reduce(waiting.copy(deleteError = ConsentDeleteError.FAILED), GuardianWaitMutation.DeleteStarted)
        assertTrue(started.isDeleting)
        assertNull(started.deleteError)
        assertFalse(reduce(started, GuardianWaitMutation.DeleteSucceeded).isDeleting)
        val failed = reduce(started, GuardianWaitMutation.DeleteFailed(ConsentDeleteError.REAUTH_REQUIRED))
        assertFalse(failed.isDeleting)
        assertEquals(ConsentDeleteError.REAUTH_REQUIRED, failed.deleteError)
    }

    @Test
    fun `a server rejection of the remembered address on resend opens the change field with the error`() {
        val resending = waiting.copy(isLoading = true)
        val rejected = reduce(resending, GuardianWaitMutation.EmailRejected)
        assertTrue(rejected.changingEmail)
        assertEquals(GuardianEmailError.INVALID, rejected.emailError)
        assertEquals("", rejected.email)
        assertFalse(rejected.canResend)
        assertFalse(rejected.isLoading)
        val own = reduce(resending, GuardianWaitMutation.OwnEmailRejected)
        assertTrue(own.changingEmail)
        assertEquals(GuardianEmailError.OWN_EMAIL, own.emailError)
        assertFalse(own.canResend)
    }

    @Test
    fun `a new session clears the already approved notice but keeps other notices`() {
        val approved = waiting.copy(notice = GuardianWaitNotice.ALREADY_APPROVED)
        assertNull(reduce(approved, GuardianWaitMutation.SessionUpdated("t***@example.com", 2)).notice)
        val resent = waiting.copy(notice = GuardianWaitNotice.RESENT)
        assertEquals(
            GuardianWaitNotice.RESENT,
            reduce(resent, GuardianWaitMutation.SessionUpdated("t***@example.com", 2)).notice
        )
    }

    @Test
    fun `clearing the notice lifts the already approved lock`() {
        val approved = waiting.copy(notice = GuardianWaitNotice.ALREADY_APPROVED)
        assertFalse(approved.canResendNow)
        val cleared = reduce(approved, GuardianWaitMutation.NoticeCleared)
        assertNull(cleared.notice)
        assertTrue(cleared.canResendNow)
    }

    @Test
    fun `signing out blocks every other action`() {
        val started = reduce(waiting, GuardianWaitMutation.SignOutStarted)
        assertTrue(started.isSigningOut)
        assertTrue(started.busy)
        assertFalse(started.canResendNow)
        assertEquals(started, reduce(started, GuardianWaitMutation.ChangeEmailStarted))
        assertEquals(started, reduce(started, GuardianWaitMutation.ResendRequested))
        assertFalse(reduce(started, GuardianWaitMutation.SignOutFinished).isSigningOut)
    }

    @Test
    fun `terminal failures turn resend off and retryable ones keep it`() {
        listOf(GuardianRequestError.NOT_MINOR, GuardianRequestError.UNAVAILABLE).forEach {
            assertFalse(reduce(waiting.copy(isLoading = true), GuardianWaitMutation.Failed(it)).canResend, "$it")
        }
        listOf(GuardianRequestError.NETWORK, GuardianRequestError.UNKNOWN, GuardianRequestError.RATE_LIMITED)
            .forEach { assertTrue(reduce(waiting.copy(isLoading = true), GuardianWaitMutation.Failed(it)).canResend) }
    }

    @Test
    fun `a rate limit with a wait blocks resend until the wait elapses`() {
        val loading = waiting.copy(isLoading = true)
        val limited = reduce(loading, GuardianWaitMutation.Failed(GuardianRequestError.RATE_LIMITED, 90))
        assertTrue(limited.resendBlocked)
        assertFalse(limited.canResendNow)
        val elapsed = reduce(limited, GuardianWaitMutation.RetryWaitElapsed)
        assertFalse(elapsed.resendBlocked)
        assertNull(elapsed.error)
        assertNull(elapsed.retryAfterSeconds)
        assertTrue(elapsed.canResendNow)
        val unknownWait = reduce(loading, GuardianWaitMutation.Failed(GuardianRequestError.RATE_LIMITED))
        assertFalse(unknownWait.resendBlocked)
    }

    @Test
    fun `a successful send bumps the sends count optimistically`() {
        val resent = reduce(waiting.copy(isLoading = true), GuardianWaitMutation.Succeeded(GuardianWaitNotice.RESENT))
        assertEquals(2, resent.sends)
        val changed = reduce(
            waiting.copy(isLoading = true, changingEmail = true),
            GuardianWaitMutation.Succeeded(GuardianWaitNotice.EMAIL_CHANGED)
        )
        assertEquals(2, changed.sends)
    }

    @Test
    fun `typed emails are redacted from intent and mutation text`() {
        assertFalse(GuardianWaitIntent.EmailChanged("tutor@example.com").toString().contains("example"))
        assertFalse(GuardianWaitMutation.EmailChanged("tutor@example.com").toString().contains("example"))
    }

    @Test
    fun `a successful send carries the receipt's masked email and a null one keeps the current`() {
        val loading = waiting.copy(isLoading = true)
        val withReceipt = GuardianWaitMutation.Succeeded(GuardianWaitNotice.EMAIL_CHANGED, "n***@example.com")
        assertEquals("n***@example.com", reduce(loading, withReceipt).emailMasked)
        val withoutReceipt = GuardianWaitMutation.Succeeded(GuardianWaitNotice.RESENT)
        assertEquals("t***@example.com", reduce(loading, withoutReceipt).emailMasked)
    }

    @Test
    fun `NOT_MINOR and UNAVAILABLE are terminal and others are not`() {
        assertTrue(waiting.copy(error = GuardianRequestError.NOT_MINOR).terminal)
        assertTrue(waiting.copy(error = GuardianRequestError.UNAVAILABLE).terminal)
        assertFalse(waiting.copy(error = GuardianRequestError.NETWORK).terminal)
        assertFalse(waiting.terminal)
    }
}
