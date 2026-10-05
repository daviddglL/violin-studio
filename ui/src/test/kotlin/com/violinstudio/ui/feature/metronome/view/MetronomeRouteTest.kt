package com.violinstudio.ui.feature.metronome.view

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.metronome.usecase.RunMetronomeUseCase
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeViewModel
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}

/** MetronomeRoute con un ViewModel real sobre una salida falsa: ciclo de vida, salir de la ruta y rotacion. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class MetronomeRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val owner = TestOwner()
    private var shown by mutableStateOf(true)
    private val output = object : AudioOutput {
        override fun play(generator: PcmGenerator): Flow<Long> = flow {
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                awaitCancellation()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Before
    fun started() {
        owner.registry.currentState = Lifecycle.State.STARTED
    }

    private fun playing(): MetronomeViewModel {
        val viewModel = MetronomeViewModel(RunMetronomeUseCase(output), Clock.systemUTC())
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown) MetronomeRoute(onBack = {}, viewModel = viewModel)
                }
            }
        }
        compose.onNodeWithTag(METRONOME_TOGGLE_TAG).performClick()
        awaitActive(1)
        return viewModel
    }

    private fun awaitActive(expected: Int) = compose.waitUntil(5_000) { active.get() == expected }

    @Test
    fun onStopSilencesTheMetronomeAndOnStartResumesItOnce() {
        playing()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        awaitActive(0)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        awaitActive(1)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        awaitActive(1)
        assertEquals(1, maxActive.get())
    }

    @Test
    fun leavingTheRouteSilencesItEvenWithoutOnStop() {
        playing()
        compose.runOnUiThread { shown = false }
        awaitActive(0)
    }
}
