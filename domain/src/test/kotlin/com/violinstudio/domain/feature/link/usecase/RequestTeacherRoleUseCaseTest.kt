package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeLinkRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestTeacherRoleUseCaseTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakeLinkRepository()
    private val request = RequestTeacherRoleUseCase(auth, repo)

    @Test
    fun `pendiente se devuelve sin refrescar claims`() = runTest {
        assertEquals(Result.success(TeacherRequestStatus.PENDING), request())
        assertEquals(listOf("requestTeacherRole"), repo.calls)
        assertFalse(auth.calls.any { it.startsWith("claims") })
    }

    @Test
    fun `aprobada refresca los claims`() = runTest {
        repo.teacherRequestResult = Result.success(TeacherRequestStatus.APPROVED)
        assertEquals(Result.success(TeacherRequestStatus.APPROVED), request())
        assertTrue("claims:true" in auth.calls)
    }

    @Test
    fun `sin sesion falla sin llamar al servidor`() = runTest {
        auth.user = null
        assertEquals(LinkFailure.NoSession, request().exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `el fallo del servidor se propaga`() = runTest {
        repo.teacherRequestResult = Result.failure(LinkFailure.NotEligible)
        assertEquals(LinkFailure.NotEligible, request().exceptionOrNull())
    }
}
