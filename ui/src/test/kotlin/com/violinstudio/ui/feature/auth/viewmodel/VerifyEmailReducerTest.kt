package com.violinstudio.ui.feature.auth.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerifyEmailReducerTest {
    private fun reduce(state: VerifyEmailState, m: VerifyEmailMutation) = VerifyEmailReducer.reduce(state, m)

    @Test
    fun `starting a check marks it in progress and clears the old message`() {
        val s = reduce(VerifyEmailState(message = VerifyEmailMessage.UNKNOWN), VerifyEmailMutation.CheckStarted)
        assertEquals(VerifyEmailState(checking = true), s)
    }

    @Test
    fun `a check that finds the email still unverified informs and stays on the screen`() {
        val s = reduce(VerifyEmailState(checking = true), VerifyEmailMutation.CheckedStillUnverified)
        assertEquals(VerifyEmailState(checking = false, message = VerifyEmailMessage.NOT_VERIFIED_YET), s)
    }

    @Test
    fun `a verified check keeps feedback and blocks every action until the session replaces the screen`() {
        val s = reduce(
            VerifyEmailState(checking = true, message = VerifyEmailMessage.NOT_VERIFIED_YET),
            VerifyEmailMutation.CheckedVerified
        )
        assertEquals(VerifyEmailState(verified = true, message = VerifyEmailMessage.VERIFIED_CONTINUING), s)
        assertFalse(s.canCheck)
        assertFalse(s.canResend)
    }

    @Test
    fun `if the screen is still shown after verifying it falls back to a message and re-enables`() {
        val s = reduce(
            VerifyEmailState(verified = true, message = VerifyEmailMessage.VERIFIED_CONTINUING),
            VerifyEmailMutation.VerifiedTimedOut
        )
        assertEquals(VerifyEmailState(message = VerifyEmailMessage.UNKNOWN), s)
        assertTrue(s.canCheck)
    }

    private val blocked = VerifyEmailState(
        resendCooldownSeconds = 30,
        message = VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS
    )

    @Test
    fun `checking while the resend block is active keeps the wait error`() {
        assertEquals(blocked.copy(checking = true), reduce(blocked, VerifyEmailMutation.CheckStarted))
        val unverified = reduce(blocked.copy(checking = true), VerifyEmailMutation.CheckedStillUnverified)
        assertEquals(blocked, unverified)
        val failed = reduce(blocked.copy(checking = true), VerifyEmailMutation.CheckFailed(VerifyEmailMessage.NETWORK))
        assertEquals(blocked, failed)
    }

    @Test
    fun `the wait error disappears when the block ends`() {
        val s = reduce(blocked, VerifyEmailMutation.CooldownTick(0))
        assertEquals(VerifyEmailState(), s)
    }

    @Test
    fun `a failed check reports the failure and stops the progress`() {
        val s = reduce(VerifyEmailState(checking = true), VerifyEmailMutation.CheckFailed(VerifyEmailMessage.NETWORK))
        assertEquals(VerifyEmailState(message = VerifyEmailMessage.NETWORK), s)
    }

    @Test
    fun `a sent verification email starts the 60 second cooldown`() {
        val s = reduce(VerifyEmailState(), VerifyEmailMutation.ResendSent)
        assertEquals(60, s.resendCooldownSeconds)
        assertEquals(VerifyEmailMessage.RESEND_SENT, s.message)
        assertFalse(s.canResend)
    }

    @Test
    fun `too many requests is a wait error that also blocks resending with no automatic retry`() {
        val s = reduce(VerifyEmailState(), VerifyEmailMutation.ResendFailed(VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS))
        assertEquals(VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS, s.message)
        assertEquals(60, s.resendCooldownSeconds)
    }

    @Test
    fun `a network failure on resend does not impose a cooldown`() {
        val s = reduce(VerifyEmailState(), VerifyEmailMutation.ResendFailed(VerifyEmailMessage.NETWORK))
        assertEquals(VerifyEmailState(message = VerifyEmailMessage.NETWORK), s)
        assertTrue(s.canResend)
    }

    @Test
    fun `cooldown ticks count down to zero and re-enable resending`() {
        val s = reduce(VerifyEmailState(resendCooldownSeconds = 1), VerifyEmailMutation.CooldownTick(0))
        assertEquals(0, s.resendCooldownSeconds)
        assertTrue(s.canResend)
    }
}
