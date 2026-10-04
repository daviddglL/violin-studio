package com.violinstudio.ui.feature.account.viewmodel

import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeleteAccountReducerTest {
    private val idle = DeleteAccountState()
    private val confirming = DeleteAccountState(step = DeleteStep.CONFIRMING)
    private val reauthPassword = DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.PASSWORD)
    private val reauthGoogle = DeleteAccountState(step = DeleteStep.REAUTH, method = ReauthMethod.GOOGLE)

    private fun reduce(s: DeleteAccountState, m: DeleteAccountMutation) = DeleteAccountReducer.reduce(s, m)

    @Test
    fun `opening asks for an explicit confirmation and nothing is deleted yet`() {
        val s = reduce(idle.copy(error = DeleteAccountError.FAILED), DeleteAccountMutation.Opened)
        assertEquals(DeleteStep.CONFIRMING, s.step)
        assertNull(s.error)
        assertTrue(s.canConfirm)
        assertFalse(s.isWorking)
    }

    @Test
    fun `cancelling goes back to idle and drops the typed password`() {
        val s = reduce(reauthPassword.copy(password = "secret"), DeleteAccountMutation.Cancelled)
        assertEquals(DeleteStep.IDLE, s.step)
        assertEquals("", s.password)
    }

    @Test
    fun `cancel is ignored while working or after deletion`() {
        val cancel = DeleteAccountMutation.Cancelled
        assertEquals(DeleteStep.CONFIRMING, reduce(confirming.copy(isWorking = true), cancel).step)
        assertEquals(DeleteStep.CONFIRMING, reduce(confirming.copy(deleted = true), cancel).step)
    }

    @Test
    fun `confirming starts the deletion once and a second confirm is ignored`() {
        val working = reduce(confirming, DeleteAccountMutation.DeleteStarted)
        assertTrue(working.isWorking)
        assertFalse(working.canConfirm)
        assertEquals(working, reduce(working, DeleteAccountMutation.DeleteStarted))
        assertEquals(idle, reduce(idle, DeleteAccountMutation.DeleteStarted))
    }

    @Test
    fun `a recent login requirement moves to reauthentication with the right method`() {
        val s = reduce(confirming.copy(isWorking = true), DeleteAccountMutation.ReauthRequired(ReauthMethod.PASSWORD))
        assertEquals(DeleteStep.REAUTH, s.step)
        assertEquals(ReauthMethod.PASSWORD, s.method)
        assertFalse(s.isWorking)
        assertFalse(s.canSubmitPassword)
        assertFalse(s.canReauthWithGoogle)
    }

    @Test
    fun `the password submit needs a non blank password and a free form`() {
        val typed = reduce(reauthPassword, DeleteAccountMutation.PasswordChanged("hunter2"))
        assertTrue(typed.canSubmitPassword)
        assertFalse(reduce(reauthPassword, DeleteAccountMutation.PasswordChanged("   ")).canSubmitPassword)
        assertFalse(typed.copy(isWorking = true).canSubmitPassword)
        assertEquals(reauthGoogle, reduce(reauthGoogle, DeleteAccountMutation.PasswordChanged("x")))
    }

    @Test
    fun `typing clears the previous reauth error but not while working`() {
        val failed = reauthPassword.copy(error = DeleteAccountError.WRONG_PASSWORD)
        assertNull(reduce(failed, DeleteAccountMutation.PasswordChanged("a")).error)
        val working = failed.copy(isWorking = true)
        assertEquals(working, reduce(working, DeleteAccountMutation.PasswordChanged("a")))
    }

    @Test
    fun `reauth blocks the form while it runs and a wrong password clears the field`() {
        val started = reduce(reauthPassword.copy(password = "bad"), DeleteAccountMutation.ReauthStarted)
        assertTrue(started.isWorking)
        val failed = reduce(started, DeleteAccountMutation.ReauthFailed(DeleteAccountError.WRONG_PASSWORD))
        assertEquals(DeleteStep.REAUTH, failed.step)
        assertEquals(DeleteAccountError.WRONG_PASSWORD, failed.error)
        assertEquals("", failed.password)
        assertFalse(failed.isWorking)
    }

    @Test
    fun `a retryable reauth failure keeps the typed password`() {
        val s = reduce(
            reauthPassword.copy(password = "ok", isWorking = true),
            DeleteAccountMutation.ReauthFailed(DeleteAccountError.NETWORK)
        )
        assertEquals("ok", s.password)
        assertEquals(DeleteAccountError.NETWORK, s.error)
    }

    @Test
    fun `a network failure on the delete keeps the account active and offers retry from confirmation`() {
        val s = reduce(
            confirming.copy(isWorking = true),
            DeleteAccountMutation.DeleteFailed(DeleteAccountError.NETWORK)
        )
        assertEquals(DeleteStep.CONFIRMING, s.step)
        assertEquals(DeleteAccountError.NETWORK, s.error)
        assertFalse(s.deleted)
        assertTrue(s.canConfirm)
    }

    @Test
    fun `a delete failure after a fresh reauth returns to confirmation, not to the password form`() {
        val s = reduce(
            reauthPassword.copy(isWorking = true, password = "x"),
            DeleteAccountMutation.DeleteFailed(DeleteAccountError.FAILED)
        )
        assertEquals(DeleteStep.CONFIRMING, s.step)
        assertEquals("", s.password)
    }

    @Test
    fun `success locks everything and never reports an error`() {
        val s = reduce(confirming.copy(isWorking = true), DeleteAccountMutation.Deleted)
        assertTrue(s.deleted)
        assertFalse(s.isWorking)
        assertFalse(s.canConfirm)
        assertFalse(s.canCancel)
        assertFalse(s.canOpen)
        assertNull(s.error)
    }

    @Test
    fun `opening again is ignored once started or deleted`() {
        assertEquals(confirming, reduce(confirming, DeleteAccountMutation.Opened))
        assertEquals(idle.copy(deleted = true), reduce(idle.copy(deleted = true), DeleteAccountMutation.Opened))
    }

    @Test
    fun `toString never shows the password`() {
        assertFalse(reauthPassword.copy(password = "hunter2").toString().contains("hunter2"))
        assertFalse(DeleteAccountIntent.PasswordChanged("hunter2").toString().contains("hunter2"))
        assertFalse(DeleteAccountMutation.PasswordChanged("hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `without a provider to reauthenticate the confirmation cannot be retried`() {
        val s = reduce(
            confirming.copy(isWorking = true),
            DeleteAccountMutation.DeleteFailed(DeleteAccountError.REAUTH_UNAVAILABLE)
        )
        assertFalse(s.canConfirm)
        assertTrue(s.canCancel)
    }

    @Test
    fun `a reauth failure reported outside the working state still shows in the reauth step`() {
        val s = reduce(reauthGoogle, DeleteAccountMutation.ReauthFailed(DeleteAccountError.PROVIDER_UNAVAILABLE))
        assertEquals(DeleteAccountError.PROVIDER_UNAVAILABLE, s.error)
        assertEquals(idle, reduce(idle, DeleteAccountMutation.ReauthFailed(DeleteAccountError.NETWORK)))
    }
}
