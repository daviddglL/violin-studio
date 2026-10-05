package com.violinstudio.ui.feature.tuner.viewmodel

import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TunerViewModelTest {
    private val profile = MutableStateFlow<UserProfile?>(profileOf(Instrument.CELLO))
    private val observeProfile = mockk<ObserveProfileUseCase> { every { this@mockk() } returns profile }

    private val readings = MutableSharedFlow<TunerReading>(extraBufferCapacity = 8)
    private var active = 0
    private val calls = mutableListOf<Pair<Instrument, Int?>>()
    private var failure: TunerFailure? = null
    private val observePitch = mockk<ObservePitchUseCase> {
        every { this@mockk(any(), any(), any()) } answers {
            calls += firstArg<Instrument>() to thirdArg<Int?>()
            capture()
        }
    }

    private fun capture(): Flow<TunerReading> = flow {
        active++
        try {
            failure?.let { throw it }
            readings.collect { emit(it) }
            awaitCancellation()
        } finally {
            active--
        }
    }

    private fun vm() = TunerViewModel(observeProfile, observePitch)

    private fun profileOf(instrument: Instrument) =
        UserProfile("u1", "Ana", instrument, "es", Role.INDEPENDENT, false, ConsentStatus.GRANTED, 1, null, false)

    private fun pitch(cents: Double) = TunerReading.Pitch(440.0, TuningTarget.OpenString(Note(69), 2), cents, 0.9)

    @Test
    fun `el instrumento inicial es el del perfil y no se escribe el perfil`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Instrument.CELLO, vm.state.value.instrument)
        assertEquals(4, vm.state.value.strings?.size)
    }

    @Test
    fun `Start con permiso inicia la captura y refleja lecturas`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
        assertEquals(1, active)
        readings.emit(pitch(800.0))
        advanceUntilIdle()
        assertEquals(800.0, (vm.state.value.reading as TunerReading.Pitch).cents, 0.0)
    }

    @Test
    fun `Stop cancela la captura y libera la fuente`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Stop)
        advanceUntilIdle()
        assertEquals(0, active)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `onCleared cancela la captura`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        val clear = TunerViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        clear.invoke(vm)
        advanceUntilIdle()
        assertEquals(0, active)
    }

    @Test
    fun `Resume tras Stop reanuda solo si estaba escuchando y hay permiso`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Resume(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
        assertEquals(1, active)
        vm.onIntent(TunerIntent.Stop)
        vm.onIntent(TunerIntent.Resume(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
    }

    @Test
    fun `Resume sin haber escuchado no captura`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Resume(granted = true, rationale = false))
        advanceUntilIdle()
        assertFalse(vm.state.value.isListening)
        assertEquals(0, calls.size)
    }

    @Test
    fun `Stop manual impide que Resume reanude`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        vm.onIntent(TunerIntent.Stop)
        vm.onIntent(TunerIntent.Resume(true, false))
        advanceUntilIdle()
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `Resume sin permiso muestra el estado de permiso y no captura`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        vm.onIntent(TunerIntent.Resume(granted = false, rationale = false))
        advanceUntilIdle()
        assertFalse(vm.state.value.isListening)
        assertEquals(MicState.PERMANENTLY_DENIED, vm.state.value.mic)
        assertEquals(0, active)
    }

    @Test
    fun `Start sin permiso ni rationale pide el permiso`() = runTest {
        vm().testMvi {
            intent(TunerIntent.Start(granted = false, rationale = false))
            assertEffect(TunerEffect.RequestMicPermission)
        }
    }

    @Test
    fun `Start sin permiso con rationale muestra el dialogo y luego pide el permiso`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.testMvi {
            intent(TunerIntent.Start(granted = false, rationale = true))
            assertState { it.showRationale }
            intent(TunerIntent.ConfirmRationale)
            assertState { !it.showRationale }
            assertEffect(TunerEffect.RequestMicPermission)
        }
    }

    @Test
    fun `rechazar el rationale deja el permiso en Denied`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(false, true))
        vm.onIntent(TunerIntent.DismissRationale)
        advanceUntilIdle()
        assertEquals(MicState.DENIED, vm.state.value.mic)
        assertFalse(vm.state.value.showRationale)
    }

    @Test
    fun `PermissionResult concedido inicia la captura y denegado sin rationale es permanente`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.PermissionResult(granted = false, rationale = false))
        advanceUntilIdle()
        assertEquals(MicState.PERMANENTLY_DENIED, vm.state.value.mic)
        assertEquals(0, active)
        vm.onIntent(TunerIntent.PermissionResult(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
        assertEquals(1, active)
    }

    @Test
    fun `OpenAppSettings emite el efecto`() = runTest {
        vm().testMvi {
            intent(TunerIntent.OpenAppSettings)
            assertEffect(TunerEffect.OpenAppSettings)
        }
    }

    @Test
    fun `un fallo de la fuente se refleja en el estado`() = runTest {
        failure = TunerFailure.MicBusy
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(TunerError.MIC_BUSY, vm.state.value.error)
        assertFalse(vm.state.value.isListening)
        assertEquals(0, active)
    }

    @Test
    fun `SelectInstrument cambia el detector sin tocar el perfil`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        vm.onIntent(TunerIntent.SelectInstrument(Instrument.VIOLA))
        advanceUntilIdle()
        assertEquals(Instrument.VIOLA, vm.state.value.instrument)
        assertEquals(listOf<Pair<Instrument, Int?>>(Instrument.CELLO to null, Instrument.VIOLA to null), calls)
        assertEquals(1, active)
        // Un perfil que cambia despues no pisa la eleccion local.
        profile.value = profileOf(Instrument.VIOLIN)
        advanceUntilIdle()
        assertEquals(Instrument.VIOLA, vm.state.value.instrument)
    }

    @Test
    fun `una nueva instancia vuelve al instrumento del perfil`() = runTest {
        vm().apply {
            onIntent(TunerIntent.SelectInstrument(Instrument.VIOLA))
        }
        advanceUntilIdle()
        val reopened = vm()
        advanceUntilIdle()
        assertEquals(Instrument.CELLO, reopened.state.value.instrument)
    }

    @Test
    fun `SelectString fija el objetivo manual y null vuelve a auto`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        vm.onIntent(TunerIntent.SelectString(2))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.selectedString)
        vm.onIntent(TunerIntent.SelectString(null))
        advanceUntilIdle()
        assertNull(vm.state.value.selectedString)
        assertEquals(listOf<Int?>(null, 2, null), calls.map { it.second })
        assertEquals(1, active)
    }
}
