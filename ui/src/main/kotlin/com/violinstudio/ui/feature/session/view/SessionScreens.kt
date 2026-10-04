package com.violinstudio.ui.feature.session.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.ui.R

const val SPLASH_TAG = "splash"
const val OFFLINE_TAG = "offline"

/** Pantalla de arranque mientras la sesión se resuelve. */
@Composable
fun SplashScreen() {
    val description = stringResource(R.string.session_loading)
    Box(
        Modifier.fillMaxSize().testTag(SPLASH_TAG).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        // Sin animación infinita: el splash dura un instante y no debe bloquear la sincronización de los tests.
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    }
}

/** Sin red y reintentando solo: la única acción es cerrar sesión. */
@Composable
fun OfflineScreen(onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(OFFLINE_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.offline_message))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.session_sign_out)) }
    }
}

/** Marcador de destinos que construyen los slices 5b/6a/6b. */
@Composable
fun PlaceholderScreen(tag: String) {
    Box(Modifier.fillMaxSize().testTag(tag))
}
