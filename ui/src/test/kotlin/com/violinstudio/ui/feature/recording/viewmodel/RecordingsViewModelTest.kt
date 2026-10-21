package com.violinstudio.ui.feature.recording.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingOutcome
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.RecordingsOverview
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.recording.usecase.DeleteRecordingUseCase
import com.violinstudio.domain.feature.recording.usecase.DiscardUploadUseCase
import com.violinstudio.domain.feature.recording.usecase.FinishRecordingUseCase
import com.violinstudio.domain.feature.recording.usecase.GetRecordingAccessUseCase
import com.violinstudio.domain.feature.recording.usecase.GrantRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveRecordingReviewsUseCase
import com.violinstudio.domain.feature.recording.usecase.ObserveRecordingsUseCase
import com.violinstudio.domain.feature.recording.usecase.RecordingPlaybackUseCase
import com.violinstudio.domain.feature.recording.usecase.RequestRecordingGuardianUseCase
import com.violinstudio.domain.feature.recording.usecase.RetryUploadUseCase
import com.violinstudio.domain.feature.recording.usecase.RevokeRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.StartRecordingUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class RecordingsViewModelTest {
    private class FakeHandle(private val take: Result<RecordedTake>) : RecordingHandle {
        override val outcome = MutableStateFlow<RecordingOutcome?>(null)
        var stops = 0
        var discards = 0

        override suspend fun stop(): Result<RecordedTake> {
            stops++
            return take
        }

        override suspend fun discard() {
            discards++
        }
    }

    private val take = RecordedTake("r1", "u1", "/x/r1.m4a", 5_000, 1_000, 0)
    private val allowed = RecordingAccess.Allowed(60_000, quotaFull = false)
    private val overview = MutableStateFlow(RecordingsOverview("u1", emptyList(), emptyList()))

    private val getAccess = mockk<GetRecordingAccessUseCase> {
        coEvery { this@mockk.invoke() } returns Result.success(allowed)
    }
    private val grant = mockk<GrantRecordingConsentUseCase>()
    private val revoke = mockk<RevokeRecordingConsentUseCase>()
    private val start = mockk<StartRecordingUseCase>()
    private val finish = mockk<FinishRecordingUseCase> {
        coEvery { this@mockk.invoke(any(), any(), any()) } returns Result.success(Unit)
    }
    private val observe = mockk<ObserveRecordingsUseCase> { every { this@mockk.invoke() } returns overview }
    private val retry = mockk<RetryUploadUseCase>()
    private val discard = mockk<DiscardUploadUseCase>()
    private val delete = mockk<DeleteRecordingUseCase>()
    private val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val playback = mockk<RecordingPlaybackUseCase>(relaxUnitFun = true) {
        every { state } returns playbackState
    }
    private val reviews = mockk<ObserveRecordingReviewsUseCase>()
    private val guardian = mockk<RequestRecordingGuardianUseCase>()

    private fun TestScope.viewModel(args: Map<String, String> = emptyMap()): RecordingsViewModel = RecordingsViewModel(
        getAccess, grant, revoke, start, finish, observe, retry, discard, delete, playback, reviews, guardian,
        SavedStateHandle(args)
    )
        .also { runCurrent() }

    private val RecordingsViewModel.now: RecordingsState get() = state.value

    @Test
    fun `carga el acceso y la vista general`() = runTest {
        val pending = QueuedRecording(take)
        val summary = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false)
        overview.value = RecordingsOverview("u1", listOf(pending), listOf(summary))
        val vm = viewModel()
        assertEquals(allowed, vm.now.access)
        assertEquals("u1", vm.now.uid)
        assertEquals(listOf(pending), vm.now.pending)
        assertEquals(listOf(summary), vm.now.recordings)
    }

    @Test
    fun `si el acceso o la lista fallan se avisa y se puede reintentar`() = runTest {
        coEvery { getAccess() } returns Result.failure(RecordingFailure.Unavailable)
        every { observe() } returns flow { throw IllegalStateException("x") }
        val vm = viewModel()
        assertTrue(vm.now.accessFailed)
        assertEquals(RecordingsMessage.LOAD_FAILED, vm.now.message)
        coEvery { getAccess() } returns Result.success(RecordingAccess.Disabled)
        vm.onIntent(RecordingsIntent.Refresh)
        runCurrent()
        assertEquals(RecordingAccess.Disabled, vm.now.access)
        assertFalse(vm.now.accessFailed)
    }

    @Test
    fun `conceder el consentimiento refresca el acceso y sus fallos se explican`() = runTest {
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(true, 2))
        coEvery { grant(2) } returns Result.failure(ConsentFailure.TemporarilyUnavailable)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.GrantConsent)
        runCurrent()
        assertEquals(RecordingsMessage.TEMPORARILY_UNAVAILABLE, vm.now.message)
        assertFalse(vm.now.busy)
        coEvery { grant(2) } returns Result.success(Unit)
        coEvery { getAccess() } returns Result.success(allowed)
        vm.onIntent(RecordingsIntent.GrantConsent)
        runCurrent()
        assertEquals(allowed, vm.now.access)
    }

    @Test
    fun `un menor no puede concederlo y los demas fallos de consentimiento tienen mensaje`() = runTest {
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(false, 1))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.GrantConsent)
        runCurrent()
        coVerify(exactly = 0) { grant(any()) }
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(true, 1))
        vm.onIntent(RecordingsIntent.Refresh)
        listOf(
            ConsentFailure.GuardianRequired to RecordingsMessage.GUARDIAN_REQUIRED,
            ConsentFailure.Network to RecordingsMessage.OFFLINE,
            ConsentFailure.RecordingDisabled to RecordingsMessage.CONSENT_FAILED
        ).forEach { (failure, message) ->
            coEvery { grant(1) } returns Result.failure(failure)
            vm.onIntent(RecordingsIntent.GrantConsent)
            runCurrent()
            assertEquals(message, vm.now.message)
        }
    }

    @Test
    fun `revocar pide confirmacion, descarta la toma en curso y refresca`() = runTest {
        val handle = FakeHandle(Result.success(take))
        coEvery { start() } returns Result.success(handle)
        coEvery { revoke() } returns Result.success(Unit)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        vm.onIntent(RecordingsIntent.AskRevoke)
        runCurrent()
        assertTrue(vm.now.confirmRevoke)
        vm.onIntent(RecordingsIntent.CancelRevoke)
        runCurrent()
        assertFalse(vm.now.confirmRevoke)
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(true, 1))
        vm.onIntent(RecordingsIntent.ConfirmRevoke)
        runCurrent()
        assertEquals(1, handle.discards)
        assertFalse(vm.now.isRecording)
        assertEquals(RecordingAccess.NeedsConsent(true, 1), vm.now.access)
        coEvery { revoke() } returns Result.failure(ConsentFailure.Network)
        vm.onIntent(RecordingsIntent.ConfirmRevoke)
        runCurrent()
        assertEquals(RecordingsMessage.OFFLINE, vm.now.message)
    }

    @Test
    fun `grabar, contar el tiempo y parar encola la toma`() = runTest {
        val handle = FakeHandle(Result.success(take))
        coEvery { start() } returns Result.success(handle)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        assertEquals(0L, vm.now.elapsedMs)
        advanceTimeBy(1_100)
        assertEquals(1_000L, vm.now.elapsedMs)
        vm.onIntent(RecordingsIntent.Stop)
        runCurrent()
        assertNull(vm.now.elapsedMs)
        assertEquals(RecordingsMessage.SAVED, vm.now.message)
        coVerify { finish(take, null, null) }
        advanceTimeBy(5_000)
        assertNull(vm.now.elapsedMs)
    }

    @Test
    fun `la parada automatica encola la toma como un parar`() = runTest {
        val handle = FakeHandle(Result.success(take))
        coEvery { start() } returns Result.success(handle)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        handle.outcome.value = RecordingOutcome.AutoStopped(take)
        runCurrent()
        assertEquals(1, handle.stops)
        assertFalse(vm.now.isRecording)
        coVerify { finish(take, null, null) }
    }

    @Test
    fun `una toma corta, un fallo al parar o al guardar se explican`() = runTest {
        coEvery { start() } returns Result.success(FakeHandle(Result.failure(RecordingFailure.TooShort)))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        vm.onIntent(RecordingsIntent.Stop)
        runCurrent()
        assertEquals(RecordingsMessage.TOO_SHORT, vm.now.message)
        coEvery { start() } returns Result.success(FakeHandle(Result.failure(RecordingFailure.RecorderFailed)))
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        vm.onIntent(RecordingsIntent.Stop)
        runCurrent()
        assertEquals(RecordingsMessage.RECORDING_FAILED, vm.now.message)
        coEvery { start() } returns Result.success(FakeHandle(Result.success(take)))
        coEvery { finish(any(), any(), any()) } returns Result.failure(IllegalStateException())
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        vm.onIntent(RecordingsIntent.Stop)
        runCurrent()
        assertEquals(RecordingsMessage.SAVE_FAILED, vm.now.message)
    }

    @Test
    fun `salir de la pantalla descarta la toma en curso`() = runTest {
        val handle = FakeHandle(Result.success(take))
        coEvery { start() } returns Result.success(handle)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Leave)
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        vm.onIntent(RecordingsIntent.Leave)
        runCurrent()
        assertEquals(1, handle.discards)
        assertEquals(0, handle.stops)
        assertFalse(vm.now.isRecording)
        coVerify(exactly = 0) { finish(any(), any(), any()) }
    }

    @Test
    fun `sin permiso se pide, se explica o se manda a ajustes`() = runTest {
        coEvery { start() } returns Result.success(FakeHandle(Result.success(take)))
        val vm = viewModel()
        val effects = mutableListOf<RecordingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onIntent(RecordingsIntent.Start(granted = false, rationale = false))
        runCurrent()
        assertEquals(listOf<RecordingsEffect>(RecordingsEffect.RequestMicPermission), effects)
        vm.onIntent(RecordingsIntent.Start(granted = false, rationale = true))
        runCurrent()
        assertEquals(MicPrompt.RATIONALE, vm.now.mic)
        vm.onIntent(RecordingsIntent.ConfirmRationale)
        runCurrent()
        assertNull(vm.now.mic)
        vm.onIntent(RecordingsIntent.PermissionResult(granted = false, rationale = false))
        runCurrent()
        assertEquals(MicPrompt.BLOCKED, vm.now.mic)
        vm.onIntent(RecordingsIntent.OpenAppSettings)
        vm.onIntent(RecordingsIntent.DismissMic)
        runCurrent()
        assertNull(vm.now.mic)
        vm.onIntent(RecordingsIntent.PermissionResult(granted = true, rationale = false))
        runCurrent()
        assertTrue(vm.now.isRecording)
        assertEquals(
            listOf(
                RecordingsEffect.RequestMicPermission,
                RecordingsEffect.RequestMicPermission,
                RecordingsEffect.OpenAppSettings
            ),
            effects
        )
        vm.onIntent(RecordingsIntent.Leave)
        runCurrent()
    }

    @Test
    fun `con el cupo lleno o sin acceso no se abre el micro y los fallos de arranque se explican`() = runTest {
        coEvery { getAccess() } returns Result.success(RecordingAccess.Allowed(60_000, quotaFull = true))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        assertEquals(RecordingsMessage.QUOTA_REACHED, vm.now.message)
        coVerify(exactly = 0) { start() }

        coEvery { getAccess() } returns Result.success(allowed)
        vm.onIntent(RecordingsIntent.Refresh)
        listOf(
            RecordingFailure.Busy to RecordingsMessage.MIC_BUSY,
            RecordingFailure.QuotaReached to RecordingsMessage.QUOTA_REACHED,
            RecordingFailure.RecorderFailed to RecordingsMessage.RECORDING_FAILED
        ).forEach { (failure, message) ->
            coEvery { start() } returns Result.failure(failure)
            vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
            runCurrent()
            assertEquals(message, vm.now.message)
            vm.onIntent(RecordingsIntent.DismissMessage)
        }
        coEvery { start() } returns Result.failure(RecordingFailure.ConsentRequired)
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(true, 1))
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        assertEquals(RecordingAccess.NeedsConsent(true, 1), vm.now.access)
    }

    @Test
    fun `reintentar, descartar y borrar actuan sobre el usuario y avisan si fallan`() = runTest {
        coEvery { retry("u1", "q1") } returns Result.success(Unit)
        coEvery { discard("u1", "q2") } returns Result.failure(IllegalStateException())
        coEvery { delete("u1", "r9") } returns Result.success(Unit)
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Retry("q1"))
        runCurrent()
        assertNull(vm.now.message)
        vm.onIntent(RecordingsIntent.Discard("q2"))
        runCurrent()
        assertEquals(RecordingsMessage.ACTION_FAILED, vm.now.message)
        vm.onIntent(RecordingsIntent.AskDelete("r9"))
        runCurrent()
        assertEquals("r9", vm.now.confirmDeleteId)
        vm.onIntent(RecordingsIntent.CancelDelete)
        runCurrent()
        assertNull(vm.now.confirmDeleteId)
        vm.onIntent(RecordingsIntent.AskDelete("r9"))
        vm.onIntent(RecordingsIntent.ConfirmDelete)
        runCurrent()
        coVerify { delete("u1", "r9") }
        coEvery { delete("u1", "r9") } returns Result.failure(IllegalStateException())
        vm.onIntent(RecordingsIntent.AskDelete("r9"))
        vm.onIntent(RecordingsIntent.ConfirmDelete)
        runCurrent()
        assertEquals(RecordingsMessage.DELETE_FAILED, vm.now.message)
        coVerify { retry("u1", "q1") }
    }

    @Test
    fun `escucha solo grabaciones listas, sigue el estado del reproductor y para al salir`() = runTest {
        val ready = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false, objectPath = "p")
        val rejected = RecordingSummary("r1", 1, 1_000, ready = false, isDemo = false)
        overview.value = RecordingsOverview("u1", emptyList(), listOf(ready, rejected))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Play("r1"))
        vm.onIntent(RecordingsIntent.Play("r0"))
        runCurrent()
        coVerify(exactly = 1) { playback.play(any()) }
        coVerify { playback.play(ready) }
        playbackState.value = PlaybackState.Playing("r0")
        runCurrent()
        assertEquals(PlaybackState.Playing("r0"), vm.now.playback)
        vm.onIntent(RecordingsIntent.StopPlayback)
        vm.onIntent(RecordingsIntent.Leave)
        runCurrent()
        verify(exactly = 2) { playback.stop() }
    }

    @Test
    fun `grabar para lo que suene`() = runTest {
        coEvery { start() } returns Result.success(FakeHandle(Result.success(take)))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        verify { playback.stop() }
        // Sin cerrar la toma, el contador seguiria vivo y runTest no terminaria nunca.
        vm.onIntent(RecordingsIntent.Leave)
        runCurrent()
    }

    @Test
    fun `en modo demo la toma se comparte con el alumno y la lista solo muestra esa tarea`() = runTest {
        val mine = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false)
        val demo = RecordingSummary("d1", 2, 1_000, ready = true, isDemo = true, assignmentId = "a1", sharedWith = "s1")
        val otherTask = demo.copy(id = "d2", assignmentId = "a2")
        val otherStudent = demo.copy(id = "d3", sharedWith = "s2")
        val queued = QueuedRecording(take, assignmentId = "a1", sharedWith = "s1")
        val queuedOther = QueuedRecording(take.copy(id = "r2"), assignmentId = "a2", sharedWith = "s1")
        overview.value =
            RecordingsOverview("u1", listOf(queued, queuedOther), listOf(mine, demo, otherTask, otherStudent))
        coEvery { start() } returns Result.success(FakeHandle(Result.success(take)))
        val vm = viewModel(
            mapOf(RecordingsViewModel.STUDENT_UID_KEY to "s1", RecordingsViewModel.ASSIGNMENT_ID_KEY to "a1")
        )
        assertTrue(vm.now.demo)
        assertEquals(listOf(queued), vm.now.pending)
        assertEquals(listOf(demo), vm.now.recordings)
        vm.onIntent(RecordingsIntent.Start(granted = true, rationale = false))
        runCurrent()
        vm.onIntent(RecordingsIntent.Stop)
        runCurrent()
        coVerify { finish(take, "a1", "s1") }
    }

    @Test
    fun `sin los dos argumentos no hay modo demo`() = runTest {
        val summary = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false)
        overview.value = RecordingsOverview("u1", emptyList(), listOf(summary))
        val vm = viewModel(mapOf(RecordingsViewModel.STUDENT_UID_KEY to "s1"))
        assertFalse(vm.now.demo)
        assertEquals(listOf(summary), vm.now.recordings)
    }

    @Test
    fun `las valoraciones se abren, se cierran y avisan si fallan`() = runTest {
        val ready = RecordingSummary("r0", 1, 1_000, ready = true, isDemo = false, objectPath = "p")
        overview.value = RecordingsOverview("u1", emptyList(), listOf(ready))
        val review = Review(Verdict.NEEDS_WORK, "Afina el la", 1L)
        every { reviews("r0") } returns MutableStateFlow(listOf(review))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.ToggleReviews("zz"))
        runCurrent()
        assertNull(vm.now.reviewsFor)
        vm.onIntent(RecordingsIntent.ToggleReviews("r0"))
        runCurrent()
        assertEquals("r0", vm.now.reviewsFor)
        assertEquals(listOf(review), vm.now.reviews)
        vm.onIntent(RecordingsIntent.ToggleReviews("r0"))
        runCurrent()
        assertNull(vm.now.reviewsFor)
        every { reviews("r0") } returns flow { throw IllegalStateException("x") }
        vm.onIntent(RecordingsIntent.ToggleReviews("r0"))
        runCurrent()
        assertTrue(vm.now.reviewsFailed)
        overview.value = RecordingsOverview("u1", emptyList(), emptyList())
        runCurrent()
        assertNull(vm.now.reviewsFor)
        assertFalse(vm.now.reviewsFailed)
    }

    @Test
    fun `un menor pide el permiso a su tutor y queda pendiente`() = runTest {
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(false, 1))
        coEvery { guardian("tutor@example.com") } returns Result.success("t***@e***.com")
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.SetGuardianEmail("tutor@example.com"))
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(false, 1, guardianPending = true))
        vm.onIntent(RecordingsIntent.RequestGuardian)
        runCurrent()
        assertEquals("t***@e***.com", vm.now.guardianSentTo)
        assertEquals("", vm.now.guardianEmail)
        assertEquals(RecordingAccess.NeedsConsent(false, 1, guardianPending = true), vm.now.access)
        assertFalse(vm.now.busy)
    }

    @Test
    fun `un adulto no pide permiso al tutor y los fallos del tutor se explican`() = runTest {
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(true, 1))
        val vm = viewModel()
        vm.onIntent(RecordingsIntent.RequestGuardian)
        runCurrent()
        coVerify(exactly = 0) { guardian(any()) }
        coEvery { getAccess() } returns Result.success(RecordingAccess.NeedsConsent(false, 1))
        vm.onIntent(RecordingsIntent.Refresh)
        vm.onIntent(RecordingsIntent.SetGuardianEmail("x@example.com"))
        listOf(
            ConsentFailure.GuardianEmailInvalid to RecordingsMessage.GUARDIAN_EMAIL_INVALID,
            ConsentFailure.InvalidArgument("guardianEmail") to RecordingsMessage.GUARDIAN_EMAIL_INVALID,
            ConsentFailure.InvalidArgument("purpose") to RecordingsMessage.CONSENT_FAILED,
            ConsentFailure.GuardianEmailMismatch to RecordingsMessage.GUARDIAN_MISMATCH,
            ConsentFailure.GuardianLegacyConsent to RecordingsMessage.GUARDIAN_LEGACY,
            ConsentFailure.RateLimited(60) to RecordingsMessage.RATE_LIMITED
        ).forEach { (failure, message) ->
            coEvery { guardian(any()) } returns Result.failure(failure)
            vm.onIntent(RecordingsIntent.RequestGuardian)
            runCurrent()
            assertEquals(message, vm.now.message)
        }
        coEvery { guardian(any()) } returns Result.failure(ConsentFailure.AlreadyGranted)
        coEvery { getAccess() } returns Result.success(allowed)
        vm.onIntent(RecordingsIntent.RequestGuardian)
        runCurrent()
        assertEquals(allowed, vm.now.access)
    }
}
