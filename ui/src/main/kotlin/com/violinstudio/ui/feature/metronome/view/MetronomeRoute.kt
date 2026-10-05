package com.violinstudio.ui.feature.metronome.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeIntent
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeViewModel

/**
 * `ON_STOP` silencia el metronomo (sin sonido en segundo plano) y `ON_START` lo reanuda solo si sonaba; el
 * ViewModel sobrevive a la rotacion y `Resume` es idempotente, asi que no se abre una segunda salida.
 */
@Composable
fun MetronomeRoute(onBack: () -> Unit, viewModel: MetronomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(MetronomeIntent.Resume) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(MetronomeIntent.Stop) }
    // Con la ruta enterrada en la pila el observador puede liberarse antes de ON_STOP: Stop es idempotente.
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(MetronomeIntent.Stop) } }
    MetronomeScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}
