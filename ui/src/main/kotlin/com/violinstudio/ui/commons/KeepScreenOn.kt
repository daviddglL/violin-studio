package com.violinstudio.ui.commons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Mantiene la pantalla encendida mientras [enabled]. Las pantallas que suenan o escuchan se paran en `ON_STOP`, y el
 * bloqueo automatico de la pantalla lo provoca a mitad de la practica. Al salir de la composicion se suelta.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
