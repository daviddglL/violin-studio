package com.violinstudio.ui.feature.tuner.view

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.tuner.viewmodel.MicState
import com.violinstudio.ui.feature.tuner.viewmodel.TunerError
import com.violinstudio.ui.feature.tuner.viewmodel.TunerIntent
import com.violinstudio.ui.feature.tuner.viewmodel.TunerState

const val TUNER_TAG = "tuner"
const val TUNER_LISTEN_TAG = "tuner_listen"
const val TUNER_STOP_TAG = "tuner_stop"
const val TUNER_RETRY_TAG = "tuner_retry"
const val TUNER_SETTINGS_TAG = "tuner_settings"
const val TUNER_RATIONALE_CONFIRM_TAG = "tuner_rationale_confirm"
const val TUNER_RATIONALE_DISMISS_TAG = "tuner_rationale_dismiss"
const val TUNER_BACK_TAG = "tuner_back"
const val TUNER_READING_TAG = "tuner_reading"
const val TUNER_REFERENCE_TAG = "tuner_reference"

fun tunerInstrumentTag(wire: String) = "tuner_instrument_$wire"

fun tunerStringTag(index: Int?) = "tuner_string_${index ?: "auto"}"

private val chipSpacing = Arrangement.spacedBy(8.dp)

/** [onStart] lee el permiso en la Activity y envía `Start`; la pantalla no conoce el checker. */
@Composable
fun TunerScreen(state: TunerState, onIntent: (TunerIntent) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
    AuthScaffold(TUNER_TAG, stringResource(R.string.tuner_title)) {
        InstrumentSelector(state, onIntent)
        StringSelector(state, onIntent)
        Spacer(Modifier.height(16.dp))
        ReadingPanel(state)
        Spacer(Modifier.height(16.dp))
        Actions(state, onIntent, onStart)
        ReferenceButton(state, onIntent)
        TextButton(onClick = onBack, modifier = Modifier.testTag(TUNER_BACK_TAG)) {
            Text(stringResource(R.string.tuner_back))
        }
    }
    if (state.showRationale) RationaleDialog(onIntent)
}

@Composable
private fun InstrumentSelector(state: TunerState, onIntent: (TunerIntent) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = chipSpacing) {
        for (instrument in Instrument.entries) {
            FilterChip(
                selected = state.instrument == instrument,
                onClick = { onIntent(TunerIntent.SelectInstrument(instrument)) },
                label = { Text(stringResource(instrument.tunerLabelRes())) },
                modifier = Modifier.testTag(tunerInstrumentTag(instrument.wire))
            )
        }
    }
}

