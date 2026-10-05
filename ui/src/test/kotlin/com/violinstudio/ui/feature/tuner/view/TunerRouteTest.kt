package com.violinstudio.ui.feature.tuner.view

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerViewModel
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}

/** TunerRoute con un ViewModel real sobre fakes: el ciclo de vida del micro y el intent de ajustes. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class TunerRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val active = AtomicInteger()
    private val owner = TestOwner()
    private var shown by mutableStateOf(true)

    private val observePitch = mockk<ObservePitchUseCase> {
        every { this@mockk(any(), any(), any()) } answers { capture() }
    }
    private val observeProfile = mockk<ObserveProfileUseCase> {
        every { this@mockk() } returns MutableStateFlow(null)
    }

    private fun capture(): Flow<TunerReading> = flow {
        active.incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            active.decrementAndGet()
        }
    }

    @Before
    fun grantMic() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        owner.registry.currentState = Lifecycle.State.STARTED
    }

    private fun show(viewModel: TunerViewModel) {
        compose.setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown) TunerRoute(onBack = {}, viewModel = viewModel)
                }
            }
        }
    }

    private fun awaitActive(expected: Int) = compose.waitUntil(5_000) { active.get() == expected }

    private fun listening(): TunerViewModel {
        val viewModel = TunerViewModel(observeProfile, observePitch)
        show(viewModel)
        compose.onNodeWithTag(TUNER_LISTEN_TAG).performClick()
        awaitActive(1)
        return viewModel
    }

    @Test
    fun onStopReleasesTheMicAndOnStartResumesIt() {
        listening()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        awaitActive(0)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        awaitActive(1)
    }

    @Test
    fun leavingTheRouteStopsTheCaptureEvenWithoutOnStop() {
        listening()
        compose.runOnUiThread { shown = false }
        awaitActive(0)
    }

    @Test
    fun theSettingsEffectOpensTheAppDetailsOfThisPackage() {
        val viewModel = TunerViewModel(observeProfile, observePitch)
        show(viewModel)
        compose.runOnUiThread { viewModel.onIntent(TunerIntent.OpenAppSettings) }
        compose.waitUntil(5_000) { shadowOf(application).peekNextStartedActivity() != null }
        val started: Intent = shadowOf(application).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${application.packageName}", started.data.toString())
    }
}
