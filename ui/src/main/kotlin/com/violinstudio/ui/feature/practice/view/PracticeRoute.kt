package com.violinstudio.ui.feature.practice.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.feature.practice.viewmodel.PracticeViewModel

/**
 * El ViewModel sobrevive a la recreación de la actividad y deriva el cronómetro de `startedAt` y el reloj, así que
 * la sesión en curso y su tiempo se conservan; la pantalla no necesita permisos.
 */
@Composable
fun PracticeRoute(onBack: () -> Unit, viewModel: PracticeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PracticeScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}
