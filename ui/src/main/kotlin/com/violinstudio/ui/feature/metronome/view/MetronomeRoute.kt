package com.violinstudio.ui.feature.metronome.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeIntent
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeViewModel

/**
 * `ON_STOP` silencia el metronomo (sin sonido en segundo plano) y al volver sigue parado: no se reanuda solo.
 * Un cambio de configuracion (rotacion, idioma, modo oscuro) no cuenta como segundo plano: el ViewModel sobrevive
 * y su reproduccion sigue, asi que ni `ON_STOP` ni salir de la composicion lo paran. Salir de la ruta (atras)
 * limpia el ViewModel y cancela la salida.
 */
@Composable
fun MetronomeRoute(
    onBack: () -> Unit,
    viewModel: MetronomeViewModel = hiltViewModel(),
    isChangingConfigurations: () -> Boolean = rememberIsChangingConfigurations()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!isChangingConfigurations()) viewModel.onIntent(MetronomeIntent.Stop)
    }
    // Con la ruta enterrada en la pila el observador puede liberarse antes de ON_STOP: Stop es idempotente.
    DisposableEffect(viewModel) {
        onDispose { if (!isChangingConfigurations()) viewModel.onIntent(MetronomeIntent.Stop) }
    }
    MetronomeScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}

@Composable
private fun rememberIsChangingConfigurations(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { { context.findActivity()?.isChangingConfigurations == true } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
