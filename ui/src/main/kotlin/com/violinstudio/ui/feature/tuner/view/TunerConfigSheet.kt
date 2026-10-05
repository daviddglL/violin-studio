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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
const val TUNER_CONFIG_CANCEL_EDIT_TAG = "tuner_config_cancel_edit"
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
 * sola validación (la del dominio) y el error vuelve por [ConfigError]. El borrador sobrevive a la rotación
 * (`rememberSaveable`) y se descarta cuando cambia la selección o la lista de presets.
 */
@Composable
fun TunerConfigContent(config: TunerConfig, error: ConfigError?, onIntent: (TunerIntent) -> Unit) {
    val baseHz = formatHz(config.referencePitch.hz)
    val baseCents = config.maxCents.value.toString()
    var hz by rememberSaveable(baseHz, config.selectedPresetId) { mutableStateOf(baseHz) }
    var cents by rememberSaveable(baseCents, config.selectedPresetId) { mutableStateOf(baseCents) }
    var label by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    fun resetForm() {
        editingId = null
        label = ""
        hz = baseHz
        cents = baseCents
    }
    // Tras guardar, borrar o seleccionar cambia la lista o la selección: el formulario vuelve a los valores activos.
    val snapshot = config.presets to config.selectedPresetId
    var seen by remember { mutableStateOf(snapshot) }
    LaunchedEffect(snapshot) {
        if (seen != snapshot) {
            seen = snapshot
            resetForm()
        }
    }
    val edit: (String) -> String = { value ->
        if (error != null) onIntent(TunerIntent.ClearConfigError)
        value
    }
    val hzValue = hz.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN
    val centsValue = cents.trim().toIntOrNull() ?: -1
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.tuner_config_title), style = MaterialTheme.typography.titleLarge)
        generalErrorRes(error)?.let { GeneralError(it) }
        NumberField(
            hz,
            R.string.tuner_config_reference,
            R.string.tuner_config_error_reference.takeIf { error == ConfigError.REFERENCE_PITCH },
            TUNER_CONFIG_HZ_TAG,
            decimal = true
        ) { hz = edit(it) }
        NumberField(
            cents,
            R.string.tuner_config_max_cents,
            R.string.tuner_config_error_max_cents.takeIf { error == ConfigError.MAX_CENTS },
            TUNER_CONFIG_CENTS_TAG,
            decimal = false
        ) { cents = edit(it) }
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
        if (config.presets.isEmpty()) Text(stringResource(R.string.tuner_config_presets_empty))
        config.presets.forEach { preset ->
            val active = preset.id == config.selectedPresetId
            PresetRow(preset, active, onIntent, onDelete = { pendingDeleteId = preset.id }) {
                editingId = preset.id
                label = preset.label
                hz = formatHz(preset.referencePitch.hz)
                cents = preset.maxCents.value.toString()
            }
        }
        NumberField(
            label,
            R.string.tuner_config_preset_label,
            labelErrorRes(error),
            TUNER_CONFIG_LABEL_TAG,
            decimal = null
        ) { label = edit(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onIntent(TunerIntent.SavePreset(editingId, label, hzValue, centsValue)) },
                modifier = Modifier.testTag(TUNER_CONFIG_SAVE_TAG)
            ) { Text(stringResource(R.string.tuner_config_preset_save)) }
            if (editingId != null) {
                OutlinedButton(onClick = ::resetForm, modifier = Modifier.testTag(TUNER_CONFIG_CANCEL_EDIT_TAG)) {
                    Text(stringResource(R.string.tuner_config_cancel_edit))
                }
            }
        }
    }
    config.presets.firstOrNull { it.id == pendingDeleteId }?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.tuner_config_delete_title)) },
            text = { Text(stringResource(R.string.tuner_config_delete_message, preset.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        onIntent(TunerIntent.DeletePreset(preset.id))
                    },
                    modifier = Modifier.testTag(TUNER_CONFIG_DELETE_CONFIRM_TAG)
                ) { Text(stringResource(R.string.tuner_config_preset_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.tuner_config_cancel)) }
            }
        )
    }
}

/** Campo con error en línea (`supportingText` + `isError`); [decimal] `null` = texto libre, sin teclado numérico. */
@Composable
private fun NumberField(
    value: String,
    labelRes: Int,
    errorRes: Int?,
    tag: String,
    decimal: Boolean?,
    onChange: (String) -> Unit
) {
    val keyboard = when (decimal) {
        true -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
        false -> KeyboardOptions(keyboardType = KeyboardType.Number)
        null -> KeyboardOptions.Default
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        isError = errorRes != null,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun GeneralError(res: Int) {
    Text(
        stringResource(res),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
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
            val selectDescription = stringResource(R.string.tuner_config_preset_select_named, preset.label)
            TextButton(
                onClick = { onIntent(TunerIntent.SelectPreset(preset.id)) },
                enabled = !selected,
                modifier = Modifier.testTag(tunerPresetSelectTag(preset.id))
                    .semantics { contentDescription = selectDescription }
            ) {
                val res = if (selected) R.string.tuner_config_preset_active else R.string.tuner_config_preset_select
                Text(stringResource(res))
            }
            val editDescription = stringResource(R.string.tuner_config_preset_edit_named, preset.label)
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag(tunerPresetEditTag(preset.id))
                    .semantics { contentDescription = editDescription }
            ) { Text(stringResource(R.string.tuner_config_preset_edit)) }
            val deleteDescription = stringResource(R.string.tuner_config_preset_delete_named, preset.label)
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag(tunerPresetDeleteTag(preset.id))
                    .semantics { contentDescription = deleteDescription }
            ) { Text(stringResource(R.string.tuner_config_preset_delete)) }
        }
    }
}

private fun labelErrorRes(error: ConfigError?) = when (error) {
    ConfigError.LABEL -> R.string.tuner_config_error_label
    ConfigError.DUPLICATE_LABEL -> R.string.tuner_config_error_duplicate
    else -> null
}

/** Errores que no pertenecen a un campo: van arriba de la hoja, donde se ven sin desplazarse. */
private fun generalErrorRes(error: ConfigError?) = when (error) {
    ConfigError.PRESET_LIMIT -> R.string.tuner_config_error_limit
    ConfigError.PRESET_NOT_FOUND -> R.string.tuner_config_error_not_found
    ConfigError.STORAGE -> R.string.tuner_config_error_storage
    ConfigError.NO_SESSION -> R.string.tuner_config_error_no_session
    ConfigError.UNKNOWN -> R.string.tuner_config_error_unknown
    else -> null
}

private fun formatHz(hz: Double) = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
