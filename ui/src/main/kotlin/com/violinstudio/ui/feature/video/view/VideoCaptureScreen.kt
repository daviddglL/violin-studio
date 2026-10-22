package com.violinstudio.ui.feature.video.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.video.viewmodel.CameraPrompt
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureIntent
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureState
import com.violinstudio.ui.feature.video.viewmodel.VideoMessage
import com.violinstudio.ui.feature.video.viewmodel.VideoPhase
import java.util.Locale

const val VIDEO_TAG = "video_capture"
const val VIDEO_BACK_TAG = "video_back"
const val VIDEO_RECORD_TAG = "video_record"
const val VIDEO_STOP_TAG = "video_stop"
const val VIDEO_MESSAGE_TAG = "video_message"
const val VIDEO_BLURRING_TAG = "video_blurring"

/** [preview] es la vista previa de la camara; los tests y las capturas pasan un hueco vacio. */
@Composable
fun VideoCaptureScreen(
    state: VideoCaptureState,
    onIntent: (VideoCaptureIntent) -> Unit,
    onRecord: () -> Unit,
    onBack: () -> Unit,
    preview: @Composable (Modifier) -> Unit = {}
) {
    val title = if (state.demo) R.string.video_demo_title else R.string.video_title
    AuthScaffold(VIDEO_TAG, stringResource(title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.video_privacy), style = MaterialTheme.typography.bodyMedium)
            preview(Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO))
            Controls(state, onIntent, onRecord)
            Prompt(state.prompt, onIntent)
            Message(state.message, onIntent)
            TextButton(
                onClick = onBack,
                enabled = state.phase == VideoPhase.IDLE,
                modifier = Modifier.testTag(VIDEO_BACK_TAG)
            ) { Text(stringResource(R.string.video_back)) }
        }
    }
}

@Composable
private fun Controls(state: VideoCaptureState, onIntent: (VideoCaptureIntent) -> Unit, onRecord: () -> Unit) {
    when (state.phase) {
        VideoPhase.IDLE -> Button(onClick = onRecord, modifier = Modifier.testTag(VIDEO_RECORD_TAG)) {
            Text(stringResource(R.string.video_record))
        }
        VideoPhase.STARTING, VideoPhase.STOPPING -> LoadingIndicator()
        VideoPhase.RECORDING -> {
            Text(
                stringResource(
                    R.string.video_elapsed,
                    clock(state.elapsedMs),
                    clock(state.maxDurationMs ?: state.elapsedMs)
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Button(
                onClick = { onIntent(VideoCaptureIntent.Stop) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(VIDEO_STOP_TAG)
            ) { Text(stringResource(R.string.video_stop)) }
        }
        VideoPhase.BLURRING -> {
            Text(
                stringResource(R.string.video_blurring),
                modifier = Modifier.testTag(VIDEO_BLURRING_TAG).semantics { liveRegion = LiveRegionMode.Polite }
            )
            LoadingIndicator()
        }
    }
}

@Composable
private fun Prompt(prompt: CameraPrompt?, onIntent: (VideoCaptureIntent) -> Unit) {
    when (prompt) {
        null -> Unit
        CameraPrompt.RATIONALE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.video_permission_rationale), modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(VideoCaptureIntent.ConfirmRationale) }) {
                Text(stringResource(R.string.video_permission_allow))
            }
        }
        CameraPrompt.BLOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.video_permission_blocked), modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(VideoCaptureIntent.OpenAppSettings) }) {
                Text(stringResource(R.string.video_permission_settings))
            }
        }
    }
}

@Composable
private fun Message(message: VideoMessage?, onIntent: (VideoCaptureIntent) -> Unit) {
    message ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(VIDEO_MESSAGE_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            stringResource(messageText(message)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onIntent(VideoCaptureIntent.DismissMessage) }) {
            Text(stringResource(R.string.video_dismiss))
        }
    }
}

private fun messageText(message: VideoMessage): Int = when (message) {
    VideoMessage.NOT_AVAILABLE -> R.string.video_msg_not_available
    VideoMessage.OFFLINE -> R.string.video_msg_offline
    VideoMessage.QUOTA_REACHED -> R.string.video_msg_quota
    VideoMessage.CAMERA_FAILED -> R.string.video_msg_camera_failed
    VideoMessage.TOO_SHORT -> R.string.video_msg_too_short
    VideoMessage.TOO_LARGE -> R.string.video_msg_too_large
    VideoMessage.BLUR_FAILED -> R.string.video_msg_blur_failed
    VideoMessage.SAVE_FAILED -> R.string.video_msg_save_failed
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private const val PREVIEW_RATIO = 3f / 4f
