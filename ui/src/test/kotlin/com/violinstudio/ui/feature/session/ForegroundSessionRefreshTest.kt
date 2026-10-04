package com.violinstudio.ui.feature.session

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.session.RefreshKind
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class ForegroundSessionRefreshTest {
    private var now = 1_000_000L
    private val trigger = SessionRefreshTrigger { now }
    private val seen = mutableListOf<RefreshKind>()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    init {
        GlobalScope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { trigger.kinds.collect { seen += it } }
    }

    @Test
    fun `an installed observer asks for a foreground refresh when the lifecycle starts`() {
        ForegroundSessionRefresh(trigger).install(owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        assertEquals(emptyList<RefreshKind>(), seen)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(RefreshKind.FOREGROUND), seen)
    }

    @Test
    fun `returning to the foreground within the interval is throttled and later is not`() {
        ForegroundSessionRefresh(trigger).install(owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        now += 1_000
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(1, seen.size)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        now += SessionRefreshTrigger.FOREGROUND_MIN_INTERVAL_MS
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(RefreshKind.FOREGROUND, RefreshKind.FOREGROUND), seen)
    }

    @Test
    fun `going to the background requests nothing`() {
        ForegroundSessionRefresh(trigger).install(owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        seen.clear()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(emptyList<RefreshKind>(), seen)
    }
}
