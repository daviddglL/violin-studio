package com.violinstudio.ui.feature.assignment.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.assignment.model.AssignmentRules
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditEffect
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditError
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditIntent
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditState
import com.violinstudio.ui.feature.assignment.viewmodel.AssignmentEditViewModel
import com.violinstudio.ui.feature.assignment.viewmodel.DescriptionError
import com.violinstudio.ui.feature.assignment.viewmodel.TitleError
import java.time.ZoneId

const val ASSIGNMENT_EDIT_TAG = "assignment_edit"
const val ASSIGNMENT_EDIT_BACK_TAG = "assignment_edit_back"
const val ASSIGNMENT_EDIT_TITLE_TAG = "assignment_edit_title"
const val ASSIGNMENT_EDIT_TITLE_ERROR_TAG = "assignment_edit_title_error"
const val ASSIGNMENT_EDIT_TITLE_COUNT_TAG = "assignment_edit_title_count"
const val ASSIGNMENT_EDIT_DESCRIPTION_TAG = "assignment_edit_description"
const val ASSIGNMENT_EDIT_DESCRIPTION_ERROR_TAG = "assignment_edit_description_error"
const val ASSIGNMENT_EDIT_DESCRIPTION_COUNT_TAG = "assignment_edit_description_count"
const val ASSIGNMENT_EDIT_DUE_TAG = "assignment_edit_due"
const val ASSIGNMENT_EDIT_DUE_CLEAR_TAG = "assignment_edit_due_clear"
const val ASSIGNMENT_EDIT_DATE_DIALOG_TAG = "assignment_edit_date_dialog"
const val ASSIGNMENT_EDIT_SAVE_TAG = "assignment_edit_save"
const val ASSIGNMENT_EDIT_LOADING_TAG = "assignment_edit_loading"
const val ASSIGNMENT_EDIT_UNAVAILABLE_TAG = "assignment_edit_unavailable"
const val ASSIGNMENT_EDIT_ERROR_TAG = "assignment_edit_error"
const val ASSIGNMENT_EDIT_ERROR_DISMISS_TAG = "assignment_edit_error_dismiss"

/**
 * El rol lo comprueba la navegación. Tarea inexistente, alumno ausente o vínculo caído (`notFound`): la pantalla
 * muestra "no disponible" y vuelve atrás; tras guardar también vuelve (la escritura es offline-first).
 */
