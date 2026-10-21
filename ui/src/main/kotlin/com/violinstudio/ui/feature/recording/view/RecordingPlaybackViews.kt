package com.violinstudio.ui.feature.recording.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.Review
import com.violinstudio.domain.feature.recording.model.Verdict
import com.violinstudio.ui.R

fun playTag(id: String) = "recording_play_$id"

fun stopPlaybackTag(id: String) = "recording_stop_$id"

/** "Escuchar" o "Parar" segun lo que suene; el aviso de fallo solo en la grabacion que fallo. */
@Composable
fun PlaybackControl(recordingId: String, playback: PlaybackState, onPlay: () -> Unit, onStop: () -> Unit) {
    val active = when (playback) {
        is PlaybackState.Loading -> playback.recordingId == recordingId
        is PlaybackState.Playing -> playback.recordingId == recordingId
        else -> false
    }
    if (active) {
        TextButton(onClick = onStop, modifier = Modifier.testTag(stopPlaybackTag(recordingId))) {
            val label = if (playback is PlaybackState.Loading) {
                R.string.recordings_playback_loading
            } else {
                R.string.recordings_stop_playback
            }
            Text(stringResource(label))
        }
    } else {
        TextButton(onClick = onPlay, modifier = Modifier.testTag(playTag(recordingId))) {
            Text(stringResource(R.string.recordings_play))
        }
    }
    if (playback is PlaybackState.Failed && playback.recordingId == recordingId) {
        Text(
            stringResource(R.string.recordings_playback_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

fun verdictText(verdict: Verdict): Int = when (verdict) {
    Verdict.APPROVED -> R.string.review_verdict_approved
    Verdict.NEEDS_WORK -> R.string.review_verdict_needs_work
}

/** Una valoracion tal como la ve el alumno; el texto puede ir vacio (solo veredicto). */
@Composable
fun ReviewView(review: Review) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val author = if (review.fromTeacher) R.string.review_from_teacher else R.string.review_from_former_teacher
        Text(
            stringResource(author, stringResource(verdictText(review.verdict))),
            style = MaterialTheme.typography.titleSmall
        )
        if (review.text.isNotBlank()) Text(review.text, style = MaterialTheme.typography.bodyMedium)
    }
}
