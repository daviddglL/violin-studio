package com.violinstudio.ui.feature.intonation.view

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
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationEffect
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationIntent
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationViewModel

/** Permiso, efectos y ciclo de vida: `ON_STOP` pausa (suelta el micro) y no se reanuda solo. */
@Composable
fun IntonationRoute(onBack: () -> Unit, viewModel: IntonationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val checker = remember(context) { ActivityMicPermissionChecker(context.findActivity()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(IntonationIntent.PermissionResult(granted, checker.shouldShowRationale()))
    }
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            IntonationEffect.RequestMicPermission -> launcher.launch(Manifest.permission.RECORD_AUDIO)
            IntonationEffect.OpenAppSettings -> context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(IntonationIntent.Pause) }
    DisposableEffect(viewModel) { onDispose { viewModel.onIntent(IntonationIntent.Pause) } }
    IntonationScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onStart = { viewModel.onIntent(IntonationIntent.Start(checker.isGranted())) },
        onBack = onBack
    )
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("IntonationRoute needs an Activity context")
}
