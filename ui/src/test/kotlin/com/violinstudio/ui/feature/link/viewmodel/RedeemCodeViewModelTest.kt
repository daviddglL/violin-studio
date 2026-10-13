package com.violinstudio.ui.feature.link.viewmodel

import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.LinkNotice
import com.violinstudio.domain.feature.link.usecase.RedeemTeacherCodeUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private typealias Scenario = MviScenario<RedeemCodeState, RedeemCodeIntent, RedeemCodeEffect>

@ExtendWith(MainDispatcherExtension::class)
class RedeemCodeViewModelTest {
    private val redeem = mockk<RedeemTeacherCodeUseCase>()

    private fun answer(result: Result<String>) {
        coEvery { redeem(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun Scenario.fill(code: String = "ABCD2345", accept: Boolean = true) {
        intent(RedeemCodeIntent.CodeChanged(code))
        assertState { it.code == code }
        if (accept) {
            intent(RedeemCodeIntent.AcceptedChanged(true))
            assertState { it.accepted }
        }
    }

    private fun failWith(failure: LinkFailure, expected: RedeemError) = runTest {
        answer(Result.failure(failure))
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == expected && it.code == "ABCD2345" && it.accepted }
        }
    }

    @Test
    fun `sin aceptar el aviso o sin codigo no se puede enviar y no se llama`() = runTest {
        RedeemCodeViewModel(redeem).testMvi {
            fill(accept = false)
            intent(RedeemCodeIntent.Submit)
            intent(RedeemCodeIntent.AcceptedChanged(true))
            assertState { it.canSubmit }
            intent(RedeemCodeIntent.CodeChanged("  "))
            assertState { !it.canSubmit }
            intent(RedeemCodeIntent.Submit)
        }
        coVerify(exactly = 0) { redeem(any(), any()) }
    }

    @Test
    fun `envia el codigo con la version vigente del aviso y avisa al terminar`() = runTest {
        answer(Result.success("t1"))
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.succeeded && !it.isLoading && !it.canSubmit }
            assertEffect(RedeemCodeEffect.Redeemed)
        }
        coVerify(exactly = 1) { redeem("ABCD2345", LinkNotice.VERSION) }
    }

    @Test
    fun `un envio doble solo llama una vez`() = runTest {
        answer(Result.success("t1"))
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.succeeded }
            assertEffect(RedeemCodeEffect.Redeemed)
        }
        coVerify(exactly = 1) { redeem(any(), any()) }
    }

    @Test
    fun `codigo invalido generico`() = failWith(LinkFailure.InvalidCode, RedeemError.InvalidCode)

    @Test
    fun `rate limited se redondea hacia arriba a minutos`() =
        failWith(LinkFailure.RateLimited(90), RedeemError.RateLimited(2))

    @Test
    fun `rate limited sin tiempo conocido`() = failWith(LinkFailure.RateLimited(null), RedeemError.RateLimited(null))

    @Test
    fun `profesor completo`() = failWith(LinkFailure.TeacherFull, RedeemError.TeacherFull)

    @Test
    fun `ya vinculado`() = failWith(LinkFailure.AlreadyLinked, RedeemError.AlreadyLinked)

    @Test
    fun `aviso desactualizado`() = failWith(LinkFailure.NoticeOutdated, RedeemError.NoticeOutdated)

    @Test
    fun `sin conexion`() = failWith(LinkFailure.Offline, RedeemError.Offline)

    @Test
    fun `no disponible temporalmente`() =
        failWith(LinkFailure.TemporarilyUnavailable, RedeemError.TemporarilyUnavailable)

    @Test
    fun `cuenta no elegible o sin consentimiento`() {
        runTest { failWith(LinkFailure.NotEligible, RedeemError.NotEligible) }
        runTest { failWith(LinkFailure.NoActiveConsent, RedeemError.NotEligible) }
    }

    @Test
    fun `cualquier otro fallo es desconocido`() = failWith(LinkFailure.Unknown, RedeemError.Unknown)

    @Test
    fun `editar el codigo limpia el error y se puede reintentar sin perder lo escrito`() = runTest {
        var attempt = 0
        coEvery { redeem(any(), any()) } coAnswers {
            delay(100)
            if (attempt++ == 0) Result.failure(LinkFailure.Offline) else Result.success("t1")
        }
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == RedeemError.Offline }
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading && it.error == null }
            assertState { it.succeeded }
            assertEffect(RedeemCodeEffect.Redeemed)
        }
    }

    @Test
    fun `cambiar el codigo tras un error lo limpia`() = runTest {
        answer(Result.failure(LinkFailure.InvalidCode))
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == RedeemError.InvalidCode }
            intent(RedeemCodeIntent.CodeChanged("ZZZZ"))
            assertState { it.error == null && it.code == "ZZZZ" }
        }
    }

    @Test
    fun `una excepcion inesperada es un error desconocido y el flujo sigue vivo`() = runTest {
        var attempt = 0
        coEvery { redeem(any(), any()) } coAnswers {
            delay(100)
            if (attempt++ == 0) error("boom") else Result.success("t1")
        }
        RedeemCodeViewModel(redeem).testMvi {
            fill()
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == RedeemError.Unknown && !it.isLoading }
            intent(RedeemCodeIntent.Submit)
            assertState { it.isLoading }
            assertState { it.succeeded }
            assertEffect(RedeemCodeEffect.Redeemed)
        }
    }

    @Test
    fun `el campo limita la longitud`() = runTest {
        RedeemCodeViewModel(redeem).testMvi {
            intent(RedeemCodeIntent.CodeChanged("A".repeat(100)))
            assertState { it.code.length == RedeemCodeState.MAX_INPUT }
        }
    }
}
