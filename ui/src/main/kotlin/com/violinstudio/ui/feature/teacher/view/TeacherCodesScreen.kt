package com.violinstudio.ui.feature.teacher.view

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.teacher.viewmodel.CodesError
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesEffect
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesIntent
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesState
import com.violinstudio.ui.feature.teacher.viewmodel.TeacherCodesViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val CODES_TAG = "teacher_codes"
const val CODES_LOADING_TAG = "codes_loading"
const val CODES_COUNT_TAG = "codes_count"
const val CODES_CREATE_TAG = "codes_create"
const val CODES_ERROR_TAG = "codes_error"
const val CODES_EMPTY_TAG = "codes_empty"
const val CODES_LOAD_FAILED_TAG = "codes_load_failed"
const val CODES_RETRY_TAG = "codes_retry"
const val CODES_BACK_TAG = "codes_back"
const val CODE_CREATED_TAG = "code_created"
const val CODE_CREATED_VALUE_TAG = "code_created_value"
const val CODE_COPY_TAG = "code_copy"
const val CODE_COPIED_TAG = "code_copied"
const val CODE_SHARE_TAG = "code_share"
const val CODE_DONE_TAG = "code_done"
const val CODE_ROW_TAG = "code_row"
const val CODE_REVOKE_TAG = "code_revoke"
const val CODE_REVOKE_DIALOG_TAG = "code_revoke_dialog"
const val CODE_REVOKE_CONFIRM_TAG = "code_revoke_confirm"
const val CODE_REVOKE_CANCEL_TAG = "code_revoke_cancel"

