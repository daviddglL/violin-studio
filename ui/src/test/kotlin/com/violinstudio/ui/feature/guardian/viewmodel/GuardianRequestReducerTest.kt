package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.session.ConsentReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardianRequestReducerTest {
    private fun reduce(state: GuardianRequestState, mutation: GuardianRequestMutation) =
        GuardianRequestReducer.reduce(state, mutation)

    private val typed = GuardianRequestState(email = "tutor@example.com")

    @Test
    fun `submit needs a non blank email`() {
        assertFalse(GuardianRequestState().canSubmit)
        assertFalse(GuardianRequestState(email = "   ").canSubmit)
        assertTrue(typed.canSubmit)
    }

    @Test
    fun `typing stores the email and clears the field and general errors`() {
        val failed = typed.copy(emailError = GuardianEmailError.INVALID, error = GuardianRequestError.NETWORK)
        val state = reduce(failed, GuardianRequestMutation.EmailChanged("otro@example.com"))
        assertEquals("otro@example.com", state.email)
        assertNull(state.emailError)
        assertNull(state.error)
    }

    @Test
    fun `typing is ignored while sending and after success`() {
        listOf(typed.copy(isLoading = true), typed.copy(succeeded = true)).forEach {
            assertEquals(it, reduce(it, GuardianRequestMutation.EmailChanged("x@y.zz")))
        }
    }

    @Test
    fun `a malformed email is rejected locally without loading`() {
        listOf("sin-arroba", "a@b", "a b@c.de", "@c.de").forEach { bad ->
            val state = reduce(GuardianRequestState(email = bad), GuardianRequestMutation.SubmitRequested)
            assertEquals(GuardianEmailError.INVALID, state.emailError, bad)
            assertFalse(state.isLoading, bad)
        }
    }

    @Test
    fun `a valid email, even with surrounding spaces, starts loading and clears errors`() {
        val before = typed.copy(
            email = "  tutor@example.com ",
            error = GuardianRequestError.RATE_LIMITED,
            retryAfterSeconds = 60
        )
        val state = reduce(before, GuardianRequestMutation.SubmitRequested)
        assertTrue(state.isLoading)
        assertNull(state.error)
        assertNull(state.retryAfterSeconds)
    }

    @Test
    fun `submit is ignored when it cannot be submitted`() {
        val busy = typed.copy(isLoading = true)
        assertEquals(busy, reduce(busy, GuardianRequestMutation.SubmitRequested))
        val sent = typed.copy(succeeded = true)
        assertEquals(sent, reduce(sent, GuardianRequestMutation.SubmitRequested))
    }

    @Test
    fun `success locks the form until the session moves on`() {
        val state = reduce(typed.copy(isLoading = true), GuardianRequestMutation.Succeeded)
        assertTrue(state.succeeded)
        assertFalse(state.isLoading)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `the server rejecting the address is a field error`() {
        val state = reduce(typed.copy(isLoading = true), GuardianRequestMutation.EmailRejected)
        assertEquals(GuardianEmailError.INVALID, state.emailError)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `failures keep the email, stop loading and carry the wait`() {
        val state = reduce(
            typed.copy(isLoading = true),
            GuardianRequestMutation.Failed(GuardianRequestError.RATE_LIMITED, 120)
        )
        assertEquals(GuardianRequestError.RATE_LIMITED, state.error)
        assertEquals(120L, state.retryAfterSeconds)
        assertEquals("tutor@example.com", state.email)
        assertFalse(state.isLoading)
        assertTrue(state.canSubmit)
    }

    @Test
    fun `the session reason is stored`() {
        assertEquals(
            ConsentReason.REVOKED,
            reduce(typed, GuardianRequestMutation.SessionUpdated(ConsentReason.REVOKED)).reason
        )
    }

    @Test
    fun `the own email is a field error and stops loading`() {
        val state = reduce(typed.copy(isLoading = true), GuardianRequestMutation.OwnEmailRejected)
        assertEquals(GuardianEmailError.OWN_EMAIL, state.emailError)
        assertFalse(state.isLoading)
    }

    @Test
    fun `already approved is a success flagged as such`() {
        val state = reduce(typed.copy(isLoading = true), GuardianRequestMutation.AlreadyApproved)
        assertTrue(state.succeeded)
        assertTrue(state.alreadyApproved)
        assertFalse(state.isLoading)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `toString never leaks the guardian email`() {
        assertFalse(typed.toString().contains("tutor@example.com"))
    }
}
