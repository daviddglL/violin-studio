package com.violinstudio.ui.feature.session

import androidx.lifecycle.LifecycleOwner
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSessionRefreshTest {
    private val owner = mockk<LifecycleOwner>()
    private val trigger = SessionRefreshTrigger()

    private fun kotlinx.coroutines.test.TestScope.collected(): List<Unit> {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { trigger.refreshes.toList(seen) }
        return seen
    }

    @Test
    fun `coming to the foreground requests exactly one refresh`() = runTest {
        val seen = collected()
        ForegroundSessionRefresh(trigger).onStart(owner)
        assertEquals(1, seen.size)
    }

    @Test
    fun `going to the background requests nothing and each return requests again`() = runTest {
        val seen = collected()
        val observer = ForegroundSessionRefresh(trigger)
        observer.onStop(owner)
        assertEquals(0, seen.size)
        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStart(owner)
        assertEquals(2, seen.size)
    }
}
