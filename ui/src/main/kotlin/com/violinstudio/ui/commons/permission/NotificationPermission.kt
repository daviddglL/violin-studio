package com.violinstudio.ui.commons.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Permiso de avisos (`POST_NOTIFICATIONS`, Android 13+). Devuelve la accion que lo pide, o `null` si no hace falta
 * ofrecerla: Android anterior a 13 (no hay permiso), ya concedido, o ya preguntado en esta sesion (si lo rechazo, no se
 * insiste; se puede activar desde los ajustes del sistema). Nunca se pide al arrancar: solo al pulsar el boton.
 */
@Composable
fun rememberNotificationPermissionRequest(): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    if (granted || asked) return null
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

private fun notificationsGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED
