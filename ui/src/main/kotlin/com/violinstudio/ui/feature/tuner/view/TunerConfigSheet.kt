package com.violinstudio.ui.feature.tuner.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.tuner.viewmodel.ConfigError
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerState

const val TUNER_CONFIG_OPEN_TAG = "tuner_config_open"
const val TUNER_CONFIG_HZ_TAG = "tuner_config_hz"
const val TUNER_CONFIG_CENTS_TAG = "tuner_config_cents"
const val TUNER_CONFIG_APPLY_TAG = "tuner_config_apply"
const val TUNER_CONFIG_CANCEL_TAG = "tuner_config_cancel"
const val TUNER_CONFIG_LABEL_TAG = "tuner_config_label"
const val TUNER_CONFIG_SAVE_TAG = "tuner_config_save"
const val TUNER_CONFIG_DELETE_CONFIRM_TAG = "tuner_config_delete_confirm"

fun tunerPresetSelectTag(id: String) = "tuner_preset_select_$id"

fun tunerPresetEditTag(id: String) = "tuner_preset_edit_$id"

fun tunerPresetDeleteTag(id: String) = "tuner_preset_delete_$id"

/** Hoja modal: cerrarla (gesto o Cancelar) no persiste nada; los valores solo viajan al pulsar Aplicar o Guardar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerConfigSheet(state: TunerState, onIntent: (TunerIntent) -> Unit) {
    if (!state.showConfig) return
    ModalBottomSheet(
        onDismissRequest = { onIntent(TunerIntent.CloseConfig) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) { TunerConfigContent(state.config, state.configError, onIntent) }
}

/**
 * Los campos son texto libre: lo que no sea número se envía como `NaN`/-1 y el caso de uso lo rechaza, así hay una
 * sola validación (la del dominio) y el error vuelve por [ConfigError].
 */
@Composable
fun TunerConfigContent(config: TunerConfig, error: ConfigError?, onIntent: (TunerIntent) -> Unit) {
    var hz by remember(config.referencePitch) { mutableStateOf(formatHz(config.referencePitch.hz)) }
    var cents by remember(config.maxCents) { mutableStateOf(config.maxCents.value.toString()) }
    var label by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<TuningConfiguration?>(null) }
    // Tras guardar o borrar la lista cambia: el formulario de preset vuelve a vacío.
    LaunchedEffect(config.presets) {
        editingId = null
        label = ""
    }
    val hzValue = hz.replace(',', '.').toDoubleOrNull() ?: Double.NaN
    val centsValue = cents.toIntOrNull() ?: -1
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.tuner_config_title), style = MaterialTheme.typography.titleLarge)
        val hzError = error == ConfigError.REFERENCE_PITCH
        NumberField(hz, R.string.tuner_config_reference, hzError, TUNER_CONFIG_HZ_TAG, decimal = true) { hz = it }
        FieldError(hzError, R.string.tuner_config_error_reference)
        val centsError = error == ConfigError.MAX_CENTS
        NumberField(cents, R.string.tuner_config_max_cents, centsError, TUNER_CONFIG_CENTS_TAG, decimal = false) {
            cents = it
        }
        FieldError(centsError, R.string.tuner_config_error_max_cents)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onIntent(TunerIntent.UpdateConfig(hzValue, centsValue)) },
                modifier = Modifier.testTag(TUNER_CONFIG_APPLY_TAG)
            ) { Text(stringResource(R.string.tuner_config_apply)) }
            OutlinedButton(
                onClick = { onIntent(TunerIntent.CloseConfig) },
                modifier = Modifier.testTag(TUNER_CONFIG_CANCEL_TAG)
            ) { Text(stringResource(R.string.tuner_config_cancel)) }
        }
        Text(stringResource(R.string.tuner_config_presets), style = MaterialTheme.typography.titleMedium)
        generalErrorRes(error)?.let { FieldError(true, it) }
        if (config.presets.isEmpty()) Text(stringResource(R.string.tuner_config_presets_empty))
        config.presets.forEach { preset ->
            PresetRow(preset, preset.id == config.selectedPresetId, onIntent, onDelete = { pendingDelete = preset }) {
                editingId = preset.id
                label = preset.label
                hz = formatHz(preset.referencePitch.hz)
                cents = preset.maxCents.value.toString()
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text(stringResource(R.string.tuner_config_preset_label)) },
            isError = error == ConfigError.LABEL,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TUNER_CONFIG_LABEL_TAG)
        )
        FieldError(error == ConfigError.LABEL, R.string.tuner_config_error_label)
        Button(
            onClick = { onIntent(TunerIntent.SavePreset(editingId, label, hzValue, centsValue)) },
            modifier = Modifier.testTag(TUNER_CONFIG_SAVE_TAG)
        ) { Text(stringResource(R.string.tuner_config_preset_save)) }
    }
    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.tuner_config_delete_title)) },
            text = { Text(stringResource(R.string.tuner_config_delete_message, preset.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onIntent(TunerIntent.DeletePreset(preset.id))
                    },
                    modifier = Modifier.testTag(TUNER_CONFIG_DELETE_CONFIRM_TAG)
                ) { Text(stringResource(R.string.tuner_config_preset_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.tuner_config_cancel)) }
            }
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    labelRes: Int,
    isError: Boolean,
    tag: String,
    decimal: Boolean,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun FieldError(visible: Boolean, res: Int) {
    if (visible) {
        Text(stringResource(res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PresetRow(
    preset: TuningConfiguration,
    selected: Boolean,
    onIntent: (TunerIntent) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Column {
        val summary = stringResource(
            R.string.tuner_config_preset_summary,
            preset.label,
            formatHz(preset.referencePitch.hz),
            preset.maxCents.value
        )
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onIntent(TunerIntent.SelectPreset(preset.id)) },
                enabled = !selected,
                modifier = Modifier.testTag(tunerPresetSelectTag(preset.id))
            ) {
                val res = if (selected) R.string.tuner_config_preset_active else R.string.tuner_config_preset_select
                Text(stringResource(res))
            }
            TextButton(onClick = onEdit, modifier = Modifier.testTag(tunerPresetEditTag(preset.id))) {
                Text(stringResource(R.string.tuner_config_preset_edit))
            }
            TextButton(onClick = onDelete, modifier = Modifier.testTag(tunerPresetDeleteTag(preset.id))) {
                Text(stringResource(R.string.tuner_config_preset_delete))
            }
        }
    }
}

private fun generalErrorRes(error: ConfigError?) = when (error) {
    ConfigError.PRESET_LIMIT -> R.string.tuner_config_error_limit
    ConfigError.PRESET_NOT_FOUND -> R.string.tuner_config_error_not_found
    ConfigError.STORAGE -> R.string.tuner_config_error_storage
    ConfigError.UNKNOWN -> R.string.tuner_config_error_unknown
    else -> null
}

private fun formatHz(hz: Double) = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
