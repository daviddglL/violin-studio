package com.violinstudio.ui.feature.intonation.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.intonation.model.IntonationSummary
import com.violinstudio.domain.feature.intonation.model.Tendency
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationError
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationIntent
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationMic
import com.violinstudio.ui.feature.intonation.viewmodel.IntonationState
import com.violinstudio.ui.feature.tuner.view.InTuneGreen
import com.violinstudio.ui.feature.tuner.view.centsLabel
import kotlin.math.abs

const val INTONATION_TAG = "intonation"
const val INTONATION_NOTE_TAG = "intonation_note"
const val INTONATION_CENTS_TAG = "intonation_cents"
const val INTONATION_START_TAG = "intonation_start"
const val INTONATION_PAUSE_TAG = "intonation_pause"
const val INTONATION_SKIP_TAG = "intonation_skip"
const val INTONATION_SETTINGS_TAG = "intonation_settings"
const val INTONATION_SCORE_TAG = "intonation_score"
const val INTONATION_RESTART_TAG = "intonation_restart"
const val INTONATION_BACK_TAG = "intonation_back"

/** Margen de "afinada" de la lectura en vivo: el mismo que puntua la nota. */
private const val LIVE_IN_TUNE_CENTS = IntonationSummary.IN_TUNE_CENTS

@Composable
fun IntonationScreen(
    state: IntonationState,
    onIntent: (IntonationIntent) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    AuthScaffold(INTONATION_TAG, stringResource(R.string.intonation_title)) {
        val exercise = state.exercise
        if (exercise == null) {
            Text(stringResource(R.string.intonation_not_found))
        } else {
            Text(stringResource(exerciseTitle(exercise.id)), style = MaterialTheme.typography.titleMedium)
            val summary = state.summary
            if (summary == null) Exercise(state, onIntent, onStart) else Result(state, summary, onIntent)
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag(INTONATION_BACK_TAG)) {
            Text(stringResource(R.string.intonation_back))
        }
    }
}

@Composable
private fun Exercise(state: IntonationState, onIntent: (IntonationIntent) -> Unit, onStart: () -> Unit) {
    Text(stringResource(R.string.intonation_how), style = MaterialTheme.typography.bodyMedium)
    val position = stringResource(R.string.intonation_position, state.index + 1, state.total)
    LinearProgressIndicator(
        progress = { if (state.total == 0) 0f else state.index.toFloat() / state.total },
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = position }
    )
    Text(position, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    state.expected?.let { note ->
        Text(
            stringResource(R.string.intonation_play, note.label()),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag(INTONATION_NOTE_TAG).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    val cents = state.cents
    val inTune = cents != null && abs(cents) <= LIVE_IN_TUNE_CENTS
    // Cambia a cada lectura: no es region activa.
    Text(
        when {
            cents != null -> centsLabel(cents)
            state.isListening -> stringResource(R.string.intonation_listening)
            else -> " "
        },
        style = MaterialTheme.typography.headlineSmall,
        color = if (inTune) InTuneGreen else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(INTONATION_CENTS_TAG)
    )
    Spacer(Modifier.height(16.dp))
    val message = when {
        state.mic == IntonationMic.BLOCKED -> R.string.tuner_mic_blocked
        state.mic == IntonationMic.DENIED -> R.string.intonation_mic_denied
        else -> state.error?.textRes()
    }
    message?.let {
        Text(
            stringResource(it),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    when {
        state.mic == IntonationMic.BLOCKED -> OutlinedButton(
            onClick = { onIntent(IntonationIntent.OpenAppSettings) },
            modifier = Modifier.fillMaxWidth().testTag(INTONATION_SETTINGS_TAG)
        ) { Text(stringResource(R.string.tuner_open_settings)) }
        state.isListening -> OutlinedButton(
            onClick = { onIntent(IntonationIntent.Pause) },
            modifier = Modifier.fillMaxWidth().testTag(INTONATION_PAUSE_TAG)
        ) { Text(stringResource(R.string.intonation_pause)) }
        else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag(INTONATION_START_TAG)) {
            Text(stringResource(if (state.index == 0) R.string.intonation_start else R.string.intonation_continue))
        }
    }
    TextButton(
        onClick = { onIntent(IntonationIntent.Skip) },
        modifier = Modifier.testTag(INTONATION_SKIP_TAG)
    ) { Text(stringResource(R.string.intonation_skip)) }
}

@Composable
private fun Result(state: IntonationState, summary: IntonationSummary, onIntent: (IntonationIntent) -> Unit) {
    Text(
        stringResource(R.string.intonation_score, summary.score),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag(INTONATION_SCORE_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    )
    Text(stringResource(R.string.intonation_in_tune_count, summary.inTune, summary.total))
    when {
        state.isNewBest -> Text(stringResource(R.string.intonation_new_best), color = InTuneGreen)
        state.best != null -> Text(stringResource(R.string.intonation_best, state.best))
    }
    Spacer(Modifier.height(8.dp))
    when {
        summary.played == 0 -> Text(stringResource(R.string.intonation_tip_none_played))
        summary.tendencies.isEmpty() -> Text(stringResource(R.string.intonation_tip_no_tendency))
        else -> summary.tendencies.forEach { Text(tendencyText(it)) }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onIntent(IntonationIntent.Restart) },
        modifier = Modifier.fillMaxWidth().testTag(INTONATION_RESTART_TAG)
    ) { Text(stringResource(R.string.intonation_restart)) }
}

@Composable
private fun tendencyText(tendency: Tendency): String {
    val name = Note(tendency.pitchClass).name
    return if (tendency.cents > 0) {
        stringResource(R.string.intonation_tip_sharp, name, tendency.cents)
    } else {
        stringResource(R.string.intonation_tip_flat, name, -tendency.cents)
    }
}

private fun Note.label() = "$name$octave"

@StringRes
fun exerciseTitle(id: String): Int = when (id) {
    "a_string_pattern" -> R.string.intonation_ex_a_string_pattern
    "d_major_one_octave" -> R.string.intonation_ex_d_major_one_octave
    "g_major_two_octaves" -> R.string.intonation_ex_g_major_two_octaves
    "a_string_third_position" -> R.string.intonation_ex_a_string_third_position
    else -> R.string.intonation_title
}

private fun IntonationError.textRes() = when (this) {
    IntonationError.MIC_BUSY -> R.string.tuner_error_busy
    IntonationError.MIC_UNAVAILABLE -> R.string.tuner_error_unavailable
    IntonationError.UNKNOWN -> R.string.intonation_error_unknown
}
