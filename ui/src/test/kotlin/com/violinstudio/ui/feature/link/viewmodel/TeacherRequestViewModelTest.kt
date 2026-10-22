package com.violinstudio.ui.feature.link.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.usecase.RequestTeacherRoleUseCase
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class TeacherRequestViewModelTest {
    private val profile = MutableStateFlow<UserProfile?>(null)
    private val observe = mockk<ObserveProfileUseCase> { every { this@mockk.invoke() } returns profile }
    private val request = mockk<RequestTeacherRoleUseCase>()
    private val created = mutableListOf<TeacherRequestViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun newViewModel() = TeacherRequestViewModel(observe, request).also { created += it }

    private fun profileWith(status: TeacherRequestStatus?) = UserProfile(
        "u1",
        "Ana",
        Instrument.VIOLIN,
        "es",
        Role.INDEPENDENT,
        false,
        ConsentStatus.GRANTED,
        1,
        null,
        false,
        teacherRequest = status
    )

    private fun answer(result: Result<TeacherRequestStatus>) {
        coEvery { request() } coAnswers {
            delay(100)
            result
        }
    }

    @Test
    fun `enviar pasa por enviando y queda pendiente`() = runTest {
        answer(Result.success(TeacherRequestStatus.PENDING))
        newViewModel().testMvi {
            intent(TeacherRequestIntent.Submit)
            assertState { it.isSending }
            assertState { !it.isSending && it.status == TeacherRequestStatus.PENDING && !it.canSubmit }
        }
        coVerify(exactly = 1) { request() }
    }

    @Test
    fun `el estado del perfil se muestra y con una solicitud viva no se envia`() = runTest {
        profile.value = profileWith(TeacherRequestStatus.REJECTED)
        val vm = newViewModel()
        vm.testMvi {
            assertState { it.status == TeacherRequestStatus.REJECTED }
            intent(TeacherRequestIntent.Submit)
        }
        advanceUntilIdle()
        assertEquals(TeacherRequestStatus.REJECTED, vm.state.value.status)
        assertFalse(vm.state.value.canSubmit)
        coVerify(exactly = 0) { request() }
    }

    @Test
    fun `la aprobacion del administrador llega por el perfil`() = runTest {
        val vm = newViewModel()
        vm.testMvi {
            profile.value = profileWith(TeacherRequestStatus.APPROVED)
            assertState { it.status == TeacherRequestStatus.APPROVED }
        }
    }

    @Test
    fun `no elegible bloquea reenviar y sin red deja reintentar`() = runTest {
        answer(Result.failure(LinkFailure.NotEligible))
        newViewModel().testMvi {
            intent(TeacherRequestIntent.Submit)
            assertState { it.isSending }
            assertState { it.error == TeacherRequestError.NotEligible && !it.canSubmit }
        }
        answer(Result.failure(LinkFailure.Offline))
        newViewModel().testMvi {
            intent(TeacherRequestIntent.Submit)
            assertState { it.isSending }
            assertState { it.error == TeacherRequestError.Offline && it.canSubmit }
        }
    }

    @Test
    fun `dos envios seguidos llaman una sola vez`() = runTest {
        answer(Result.success(TeacherRequestStatus.PENDING))
        newViewModel().testMvi {
            intent(TeacherRequestIntent.Submit)
            intent(TeacherRequestIntent.Submit)
            assertState { it.isSending }
            assertState { it.status == TeacherRequestStatus.PENDING }
        }
        coVerify(exactly = 1) { request() }
    }
}