@Composable
private fun StringSelector(state: TunerState, onIntent: (TunerIntent) -> Unit) {
    val strings = state.strings
    if (strings == null) {
        Text(
            stringResource(R.string.tuner_chromatic_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = chipSpacing) {
        FilterChip(
            selected = state.selectedString == null,
            onClick = { onIntent(TunerIntent.SelectString(null)) },
            label = { Text(stringResource(R.string.tuner_string_auto)) },
            modifier = Modifier.testTag(tunerStringTag(null))
        )
        strings.forEachIndexed { index, note ->
            FilterChip(
                selected = state.selectedString == index,
                onClick = { onIntent(TunerIntent.SelectString(index)) },
                label = { Text(note.label()) },
                modifier = Modifier.testTag(tunerStringTag(index))
            )
        }
    }
}

@Composable
private fun ReadingPanel(state: TunerState) {
    val reading = state.reading
    val pitch = reading as? TunerReading.Pitch
    val maxCents = state.config.maxCents.value
    val offScale = pitch != null && isOffScale(pitch.cents, maxCents)
    val text = when (reading) {
        TunerReading.Idle -> stringResource(R.string.tuner_status_idle)
        TunerReading.NoPitch -> stringResource(R.string.tuner_status_no_pitch)
        is TunerReading.Pitch -> "${reading.target.note.label()}  ${centsLabel(reading.cents)}"
    }
    val description = when {
        pitch == null -> stringResource(R.string.tuner_wheel_description_empty)
        offScale -> stringResource(
            R.string.tuner_wheel_description_off_scale,
            pitch.target.note.label(),
            centsLabel(pitch.cents)
        )
        else -> stringResource(R.string.tuner_wheel_description, pitch.target.note.label(), centsLabel(pitch.cents))
    }
    TuningWheel(pitch?.cents, maxCents, state.isInTune, description)
    // La lectura cambia a cada fotograma: no es región activa (TalkBack la repetiría sin parar).
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = when {
            offScale -> MaterialTheme.colorScheme.error
            state.isInTune -> InTuneGreen
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.testTag(TUNER_READING_TAG)
    )
    // Fuera de escala no depende solo del color (REQ-TUN-13): lleva texto propio.
    if (offScale) Text(stringResource(R.string.tuner_off_scale), color = MaterialTheme.colorScheme.error)
    // Solo este mensaje se anuncia, y solo cuando aparece (la transición a afinado).
    if (state.isInTune) Text(stringResource(R.string.tuner_in_tune), color = InTuneGreen, modifier = Modifier.polite())
}

/** Permiso y fallos del micro: selector y rueda siguen visibles (degradación), solo cambia la acción. */
@Composable
private fun Actions(state: TunerState, onIntent: (TunerIntent) -> Unit, onStart: () -> Unit) {
    val message = when {
        state.mic == MicState.PERMANENTLY_DENIED -> R.string.tuner_mic_blocked
        state.mic == MicState.DENIED -> R.string.tuner_mic_denied
        else -> state.error?.textRes()
    }
    message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
    when {
        state.mic == MicState.PERMANENTLY_DENIED -> OutlinedButton(
            onClick = { onIntent(TunerIntent.OpenAppSettings) },
            modifier = Modifier.fillMaxWidth().testTag(TUNER_SETTINGS_TAG)
        ) { Text(stringResource(R.string.tuner_open_settings)) }
        state.error != null && state.error != TunerError.AUDIO_OUTPUT_UNAVAILABLE -> OutlinedButton(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().testTag(TUNER_RETRY_TAG)
        ) { Text(stringResource(R.string.tuner_retry)) }
        state.isListening -> OutlinedButton(
            onClick = { onIntent(TunerIntent.Stop) },
            modifier = Modifier.fillMaxWidth().testTag(TUNER_STOP_TAG)
        ) { Text(stringResource(R.string.tuner_stop)) }
        else -> Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().testTag(TUNER_LISTEN_TAG)
        ) { Text(stringResource(R.string.tuner_listen)) }
    }
}

@Composable
private fun RationaleDialog(onIntent: (TunerIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(TunerIntent.DismissRationale) },
        title = { Text(stringResource(R.string.tuner_rationale_title)) },
        text = { Text(stringResource(R.string.tuner_rationale_message)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(TunerIntent.ConfirmRationale) },
                modifier = Modifier.testTag(TUNER_RATIONALE_CONFIRM_TAG)
            ) { Text(stringResource(R.string.tuner_rationale_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(TunerIntent.DismissRationale) },
                modifier = Modifier.testTag(TUNER_RATIONALE_DISMISS_TAG)
            ) { Text(stringResource(R.string.tuner_rationale_dismiss)) }
        }
    )
}

/** Tono de la cuerda elegida (hace falta una concreta); excluyente con la escucha del micro. */
@Composable
private fun ReferenceButton(state: TunerState, onIntent: (TunerIntent) -> Unit) {
    val playing = state.isPlayingReference
    OutlinedButton(
        onClick = { onIntent(TunerIntent.ToggleReference) },
        enabled = !state.isListening && (playing || state.selectedString != null),
        modifier = Modifier.fillMaxWidth().testTag(TUNER_REFERENCE_TAG)
    ) { Text(stringResource(if (playing) R.string.tuner_reference_stop else R.string.tuner_reference_play)) }
}

private fun Modifier.polite() = semantics { liveRegion = LiveRegionMode.Polite }

private fun Note.label() = "$name$octave"

private fun Instrument.tunerLabelRes() = when (this) {
    Instrument.VIOLIN -> R.string.tuner_instrument_violin
    Instrument.VIOLA -> R.string.tuner_instrument_viola
    Instrument.CELLO -> R.string.tuner_instrument_cello
    Instrument.DOUBLE_BASS -> R.string.tuner_instrument_double_bass
    Instrument.OTHER -> R.string.tuner_instrument_other
}

private fun TunerError.textRes() = when (this) {
    TunerError.MIC_BUSY -> R.string.tuner_error_busy
    TunerError.MIC_UNAVAILABLE -> R.string.tuner_error_unavailable
    TunerError.AUDIO_OUTPUT_UNAVAILABLE -> R.string.tuner_error_output
    TunerError.UNKNOWN -> R.string.tuner_error_unknown
}
