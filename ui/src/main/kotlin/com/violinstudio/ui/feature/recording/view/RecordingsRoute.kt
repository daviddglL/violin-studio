package com.violinstudio.ui.feature.recording.view

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.commons.permission.ActivityMicPermissionChecker
import com.violinstudio.ui.commons.permission.snapshot
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsEffect
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsIntent
import com.violinstudio.ui.feature.recording.viewmodel.RecordingsViewModel

/**
 * Conecta permiso, efectos y ciclo de vida. Solo se graba con la pantalla visible: `ON_STOP` (o salir) descarta la
 * toma en curso; volver a primer plano refresca el acceso (el permiso pudo cambiar fuera).
 */
@Composable
fun RecordingsRoute(
    onBack: () -> Unit,
    onRecordVideo: (() -> Unit)? = null,
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val checker = remember(context) { ActivityMicPermissionChecker(context.findActivity()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(RecordingsIntent.PermissionResult(granted, checker.shouldShowRationale()))
    }
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            RecordingsEffect.RequestMicPermission -> launcher.launch(Manifest.permission.RECORD_AUDIO)

            RecordingsEffect.OpenAppSettings -> context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(RecordingsIntent.Leave) }
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(RecordingsIntent.Leave) } }
    RecordingsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onRecord = {
            val permission = checker.snapshot()
            viewModel.onIntent(RecordingsIntent.Start(permission.granted, permission.rationale))
        },
        onBack = onBack,
        onRecordVideo = onRecordVideo
    )
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("RecordingsRoute needs an Activity context")
}
