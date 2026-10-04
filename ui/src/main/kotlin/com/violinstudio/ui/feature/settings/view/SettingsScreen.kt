package com.violinstudio.ui.feature.settings.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.onboarding.view.labelRes
import com.violinstudio.ui.feature.settings.viewmodel.RevokeError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsError
import com.violinstudio.ui.feature.settings.viewmodel.SettingsIntent
import com.violinstudio.ui.feature.settings.viewmodel.SettingsState
import com.violinstudio.ui.feature.settings.viewmodel.SettingsViewModel

const val SETTINGS_TAG = "settings"
const val SETTINGS_NAME_TAG = "settings_name"
const val SETTINGS_LOCALE_TAG = "settings_locale"
const val SETTINGS_SAVE_TAG = "settings_save"
const val SETTINGS_REVOKE_TAG = "settings_revoke"
const val SETTINGS_REVOKE_CONFIRM_TAG = "settings_revoke_confirm"
const val SETTINGS_REVOKE_CANCEL_TAG = "settings_revoke_cancel"
const val SETTINGS_RETRY_TAG = "settings_retry"
const val SETTINGS_BACK_TAG = "settings_back"

fun settingsInstrumentTag(wire: String) = "settings_instrument_$wire"

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel::onIntent, onBack)
}

/** El borrado de cuenta es el flujo compartido (D1): con el flujo abierto o terminado el resto queda bloqueado. */
@Composable
fun SettingsScreen(state: SettingsState, onIntent: (SettingsIntent) -> Unit, onBack: () -> Unit) {
    val delete = LocalDeleteAccount.current
    val shown = state.copy(deleteActive = delete.active)
    AuthScaffold(SETTINGS_TAG, stringResource(R.string.settings_title)) {
        if (!shown.loaded) {
            Text(stringResource(R.string.settings_loading), Modifier.polite())
        } else {
            ProfileForm(shown, onIntent)
            PrivacySection(shown, onIntent)
            delete.entry(!shown.busy)
        }
        TextButton(
            onClick = onBack,
            enabled = !shown.revoked && !shown.deleteActive,
            modifier = Modifier.testTag(SETTINGS_BACK_TAG)
        ) { Text(stringResource(R.string.settings_back)) }
    }
}

@Composable
private fun ProfileForm(state: SettingsState, onIntent: (SettingsIntent) -> Unit) {
    val nameError = ProfileField.DISPLAY_NAME in state.fieldErrors
    OutlinedTextField(
        value = state.fields.displayName,
        onValueChange = { onIntent(SettingsIntent.DisplayNameChanged(it)) },
        enabled = !state.busy,
        label = { Text(stringResource(R.string.settings_name_label)) },
        isError = nameError,
        supportingText = fieldError(nameError, R.string.settings_field_name),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_NAME_TAG)
    )
    InstrumentPicker(state, onIntent)
    val localeError = ProfileField.LOCALE in state.fieldErrors
    OutlinedTextField(
        value = state.fields.locale,
        onValueChange = { onIntent(SettingsIntent.LocaleChanged(it)) },
        enabled = !state.busy,
        label = { Text(stringResource(R.string.settings_locale_label)) },
        isError = localeError,
        supportingText = fieldError(localeError, R.string.settings_field_locale),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(SETTINGS_LOCALE_TAG)
    )
    state.error?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
    if (state.saved) AuthMessage(stringResource(R.string.settings_saved), isError = false)
    Button(
        onClick = { onIntent(SettingsIntent.Save) },
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_SAVE_TAG)
    ) { Text(stringResource(if (state.isSaving) R.string.settings_saving else R.string.settings_save)) }
}

private fun fieldError(show: Boolean, res: Int): (@Composable () -> Unit)? =
    if (show) ({ Text(stringResource(res), Modifier.polite()) }) else null

@Composable
private fun InstrumentPicker(state: SettingsState, onIntent: (SettingsIntent) -> Unit) {
    Text(
        stringResource(R.string.onboarding_instrument_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for (instrument in Instrument.entries) {
            val selected = state.fields.instrument == instrument
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = selected,
                    enabled = !state.busy,
                    onClick = { onIntent(SettingsIntent.InstrumentSelected(instrument)) },
                    role = Role.RadioButton
                ).testTag(settingsInstrumentTag(instrument.wire)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null, enabled = !state.busy)
                Text(stringResource(instrument.labelRes()), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
            }
        }
    }
}

/** Retirar el consentimiento pide confirmacion explicita; tras retirarlo solo queda esperar a la sesion. */
@Composable
private fun PrivacySection(state: SettingsState, onIntent: (SettingsIntent) -> Unit) {
    Text(
        stringResource(R.string.settings_privacy_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { heading() }
    )
    state.revokeError?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
    when {
        state.revoked -> AuthMessage(stringResource(R.string.settings_revoked), isError = false)
        state.confirmingRevoke -> ConfirmRevokePanel(onIntent)
        state.revokeStalled -> {
            AuthMessage(stringResource(R.string.settings_revoke_stalled), isError = true)
            OutlinedButton(
                onClick = { onIntent(SettingsIntent.RetryRefresh) },
                modifier = Modifier.fillMaxWidth().testTag(SETTINGS_RETRY_TAG)
            ) { Text(stringResource(R.string.settings_revoke_retry)) }
        }
        state.revokeError != RevokeError.UNAVAILABLE -> OutlinedButton(
            onClick = { onIntent(SettingsIntent.RevokeConsent) },
            enabled = state.canRevoke,
            modifier = Modifier.fillMaxWidth().testTag(SETTINGS_REVOKE_TAG)
        ) { Text(stringResource(if (state.isRevoking) R.string.settings_revoking else R.string.settings_revoke)) }
    }
}

/** El foco pasa al boton de confirmar al abrirse el panel, para que TalkBack lea la pregunta y la accion. */
@Composable
private fun ConfirmRevokePanel(onIntent: (SettingsIntent) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AuthMessage(stringResource(R.string.settings_revoke_confirm), isError = false)
    Button(
        onClick = { onIntent(SettingsIntent.ConfirmRevoke) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SETTINGS_REVOKE_CONFIRM_TAG)
    ) { Text(stringResource(R.string.settings_revoke_confirm_yes)) }
    OutlinedButton(
        onClick = { onIntent(SettingsIntent.CancelRevoke) },
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_REVOKE_CANCEL_TAG)
    ) { Text(stringResource(R.string.settings_revoke_cancel)) }
}

private fun Modifier.polite() = semantics { liveRegion = LiveRegionMode.Polite }

private fun SettingsError.textRes() = when (this) {
    SettingsError.NETWORK -> R.string.settings_error_network
    SettingsError.NOT_ALLOWED -> R.string.settings_error_not_allowed
    SettingsError.UNAVAILABLE -> R.string.settings_error_unavailable
    SettingsError.UNKNOWN -> R.string.settings_error_unknown
}

private fun RevokeError.textRes() = when (this) {
    RevokeError.NETWORK -> R.string.settings_revoke_network
    RevokeError.UNAVAILABLE -> R.string.settings_revoke_unavailable
    RevokeError.UNKNOWN -> R.string.settings_revoke_unknown
}
