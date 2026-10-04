package com.violinstudio.ui.feature.consent.view

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.auth.view.AuthSubmitButton
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import com.violinstudio.ui.feature.consent.viewmodel.ConsentEffect
import com.violinstudio.ui.feature.consent.viewmodel.ConsentError
import com.violinstudio.ui.feature.consent.viewmodel.ConsentIntent
import com.violinstudio.ui.feature.consent.viewmodel.ConsentState
import com.violinstudio.ui.feature.consent.viewmodel.ConsentViewModel
import com.violinstudio.ui.feature.session.view.PlaceholderScreen

const val CONSENT_TAG = "consent"
const val CONSENT_INTRO_TAG = "consent_intro"
const val CONSENT_CHECKBOX_TAG = "consent_checkbox"
const val CONSENT_POLICY_LINK_TAG = "consent_policy_link"
const val CONSENT_DELETE_TAG = "consent_delete"
const val CONSENT_SIGN_OUT_TAG = "consent_sign_out"

/**
 * Destino `consent` del host de sesión: el adulto ve esta pantalla; el menor sigue con un marcador hasta el slice 6b
 * (flujo de tutor). [adultViewModel] se inyecta para poder probar el destino sin Hilt.
 */
@Composable
fun ConsentSlot(
    pending: SessionState.ConsentPending,
    adultViewModel: @Composable () -> ConsentViewModel = { hiltViewModel() }
) {
    if (pending.isMinor) PlaceholderScreen(CONSENT_TAG) else ConsentRoute(pending, adultViewModel())
}

/**
 * La sesión manda: cada `ConsentPending` (al abrir y en cada cambio en caliente de política o de motivo) se entrega al
 * ViewModel. El enlace a la política se abre aquí, con `ACTION_VIEW`, porque necesita el contexto.
 */
@Composable
fun ConsentRoute(pending: SessionState.ConsentPending, viewModel: ConsentViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val onIntent by rememberUpdatedState(viewModel::onIntent)
    LaunchedEffect(pending.config, pending.reason) {
        onIntent(ConsentIntent.SessionUpdated(pending.config, pending.reason))
    }
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is ConsentEffect.OpenPolicy -> try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                onIntent(ConsentIntent.PolicyLinkFailed)
            } catch (_: SecurityException) {
                onIntent(ConsentIntent.PolicyLinkFailed)
            }
        }
    }
    ConsentScreen(state, viewModel::onIntent)
}

@Composable
fun ConsentScreen(state: ConsentState, onIntent: (ConsentIntent) -> Unit) {
    val version = state.policyVersion
    AuthScaffold(CONSENT_TAG, stringResource(state.reason.titleRes())) {
        Text(
            state.reason.intro(version),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag(CONSENT_INTRO_TAG)
        )
        if (version != null) {
            TextButton(
                onClick = { onIntent(ConsentIntent.OpenPolicy) },
                modifier = Modifier.testTag(CONSENT_POLICY_LINK_TAG)
            ) { Text(stringResource(R.string.consent_read_policy, version)) }
            val editable = !state.isLoading && !state.succeeded && !state.isDeleting
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).toggleable(
                    value = state.checked,
                    enabled = editable,
                    role = Role.Checkbox,
                    onValueChange = { onIntent(ConsentIntent.CheckedChanged(it)) }
                ).testTag(CONSENT_CHECKBOX_TAG),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = state.checked, onCheckedChange = null, enabled = editable)
                Text(
                    stringResource(R.string.consent_checkbox, version),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        state.error?.let { AuthMessage(it.message(version), isError = true) }
        if (state.policyLinkFailed) AuthMessage(stringResource(R.string.consent_link_failed), isError = true)
        state.deleteError?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        AuthSubmitButton(
            label = stringResource(if (state.isLoading) R.string.consent_accepting else R.string.consent_accept),
            enabled = state.canAccept,
            onClick = { onIntent(ConsentIntent.Accept) }
        )
        // Quien no acepta solo puede borrar la cuenta o cerrar sesión (C5): no hay otra salida de esta pantalla.
        OutlinedButton(
            onClick = { onIntent(ConsentIntent.DeleteAccount) },
            enabled = !state.isDeleting && !state.isLoading,
            modifier = Modifier.fillMaxWidth().testTag(CONSENT_DELETE_TAG)
        ) {
            Text(stringResource(if (state.isDeleting) R.string.consent_deleting else R.string.consent_delete))
        }
        TextButton(
            onClick = { onIntent(ConsentIntent.SignOut) },
            modifier = Modifier.testTag(CONSENT_SIGN_OUT_TAG)
        ) { Text(stringResource(R.string.session_sign_out)) }
    }
}

private fun ConsentReason.titleRes() = when (this) {
    ConsentReason.FIRST -> R.string.consent_title_first
    ConsentReason.POLICY_UPDATED -> R.string.consent_title_updated
    ConsentReason.REVOKED -> R.string.consent_title_revoked
}

@Composable
private fun ConsentReason.intro(version: Int?): String = when (this) {
    ConsentReason.FIRST -> stringResource(R.string.consent_intro_first)
    ConsentReason.POLICY_UPDATED -> versioned(R.string.consent_intro_updated, version)
    ConsentReason.REVOKED -> versioned(R.string.consent_intro_revoked, version)
}

// Sin versión conocida todavía (la sesión aún no llegó): el texto va sin número en vez de inventar uno.
@Composable
private fun versioned(id: Int, version: Int?): String =
    if (version != null) stringResource(id, version) else stringResource(R.string.consent_intro_first)

@Composable
private fun ConsentError.message(version: Int?): String = when (this) {
    ConsentError.NETWORK -> stringResource(R.string.consent_error_network)
    ConsentError.POLICY_CHANGED ->
        if (version != null) {
            stringResource(R.string.consent_error_policy_changed, version)
        } else {
            stringResource(R.string.consent_error_policy_unavailable)
        }
    ConsentError.POLICY_UNAVAILABLE -> stringResource(R.string.consent_error_policy_unavailable)
    ConsentError.GUARDIAN_REQUIRED -> stringResource(R.string.consent_error_guardian)
    ConsentError.UNKNOWN -> stringResource(R.string.consent_error_unknown)
}

private fun ConsentDeleteError.textRes() = when (this) {
    ConsentDeleteError.REAUTH_REQUIRED -> R.string.consent_delete_reauth
    ConsentDeleteError.FAILED -> R.string.consent_delete_failed
    ConsentDeleteError.NETWORK -> R.string.consent_delete_network
}
