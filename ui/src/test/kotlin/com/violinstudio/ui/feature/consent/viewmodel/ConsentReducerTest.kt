package com.violinstudio.ui.feature.consent.viewmodel

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.session.ConsentReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsentReducerTest {
    private val v1 = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val v2 = IdentityConfig(2, "https://example.test/policy/2", 14, true)
    private val loaded = ConsentState(config = v1)

    private fun reduce(state: ConsentState, mutation: ConsentMutation) = ConsentReducer.reduce(state, mutation)

    @Test
    fun `the first session update loads the server policy and reason`() {
        val state = reduce(ConsentState(), ConsentMutation.SessionUpdated(v1, ConsentReason.REVOKED))
        assertEquals(v1, state.config)
        assertEquals(1, state.policyVersion)
        assertEquals(ConsentReason.REVOKED, state.reason)
        assertFalse(state.checked)
    }

    @Test
    fun `accept needs the checkbox and a loaded policy`() {
        assertFalse(ConsentState().copy(checked = true).canAccept)
        assertFalse(loaded.canAccept)
        assertTrue(reduce(loaded, ConsentMutation.CheckedChanged(true)).canAccept)
        val checked = reduce(loaded, ConsentMutation.CheckedChanged(true))
        val unchecked = reduce(checked, ConsentMutation.CheckedChanged(false))
        assertFalse(unchecked.canAccept)
    }

    @Test
    fun `a hot bump to a newer version resets the checkbox and the error`() {
        val checked = loaded.copy(checked = true, error = ConsentError.NETWORK)
        val state = reduce(checked, ConsentMutation.SessionUpdated(v2, ConsentReason.POLICY_UPDATED))
        assertEquals(v2, state.config)
        assertEquals(ConsentReason.POLICY_UPDATED, state.reason)
        assertFalse(state.checked)
        assertNull(state.error)
    }

    @Test
    fun `the same session update keeps the checkbox and a stale older version never downgrades the policy`() {
        val checked = loaded.copy(checked = true)
        assertEquals(checked, reduce(checked, ConsentMutation.SessionUpdated(v1, ConsentReason.FIRST)))
        val onV2 = ConsentState(config = v2, checked = true)
        val state = reduce(onV2, ConsentMutation.SessionUpdated(v1, ConsentReason.POLICY_UPDATED))
        assertEquals(v2, state.config)
        assertTrue(state.checked)
    }

    @Test
    fun `success is lifted when the session brings a different version or reason`() {
        val done = loaded.copy(checked = true, succeeded = true)
        assertFalse(reduce(done, ConsentMutation.SessionUpdated(v2, ConsentReason.POLICY_UPDATED)).succeeded)
        assertFalse(reduce(done, ConsentMutation.SessionUpdated(v1, ConsentReason.REVOKED)).succeeded)
        val onV2 = ConsentState(config = v2, checked = true, succeeded = true)
        val stale = reduce(onV2, ConsentMutation.SessionUpdated(v1, ConsentReason.POLICY_UPDATED))
        assertFalse(stale.succeeded)
        assertTrue(stale.canAccept)
        assertEquals(v2, stale.config)
    }

    @Test
    fun `success stays when the session repeats the same version and reason`() {
        val done = loaded.copy(checked = true, succeeded = true)
        assertTrue(reduce(done, ConsentMutation.SessionUpdated(v1, ConsentReason.FIRST)).succeeded)
    }

    @Test
    fun `accept is blocked while loading or after success`() {
        val ready = loaded.copy(checked = true)
        assertTrue(reduce(ready, ConsentMutation.AcceptRequested).isLoading)
        assertFalse(ready.copy(isLoading = true).canAccept)
        assertFalse(ready.copy(succeeded = true).canAccept)
        assertEquals(ConsentState(config = v1), reduce(loaded, ConsentMutation.AcceptRequested))
    }

    @Test
    fun `succeeded keeps accept blocked and the checkbox cannot be toggled meanwhile`() {
        val done = reduce(loaded.copy(checked = true, isLoading = true), ConsentMutation.Succeeded)
        assertTrue(done.succeeded)
        assertFalse(done.isLoading)
        assertFalse(done.canAccept)
        assertEquals(done, reduce(done, ConsentMutation.CheckedChanged(false)))
        val loading = loaded.copy(checked = true, isLoading = true)
        assertEquals(loading, reduce(loading, ConsentMutation.CheckedChanged(false)))
    }

    @Test
    fun `failure stops loading and allows a retry with the checkbox kept`() {
        val failed = reduce(loaded.copy(checked = true, isLoading = true), ConsentMutation.Failed(ConsentError.NETWORK))
        assertEquals(ConsentError.NETWORK, failed.error)
        assertFalse(failed.isLoading)
        assertTrue(failed.canAccept)
    }

    @Test
    fun `an outdated policy swaps in the reloaded config and asks to accept again`() {
        val state = reduce(
            loaded.copy(checked = true, isLoading = true),
            ConsentMutation.PolicyOutdated(v2)
        )
        assertEquals(v2, state.config)
        assertFalse(state.checked)
        assertFalse(state.isLoading)
        assertEquals(ConsentError.POLICY_CHANGED, state.error)
        assertFalse(state.canAccept)
    }

    @Test
    fun `toggling the checkbox clears a transient error but keeps the policy changed notice`() {
        assertNull(reduce(loaded.copy(error = ConsentError.NETWORK), ConsentMutation.CheckedChanged(true)).error)
        val notice = loaded.copy(error = ConsentError.POLICY_CHANGED)
        assertEquals(ConsentError.POLICY_CHANGED, reduce(notice, ConsentMutation.CheckedChanged(true)).error)
    }

    @Test
    fun `opening the policy clears an earlier link failure and a failed open flags it`() {
        val flagged = reduce(loaded, ConsentMutation.PolicyLinkFailed)
        assertTrue(flagged.policyLinkFailed)
        assertFalse(reduce(flagged, ConsentMutation.OpenPolicyRequested).policyLinkFailed)
    }

    @Test
    fun `only https policy links with a host and no credentials are safe`() {
        assertTrue(isSafePolicyUrl("https://example.test/policy"))
        assertTrue(isSafePolicyUrl("HTTPS://example.test/policy?v=2"))
        listOf(
            "http://example.test/policy",
            "intent://example.test#Intent;scheme=https;end",
            "javascript:alert(1)",
            "file:///sdcard/policy.html",
            "https:///policy",
            "https://user:pw@example.test/policy",
            "example.test/policy",
            "https://exa mple.test",
            ""
        ).forEach { assertFalse(isSafePolicyUrl(it), it) }
    }
}
