package com.violinstudio.ui.feature.auth.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResetPasswordReducerTest {
    private fun reduce(state: ResetPasswordState, m: ResetPasswordMutation) = ResetPasswordReducer.reduce(state, m)

    @Test
    fun `typing clears errors and the sent confirmation`() {
        val s = ResetPasswordState(email = "a", emailError = ResetFieldError.EMAIL_INVALID, sent = true)
        assertEquals(ResetPasswordState(email = "ab"), reduce(s, ResetPasswordMutation.EmailChanged("ab")))
    }

    @Test
    fun `a malformed or empty email is a field error and does not load`() {
        val empty = reduce(ResetPasswordState(), ResetPasswordMutation.SubmitRequested)
        assertEquals(ResetFieldError.EMAIL_EMPTY, empty.emailError)
        val bad = reduce(ResetPasswordState(email = "nope"), ResetPasswordMutation.SubmitRequested)
        assertEquals(ResetFieldError.EMAIL_INVALID, bad.emailError)
        assertFalse(bad.isLoading)
    }

    @Test
    fun `a valid email starts loading and sent is the uniform answer`() {
        val loading = reduce(ResetPasswordState(email = "ana@example.test"), ResetPasswordMutation.SubmitRequested)
        assertTrue(loading.isLoading)
        val sent = reduce(loading, ResetPasswordMutation.Sent)
        assertEquals(ResetPasswordState(email = "ana@example.test", sent = true), sent)
    }

    @Test
    fun `failures stop loading and never mark as sent`() {
        val s = reduce(ResetPasswordState(isLoading = true), ResetPasswordMutation.Failed(ResetError.NETWORK))
        assertEquals(ResetPasswordState(error = ResetError.NETWORK), s)
        val rejected = reduce(ResetPasswordState(isLoading = true), ResetPasswordMutation.EmailRejected)
        assertEquals(ResetFieldError.EMAIL_INVALID, rejected.emailError)
        assertFalse(rejected.sent)
    }

    @Test
    fun `leaving the screen drops the sent confirmation and errors so returning never shows a stale one`() {
        val sent = ResetPasswordState(email = "a@b.co", sent = true, error = ResetError.NETWORK)
        assertEquals(ResetPasswordState(email = "a@b.co"), reduce(sent, ResetPasswordMutation.ScreenLeft))
    }
}
