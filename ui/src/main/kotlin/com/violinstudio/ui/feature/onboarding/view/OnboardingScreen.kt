package com.violinstudio.ui.feature.onboarding.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.profile.failure.ProfileField
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.auth.view.AuthSubmitButton
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingDeleteError
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingError
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingIntent
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingState
import com.violinstudio.ui.feature.onboarding.viewmodel.OnboardingViewModel

const val ONBOARDING_TAG = "onboarding"
const val ONBOARDING_NAME_TAG = "onboarding_name"
const val ONBOARDING_DAY_TAG = "onboarding_day"
const val ONBOARDING_MONTH_TAG = "onboarding_month"
const val ONBOARDING_YEAR_TAG = "onboarding_year"
const val ONBOARDING_AGE_HINT_TAG = "onboarding_age_hint"
const val ONBOARDING_DELETE_TAG = "onboarding_delete"
const val ONBOARDING_SIGN_OUT_TAG = "onboarding_sign_out"

fun onboardingInstrumentTag(wire: String) = "onboarding_instrument_$wire"

private const val DAY_MONTH_DIGITS = 2
private const val YEAR_DIGITS = 4

@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingScreen(state, viewModel::onIntent)
}

/**
 * La fecha se teclea en tres campos numéricos (día, mes, año). La pantalla no muestra ninguna edad ni veredicto legal
 * decidido por el cliente: solo una nota suave si la pista lo indica.
 */
@Composable
fun OnboardingScreen(state: OnboardingState, onIntent: (OnboardingIntent) -> Unit) {
    AuthScaffold(ONBOARDING_TAG, stringResource(R.string.onboarding_title)) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = { onIntent(OnboardingIntent.DisplayNameChanged(it)) },
            label = { Text(stringResource(R.string.onboarding_name_label)) },
            isError = ProfileField.DISPLAY_NAME in state.fieldErrors,
            supportingText = if (ProfileField.DISPLAY_NAME in state.fieldErrors) {
                { Text(stringResource(R.string.onboarding_field_name), Modifier.polite()) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_NAME_TAG)
        )
        InstrumentPicker(state, onIntent)
        BirthDateFields(state, onIntent)
        if (state.showAgeHint) {
            Text(
                stringResource(R.string.onboarding_age_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp).testTag(ONBOARDING_AGE_HINT_TAG)
            )
        }
        if (ProfileField.LOCALE in state.fieldErrors) {
            AuthMessage(stringResource(R.string.onboarding_field_locale), isError = true)
        }
        state.error?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        state.deleteError?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        AuthSubmitButton(
            label = stringResource(if (state.isLoading) R.string.onboarding_loading else R.string.onboarding_submit),
            enabled = state.canSubmit,
            onClick = { onIntent(OnboardingIntent.Submit) }
        )
        if (state.error == OnboardingError.UNDERAGE_NOT_ALLOWED) {
            OutlinedButton(
                onClick = { onIntent(OnboardingIntent.DeleteAccount) },
                enabled = !state.isDeleting && !state.isLoading,
                modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_DELETE_TAG)
            ) {
                Text(stringResource(if (state.isDeleting) R.string.onboarding_deleting else R.string.onboarding_delete))
            }
        }
        TextButton(
            onClick = { onIntent(OnboardingIntent.SignOut) },
            modifier = Modifier.testTag(ONBOARDING_SIGN_OUT_TAG)
        ) { Text(stringResource(R.string.session_sign_out)) }
    }
}

private fun Modifier.polite() = semantics { liveRegion = LiveRegionMode.Polite }

@Composable
private fun InstrumentPicker(state: OnboardingState, onIntent: (OnboardingIntent) -> Unit) {
    Text(
        stringResource(R.string.onboarding_instrument_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for (instrument in Instrument.entries) {
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = state.instrument == instrument,
                    onClick = { onIntent(OnboardingIntent.InstrumentSelected(instrument)) },
                    role = Role.RadioButton
                ).testTag(onboardingInstrumentTag(instrument.wire)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = state.instrument == instrument, onClick = null)
                Text(stringResource(instrument.labelRes()), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
            }
        }
    }
    if (ProfileField.INSTRUMENT in state.fieldErrors) {
        Text(
            stringResource(R.string.onboarding_field_instrument),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.polite()
        )
    }
}

@Composable
private fun BirthDateFields(state: OnboardingState, onIntent: (OnboardingIntent) -> Unit) {
    val hasError = ProfileField.BIRTH_DATE in state.fieldErrors
    val errorText = if (hasError) {
        stringResource(
            if (state.birthDateInFuture) R.string.onboarding_field_birth_future else R.string.onboarding_field_birth
        )
    } else {
        null
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_birth_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().semantics { heading() }
    )
    // Cada campo dice que parte de la fecha es; el error va como texto de apoyo del ultimo (el año).
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DatePart(
            DatePartSpec(
                state.day,
                R.string.onboarding_day_label,
                R.string.onboarding_birth_day_desc,
                DAY_MONTH_DIGITS
            ),
            hasError,
            ONBOARDING_DAY_TAG,
            1f,
            null
        ) { onIntent(OnboardingIntent.BirthDateChanged(it, state.month, state.year)) }
        DatePart(
            DatePartSpec(
                state.month,
                R.string.onboarding_month_label,
                R.string.onboarding_birth_month_desc,
                DAY_MONTH_DIGITS
            ),
            hasError,
            ONBOARDING_MONTH_TAG,
            1f,
            null
        ) { onIntent(OnboardingIntent.BirthDateChanged(state.day, it, state.year)) }
        DatePart(
            DatePartSpec(state.year, R.string.onboarding_year_label, R.string.onboarding_birth_year_desc, YEAR_DIGITS),
            hasError,
            ONBOARDING_YEAR_TAG,
            1.4f,
            errorText
        ) { onIntent(OnboardingIntent.BirthDateChanged(state.day, state.month, it)) }
    }
}

private class DatePartSpec(val value: String, val labelRes: Int, val descriptionRes: Int, val maxDigits: Int)

@Composable
private fun RowScope.DatePart(
    spec: DatePartSpec,
    isError: Boolean,
    tag: String,
    weight: Float,
    supporting: String?,
    onChange: (String) -> Unit
) {
    val description = stringResource(spec.descriptionRes)
    OutlinedTextField(
        value = spec.value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(spec.maxDigits)) },
        label = { Text(stringResource(spec.labelRes)) },
        isError = isError,
        supportingText = supporting?.let { { Text(it, Modifier.polite()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.weight(weight).semantics { contentDescription = description }.testTag(tag)
    )
}

internal fun Instrument.labelRes() = when (this) {
    Instrument.VIOLIN -> R.string.onboarding_instrument_violin
    Instrument.VIOLA -> R.string.onboarding_instrument_viola
    Instrument.CELLO -> R.string.onboarding_instrument_cello
    Instrument.DOUBLE_BASS -> R.string.onboarding_instrument_double_bass
    Instrument.OTHER -> R.string.onboarding_instrument_other
}

private fun OnboardingError.textRes() = when (this) {
    OnboardingError.UNDERAGE_NOT_ALLOWED -> R.string.onboarding_error_underage
    OnboardingError.NETWORK -> R.string.onboarding_error_network
    OnboardingError.UNKNOWN -> R.string.onboarding_error_unknown
}

private fun OnboardingDeleteError.textRes() = when (this) {
    OnboardingDeleteError.REAUTH_REQUIRED -> R.string.onboarding_delete_reauth
    OnboardingDeleteError.FAILED -> R.string.onboarding_delete_failed
    OnboardingDeleteError.NETWORK -> R.string.onboarding_delete_network
}
