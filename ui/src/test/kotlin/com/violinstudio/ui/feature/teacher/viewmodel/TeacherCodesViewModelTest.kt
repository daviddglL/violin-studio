package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.usecase.CreateTeacherCodeUseCase
import com.violinstudio.domain.feature.link.usecase.ListTeacherCodesUseCase
import com.violinstudio.domain.feature.link.usecase.RevokeTeacherCodeUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias CodesScenario = MviScenario<TeacherCodesState, TeacherCodesIntent, TeacherCodesEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TeacherCodesViewModelTest {
    private val create = mockk<CreateTeacherCodeUseCase>()
    private val list = mockk<ListTeacherCodesUseCase>()
    private val revoke = mockk<RevokeTeacherCodeUseCase>()
    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val expiry = Instant.parse("2026-10-20T10:00:00Z")
    private val listed = TeacherCode("h1", null, "AB", expiry)
    private val other = TeacherCode("h2", null, "CD", expiry.plusSeconds(60))
    private val fresh = TeacherCode("h3", "WXYZ2345", "45", expiry.plusSeconds(120))

    private val created = mutableListOf<TeacherCodesViewModel>()

    @AfterEach
    fun cancelScopes() = created.forEach { it.viewModelScope.cancel() }

    private fun viewModel(
        codes: Result<List<TeacherCode>> = Result.success(listOf(listed, other))
    ): TeacherCodesViewModel {
        coEvery { list() } returns codes
        return TeacherCodesViewModel(create, list, revoke) { now }.also { created += it }
    }

    private fun createAnswers(result: Result<TeacherCode>) {
        coEvery { create() } coAnswers {
            delay(100)
            result
        }
    }

    private fun revokeAnswers(result: Result<Unit>) {
        coEvery { revoke(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun CodesScenario.loaded() = assertState { !it.isLoading && it.codes == listOf(listed, other) }

    private suspend fun CodesScenario.createdOk() {
        createAnswers(Result.success(fresh))
        intent(TeacherCodesIntent.Create)
        assertState { it.isCreating }
        assertState { !it.isCreating && it.created == fresh }
    }

    @Test
    fun `carga los codigos activos al abrir`() = runTest {
        viewModel().testMvi { loaded() }
    }

    @Test
    fun `un fallo al listar ofrece reintentar y reintentar recupera la lista`() = runTest {
        val vm = viewModel(Result.failure(LinkFailure.Offline))
        vm.testMvi {
            assertState { !it.isLoading && it.loadFailed }
            coEvery { list() } returns Result.success(listOf(listed))
            intent(TeacherCodesIntent.Retry)
            assertState { it.isLoading && !it.loadFailed }
            assertState { !it.isLoading && it.codes == listOf(listed) }
        }
    }

    @Test
    fun `crear muestra el codigo en claro solo en el estado de creacion y lo anade a la lista sin el`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
        }
    }

    @Test
    fun `el codigo recien creado entra en la lista solo con la pista`() = runTest {
        val vm = viewModel()
        vm.testMvi {
            loaded()
            createdOk()
        }
        val codes = vm.state.value.codes
        assertEquals(listOf("AB", "CD", "45"), codes.map { it.hint })
        assertEquals(listOf(null, null, null), codes.map { it.code })
    }

    @Test
    fun `dos pulsaciones de crear llaman una sola vez`() = runTest {
        viewModel().testMvi {
            loaded()
            createAnswers(Result.success(fresh))
            intent(TeacherCodesIntent.Create)
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating }
            assertState { !it.isCreating && it.created == fresh }
        }
        coVerify(exactly = 1) { create() }
    }

    @Test
    fun `cerrar el codigo creado lo borra del estado`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.DismissCreated)
            assertState { it.created == null && !it.copied }
        }
    }

    @Test
    fun `crear con un codigo sin cerrar no llama y no lo pierde`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.Create)
            intent(TeacherCodesIntent.DismissCreated)
            assertState { it.created == null }
        }
        coVerify(exactly = 1) { create() }
    }

    @Test
    fun `con el cupo lleno no se llama a crear`() = runTest {
        val full = (1..5).map { TeacherCode("f$it", null, "F$it", expiry) }
        viewModel(Result.success(full)).testMvi {
            assertState { !it.isLoading && it.codes == full && !it.canCreate }
            intent(TeacherCodesIntent.Create)
        }
        coVerify(exactly = 0) { create() }
    }

    @Test
    fun `un fallo al crear deja el boton listo para reintentar`() = runTest {
        createAnswers(Result.failure(LinkFailure.Offline))
        val vm = viewModel()
        vm.testMvi {
            loaded()
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating }
            assertState { it.created == null && it.error == CodesError.TryAgain && it.canCreate }
        }
    }

    @Test
    fun `los codigos caducados no se muestran ni cuentan`() = runTest {
        val expired = TeacherCode("x", null, "XX", now)
        viewModel(Result.success(listOf(expired, listed))).testMvi {
            assertState { !it.isLoading && it.codes == listOf(listed) }
        }
    }

    @Test
    fun `refrescar recarga la lista sin pasar por la carga`() = runTest {
        viewModel().testMvi {
            loaded()
            coEvery { list() } returns Result.success(listOf(other))
            intent(TeacherCodesIntent.Refresh)
            assertState { !it.isLoading && it.codes == listOf(other) }
        }
    }

    @Test
    fun `el limite del servidor recarga la lista por si estaba desactualizada`() = runTest {
        createAnswers(Result.failure(LinkFailure.CodeLimitReached))
        viewModel().testMvi {
            loaded()
            coEvery { list() } returns Result.success(listOf(listed))
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating }
            assertState { it.error == CodesError.LimitReached }
            assertState { it.codes == listOf(listed) }
        }
    }

    @Test
    fun `cerrar tras copiar pide borrar el portapapeles y sin copiar no`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.Copy)
            assertState { it.copied }
            assertEffect(TeacherCodesEffect.CopyToClipboard("WXYZ2345"))
            intent(TeacherCodesIntent.DismissCreated)
            assertState { it.created == null && !it.copied }
            assertEffect(TeacherCodesEffect.ClearClipboard("WXYZ2345"))
        }
    }

    @Test
    fun `compartir y refrescar al volver mantienen el codigo sin cerrar`() = runTest {
        val vm = viewModel()
        vm.testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.Share)
            assertEffect(TeacherCodesEffect.ShareCode("WXYZ2345"))
            intent(TeacherCodesIntent.Refresh)
        }
        runCurrent()
        assertEquals(fresh, vm.state.value.created)
    }
    @Test
    fun `cerrar sin copiar no toca el portapapeles`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.DismissCreated)
            assertState { it.created == null }
            assertNoEffects()
        }
    }

    @Test
    fun `un viewmodel nuevo no conoce el codigo creado antes`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
        }
        val recreated = viewModel()
        runCurrent()
        assertNull(recreated.state.value.created)
        assertFalse(recreated.state.value.codes.any { it.code != null })
    }

    @Test
    fun `el estado y los efectos no revelan el codigo en claro`() = runTest {
        val vm = viewModel()
        vm.testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.Copy)
            assertState { it.copied }
            assertEffect(TeacherCodesEffect.CopyToClipboard("WXYZ2345"))
        }
        assertFalse(vm.state.value.toString().contains("WXYZ2345"))
        assertFalse(TeacherCodesEffect.ShareCode("WXYZ2345").toString().contains("WXYZ2345"))
        assertFalse(TeacherCodesEffect.CopyToClipboard("WXYZ2345").toString().contains("WXYZ2345"))
    }

    @Test
    fun `compartir emite un efecto con el codigo`() = runTest {
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.Share)
            assertEffect(TeacherCodesEffect.ShareCode("WXYZ2345"))
        }
    }

    @Test
    fun `copiar o compartir sin codigo creado no hace nada`() = runTest {
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.Copy)
            intent(TeacherCodesIntent.Share)
            assertNoEffects()
        }
    }

    @Test
    fun `el limite de codigos da un error claro y no crea nada`() = runTest {
        createAnswers(Result.failure(LinkFailure.CodeLimitReached))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating }
            assertState { !it.isCreating && it.created == null && it.error == CodesError.LimitReached }
        }
    }

    @Test
    fun `no profesor y sin consentimiento dan el mismo error generico`() = runTest {
        for (failure in listOf(LinkFailure.NotTeacher, LinkFailure.NoActiveConsent, LinkFailure.Unknown)) {
            createAnswers(Result.failure(failure))
            viewModel().testMvi {
                loaded()
                intent(TeacherCodesIntent.Create)
                assertState { it.isCreating }
                assertState { it.error == CodesError.Unavailable }
            }
        }
    }

    @Test
    fun `sin red o servicio caido pide reintentar`() = runTest {
        for (failure in listOf(LinkFailure.Offline, LinkFailure.TemporarilyUnavailable)) {
            createAnswers(Result.failure(failure))
            viewModel().testMvi {
                loaded()
                intent(TeacherCodesIntent.Create)
                assertState { it.isCreating }
                assertState { it.error == CodesError.TryAgain }
            }
        }
    }

    @Test
    fun `crear de nuevo limpia el error`() = runTest {
        createAnswers(Result.failure(LinkFailure.Offline))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating }
            assertState { it.error == CodesError.TryAgain }
            createAnswers(Result.success(fresh))
            intent(TeacherCodesIntent.Create)
            assertState { it.isCreating && it.error == null }
            assertState { it.created == fresh }
        }
    }

    @Test
    fun `cancelar la revocacion no llama`() = runTest {
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.RequestRevoke(listed))
            assertState { it.revokeTarget == listed }
            intent(TeacherCodesIntent.CancelRevoke)
            assertState { it.revokeTarget == null }
        }
        coVerify(exactly = 0) { revoke(any()) }
    }

    @Test
    fun `confirmar revoca el codigo elegido y lo quita de la lista`() = runTest {
        revokeAnswers(Result.success(Unit))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.RequestRevoke(listed))
            assertState { it.revokeTarget == listed }
            intent(TeacherCodesIntent.ConfirmRevoke)
            assertState { it.isRevoking }
            assertState { !it.isRevoking && it.revokeTarget == null && it.codes == listOf(other) }
        }
        coVerify(exactly = 1) { revoke("h1") }
    }

    @Test
    fun `revocar el codigo recien creado tambien lo oculta`() = runTest {
        revokeAnswers(Result.success(Unit))
        viewModel().testMvi {
            loaded()
            createdOk()
            intent(TeacherCodesIntent.RequestRevoke(fresh.copy(code = null)))
            assertState { it.revokeTarget != null }
            intent(TeacherCodesIntent.ConfirmRevoke)
            assertState { it.isRevoking }
            assertState { it.created == null && it.codes.none { c -> c.id == "h3" } }
        }
    }

    @Test
    fun `una doble confirmacion revoca una sola vez`() = runTest {
        revokeAnswers(Result.success(Unit))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.RequestRevoke(listed))
            assertState { it.revokeTarget == listed }
            intent(TeacherCodesIntent.ConfirmRevoke)
            intent(TeacherCodesIntent.ConfirmRevoke)
            assertState { it.isRevoking }
            assertState { !it.isRevoking }
        }
        coVerify(exactly = 1) { revoke(any()) }
    }

    @Test
    fun `un fallo al revocar usa un texto generico aunque llegue como codigo invalido`() = runTest {
        revokeAnswers(Result.failure(LinkFailure.InvalidCode))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.RequestRevoke(listed))
            assertState { it.revokeTarget == listed }
            intent(TeacherCodesIntent.ConfirmRevoke)
            assertState { it.isRevoking }
            assertState {
                !it.isRevoking && it.revokeTarget == null && it.error == CodesError.RevokeFailed &&
                    it.codes == listOf(listed, other)
            }
        }
    }

    @Test
    fun `sin red al revocar pide reintentar`() = runTest {
        revokeAnswers(Result.failure(LinkFailure.Offline))
        viewModel().testMvi {
            loaded()
            intent(TeacherCodesIntent.RequestRevoke(listed))
            assertState { it.revokeTarget == listed }
            intent(TeacherCodesIntent.ConfirmRevoke)
            assertState { it.isRevoking }
            assertState { it.error == CodesError.TryAgain }
        }
    }
}
