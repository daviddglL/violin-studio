package com.violinstudio.ui.feature.tuner.view

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
import com.violinstudio.ui.feature.tuner.viewmodel.TunerEffect
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerViewModel

/**
 * Conecta permiso, efectos y ciclo de vida: `ON_STOP` libera el micro y `ON_START` reanuda solo con permiso. Al
 * salir de la ruta el ViewModel se destruye y su `onCleared` cancela la captura.
 */
@Composable
fun TunerRoute(onBack: () -> Unit, viewModel: TunerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val checker = remember(context) { ActivityMicPermissionChecker(context.findActivity()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(TunerIntent.PermissionResult(granted, checker.shouldShowRationale()))
    }
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            TunerEffect.RequestMicPermission -> launcher.launch(Manifest.permission.RECORD_AUDIO)
            TunerEffect.OpenAppSettings -> context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val permission = checker.snapshot()
        viewModel.onIntent(TunerIntent.Resume(permission.granted, permission.rationale))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(TunerIntent.Stop) }
    TunerScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onStart = {
            val permission = checker.snapshot()
            viewModel.onIntent(TunerIntent.Start(permission.granted, permission.rationale))
        },
        onBack = onBack
    )
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("TunerRoute needs an Activity context")
}
