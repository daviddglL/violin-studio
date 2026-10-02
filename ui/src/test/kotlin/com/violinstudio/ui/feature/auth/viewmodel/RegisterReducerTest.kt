package com.violinstudio.ui.feature.auth.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegisterReducerTest {
    private fun reduce(state: RegisterState, m: RegisterMutation) = RegisterReducer.reduce(state, m)

    @Test
    fun `typing clears the field error and the general error`() {
        val s = RegisterState(emailError = RegisterFieldError.EMAIL_INVALID, error = RegisterError.NETWORK)
        assertEquals(RegisterState(email = "a"), reduce(s, RegisterMutation.EmailChanged("a")))
        val p = RegisterState(passwordError = RegisterFieldError.PASSWORD_WEAK)
        assertEquals(RegisterState(password = "x"), reduce(p, RegisterMutation.PasswordChanged("x")))
    }

    @Test
    fun `submit flags empty, malformed and short values without loading`() {
        val empty = reduce(RegisterState(), RegisterMutation.SubmitRequested)
        assertEquals(RegisterFieldError.EMAIL_EMPTY, empty.emailError)
        assertEquals(RegisterFieldError.PASSWORD_EMPTY, empty.passwordError)
        val bad = reduce(RegisterState(email = "no-at", password = "12345"), RegisterMutation.SubmitRequested)
        assertEquals(RegisterFieldError.EMAIL_INVALID, bad.emailError)
        assertEquals(RegisterFieldError.PASSWORD_TOO_SHORT, bad.passwordError)
        assertFalse(bad.isLoading)
    }

    @Test
    fun `submit with valid values starts loading`() {
        val s = reduce(
            RegisterState(email = "ana@example.test", password = "123456", error = RegisterError.UNKNOWN),
            RegisterMutation.SubmitRequested
        )
        assertTrue(s.isLoading)
        assertEquals(null, s.error)
    }

    @Test
    fun `a weak password from the server is a field error and keeps the typed values`() {
        val loading = RegisterState(email = "a@b.co", password = "123456", isLoading = true)
        val s = reduce(loading, RegisterMutation.Rejected(null, RegisterFieldError.PASSWORD_WEAK))
        assertEquals(loading.copy(isLoading = false, passwordError = RegisterFieldError.PASSWORD_WEAK), s)
    }

    @Test
    fun `failures stop loading and success too`() {
        val loading = RegisterState(isLoading = true)
        assertEquals(
            RegisterError.ACCOUNT_UNAVAILABLE,
            reduce(loading, RegisterMutation.Failed(RegisterError.ACCOUNT_UNAVAILABLE)).error
        )
    }

    @Test
    fun `success drops the password and blocks submit until the session swaps the screen`() {
        val s = reduce(
            RegisterState(email = "a@b.co", password = "secret1", isLoading = true),
            RegisterMutation.Succeeded
        )
        assertEquals(RegisterState(email = "a@b.co", succeeded = true), s)
        assertFalse(s.canSubmit)
        assertTrue(reduce(s, RegisterMutation.EmailChanged("x")).canSubmit)
    }

    @Test
    fun `the state never prints the credentials`() {
        val text = RegisterState(email = "ana@example.test", password = "hunter2").toString()
        assertFalse(text.contains("hunter2") || text.contains("ana@example.test"))
    }

    @Test
    fun `leaving the screen forgets the password and transient feedback but keeps the email`() {
        val s = RegisterState(
            email = "a@b.co",
            password = "secret1",
            passwordError = RegisterFieldError.PASSWORD_WEAK,
            error = RegisterError.ACCOUNT_UNAVAILABLE
        )
        assertEquals(RegisterState(email = "a@b.co"), reduce(s, RegisterMutation.ScreenLeft))
    }
}
