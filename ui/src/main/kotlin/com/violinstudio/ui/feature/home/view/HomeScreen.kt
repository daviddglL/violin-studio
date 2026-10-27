package com.violinstudio.ui.feature.home.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.commons.components.ErrorView
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.home.viewmodel.HealthStatus
import com.violinstudio.ui.feature.home.viewmodel.HomeEffect
import com.violinstudio.ui.feature.home.viewmodel.HomeIntent
import com.violinstudio.ui.feature.home.viewmodel.HomeState
import com.violinstudio.ui.feature.home.viewmodel.HomeViewModel
import com.violinstudio.ui.feature.home.viewmodel.PracticeTipsViewModel

@Composable
fun HomeRoute(
    navigation: HomeNavigation? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    tipsViewModel: PracticeTipsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tips by tipsViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val unknownError = stringResource(R.string.error_unknown)
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is HomeEffect.ShowError ->
                snackbarHostState.showSnackbar(effect.message ?: unknownError)
        }
    }
    HomeScreen(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        navigation = navigation,
        tips = tips.tips
    )
}

@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    navigation: HomeNavigation? = null,
    tips: List<PracticeTip> = emptyList()
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        // Centrado cuando cabe y con scroll cuando no (horizontal, pantallas bajas, fuente grande): las tarjetas
        // inferiores siempre son alcanzables (REQ-NAV-P01).
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            HomeContent(state, onIntent, navigation, tips, maxHeight)
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    navigation: HomeNavigation?,
    tips: List<PracticeTip>,
    minHeight: Dp
) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        when (val status = state.status) {
            HealthStatus.Idle -> Text(stringResource(R.string.home_status_idle))

            HealthStatus.Loading -> LoadingIndicator()

            is HealthStatus.Ok -> Text(
                text = stringResource(R.string.home_status_ok, status.version),
                modifier = Modifier.testTag("health_ok")
            )

            is HealthStatus.Error -> ErrorView(status.message ?: stringResource(R.string.error_unknown))
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onIntent(HomeIntent.CheckHealth) },
            enabled = state.status !is HealthStatus.Loading
        ) {
            Text(stringResource(R.string.home_check_health))
        }
        navigation?.let {
            // Solo en el inicio con sesion: los consejos salen del historial del usuario.
            PracticeTipsCard(tips, Modifier.padding(vertical = 16.dp))
            OutlinedButton(onClick = it.onOpenSettings, modifier = Modifier.testTag("home_settings")) {
                Text(stringResource(R.string.home_open_settings))
            }
            OutlinedButton(onClick = it.onOpenTuner, modifier = Modifier.testTag("home_tuner")) {
                Text(stringResource(R.string.tuner_home_card))
            }
            OutlinedButton(onClick = it.onOpenMetronome, modifier = Modifier.testTag("home_metronome")) {
                Text(stringResource(R.string.metronome_home_card))
            }
            OutlinedButton(onClick = it.onOpenPractice, modifier = Modifier.testTag("home_practice")) {
                Text(stringResource(R.string.practice_home_card))
            }
            it.onOpenStats?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_stats")) {
                    Text(stringResource(R.string.home_stats_card))
                }
            }
            it.onOpenCurriculum?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_curriculum")) {
                    Text(stringResource(R.string.home_curriculum_card))
                }
            }
            it.onOpenRecordings?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_recordings")) {
                    Text(stringResource(R.string.home_recordings_card))
                }
            }
            it.onOpenRedeem?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_redeem")) {
                    Text(stringResource(R.string.home_redeem_card))
                }
            }
            it.onOpenTeacherRequest?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_teacher_request")) {
                    Text(stringResource(R.string.home_teacher_request_card))
                }
            }
            it.onEnableNotifications?.let { enable ->
                OutlinedButton(onClick = enable, modifier = Modifier.testTag("home_enable_notifications")) {
                    Text(stringResource(R.string.home_enable_notifications))
                }
            }
            it.onOpenAssignments?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_assignments")) {
                    Text(stringResource(R.string.home_assignments_card))
                }
            }
            it.onOpenStudents?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_students")) {
                    Text(stringResource(R.string.home_students_card))
                }
            }
            it.onOpenCodes?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_codes")) {
                    Text(stringResource(R.string.home_codes_card))
                }
            }
            it.onOpenMyTeacher?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.testTag("home_my_teacher")) {
                    Text(stringResource(R.string.home_my_teacher_card))
                }
            }
        }
    }
}

/** Accesos de Home a las rutas de negocio; el resto de tarjetas llegan con cada slice. */
data class HomeNavigation(
    val onOpenSettings: () -> Unit,
    val onOpenTuner: () -> Unit,
    val onOpenMetronome: () -> Unit,
    val onOpenPractice: () -> Unit,
    /** Solo para `independent` (la navegación decide por rol; Home no conoce la sesión). */
    val onOpenRedeem: (() -> Unit)? = null,
    /** Solo para `independent` adulto: pedir el alta de profesor. */
    val onOpenTeacherRequest: (() -> Unit)? = null,
    /** Solo para `student`. */
    val onOpenMyTeacher: (() -> Unit)? = null,
    /** Solo para `teacher`. */
    val onOpenStudents: (() -> Unit)? = null,
    val onOpenCodes: (() -> Unit)? = null,
    /** Solo para `student`. */
    val onOpenAssignments: (() -> Unit)? = null,
    /** Comunes a todos los roles; nulos solo en tests que no los usan. */
    val onOpenStats: (() -> Unit)? = null,
    val onOpenCurriculum: (() -> Unit)? = null,
    val onOpenRecordings: (() -> Unit)? = null,
    /** Pide el permiso de avisos (Android 13+); solo con los avisos encendidos y el permiso sin conceder. */
    val onEnableNotifications: (() -> Unit)? = null
)
