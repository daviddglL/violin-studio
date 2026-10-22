package com.violinstudio.ui.feature.video.view

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.violinstudio.domain.feature.recording.model.VideoClip
import com.violinstudio.ui.R

const val VIDEO_PLAYER_TAG = "video_player"
const val VIDEO_PLAYER_CLOSE_TAG = "video_player_close"

/**
 * Reproduce un video (4c) ya difuminado desde memoria: nunca se escribe en disco. Se pausa al salir de primer plano y
 * libera el reproductor al cerrarse; cerrar es "Parar" en la lista.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(clip: VideoClip, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember(clip) {
        val source = DataSource.Factory { ByteArrayDataSource(clip.bytes) }
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(MEMORY_URI)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AndroidView(
                    factory = { PlayerView(it).also { view -> view.player = player } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(PLAYER_RATIO).testTag(VIDEO_PLAYER_TAG)
                )
                TextButton(onClick = onClose, modifier = Modifier.testTag(VIDEO_PLAYER_CLOSE_TAG)) {
                    Text(stringResource(R.string.video_player_close))
                }
            }
        }
    }
}

/** El origen es un `ByteArrayDataSource`: la URI solo cumple el contrato de `MediaItem`. */
private val MEMORY_URI: Uri = Uri.parse("memory://video")

private const val PLAYER_RATIO = 3f / 4f
