package com.violinstudio.ui.feature.metronome.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeError
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeIntent
import com.violinstudio.ui.feature.metronome.viewmodel.MetronomeState
import kotlin.math.roundToInt

const val METRONOME_TAG = "metronome"
const val METRONOME_BPM_TAG = "metronome_bpm"
const val METRONOME_SLIDER_TAG = "metronome_slider"
const val METRONOME_INCREASE_TAG = "metronome_increase"
const val METRONOME_DECREASE_TAG = "metronome_decrease"
const val METRONOME_TAP_TAG = "metronome_tap"
const val METRONOME_TOGGLE_TAG = "metronome_toggle"
const val METRONOME_BACK_TAG = "metronome_back"

fun metronomeSignatureTag(signature: TimeSignature) = "metronome_signature_${signature.label}"

fun metronomeBeatTag(position: Int) = "metronome_beat_$position"

private val minTarget = 48.dp

@Composable
fun MetronomeScreen(state: MetronomeState, onIntent: (MetronomeIntent) -> Unit, onBack: () -> Unit) {
    AuthScaffold(METRONOME_TAG, stringResource(R.string.metronome_title)) {
        Text(
            stringResource(R.string.metronome_bpm, state.tempo.bpm),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag(METRONOME_BPM_TAG)
        )
        TempoControl(state, onIntent)
        Spacer(Modifier.height(16.dp))
        BeatIndicator(state)
        Spacer(Modifier.height(16.dp))
        SignatureSelector(state, onIntent)
        if (state.signature == TimeSignature.SIX_EIGHT) {
            Text(stringResource(R.string.metronome_six_eight_hint), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Actions(state, onIntent)
        TextButton(onClick = onBack, modifier = Modifier.testTag(METRONOME_BACK_TAG)) {
            Text(stringResource(R.string.metronome_back))
        }
    }
}

@Composable
private fun TempoControl(state: MetronomeState, onIntent: (MetronomeIntent) -> Unit) {
    val slider = stringResource(R.string.metronome_slider)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("−", stringResource(R.string.metronome_decrease), METRONOME_DECREASE_TAG) {
            onIntent(MetronomeIntent.Decrement)
        }
        Slider(
            value = state.tempo.bpm.toFloat(),
            onValueChange = { onIntent(MetronomeIntent.SetBpm(it.roundToInt())) },
            valueRange = Tempo.MIN_BPM.toFloat()..Tempo.MAX_BPM.toFloat(),
            modifier = Modifier.weight(1f).semantics { contentDescription = slider }.testTag(METRONOME_SLIDER_TAG)
        )
        StepButton("+", stringResource(R.string.metronome_increase), METRONOME_INCREASE_TAG) {
            onIntent(MetronomeIntent.Increment)
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = minTarget, minHeight = minTarget)
            .semantics { contentDescription = description }
            .testTag(tag)
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
}

/** Un punto por tiempo: el que suena va relleno y el acento es mayor, asi que no depende solo del color. */
@Composable
private fun BeatIndicator(state: MetronomeState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(32.dp)
    ) {
        for (position in 0 until state.signature.beats) {
            val lit = state.tick?.position == position
            val color = MaterialTheme.colorScheme.primary
            val look = if (lit) Modifier.background(color, CircleShape) else Modifier.border(2.dp, color, CircleShape)
            Box(
                Modifier.size(if (position == 0) 28.dp else 18.dp)
                    .then(look)
                    .semantics { selected = lit }
                    .testTag(metronomeBeatTag(position))
            )
        }
    }
}

@Composable
private fun SignatureSelector(state: MetronomeState, onIntent: (MetronomeIntent) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (signature in TimeSignature.entries) {
            FilterChip(
                selected = state.signature == signature,
                onClick = { onIntent(MetronomeIntent.SetSignature(signature)) },
                label = { Text(signature.label) },
                modifier = Modifier.testTag(metronomeSignatureTag(signature))
            )
        }
    }
}

@Composable
private fun Actions(state: MetronomeState, onIntent: (MetronomeIntent) -> Unit) {
    state.error?.let {
        // Solo el fallo se anuncia, y solo cuando aparece.
        Text(
            stringResource(it.textRes()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    val label = when {
        state.error != null -> R.string.metronome_retry
        state.isPlaying -> R.string.metronome_stop
        else -> R.string.metronome_start
    }
    Button(
        onClick = { onIntent(MetronomeIntent.Toggle) },
        modifier = Modifier.fillMaxWidth().heightIn(min = minTarget).testTag(METRONOME_TOGGLE_TAG)
    ) { Text(stringResource(label)) }
    OutlinedButton(
        onClick = { onIntent(MetronomeIntent.Tap) },
        modifier = Modifier.fillMaxWidth().heightIn(min = minTarget).testTag(METRONOME_TAP_TAG)
    ) { Text(stringResource(R.string.metronome_tap)) }
}

private fun MetronomeError.textRes() = when (this) {
    MetronomeError.OUTPUT_UNAVAILABLE -> R.string.metronome_error_output
    MetronomeError.UNKNOWN -> R.string.metronome_error_unknown
}
