package com.violinstudio.ui.feature.account.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountViewModel
import com.violinstudio.ui.feature.account.viewmodel.DeleteStep

/**
 * Lo que una pantalla recibe del borrado compartido (D1): [active] indica que el flujo esta abierto, trabajando o
 * terminado (el resto de sus acciones deben quedar deshabilitadas) y [entry] pinta el punto de entrada; su argumento
 * dice si la propia pantalla permite empezar ahora.
 */
class DeleteAccountScope(val active: Boolean, val entry: @Composable (enabled: Boolean) -> Unit)

/** Sin proveedor (tests, previsualizaciones) no hay borrado: la pantalla no pinta nada y nada queda bloqueado. */
val LocalDeleteAccount = compositionLocalOf { DeleteAccountScope(active = false) { } }

/**
 * Da a las pantallas de [content] el mismo flujo de borrado, con un ViewModel propio de este destino (si la sesion
 * cambia de destino, un flujo a medias no viaja a otra pantalla). [viewModel] nulo: sin borrado.
 */
@Composable
fun WithDeleteAccount(viewModel: (@Composable () -> DeleteAccountViewModel)?, content: @Composable () -> Unit) {
    if (viewModel == null) {
        content()
        return
    }
    val vm = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = DeleteAccountScope(state.step != DeleteStep.IDLE || state.deleted) { enabled ->
        DeleteAccountEntry(state, vm::onIntent, enabled)
    }
    CompositionLocalProvider(LocalDeleteAccount provides scope, content = content)
}
