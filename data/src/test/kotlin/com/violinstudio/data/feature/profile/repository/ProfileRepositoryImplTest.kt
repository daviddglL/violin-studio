package com.violinstudio.data.feature.profile.repository

import app.cash.turbine.test
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.profile.datasource.FakeIdentityFunctionsDataSource
import com.violinstudio.data.feature.profile.datasource.FakeProfileRemoteDataSource
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.EditableProfile
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.ProfileRegistration
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProfileRepositoryImplTest {
    private val remote = FakeProfileRemoteDataSource()
    private val functions = FakeIdentityFunctionsDataSource()
    private val repo = ProfileRepositoryImpl(remote, functions)

    private val registration =
        ProfileRegistration.create(LocalDate.of(2010, 1, 2), "Ana", Instrument.CELLO, "es-ES").getOrThrow()

    @Test
    fun `observe emite el perfil mapeado y null cuando no existe`() = runTest {
        repo.observe("u1").test {
            remote.snapshots.emit(null)
            assertNull(awaitItem())
            remote.snapshots.emit(mapOf("displayName" to "Ana", "consentStatus" to "granted", "isMinor" to false))
            val profile = awaitItem()!!
            assertEquals("u1", profile.uid)
            assertEquals(ConsentStatus.GRANTED, profile.consentStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission-denied y not-found del listener son NoProfile`() = runTest {
        listOf(Code.PERMISSION_DENIED, Code.NOT_FOUND).forEach { code ->
            remote.observeFailure = FirebaseFirestoreException("x", code)
            repo.observe("u1").test { assertSame(ProfileFailure.NoProfile, awaitError()) }
        }
    }

    @Test
    fun `otros fallos del listener son Network o Unknown`() = runTest {
        remote.observeFailure = FirebaseFirestoreException("x", Code.UNAVAILABLE)
        repo.observe("u1").test { assertSame(ProfileFailure.Network, awaitError()) }
        remote.observeFailure = FirebaseFirestoreException("x", Code.INTERNAL)
        repo.observe("u1").test { assertTrue(awaitError() is ProfileFailure.Unknown) }
        remote.observeFailure = IllegalStateException("boom")
        repo.observe("u1").test { assertTrue(awaitError() is ProfileFailure.Unknown) }
    }

    @Test
    fun `register llama al callable con los cuatro campos y sin role`() = runTest {
        functions.response = mapOf("isMinor" to true, "consentStatus" to "pending", "requiredPolicyVersion" to 1)
        assertTrue(repo.register(registration).isSuccess)
        val (name, payload) = functions.calls.single()
        assertEquals("registerProfile", name)
        assertEquals(
            mapOf("birthDate" to "2010-01-02", "displayName" to "Ana", "instrument" to "cello", "locale" to "es-ES"),
            payload
        )
    }

    @Test
    fun `register mapea el error del callable`() = runTest {
        functions.failure = FunctionsCallException("FAILED_PRECONDITION", mapOf("reason" to "UNDERAGE_NOT_ALLOWED"))
        assertSame(ProfileFailure.UnderageNotAllowed, repo.register(registration).exceptionOrNull())
        functions.failure = FunctionsCallException("UNAVAILABLE", null)
        assertSame(ProfileFailure.Network, repo.register(registration).exceptionOrNull())
    }

    @Test
    fun `update escribe solo los tres campos editables`() = runTest {
        val edit = EditableProfile.create("Ana", Instrument.VIOLA, "es").getOrThrow()
        assertTrue(repo.update("u1", edit).isSuccess)
        val (uid, fields) = remote.updates.single()
        assertEquals("u1", uid)
        assertEquals(mapOf("displayName" to "Ana", "instrument" to "viola", "locale" to "es"), fields)
    }

    @Test
    fun `update mapea red, documento ausente y permiso denegado`() = runTest {
        val edit = EditableProfile.create("Ana", Instrument.VIOLA, "es").getOrThrow()
        remote.updateFailure = FirebaseFirestoreException("x", Code.UNAVAILABLE)
        assertSame(ProfileFailure.Network, repo.update("u1", edit).exceptionOrNull())
        remote.updateFailure = FirebaseFirestoreException("x", Code.NOT_FOUND)
        assertSame(ProfileFailure.NoProfile, repo.update("u1", edit).exceptionOrNull())
        remote.updateFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        assertTrue(repo.update("u1", edit).exceptionOrNull() is ProfileFailure.Unknown)
    }

    @Test
    fun `la cancelacion se relanza en register y update`() {
        val edit = EditableProfile.create("Ana", Instrument.VIOLA, "es").getOrThrow()
        functions.failure = CancellationException("c")
        assertThrows<CancellationException> { runBlocking { repo.register(registration) } }
        remote.updateFailure = CancellationException("c")
        assertThrows<CancellationException> { runBlocking { repo.update("u1", edit) } }
    }
}
