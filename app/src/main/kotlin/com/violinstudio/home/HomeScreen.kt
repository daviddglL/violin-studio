package com.violinstudio.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.R
import com.violinstudio.core.ui.ObserveAsEvents
import com.violinstudio.core.ui.components.ErrorView
import com.violinstudio.core.ui.components.LoadingIndicator

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is HomeEffect.ShowError ->
                snackbarHostState.showSnackbar(effect.message ?: context.getString(R.string.error_unknown))
        }
    }
    HomeScreen(state = state, onIntent = viewModel::onIntent, snackbarHostState = snackbarHostState)
}

@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
        }
    }
}
