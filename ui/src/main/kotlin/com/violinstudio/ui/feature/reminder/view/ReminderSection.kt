package com.violinstudio.ui.feature.reminder.view

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.permission.rememberNotificationPermissionRequest
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.reminder.viewmodel.ReminderIntent
import com.violinstudio.ui.feature.reminder.viewmodel.ReminderState
import com.violinstudio.ui.feature.reminder.viewmodel.ReminderViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val REMINDER_SWITCH_TAG = "reminder_switch"
const val REMINDER_TIME_TAG = "reminder_time"
const val REMINDER_TIME_OK_TAG = "reminder_time_ok"

@Composable
fun ReminderRoute(enabled: Boolean, viewModel: ReminderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val request = rememberNotificationPermissionRequest()
    // Se vuelve a mirar al volver de los ajustes del sistema y tras responder al permiso (cambia [request]).
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context) }
    LaunchedEffect(request) { allowed = notificationsAllowed(context) }
    ReminderSection(state, enabled, allowed, request, viewModel::onIntent)
}

private fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Interruptor y hora del recordatorio diario. Al encenderlo en Android 13+ sin permiso de avisos se pide en ese
 * momento ([requestPermission], nunca al arrancar); si se rechaza o los avisos estan apagados ([notificationsAllowed]
 * false), el ajuste queda encendido y se avisa de que no llegara hasta activarlos.
 */
@Composable
fun ReminderSection(
    state: ReminderState,
    enabled: Boolean,
    notificationsAllowed: Boolean,
    requestPermission: (() -> Unit)?,
    onIntent: (ReminderIntent) -> Unit
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val reminder = state.reminder
    val canEdit = enabled && state.loaded
    Text(
        stringResource(R.string.settings_reminder_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).semantics { heading() }
    )
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = reminder.enabled,
            enabled = canEdit,
            role = Role.Switch,
            onValueChange = { on ->
                if (on) requestPermission?.invoke()
                onIntent(ReminderIntent.Toggle(on))
            }
        ).testTag(REMINDER_SWITCH_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.settings_reminder_switch), Modifier.weight(1f).padding(vertical = 8.dp))
        Switch(checked = reminder.enabled, onCheckedChange = null, enabled = canEdit)
    }
    if (reminder.enabled) {
        OutlinedButton(
            onClick = { picking = true },
            enabled = canEdit,
            modifier = Modifier.fillMaxWidth().testTag(REMINDER_TIME_TAG)
        ) { Text(stringResource(R.string.settings_reminder_time, reminder.time.formatted())) }
        if (!notificationsAllowed) AuthMessage(stringResource(R.string.settings_reminder_permission), isError = false)
    }
    if (state.saveFailed) AuthMessage(stringResource(R.string.settings_reminder_error), isError = true)
    if (picking) {
        TimeDialog(
            initial = reminder.time,
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onIntent(ReminderIntent.SetTime(it))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder_time_title)) },
        text = { TimePicker(picker) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(LocalTime.of(picker.hour, picker.minute)) },
                modifier = Modifier.testTag(REMINDER_TIME_OK_TAG)
            ) { Text(stringResource(R.string.settings_reminder_time_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_reminder_time_cancel)) }
        }
    )
}

private fun LocalTime.formatted(): String = format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
