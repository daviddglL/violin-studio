package com.violinstudio.ui.feature.practice.view

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.usecase.DeletePracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.DiscardRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeHistoryUseCase
import com.violinstudio.domain.feature.practice.usecase.ObserveRunningSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StartPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.StopPracticeSessionUseCase
import com.violinstudio.domain.feature.practice.usecase.UpdatePracticeNotesUseCase
import com.violinstudio.domain.feature.practice.usecase.WeeklyPracticeTotalUseCase
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.practice.viewmodel.PracticeViewModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class FixedClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** PracticeRoute con el ViewModel real retenido por la actividad: la recreacion no reinicia el cronometro. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class PracticeRouteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val clock = FixedClock(t0.plusSeconds(120))
    private val profile = MutableStateFlow<UserProfile?>(
        UserProfile("u1", "Ana", Instrument.VIOLA, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false)
    )
    private val running = MutableStateFlow<RunningSession?>(RunningSession("r1", t0, Instrument.VIOLA))
    private val history = MutableStateFlow(emptyList<PracticeSession>())

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PracticeViewModel(
            mockk<ObserveProfileUseCase> { every { this@mockk() } returns profile },
            mockk<ObserveRunningSessionUseCase> { every { this@mockk() } returns running },
            mockk<ObservePracticeHistoryUseCase> { every { this@mockk() } returns history },
            mockk<WeeklyPracticeTotalUseCase> { every { this@mockk() } returns MutableStateFlow(0) },
            mockk<StartPracticeSessionUseCase>(),
            mockk<StopPracticeSessionUseCase>(),
            mockk<DiscardRunningSessionUseCase>(),
            mockk<UpdatePracticeNotesUseCase>(),
            mockk<DeletePracticeSessionUseCase>(),
            clock
        ) as T
    }

    @Before
    fun launched() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    private fun viewModel(): PracticeViewModel {
        lateinit var viewModel: PracticeViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it, factory)[PracticeViewModel::class.java] }
        return viewModel
    }

    private fun show(onBack: () -> Unit = {}) {
        scenario.onActivity { activity ->
            val viewModel = ViewModelProvider(activity, factory)[PracticeViewModel::class.java]
            activity.setContent { ViolinStudioTheme { PracticeRoute(onBack = onBack, viewModel = viewModel) } }
        }
    }

    @After
    fun stopTicker() {
        // Sin perfil el ViewModel cancela el ticker de 1 s: si no, sigue vivo tras el test.
        profile.value = null
        scenario.close()
    }

    @Test
    fun `la ruta muestra la sesion en curso derivada del reloj`() {
        show()
        compose.waitUntil(5_000) { viewModel().state.value.elapsedSec == 120L }
        compose.onNodeWithTag(PRACTICE_TIMER_TAG).assertTextEquals("2:00")
    }

    @Test
    fun `tras recrear la actividad el cronometro sigue en 2 minutos`() {
        show()
        compose.waitUntil(5_000) { viewModel().state.value.elapsedSec == 120L }
        val before = viewModel()
        scenario.recreate()
        // El ViewModel lo retiene el ViewModelStore de la actividad, como con Hilt.
        assertSame(before, viewModel())
        show()
        compose.onNodeWithTag(PRACTICE_TIMER_TAG).assertTextEquals("2:00")
        assertEquals(RunningSession("r1", t0, Instrument.VIOLA), viewModel().state.value.running)
    }

    @Test
    fun `atras de la ruta avisa al llamador`() {
        var back = 0
        show { back++ }
        compose.onNodeWithTag(PRACTICE_BACK_TAG).performClick()
        assertEquals(1, back)
    }
}
