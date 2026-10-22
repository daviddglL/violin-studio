package com.violinstudio.domain.feature.recording.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.FakeConsentRepository
import com.violinstudio.domain.feature.config
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingAccount
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeRecordingConsentRepository
import com.violinstudio.domain.testing.FakeRecordingQueue
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingAccessUseCasesTest {
    private class FakeAccounts(var result: Result<RecordingAccount>) : RecordingAccountSource {
        override suspend fun current(uid: String): Result<RecordingAccount> = result
    }

    private class FakeRecorder : Recorder {
        val deleted = mutableListOf<String>()

        override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> =
            Result.failure(IllegalStateException())

        override suspend fun deleteTake(take: RecordedTake) {
            deleted += take.id
        }
    }

    private val auth = FakeAuthRepository().also { it.user = verifiedUser }
    private val consent = FakeConsentRepository().also {
        it.configResult = Result.success(config.copy(recordingsEnabled = true, recordingConsentVersion = 2))
    }
    private val account = RecordingAccount(Role.STUDENT, false, true, true, 2, 0, 0)
    private val accounts = FakeAccounts(Result.success(account))
    private val queue = FakeRecordingQueue()
    private val access = GetRecordingAccessUseCase(auth, consent, accounts, queue)

    private fun take(id: String) = QueuedRecording(RecordedTake(id, verifiedUser.uid, "/x/$id.m4a", 5_000, 1, 0))

    @Test
    fun `con todo vigente se puede grabar con el limite del rol`() = runTest {
        assertEquals(RecordingAccess.Allowed(60_000, quotaFull = false), access().getOrThrow())
        accounts.result = Result.success(account.copy(role = Role.TEACHER))
        assertEquals(RecordingAccess.Allowed(120_000, quotaFull = false), access().getOrThrow())
    }

    @Test
    fun `el video solo se ofrece con VIDEO_ENABLED`() = runTest {
        consent.configResult =
            Result.success(config.copy(recordingsEnabled = true, videoEnabled = true, recordingConsentVersion = 2))
        assertEquals(RecordingAccess.Allowed(60_000, quotaFull = false, videoEnabled = true), access().getOrThrow())
    }

    @Test
    fun `el cupo cuenta las vivas y las de la cola`() = runTest {
        accounts.result = Result.success(account.copy(liveCount = 19))
        queue.items.value = listOf(take("r1"))
        assertEquals(RecordingAccess.Allowed(60_000, quotaFull = true), access().getOrThrow())
    }

    @Test
    fun `flags apagados o menor sin flag de menores es Disabled`() = runTest {
        accounts.result = Result.success(account.copy(isMinor = true))
        assertEquals(RecordingAccess.Disabled, access().getOrThrow())
        consent.configResult = Result.success(config.copy(recordingsEnabled = false))
        assertEquals(RecordingAccess.Disabled, access().getOrThrow())
    }

    @Test
    fun `sin consentimiento vigente se pide, al tutor si es menor`() = runTest {
        accounts.result = Result.success(account.copy(recordingConsentGranted = false))
        assertEquals(RecordingAccess.NeedsConsent(selfGrant = true, version = 2), access().getOrThrow())
        accounts.result = Result.success(account.copy(recordingConsentVersion = 1))
        assertEquals(RecordingAccess.NeedsConsent(selfGrant = true, version = 2), access().getOrThrow())
        consent.configResult = Result.success(
            config.copy(recordingsEnabled = true, minorRecordingEnabled = true, recordingConsentVersion = 2)
        )
        accounts.result = Result.success(account.copy(isMinor = true, baseConsentGranted = false))
        assertEquals(RecordingAccess.NeedsConsent(selfGrant = false, version = 2), access().getOrThrow())
        accounts.result =
            Result.success(account.copy(isMinor = true, recordingConsentGranted = false, guardianPending = true))
        assertEquals(
            RecordingAccess.NeedsConsent(selfGrant = false, version = 2, guardianPending = true),
            access().getOrThrow()
        )
        // Un adulto no espera a ningun tutor aunque quede una marca antigua.
        accounts.result = Result.success(account.copy(recordingConsentGranted = false, guardianPending = true))
        assertEquals(RecordingAccess.NeedsConsent(selfGrant = true, version = 2), access().getOrThrow())
    }

    @Test
    fun `pedir el permiso al tutor recorta el email y no envia uno con mal formato`() = runTest {
        val repo = FakeRecordingConsentRepository()
        val request = RequestRecordingGuardianUseCase(repo)
        assertEquals(ConsentFailure.GuardianEmailInvalid, request("no-es-email").exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
        assertEquals("t***@e***.com", request("  tutor@example.com ").getOrThrow())
        assertEquals(listOf("guardian:tutor@example.com"), repo.calls)
        repo.guardianResult = Result.failure(ConsentFailure.GuardianEmailMismatch)
        assertEquals(ConsentFailure.GuardianEmailMismatch, request("otro@example.com").exceptionOrNull())
    }

    @Test
    fun `sin sesion, config o cuenta falla cerrado`() = runTest {
        accounts.result = Result.failure(IOException())
        assertEquals(RecordingFailure.Unavailable, access().exceptionOrNull())
        consent.configResult = Result.failure(IOException())
        assertEquals(RecordingFailure.Unavailable, access().exceptionOrNull())
        auth.user = null
        assertEquals(RecordingFailure.NoSession, access().exceptionOrNull())
    }

    @Test
    fun `conceder llama al servidor con la version`() = runTest {
        val repo = FakeRecordingConsentRepository()
        assertTrue(GrantRecordingConsentUseCase(repo)(2).isSuccess)
        assertEquals(listOf("grant:2"), repo.calls)
    }

    @Test
    fun `revocar vacia la cola solo si el servidor revoco`() = runTest {
        val repo = FakeRecordingConsentRepository()
        val recorder = FakeRecorder()
        val revoke = RevokeRecordingConsentUseCase(auth, repo, ClearRecordingQueueUseCase(queue, recorder))
        queue.items.value = listOf(take("r1"))
        repo.revokeResult = Result.failure(ConsentFailure.Network)
        assertTrue(revoke().isFailure)
        assertEquals(1, queue.items.value.size)
        repo.revokeResult = Result.success(Unit)
        assertTrue(revoke().isSuccess)
        assertTrue(queue.items.value.isEmpty())
        assertEquals(listOf("r1"), recorder.deleted)
    }
}
