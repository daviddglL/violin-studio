package com.violinstudio.ui.feature.recording.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.recording.model.MediaKind
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.recording.viewmodel.AssignmentDemosIntent
import com.violinstudio.ui.feature.recording.viewmodel.AssignmentDemosState
import com.violinstudio.ui.feature.recording.viewmodel.AssignmentDemosViewModel

const val ASSIGNMENT_DEMOS_TAG = "assignment_demos"
const val ASSIGNMENT_DEMOS_BACK_TAG = "assignment_demos_back"
const val ASSIGNMENT_DEMOS_EMPTY_TAG = "assignment_demos_empty"
const val ASSIGNMENT_DEMOS_FAILED_TAG = "assignment_demos_failed"
const val ASSIGNMENT_DEMOS_RETRY_TAG = "assignment_demos_retry"

/** El rol lo comprueba la navegacion. Al salir o pasar a segundo plano deja de sonar. */
@Composable
fun AssignmentDemosRoute(onBack: () -> Unit, viewModel: AssignmentDemosViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(AssignmentDemosIntent.Leave) }
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(AssignmentDemosIntent.Leave) } }
    AssignmentDemosScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun AssignmentDemosScreen(state: AssignmentDemosState, onIntent: (AssignmentDemosIntent) -> Unit, onBack: () -> Unit) {
    AuthScaffold(ASSIGNMENT_DEMOS_TAG, stringResource(R.string.assignment_demos_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                state.isLoading -> LoadingIndicator()

                state.loadFailed -> {
                    Text(
                        stringResource(R.string.assignment_demos_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ASSIGNMENT_DEMOS_FAILED_TAG)
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    OutlinedButton(
                        onClick = { onIntent(AssignmentDemosIntent.Retry) },
                        modifier = Modifier.testTag(ASSIGNMENT_DEMOS_RETRY_TAG)
                    ) { Text(stringResource(R.string.link_retry)) }
                }

                state.demos.isEmpty() -> Text(
                    stringResource(R.string.assignment_demos_empty),
                    modifier = Modifier.testTag(ASSIGNMENT_DEMOS_EMPTY_TAG)
                )

                else -> state.demos.forEach { demo ->
                    Card(Modifier.fillMaxWidth().testTag(recordingTag(demo.id))) {
                        Column(Modifier.padding(12.dp)) {
                            Text(recordingDetail(demo))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlaybackControl(
                                    demo.id,
                                    state.playback,
                                    onPlay = { onIntent(AssignmentDemosIntent.Play(demo.id)) },
                                    onStop = { onIntent(AssignmentDemosIntent.Stop) },
                                    video = demo.kind == MediaKind.VIDEO
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.testTag(ASSIGNMENT_DEMOS_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
        }
    }
}
