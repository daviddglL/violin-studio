package com.violinstudio.ui.feature.auth.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoginReducerTest {
    private fun reduce(state: LoginState, m: LoginMutation) = LoginReducer.reduce(state, m)

    @Test
    fun `typing updates the field and clears its error and the general error`() {
        val s = LoginState(emailError = LoginFieldError.EMAIL_EMPTY, error = LoginError.NETWORK)
        val typed = reduce(s, LoginMutation.EmailChanged("ana@example.test"))
        assertEquals(LoginState(email = "ana@example.test"), typed)
        val pass = reduce(
            LoginState(passwordError = LoginFieldError.PASSWORD_EMPTY),
            LoginMutation.PasswordChanged("secret")
        )
        assertEquals(LoginState(password = "secret"), pass)
    }

    @Test
    fun `submitting empty fields marks each one and does not start loading`() {
        val s = reduce(LoginState(email = "  "), LoginMutation.SubmitRequested)
        assertEquals(LoginFieldError.EMAIL_EMPTY, s.emailError)
        assertEquals(LoginFieldError.PASSWORD_EMPTY, s.passwordError)
        assertFalse(s.isLoading)
    }

    @Test
    fun `submitting valid fields starts loading and clears the old error`() {
        val s = reduce(
            LoginState(email = "ana@example.test", password = "x", error = LoginError.NETWORK),
            LoginMutation.SubmitRequested
        )
        assertTrue(s.isLoading)
        assertNull(s.error)
        assertNull(s.emailError)
    }

    @Test
    fun `wrong password and unknown email share the same error`() {
        val loading = LoginState(email = "a@b.co", password = "x", isLoading = true)
        val failed = reduce(loading, LoginMutation.Failed(LoginError.INVALID_CREDENTIALS))
        assertEquals(loading.copy(isLoading = false, password = "", error = LoginError.INVALID_CREDENTIALS), failed)
    }

    @Test
    fun `success drops the password and blocks submit until the session swaps the screen`() {
        val s = reduce(LoginState(email = "a@b.co", password = "secret", isLoading = true), LoginMutation.Succeeded)
        assertEquals(LoginState(email = "a@b.co", succeeded = true), s)
        assertFalse(s.canSubmit)
        assertTrue(reduce(s, LoginMutation.PasswordChanged("x")).canSubmit)
    }

    @Test
    fun `wrong credentials drop the password but network failures keep it`() {
        val loading = LoginState(email = "a@b.co", password = "secret", isLoading = true)
        assertEquals("", reduce(loading, LoginMutation.Failed(LoginError.INVALID_CREDENTIALS)).password)
        assertEquals("secret", reduce(loading, LoginMutation.Failed(LoginError.NETWORK)).password)
        assertEquals("secret", reduce(loading, LoginMutation.Failed(LoginError.TOO_MANY_REQUESTS)).password)
    }

    @Test
    fun `the state never prints the email or the password`() {
        val text = LoginState(email = "ana@example.test", password = "hunter2").toString()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("ana@example.test"))
    }

    @Test
    fun `leaving the screen forgets the password and transient feedback but keeps the email`() {
        val s = LoginState(
            email = "a@b.co",
            password = "secret",
            passwordError = LoginFieldError.PASSWORD_EMPTY,
            error = LoginError.NETWORK,
            succeeded = true
        )
        assertEquals(LoginState(email = "a@b.co"), reduce(s, LoginMutation.ScreenLeft))
    }
}
