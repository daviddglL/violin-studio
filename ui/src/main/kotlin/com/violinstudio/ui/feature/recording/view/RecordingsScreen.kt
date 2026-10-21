package com.violinstudio.ui.feature.recording.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordingAccess
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.recording.viewmodel.MicPrompt
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsIntent
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsMessage
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsState
import java.util.Locale

const val RECORDINGS_TAG = "recordings"
const val RECORDINGS_BACK_TAG = "recordings_back"
const val RECORDINGS_RECORD_TAG = "recordings_record"
const val RECORDINGS_STOP_TAG = "recordings_stop"
const val RECORDINGS_GRANT_TAG = "recordings_grant"
const val RECORDINGS_REVOKE_TAG = "recordings_revoke"
const val RECORDINGS_MESSAGE_TAG = "recordings_message"
const val RECORDINGS_REVIEWS_TAG = "recordings_reviews"

fun pendingTag(id: String) = "recordings_pending_$id"

fun recordingTag(id: String) = "recordings_item_$id"

fun reviewsToggleTag(id: String) = "recordings_reviews_toggle_$id"

@Composable
fun RecordingsScreen(
    state: RecordingsState,
    onIntent: (RecordingsIntent) -> Unit,
    onRecord: () -> Unit,
    onBack: () -> Unit
) {
    AuthScaffold(RECORDINGS_TAG, stringResource(R.string.recordings_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Access(state, onIntent, onRecord)
            Mic(state.mic, onIntent)
            Message(state.message, onIntent)
            if (state.pending.isNotEmpty()) {
                SectionTitle(stringResource(R.string.recordings_pending_title))
                state.pending.forEach { PendingItem(it, onIntent) }
            }
            SectionTitle(stringResource(R.string.recordings_uploaded_title))
            if (state.recordings.isEmpty()) {
                Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyMedium)
            }
            state.recordings.forEach { RecordingItem(it, state, onIntent) }
            if (state.access.isConsented()) {
                TextButton(
                    onClick = { onIntent(RecordingsIntent.AskRevoke) },
                    enabled = !state.busy && !state.isRecording,
                    modifier = Modifier.testTag(RECORDINGS_REVOKE_TAG)
                ) { Text(stringResource(R.string.recordings_revoke)) }
            }
            TextButton(onClick = onBack, modifier = Modifier.testTag(RECORDINGS_BACK_TAG)) {
                Text(stringResource(R.string.recordings_back))
            }
        }
    }
    if (state.confirmRevoke) {
        Confirm(
            title = R.string.recordings_revoke_title,
            body = R.string.recordings_revoke_body,
            confirm = R.string.recordings_revoke_confirm,
            onConfirm = { onIntent(RecordingsIntent.ConfirmRevoke) },
            onDismiss = { onIntent(RecordingsIntent.CancelRevoke) }
        )
    }
    if (state.confirmDeleteId != null) {
        Confirm(
            title = R.string.recordings_delete_title,
            body = R.string.recordings_delete_body,
            confirm = R.string.recordings_delete,
            onConfirm = { onIntent(RecordingsIntent.ConfirmDelete) },
            onDismiss = { onIntent(RecordingsIntent.CancelDelete) }
        )
    }
}

private fun RecordingAccess?.isConsented() = this is RecordingAccess.Allowed

@Composable
private fun Access(state: RecordingsState, onIntent: (RecordingsIntent) -> Unit, onRecord: () -> Unit) {
    when (val access = state.access) {
        null -> if (state.accessFailed) {
            Text(stringResource(R.string.recordings_access_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onIntent(RecordingsIntent.Refresh) }) {
                Text(stringResource(R.string.recordings_retry))
            }
        } else {
            Text(stringResource(R.string.recordings_checking))
            LoadingIndicator()
        }
        RecordingAccess.Disabled -> Text(stringResource(R.string.recordings_disabled))
        is RecordingAccess.NeedsConsent -> Consent(access, state.busy, onIntent)
        is RecordingAccess.Allowed -> Recorder(state, access, onIntent, onRecord)
    }
}

@Composable
private fun Consent(access: RecordingAccess.NeedsConsent, busy: Boolean, onIntent: (RecordingsIntent) -> Unit) {
    SectionTitle(stringResource(R.string.recordings_consent_title))
    if (access.selfGrant) {
        Text(stringResource(R.string.recordings_consent_body), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { onIntent(RecordingsIntent.GrantConsent) },
            enabled = !busy,
            modifier = Modifier.testTag(RECORDINGS_GRANT_TAG)
        ) { Text(stringResource(R.string.recordings_consent_accept)) }
    } else {
        Text(stringResource(R.string.recordings_consent_guardian), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Recorder(
    state: RecordingsState,
    access: RecordingAccess.Allowed,
    onIntent: (RecordingsIntent) -> Unit,
    onRecord: () -> Unit
) {
    val elapsed = state.elapsedMs
    if (elapsed != null) {
        Text(
            stringResource(R.string.recordings_elapsed, clock(elapsed), clock(access.maxDurationMs)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Button(
            onClick = { onIntent(RecordingsIntent.Stop) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag(RECORDINGS_STOP_TAG)
        ) { Text(stringResource(R.string.recordings_stop)) }
    } else if (access.quotaFull) {
        Text(stringResource(R.string.recordings_quota_full), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            stringResource(R.string.recordings_limit, clock(access.maxDurationMs)),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRecord, enabled = !state.busy, modifier = Modifier.testTag(RECORDINGS_RECORD_TAG)) {
            Text(stringResource(R.string.recordings_record))
        }
    }
}

@Composable
private fun Mic(prompt: MicPrompt?, onIntent: (RecordingsIntent) -> Unit) {
    when (prompt) {
        null -> Unit
        MicPrompt.RATIONALE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recordings_mic_rationale), modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(RecordingsIntent.ConfirmRationale) }) {
                Text(stringResource(R.string.recordings_mic_allow))
            }
        }
        MicPrompt.BLOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recordings_mic_blocked), modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(RecordingsIntent.OpenAppSettings) }) {
                Text(stringResource(R.string.recordings_mic_settings))
            }
        }
    }
}

