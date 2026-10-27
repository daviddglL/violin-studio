package com.violinstudio.ui.feature.video.view

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureEffect
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureIntent
import com.violinstudio.ui.feature.video.viewmodel.VideoCaptureViewModel

/**
 * Conecta permisos, camara, efectos y ciclo de vida. Solo se graba con la pantalla visible: `ON_STOP` (o salir)
 * descarta la toma en curso. La camara solo se abre con los permisos concedidos.
 */
@Composable
fun VideoCaptureRoute(onBack: () -> Unit, viewModel: VideoCaptureViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val owner = LocalLifecycleOwner.current
    val camera = remember(context) { CameraVideoRecorder(context.applicationContext) }
    var permitted by remember(activity) { mutableStateOf(activity.hasVideoPermissions()) }
    // Se vuelve a leer el permiso real: cerrar el dialogo devuelve un resultado vacio.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = activity.hasVideoPermissions()
        viewModel.onIntent(VideoCaptureIntent.PermissionResult(permitted, activity.shouldShowVideoRationale()))
    }
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            VideoCaptureEffect.RequestPermissions ->
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

            VideoCaptureEffect.OpenAppSettings -> context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )

            is VideoCaptureEffect.BeginCapture -> camera.start(effect.rawPath, effect.maxDurationMs) { path, ok ->
                viewModel.onIntent(VideoCaptureIntent.CaptureFinalized(path, ok))
            }

            VideoCaptureEffect.EndCapture -> camera.stop()

            VideoCaptureEffect.CancelCapture -> camera.cancel()

            VideoCaptureEffect.Saved -> onBack()
        }
    }
    // El permiso pudo cambiar fuera (Ajustes) mientras la pantalla no estaba visible.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = activity.hasVideoPermissions() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(VideoCaptureIntent.Leave) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onIntent(VideoCaptureIntent.Leave)
            camera.release()
        }
    }
    VideoCaptureScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onRecord = {
            viewModel.onIntent(
                VideoCaptureIntent.Start(activity.hasVideoPermissions(), activity.shouldShowVideoRationale())
            )
        },
        onBack = onBack,
        preview = { modifier ->
            // Sin permiso de camara no se abre la camara: solo queda el hueco de la vista previa.
            if (permitted) {
                AndroidView(
                    factory = { PreviewView(it).also { view -> camera.bind(owner, view.surfaceProvider) } },
                    modifier = modifier
                )
            }
        }
    )
}

private fun Activity.hasVideoPermissions(): Boolean = CameraVideoRecorder.PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private fun Activity.shouldShowVideoRationale(): Boolean =
    CameraVideoRecorder.PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("VideoCaptureRoute needs an Activity context")
}