@Composable
fun AssignmentEditRoute(onBack: () -> Unit, viewModel: AssignmentEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            AssignmentEditEffect.Saved -> onBack()
        }
    }
    LaunchedEffect(state.notFound) { if (state.notFound) onBack() }
    AssignmentEditScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun AssignmentEditScreen(
    state: AssignmentEditState,
    onIntent: (AssignmentEditIntent) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(ASSIGNMENT_EDIT_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(ASSIGNMENT_EDIT_BACK_TAG)) {
                Text(stringResource(R.string.assignment_cancel))
            }
            Text(
                stringResource(
                    if (state.isEditing) R.string.assignment_edit_title_edit else R.string.assignment_edit_title_new
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            if (state.notFound) {
                Text(
                    stringResource(R.string.assignment_edit_unavailable),
                    modifier = Modifier.testTag(ASSIGNMENT_EDIT_UNAVAILABLE_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else {
                Form(state, onIntent, zone)
            }
        }
    }
}

@Composable
private fun Form(state: AssignmentEditState, onIntent: (AssignmentEditIntent) -> Unit, zone: ZoneId) {
    // Sin editar mientras se carga la tarea o se guarda.
    val enabled = !state.loading && !state.busy
    OutlinedTextField(
        value = state.title,
        onValueChange = { onIntent(AssignmentEditIntent.TitleChanged(it)) },
        label = { Text(stringResource(R.string.assignment_field_title)) },
        singleLine = true,
        enabled = enabled,
        isError = state.titleError != null,
        supportingText = {
            Field(state.titleError?.let { titleErrorText(it) }, ASSIGNMENT_EDIT_TITLE_ERROR_TAG) {
                Counter(state.titleCount, AssignmentRules.TITLE_MAX, ASSIGNMENT_EDIT_TITLE_COUNT_TAG)
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth().testTag(ASSIGNMENT_EDIT_TITLE_TAG)
    )
    OutlinedTextField(
        value = state.description,
        onValueChange = { onIntent(AssignmentEditIntent.DescriptionChanged(it)) },
        label = { Text(stringResource(R.string.assignment_field_description)) },
        minLines = 3,
        maxLines = 8,
        enabled = enabled,
        isError = state.descriptionError != null,
        supportingText = {
            Field(
                state.descriptionError?.let { descriptionErrorText(it) },
                ASSIGNMENT_EDIT_DESCRIPTION_ERROR_TAG
            ) {
                Counter(state.descriptionCount, AssignmentRules.DESCRIPTION_MAX, ASSIGNMENT_EDIT_DESCRIPTION_COUNT_TAG)
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag(ASSIGNMENT_EDIT_DESCRIPTION_TAG)
    )
    DueDate(state, enabled, onIntent, zone)
    state.error?.let {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (it == AssignmentEditError.PERMISSION_DENIED) {
                        R.string.assignment_error_permission
                    } else {
                        R.string.assignment_error_unknown
                    }
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).testTag(ASSIGNMENT_EDIT_ERROR_TAG)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            )
            TextButton(
                onClick = { onIntent(AssignmentEditIntent.DismissError) },
                modifier = Modifier.testTag(ASSIGNMENT_EDIT_ERROR_DISMISS_TAG)
            ) { Text(stringResource(R.string.assignment_dismiss)) }
        }
    }
    Button(
        onClick = { onIntent(AssignmentEditIntent.Save) },
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth().testTag(ASSIGNMENT_EDIT_SAVE_TAG)
    ) { Text(stringResource(R.string.assignment_save)) }
}

/** Error inline (live region) y a su lado el contador. */
@Composable
private fun Field(error: String?, errorTag: String, counter: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (error != null) {
            Text(
                error,
                modifier = Modifier.weight(1f).testTag(errorTag).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        counter()
    }
}

@Composable
private fun Counter(count: Int, max: Int, tag: String) {
    Text(stringResource(R.string.assignment_counter, count, max), modifier = Modifier.testTag(tag))
}

@Composable
private fun titleErrorText(error: TitleError): String = when (error) {
    TitleError.EMPTY -> stringResource(R.string.assignment_error_title_empty)
    TitleError.TOO_LONG -> stringResource(R.string.assignment_error_title_too_long, AssignmentRules.TITLE_MAX)
    TitleError.INVALID -> stringResource(R.string.assignment_error_title_invalid)
}

@Composable
private fun descriptionErrorText(error: DescriptionError): String = when (error) {
    DescriptionError.TOO_LONG ->
        stringResource(R.string.assignment_error_description_too_long, AssignmentRules.DESCRIPTION_MAX)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDate(
    state: AssignmentEditState,
    enabled: Boolean,
    onIntent: (AssignmentEditIntent) -> Unit,
    zone: ZoneId
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val locale = rememberLocale()
    val due = state.dueAt
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { picking = true },
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(ASSIGNMENT_EDIT_DUE_TAG)
        ) {
            Text(
                if (due == null) {
                    stringResource(R.string.assignment_due_pick)
                } else {
                    stringResource(R.string.assignment_due_chosen, formatDueDate(due, locale, zone))
                }
            )
        }
        if (due != null) {
            TextButton(
                onClick = { onIntent(AssignmentEditIntent.DueAtChanged(null)) },
                enabled = enabled,
                modifier = Modifier.testTag(ASSIGNMENT_EDIT_DUE_CLEAR_TAG)
            ) { Text(stringResource(R.string.assignment_due_clear)) }
        }
    }
    if (picking) {
        val initialMillis = due?.let { localDateOf(it, zone).toPickerMillis() }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            modifier = Modifier.testTag(ASSIGNMENT_EDIT_DATE_DIALOG_TAG),
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            val dueAt = dueAtOfLocalDate(pickerMillisToLocalDate(it), zone)
                            onIntent(AssignmentEditIntent.DueAtChanged(dueAt))
                        }
                        picking = false
                    }
                ) { Text(stringResource(R.string.assignment_date_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.assignment_cancel)) }
            }
        ) { DatePicker(pickerState) }
    }
}