/** El rol lo comprueba la navegación antes de componer esta ruta; aquí no hay lógica de sesión. */
@Composable
fun TeacherCodesRoute(onBack: () -> Unit, viewModel: TeacherCodesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is TeacherCodesEffect.CopyToClipboard -> context.copySensitive(effect.code)
            is TeacherCodesEffect.ShareCode -> context.shareText(effect.code)
            is TeacherCodesEffect.ClearClipboard -> context.clearClipIfEquals(effect.code)
        }
    }
    // El código en claro no sobrevive a salir de la pantalla (también al abrir el selector de compartir).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(TeacherCodesIntent.DismissCreated) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(TeacherCodesIntent.Refresh) }
    TeacherCodesScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun TeacherCodesScreen(state: TeacherCodesState, onIntent: (TeacherCodesIntent) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(CODES_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(CODES_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.teacher_codes_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            when {
                state.isLoading -> {
                    val loading = stringResource(R.string.link_loading)
                    CircularProgressIndicator(
                        Modifier.testTag(CODES_LOADING_TAG).semantics {
                            contentDescription = loading
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
                state.loadFailed -> LoadFailed(onIntent)
                else -> Content(state, onIntent)
            }
        }
    }
    state.revokeTarget?.let { RevokeDialog(it, state.isRevoking, onIntent) }
}

@Composable
private fun Content(state: TeacherCodesState, onIntent: (TeacherCodesIntent) -> Unit) {
    Text(stringResource(R.string.teacher_codes_intro), style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = { onIntent(TeacherCodesIntent.Create) },
        enabled = state.canCreate,
        modifier = Modifier.fillMaxWidth().testTag(CODES_CREATE_TAG)
    ) {
        Text(stringResource(if (state.isCreating) R.string.teacher_codes_creating else R.string.teacher_codes_create))
    }
    state.error?.let {
        Text(
            it.message(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CODES_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
        )
    }
    state.created?.let { Created(it, state.copied, onIntent) }
    Text(
        stringResource(R.string.teacher_codes_count, state.codes.size, TeacherCodesState.MAX_ACTIVE_CODES),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(CODES_COUNT_TAG)
    )
    if (state.codes.isEmpty()) Text(stringResource(R.string.teacher_codes_empty), Modifier.testTag(CODES_EMPTY_TAG))
    state.codes.forEach { code ->
        val revokeDesc = stringResource(R.string.teacher_code_revoke_desc, code.hint)
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().testTag(CODE_ROW_TAG),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.teacher_code_hint, code.hint))
                Text(
                    stringResource(R.string.teacher_code_expires, code.expiresAt.formattedDateTime()),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(
                onClick = { onIntent(TeacherCodesIntent.RequestRevoke(code)) },
                enabled = !state.isRevoking,
                modifier = Modifier.testTag(CODE_REVOKE_TAG).semantics { contentDescription = revokeDesc }
            ) { Text(stringResource(R.string.teacher_code_revoke)) }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Created(code: TeacherCode, copied: Boolean, onIntent: (TeacherCodesIntent) -> Unit) {
    val spelled = code.code.orEmpty().toList().joinToString(" ")
    Card(Modifier.fillMaxWidth().testTag(CODE_CREATED_TAG)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.teacher_code_created_title), style = MaterialTheme.typography.titleMedium)
            Text(
                code.code.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag(CODE_CREATED_VALUE_TAG).semantics { contentDescription = spelled }
            )
            Text(stringResource(R.string.teacher_code_expires, code.expiresAt.formattedDateTime()))
            Text(stringResource(R.string.teacher_code_created_warning), style = MaterialTheme.typography.bodyMedium)
            if (copied) {
                Text(
                    stringResource(R.string.teacher_code_copied),
                    modifier = Modifier.testTag(CODE_COPIED_TAG).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onIntent(TeacherCodesIntent.Copy) },
                    modifier = Modifier.testTag(CODE_COPY_TAG)
                ) { Text(stringResource(R.string.teacher_code_copy)) }
                OutlinedButton(
                    onClick = { onIntent(TeacherCodesIntent.Share) },
                    modifier = Modifier.testTag(CODE_SHARE_TAG)
                ) { Text(stringResource(R.string.teacher_code_share)) }
                TextButton(
                    onClick = { onIntent(TeacherCodesIntent.DismissCreated) },
                    modifier = Modifier.testTag(CODE_DONE_TAG)
                ) { Text(stringResource(R.string.teacher_code_done)) }
            }
        }
    }
}

@Composable
private fun LoadFailed(onIntent: (TeacherCodesIntent) -> Unit) {
    Text(
        stringResource(R.string.teacher_codes_load_failed),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(CODES_LOAD_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
    Button(onClick = { onIntent(TeacherCodesIntent.Retry) }, modifier = Modifier.testTag(CODES_RETRY_TAG)) {
        Text(stringResource(R.string.link_retry))
    }
}

@Composable
private fun RevokeDialog(code: TeacherCode, busy: Boolean, onIntent: (TeacherCodesIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(TeacherCodesIntent.CancelRevoke) },
        modifier = Modifier.testTag(CODE_REVOKE_DIALOG_TAG),
        title = { Text(stringResource(R.string.teacher_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.teacher_revoke_dialog_body, code.hint)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(TeacherCodesIntent.ConfirmRevoke) },
                enabled = !busy,
                modifier = Modifier.testTag(CODE_REVOKE_CONFIRM_TAG)
            ) { Text(stringResource(R.string.teacher_code_revoke)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(TeacherCodesIntent.CancelRevoke) },
                enabled = !busy,
                modifier = Modifier.testTag(CODE_REVOKE_CANCEL_TAG)
            ) { Text(stringResource(R.string.link_unlink_cancel)) }
        }
    )
}

@Composable
private fun CodesError.message(): String = when (this) {
    CodesError.LimitReached -> stringResource(R.string.teacher_codes_error_limit, TeacherCodesState.MAX_ACTIVE_CODES)
    CodesError.Unavailable -> stringResource(R.string.teacher_codes_error_unavailable)
    CodesError.TryAgain -> stringResource(R.string.teacher_codes_error_try_again)
    CodesError.RevokeFailed -> stringResource(R.string.teacher_codes_error_revoke)
}

@Composable
private fun Instant.formattedDateTime(): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        .format(atZone(ZoneId.systemDefault()))
}

/** Marca el clip como sensible (Android 13+ oculta la vista previa); versiones anteriores ignoran el extra. */
private fun Context.copySensitive(code: String) {
    val clip = ClipData.newPlainText("", code)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun Context.shareText(code: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, getString(R.string.teacher_code_share_text, code))
    try {
        startActivity(Intent.createChooser(send, getString(R.string.teacher_code_share_chooser)))
    } catch (_: ActivityNotFoundException) {
        // Sin app que reciba el texto: el código sigue visible para copiarlo.
    }
}

/** Borra el portapapeles solo si aún contiene [code] (no pisa lo que el usuario haya copiado después). Android 9+. */
internal fun Context.clearClipIfEquals(code: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    if (current == code) clipboard.clearPrimaryClip()
}
