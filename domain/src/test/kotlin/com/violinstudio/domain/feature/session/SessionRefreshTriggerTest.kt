package com.violinstudio.domain.feature.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRefreshTriggerTest {
    private var now = 1_000_000L
    private val trigger = SessionRefreshTrigger { now }

    private fun TestScope.collected(): List<RefreshKind> {
        val seen = mutableListOf<RefreshKind>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trigger.kinds.toList(seen) }
        return seen
    }

    @Test
    fun `foreground requests are limited to one per interval`() = runTest {
        val seen = collected()
        trigger.requestForegroundRefresh()
        now += SessionRefreshTrigger.FOREGROUND_MIN_INTERVAL_MS - 1
        trigger.requestForegroundRefresh()
        assertEquals(listOf(RefreshKind.FOREGROUND), seen)
        now += 1
        trigger.requestForegroundRefresh()
        assertEquals(listOf(RefreshKind.FOREGROUND, RefreshKind.FOREGROUND), seen)
    }

    @Test
    fun `explicit requests are never throttled and carry their kind`() = runTest {
        val seen = collected()
        trigger.requestRefresh()
        trigger.requestRefresh()
        trigger.requestForegroundRefresh()
        trigger.requestRefresh()
        assertEquals(
            listOf(RefreshKind.EXPLICIT, RefreshKind.EXPLICIT, RefreshKind.FOREGROUND, RefreshKind.EXPLICIT),
            seen
        )
    }

    @Test
    fun `the legacy refreshes flow still emits for both kinds`() = runTest {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trigger.refreshes.toList(seen) }
        trigger.requestRefresh()
        trigger.requestForegroundRefresh()
        assertEquals(2, seen.size)
    }
}