@Composable
private fun Message(message: RecordingsMessage?, onIntent: (RecordingsIntent) -> Unit) {
    message ?: return
    val isError = message != RecordingsMessage.SAVED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(RECORDINGS_MESSAGE_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            stringResource(messageText(message)),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onIntent(RecordingsIntent.DismissMessage) }) {
            Text(stringResource(R.string.recordings_dismiss))
        }
    }
}

private fun messageText(message: RecordingsMessage): Int = when (message) {
    RecordingsMessage.LOAD_FAILED -> R.string.recordings_msg_load_failed
    RecordingsMessage.OFFLINE -> R.string.recordings_msg_offline
    RecordingsMessage.CONSENT_FAILED -> R.string.recordings_msg_consent_failed
    RecordingsMessage.GUARDIAN_REQUIRED -> R.string.recordings_msg_guardian
    RecordingsMessage.TEMPORARILY_UNAVAILABLE -> R.string.recordings_msg_temporarily
    RecordingsMessage.QUOTA_REACHED -> R.string.recordings_msg_quota
    RecordingsMessage.MIC_BUSY -> R.string.recordings_msg_mic_busy
    RecordingsMessage.TOO_SHORT -> R.string.recordings_msg_too_short
    RecordingsMessage.RECORDING_FAILED -> R.string.recordings_msg_failed
    RecordingsMessage.SAVED -> R.string.recordings_msg_saved
    RecordingsMessage.SAVE_FAILED -> R.string.recordings_msg_save_failed
    RecordingsMessage.DELETE_FAILED -> R.string.recordings_msg_delete_failed
    RecordingsMessage.ACTION_FAILED -> R.string.recordings_msg_action_failed
}

@Composable
private fun PendingItem(item: QueuedRecording, onIntent: (RecordingsIntent) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(pendingTag(item.id))) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.recordings_item, clock(item.take.durationMs), stringResource(pendingText(item)))
            )
            if (item.state == QueueState.FAILED || item.state == QueueState.RECORDED) {
                Row {
                    if (item.state == QueueState.FAILED) {
                        TextButton(onClick = { onIntent(RecordingsIntent.Retry(item.id)) }) {
                            Text(stringResource(R.string.recordings_retry_upload))
                        }
                    }
                    TextButton(onClick = { onIntent(RecordingsIntent.Discard(item.id)) }) {
                        Text(stringResource(R.string.recordings_discard))
                    }
                }
            }
        }
    }
}

private fun pendingText(item: QueuedRecording): Int = when (item.state) {
    QueueState.RECORDED -> R.string.recordings_state_queued
    QueueState.UPLOADING -> R.string.recordings_state_uploading
    QueueState.UPLOADED, QueueState.DONE -> R.string.recordings_state_processing
    QueueState.FAILED -> when (item.lastError) {
        FailReason.QUOTA -> R.string.recordings_state_failed_quota
        FailReason.SIZE -> R.string.recordings_state_failed_size
        FailReason.PERMISSION -> R.string.recordings_state_failed_permission
        FailReason.UNKNOWN, null -> R.string.recordings_state_failed
    }
}

@Composable
private fun RecordingItem(item: RecordingSummary, state: RecordingsState, onIntent: (RecordingsIntent) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(recordingTag(item.id))) {
        Column(Modifier.padding(12.dp)) {
            val status =
                stringResource(if (item.ready) R.string.recordings_state_ready else R.string.recordings_state_rejected)
            val detail = recordingDetail(item)
            Text(
                if (item.isDemo) {
                    stringResource(R.string.recordings_item_demo, detail)
                } else {
                    stringResource(R.string.recordings_item, detail, status)
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.ready && !state.isRecording) {
                    PlaybackControl(
                        item.id,
                        state.playback,
                        onPlay = { onIntent(RecordingsIntent.Play(item.id)) },
                        onStop = { onIntent(RecordingsIntent.StopPlayback) }
                    )
                }
                if (item.ready && !item.isDemo) {
                    val open = state.reviewsFor == item.id
                    TextButton(
                        onClick = { onIntent(RecordingsIntent.ToggleReviews(item.id)) },
                        modifier = Modifier.testTag(reviewsToggleTag(item.id))
                    ) {
                        Text(
                            stringResource(
                                if (open) R.string.recordings_reviews_hide else R.string.recordings_reviews_show
                            )
                        )
                    }
                }
                TextButton(onClick = { onIntent(RecordingsIntent.AskDelete(item.id)) }) {
                    Text(stringResource(R.string.recordings_delete))
                }
            }
            if (state.reviewsFor == item.id) Reviews(state)
        }
    }
}

@Composable
private fun Reviews(state: RecordingsState) {
    val reviews = state.reviews
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(RECORDINGS_REVIEWS_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        when {
            state.reviewsFailed -> Text(
                stringResource(R.string.recordings_reviews_failed),
                color = MaterialTheme.colorScheme.error
            )
            reviews == null -> LoadingIndicator()
            reviews.isEmpty() -> Text(stringResource(R.string.recordings_reviews_empty))
            else -> reviews.forEach { ReviewView(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Confirm(title: Int, body: Int, confirm: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.recordings_cancel)) } }
    )
}

/** `m:ss` sin locale: solo cifras. */
internal fun clock(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
