package com.violinstudio.ui.feature.recording.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.recording.viewmodel.ReviewEditor
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsIntent
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsMessage
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsState
import com.violinstudio.ui.feature.recording.viewmodel.StudentRecordingsViewModel
import java.text.DateFormat
import java.util.Date

const val STUDENT_RECORDINGS_TAG = "student_recordings"
const val STUDENT_RECORDINGS_BACK_TAG = "student_recordings_back"
const val STUDENT_RECORDINGS_EMPTY_TAG = "student_recordings_empty"
const val STUDENT_RECORDINGS_FAILED_TAG = "student_recordings_failed"
const val STUDENT_RECORDINGS_MESSAGE_TAG = "student_recordings_message"
const val REVIEW_TEXT_TAG = "review_text"
const val REVIEW_SAVE_TAG = "review_save"

fun reviewTag(id: String) = "student_recordings_review_$id"

fun verdictTag(verdict: Verdict) = "review_verdict_${verdict.wire}"

/** El rol lo comprueba la navegacion. Al salir o pasar a segundo plano deja de sonar. */
@Composable
fun StudentRecordingsRoute(onBack: () -> Unit, viewModel: StudentRecordingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(StudentRecordingsIntent.Leave) }
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(StudentRecordingsIntent.Leave) } }
    StudentRecordingsScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun StudentRecordingsScreen(
    state: StudentRecordingsState,
    onIntent: (StudentRecordingsIntent) -> Unit,
    onBack: () -> Unit
) {
    AuthScaffold(STUDENT_RECORDINGS_TAG, stringResource(R.string.student_recordings_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.student_recordings_intro), style = MaterialTheme.typography.bodyMedium)
            Message(state.message, onIntent)
            when {
                state.isLoading -> LoadingIndicator()
                state.loadFailed -> {
                    Text(
                        stringResource(R.string.student_recordings_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(STUDENT_RECORDINGS_FAILED_TAG)
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    OutlinedButton(onClick = { onIntent(StudentRecordingsIntent.Retry) }) {
                        Text(stringResource(R.string.student_recordings_retry))
                    }
                }
                state.recordings.isEmpty() -> Text(
                    stringResource(R.string.student_recordings_empty),
                    modifier = Modifier.testTag(STUDENT_RECORDINGS_EMPTY_TAG)
                )
                else -> state.recordings.forEach { StudentRecordingItem(it, state.playback, onIntent) }
            }
            TextButton(onClick = onBack, modifier = Modifier.testTag(STUDENT_RECORDINGS_BACK_TAG)) {
                Text(stringResource(R.string.student_recordings_back))
            }
        }
    }
    state.editor?.let { ReviewDialog(it, onIntent) }
}

@Composable
private fun StudentRecordingItem(
    item: RecordingSummary,
    playback: PlaybackState,
    onIntent: (StudentRecordingsIntent) -> Unit
) {
    Card(Modifier.fillMaxWidth().testTag(recordingTag(item.id))) {
        Column(Modifier.padding(12.dp)) {
            Text(recordingDetail(item))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaybackControl(
                    item.id,
                    playback,
                    onPlay = { onIntent(StudentRecordingsIntent.Play(item.id)) },
                    onStop = { onIntent(StudentRecordingsIntent.Stop) },
                    video = item.kind == MediaKind.VIDEO
                )
                TextButton(
                    onClick = { onIntent(StudentRecordingsIntent.OpenReview(item.id)) },
                    modifier = Modifier.testTag(reviewTag(item.id))
                ) { Text(stringResource(R.string.student_recordings_review)) }
            }
        }
    }
}

/** Fecha y duracion; sin ids ni rutas. */
internal fun recordingDetail(item: RecordingSummary): String {
    val date = item.createdAtMs?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    }
    return listOfNotNull(date, item.durationMs?.let(::clock)).joinToString(" · ")
}

@Composable
private fun ReviewDialog(editor: ReviewEditor, onIntent: (StudentRecordingsIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!editor.saving) onIntent(StudentRecordingsIntent.CloseReview) },
        title = { Text(stringResource(R.string.student_recordings_editor_title)) },
        text = {
            if (editor.loading) {
                LoadingIndicator()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Verdict.entries.forEach { verdict ->
                            FilterChip(
                                selected = editor.verdict == verdict,
                                onClick = { onIntent(StudentRecordingsIntent.SetVerdict(verdict)) },
                                label = { Text(stringResource(verdictText(verdict))) },
                                enabled = !editor.saving,
                                modifier = Modifier.testTag(verdictTag(verdict))
                            )
                        }
                    }
                    OutlinedTextField(
                        value = editor.text,
                        onValueChange = { onIntent(StudentRecordingsIntent.SetText(it)) },
                        label = { Text(stringResource(R.string.student_recordings_editor_text)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.student_recordings_editor_count,
                                    editor.text.length,
                                    Review.MAX_TEXT
                                )
                            )
                        },
                        enabled = !editor.saving,
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag(REVIEW_TEXT_TAG)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onIntent(StudentRecordingsIntent.SaveReview) },
                enabled = editor.canSave,
                modifier = Modifier.testTag(REVIEW_SAVE_TAG)
            ) { Text(stringResource(R.string.student_recordings_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(StudentRecordingsIntent.CloseReview) }, enabled = !editor.saving) {
                Text(stringResource(R.string.student_recordings_editor_cancel))
            }
        }
    )
}

@Composable
private fun Message(message: StudentRecordingsMessage?, onIntent: (StudentRecordingsIntent) -> Unit) {
    message ?: return
    val text = when (message) {
        StudentRecordingsMessage.SAVED -> R.string.student_recordings_msg_saved
        StudentRecordingsMessage.SAVE_FAILED -> R.string.student_recordings_msg_save_failed
        StudentRecordingsMessage.REVIEW_LOAD_FAILED -> R.string.student_recordings_msg_load_failed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(STUDENT_RECORDINGS_MESSAGE_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            stringResource(text),
            color = if (message == StudentRecordingsMessage.SAVED) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onIntent(StudentRecordingsIntent.DismissMessage) }) {
            Text(stringResource(R.string.student_recordings_dismiss))
        }
    }
}
