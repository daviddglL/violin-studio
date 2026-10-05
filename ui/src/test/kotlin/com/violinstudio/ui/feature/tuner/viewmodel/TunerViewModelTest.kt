package com.violinstudio.ui.feature.tuner.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.PlayReferenceToneUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    private var maxActive = 0
    private var finishes = false
    private val calls = mutableListOf<Pair<Instrument, Int?>>()
    private var failure: Throwable? = null
    private val observePitch = mockk<ObservePitchUseCase> {
        every { this@mockk(any(), any(), any()) } answers {
            calls += firstArg<Instrument>() to thirdArg<Int?>()
            capture()
        }
    }

    private var toneActive = 0
    private var toneMax = 0
    private var toneFailure: Throwable? = null
    private var toneGate: CompletableDeferred<Unit>? = null
    private var toneStuck = false
    private val toneCalls = mutableListOf<Note>()
    private val playTone = mockk<PlayReferenceToneUseCase> {
        every { this@mockk(any(), ReferencePitch.DEFAULT) } answers {
            // La funcion con value classes se compila con parametros crudos (Int).
            toneCalls += Note(firstArg<Int>())
            tone()
        }
    }

    private fun tone(): Flow<Unit> = flow {
        toneMax = maxOf(toneMax, ++toneActive)
        try {
            toneFailure?.let { throw it }
            toneGate?.await()
            emit(Unit)
            awaitCancellation()
        } finally {
            toneActive--
            if (toneStuck) withContext(NonCancellable) { delay(10_000) }
        }
    }

    private fun capture(): Flow<TunerReading> = flow {
        active++
        maxActive = maxOf(maxActive, active)
        try {
            failure?.let { throw it }
            if (!finishes) readings.collect { emit(it) }
            if (!finishes) awaitCancellation()
        } finally {
            active--
        }
    }

    private fun vm() = TunerViewModel(observeProfile, observePitch, playTone)

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
    fun `limpiar el ViewModel cancela la captura`() = runTest {
        val store = ViewModelStore()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm() as T
            }
        )
        val vm = provider[TunerViewModel::class.java]
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(1, active)
        store.clear()
        advanceUntilIdle()
        assertEquals(0, active)
    }

    @Test
    fun `Resume tras Stop reanuda la captura si hay permiso`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Resume(granted = true, rationale = false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
        assertEquals(1, active)
    }

    @Test
    fun `Start estando ya escuchando no abre otra captura`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(1, calls.size)
        assertEquals(1, maxActive)
    }

    @Test
    fun `Resume concedido en ajustes sin escucha previa pasa a Granted sin capturar`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.PermissionResult(granted = false, rationale = false))
        vm.onIntent(TunerIntent.Resume(granted = true, rationale = false))
        advanceUntilIdle()
        assertEquals(MicState.GRANTED, vm.state.value.mic)
        assertFalse(vm.state.value.isListening)
        assertEquals(0, calls.size)
    }

    @Test
    fun `Resume sin permiso pero con rationale es Denied`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        vm.onIntent(TunerIntent.Resume(granted = false, rationale = true))
        advanceUntilIdle()
        assertEquals(MicState.DENIED, vm.state.value.mic)
    }

    @Test
    fun `permiso revocado tras conceder vuelve a pedirse`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        vm.onIntent(TunerIntent.Stop)
        advanceUntilIdle()
        vm.testMvi {
            intent(TunerIntent.Start(granted = false, rationale = false))
            assertEffect(TunerEffect.RequestMicPermission)
        }
    }

    @Test
    fun `una excepcion que no es TunerFailure es MIC_UNAVAILABLE`() = runTest {
        failure = IllegalStateException("boom")
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(TunerError.MIC_UNAVAILABLE, vm.state.value.error)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `un TunerFailure ajeno al micro es UNKNOWN`() = runTest {
        failure = TunerFailure.PresetLimitReached
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(TunerError.UNKNOWN, vm.state.value.error)
    }

    @Test
    fun `una CancellationException no marca error ni para la escucha`() = runTest {
        failure = CancellationException("ajena")
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isListening)
    }

    @Test
    fun `MicPermissionDenied de la fuente da mic Denied sin error`() = runTest {
        failure = TunerFailure.MicPermissionDenied
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(MicState.DENIED, vm.state.value.mic)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `si el flujo termina con normalidad se deja de escuchar`() = runTest {
        finishes = true
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertFalse(vm.state.value.isListening)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `un fallo del flujo de perfil deja el modo cromatico y no rompe el arranque`() = runTest {
        every { observeProfile() } returns flow { throw IllegalStateException("sin perfil") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(Instrument.OTHER, vm.state.value.instrument)
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertTrue(vm.state.value.isListening)
    }

    @Test
    fun `un perfil tardio reinicia la captura con su instrumento`() = runTest {
        profile.value = null
        val vm = vm()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(listOf<Pair<Instrument, Int?>>(Instrument.OTHER to null), calls)
        profile.value = profileOf(Instrument.CELLO)
        advanceUntilIdle()
        assertEquals(Instrument.CELLO, vm.state.value.instrument)
        assertEquals(listOf<Pair<Instrument, Int?>>(Instrument.OTHER to null, Instrument.CELLO to null), calls)
        assertEquals(1, active)
        assertEquals(1, maxActive)
    }

    @Test
    fun `ConfirmRationale no toca el estado del micro`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.Start(false, true))
        vm.onIntent(TunerIntent.ConfirmRationale)
        advanceUntilIdle()
        assertEquals(MicState.UNKNOWN, vm.state.value.mic)
        assertFalse(vm.state.value.showRationale)
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
        assertEquals(1, maxActive)
        // Un perfil que cambia despues no pisa la eleccion local.
        profile.value = profileOf(Instrument.VIOLIN)
        advanceUntilIdle()
        assertEquals(Instrument.VIOLA, vm.state.value.instrument)
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
        assertEquals(1, maxActive)
    }

    private fun TestScope.playingA3(): TunerViewModel {
        val vm = vm()
        advanceUntilIdle()
        vm.onIntent(TunerIntent.SelectString(3))
        vm.onIntent(TunerIntent.ToggleReference)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `ToggleReference reproduce la cuerda elegida y otra vez la detiene`() = runTest {
        val vm = playingA3()
        assertEquals(listOf(Note(57)), toneCalls)
        assertTrue(vm.state.value.isPlayingReference)
        vm.onIntent(TunerIntent.ToggleReference)
        advanceUntilIdle()
        assertEquals(0, toneActive)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `ToggleReference sin cuerda elegida no suena`() = runTest {
        val vm = vm()
        vm.onIntent(TunerIntent.ToggleReference)
        advanceUntilIdle()
        assertEquals(0, toneCalls.size)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `cambiar de cuerda con el tono sonando lo reinicia con una sola salida`() = runTest {
        val vm = playingA3()
        vm.onIntent(TunerIntent.SelectString(2))
        advanceUntilIdle()
        assertEquals(listOf(Note(57), Note(50)), toneCalls)
        assertEquals(1, toneMax)
        assertEquals(1, toneActive)
    }

    @Test
    fun `cambiar de instrumento detiene el tono`() = runTest {
        val vm = playingA3()
        vm.onIntent(TunerIntent.SelectInstrument(Instrument.VIOLIN))
        advanceUntilIdle()
        assertEquals(0, toneActive)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `Stop detiene el tono`() = runTest {
        val vm = playingA3()
        vm.onIntent(TunerIntent.Stop)
        advanceUntilIdle()
        assertEquals(0, toneActive)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `tono y micro son excluyentes`() = runTest {
        val vm = playingA3()
        vm.onIntent(TunerIntent.Start(true, false))
        advanceUntilIdle()
        assertEquals(0, toneActive)
        assertTrue(vm.state.value.isListening)
        vm.onIntent(TunerIntent.ToggleReference)
        advanceUntilIdle()
        assertEquals(1, toneCalls.size)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `un fallo de la salida muestra el error y deja de sonar`() = runTest {
        toneFailure = IllegalStateException("audio")
        val vm = playingA3()
        assertEquals(TunerError.AUDIO_OUTPUT_UNAVAILABLE, vm.state.value.error)
        assertFalse(vm.state.value.isPlayingReference)
    }

    @Test
    fun `un fallo de la salida no toca el micro ni muestra su mensaje`() = runTest {
        toneFailure = IllegalStateException("audio")
        val vm = playingA3()
        assertEquals(MicState.UNKNOWN, vm.state.value.mic)
        assertFalse(vm.state.value.isListening)
    }

    @Test
    fun `suena solo cuando la salida ya arranco y un segundo toque mientras arranca se ignora`() = runTest {
        toneGate = CompletableDeferred()
        val vm = vm()
        advanceUntilIdle()
        vm.onIntent(TunerIntent.SelectString(3))
        vm.onIntent(TunerIntent.ToggleReference)
        vm.onIntent(TunerIntent.ToggleReference)
        advanceUntilIdle()
        assertEquals(1, toneCalls.size)
        assertFalse(vm.state.value.isPlayingReference)
        toneGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlayingReference)
        assertEquals(1, toneActive)
    }

    @Test
    fun `una salida que no termina de cancelarse no bloquea las intenciones`() = runTest {
        toneStuck = true
        val vm = playingA3()
        vm.onIntent(TunerIntent.Stop)
        advanceTimeBy(600)
        assertFalse(vm.state.value.isPlayingReference)
        vm.onIntent(TunerIntent.SelectString(2))
        advanceTimeBy(600)
        assertEquals(2, vm.state.value.selectedString)
    }
}
