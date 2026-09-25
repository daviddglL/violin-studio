package com.violinstudio.home

import com.violinstudio.core.data.health.HealthRepository
import com.violinstudio.core.model.HealthInfo
import com.violinstudio.core.testing.MainDispatcherExtension
import com.violinstudio.core.testing.testMvi
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class HomeViewModelTest {
    // La latencia hace observable el estado Loading (StateFlow descarta estados intermedios).
    private fun viewModel(fetch: suspend () -> HealthInfo) = HomeViewModel(
        HealthRepository {
            delay(100)
            fetch()
        }
    )

    @Test
    fun `CheckHealth pasa por Loading y termina en Ok`() = runTest {
        viewModel { HealthInfo("ok", "0.1.0") }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Ok("0.1.0") }
            assertNoEffects()
        }
    }

    @Test
    fun `un error termina en Error y emite ShowError con el mensaje`() = runTest {
        viewModel { throw IOException("sin red") }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Error("sin red") }
            assertEffect(HomeEffect.ShowError("sin red"))
        }
    }

    @Test
    fun `un error sin mensaje llega como null para que la UI ponga el texto por defecto`() = runTest {
        viewModel { throw IllegalStateException() }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Error(null) }
            assertEffect(HomeEffect.ShowError(null))
        }
    }
}
