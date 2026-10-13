package com.violinstudio.ui.feature.link.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeEffect
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeIntent
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeState
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeViewModel
import com.violinstudio.ui.feature.link.viewmodel.RedeemError

const val REDEEM_TAG = "redeem_code"
const val REDEEM_CODE_FIELD_TAG = "redeem_code_field"
const val REDEEM_NOTICE_TAG = "redeem_notice"
const val REDEEM_CHECKBOX_TAG = "redeem_checkbox"
const val REDEEM_SUBMIT_TAG = "redeem_submit"
const val REDEEM_ERROR_TAG = "redeem_error"
const val REDEEM_BACK_TAG = "redeem_back"

/** El rol lo comprueba la navegación antes de componer esta ruta; aquí no hay lógica de sesión. */
@Composable
fun RedeemCodeRoute(onBack: () -> Unit, viewModel: RedeemCodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            RedeemCodeEffect.Redeemed -> onBack()
        }
    }
    RedeemCodeScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun RedeemCodeScreen(state: RedeemCodeState, onIntent: (RedeemCodeIntent) -> Unit, onBack: () -> Unit) {
    val editable = !state.isLoading && !state.succeeded
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(REDEEM_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(REDEEM_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.link_redeem_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.link_redeem_intro), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = state.code,
                onValueChange = { onIntent(RedeemCodeIntent.CodeChanged(it)) },
                enabled = editable,
                label = { Text(stringResource(R.string.link_redeem_code_label)) },
                singleLine = true,
                isError = state.error != null,
                keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onIntent(RedeemCodeIntent.Submit) }),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth().testTag(REDEEM_CODE_FIELD_TAG)
            )
            NoticeCard()
            AcceptRow(state, editable, onIntent)
            state.error?.let { ErrorText(it) }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onIntent(RedeemCodeIntent.Submit) },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().testTag(REDEEM_SUBMIT_TAG)
            ) {
                val label = if (state.isLoading) R.string.link_redeem_submitting else R.string.link_redeem_submit
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun NoticeCard() {
    Card(Modifier.fillMaxWidth().testTag(REDEEM_NOTICE_TAG)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.link_redeem_notice_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.link_redeem_notice_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AcceptRow(state: RedeemCodeState, editable: Boolean, onIntent: (RedeemCodeIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = state.accepted,
            enabled = editable,
            role = Role.Checkbox,
            onValueChange = { onIntent(RedeemCodeIntent.AcceptedChanged(it)) }
        ).padding(vertical = 8.dp).testTag(REDEEM_CHECKBOX_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = state.accepted, onCheckedChange = null, enabled = editable)
        Text(stringResource(R.string.link_redeem_notice_checkbox), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ErrorText(error: RedeemError) {
    Text(
        text = error.message(),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(REDEEM_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
}

@Composable
private fun RedeemError.message(): String = when (this) {
    RedeemError.InvalidCode -> stringResource(R.string.link_error_invalid_code)
    is RedeemError.RateLimited ->
        if (minutes != null) {
            stringResource(R.string.link_error_rate_limited, minutes)
        } else {
            stringResource(R.string.link_error_rate_limited_unknown)
        }
    RedeemError.TeacherFull -> stringResource(R.string.link_error_teacher_full)
    RedeemError.AlreadyLinked -> stringResource(R.string.link_error_already_linked)
    RedeemError.NoticeOutdated -> stringResource(R.string.link_error_notice_outdated)
    RedeemError.NotEligible -> stringResource(R.string.link_error_not_eligible)
    RedeemError.Offline -> stringResource(R.string.link_error_offline)
    RedeemError.TemporarilyUnavailable -> stringResource(R.string.link_error_temporarily_unavailable)
    RedeemError.Unknown -> stringResource(R.string.link_error_unknown)
}
