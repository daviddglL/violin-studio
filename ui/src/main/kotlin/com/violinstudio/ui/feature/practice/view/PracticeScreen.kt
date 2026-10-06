package com.violinstudio.ui.feature.practice.view

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.practice.model.PracticeRules
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.practice.viewmodel.PracticeIntent
import com.violinstudio.ui.feature.practice.viewmodel.PracticeMessage
import com.violinstudio.ui.feature.practice.viewmodel.PracticeState
import java.time.ZoneId

const val PRACTICE_TAG = "practice"
const val PRACTICE_BACK_TAG = "practice_back"
const val PRACTICE_START_TAG = "practice_start"
const val PRACTICE_STOP_TAG = "practice_stop"
const val PRACTICE_TIMER_TAG = "practice_timer"
const val PRACTICE_WEEKLY_TAG = "practice_weekly"
const val PRACTICE_EMPTY_TAG = "practice_empty"
const val PRACTICE_MESSAGE_TAG = "practice_message"
const val PRACTICE_MESSAGE_DISMISS_TAG = "practice_message_dismiss"
const val PRACTICE_RETRY_TAG = "practice_retry"
const val PRACTICE_SAVE_DIALOG_TAG = "practice_save_dialog"
const val PRACTICE_NOTES_FIELD_TAG = "practice_notes_field"
const val PRACTICE_NOTES_COUNTER_TAG = "practice_notes_counter"
const val PRACTICE_SAVE_TAG = "practice_save"
const val PRACTICE_DISCARD_TAG = "practice_discard"
const val PRACTICE_CONTINUE_TAG = "practice_continue"
const val PRACTICE_DELETE_DIALOG_TAG = "practice_delete_dialog"
const val PRACTICE_DELETE_CONFIRM_TAG = "practice_delete_confirm"
const val PRACTICE_DELETE_CANCEL_TAG = "practice_delete_cancel"
const val PRACTICE_EDIT_DIALOG_TAG = "practice_edit_dialog"
const val PRACTICE_EDIT_FIELD_TAG = "practice_edit_field"
const val PRACTICE_EDIT_SAVE_TAG = "practice_edit_save"
const val PRACTICE_EDIT_CANCEL_TAG = "practice_edit_cancel"
const val PRACTICE_EDIT_COUNTER_TAG = "practice_edit_counter"
const val PRACTICE_EDIT_MESSAGE_TAG = "practice_edit_message"
const val PRACTICE_SAVE_MESSAGE_TAG = "practice_save_message"

fun practiceInstrumentTag(wire: String) = "practice_instrument_$wire"

fun practiceItemTag(id: String) = "practice_item_$id"

fun practiceItemPendingTag(id: String) = "practice_item_pending_$id"

fun practiceItemEditTag(id: String) = "practice_item_edit_$id"

fun practiceItemDeleteTag(id: String) = "practice_item_delete_$id"

private val minTarget = 48.dp

/** Sin estado propio salvo la edición de notas en curso; la zona se inyecta para fechas deterministas. */
@Composable
fun PracticeScreen(
    state: PracticeState,
    onIntent: (PracticeIntent) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val weekly = remember(state.weeklyTotalSec, configuration) { resources.practiceDuration(state.weeklyTotalSec) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(PRACTICE_TAG),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.practice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            // Con un diálogo abierto el aviso va dentro de él: detrás del modal no se vería.
            state.message?.takeIf { !state.showSave && editingId == null }
                ?.let { message -> item { MessageBanner(message, state.retryable, onIntent) } }
            item { SessionControls(state, onIntent) }
            item {
                Text(
                    stringResource(R.string.practice_weekly_total, weekly),
                    modifier = Modifier.testTag(PRACTICE_WEEKLY_TAG)
                )
            }
            if (state.history.isEmpty()) {
                item {
                    Text(stringResource(R.string.practice_empty), modifier = Modifier.testTag(PRACTICE_EMPTY_TAG))
                }
            }
            items(state.history, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    zone = zone,
                    onEdit = {
                        editingId = session.id
                        editText = session.notes.orEmpty()
                    },
                    onDelete = { onIntent(PracticeIntent.RequestDelete(session.id)) }
                )
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.testTag(PRACTICE_BACK_TAG)) {
                    Text(stringResource(R.string.practice_back))
                }
            }
        }
    }
    if (state.showSave) SaveDialog(state, onIntent)
    if (state.confirmDeleteId != null) DeleteDialog(onIntent)
    val editing = editingId?.let { id -> state.history.firstOrNull { it.id == id } }
    val normalized = PracticeRules.notes(editText).getOrNull()
    // El diálogo sigue abierto hasta que el historial refleja las notas (o la sesión desaparece): un fallo no pierde el texto.
    LaunchedEffect(state.history, editingId, submitted) {
        if (editingId != null && (editing == null || (submitted && editing.notes == normalized))) {
            editingId = null
            submitted = false
        }
    }
    if (editing != null) {
        EditNotesDialog(
            text = editText,
            message = state.message,
            onTextChange = {
                editText = it
                submitted = false
            },
            onSave = {
                if (normalized == editing.notes) {
                    editingId = null
                } else {
                    submitted = true
                    onIntent(PracticeIntent.UpdateNotes(editing.id, editText))
                }
            },
            onCancel = {
                editingId = null
                submitted = false
            }
        )
    }
}

@Composable
private fun MessageBanner(message: PracticeMessage, retryable: Boolean, onIntent: (PracticeIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(message.textRes()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag(PRACTICE_MESSAGE_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (retryable) {
            TextButton(onClick = { onIntent(PracticeIntent.Retry) }, modifier = Modifier.testTag(PRACTICE_RETRY_TAG)) {
                Text(stringResource(R.string.practice_retry))
            }
        }
        TextButton(
            onClick = { onIntent(PracticeIntent.DismissMessage) },
            modifier = Modifier.testTag(PRACTICE_MESSAGE_DISMISS_TAG)
        ) { Text(stringResource(R.string.practice_dismiss)) }
    }
}

@Composable
private fun SessionControls(state: PracticeState, onIntent: (PracticeIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val running = state.running
        val timerDescription = LocalContext.current.resources.practiceDuration(state.elapsedSec.toInt())
        if (running == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (instrument in Instrument.entries) {
                    FilterChip(
                        selected = state.instrument == instrument,
                        onClick = { onIntent(PracticeIntent.SelectInstrument(instrument)) },
                        label = { Text(stringResource(instrument.labelRes())) },
                        modifier = Modifier.testTag(practiceInstrumentTag(instrument.wire))
                    )
                }
            }
            Button(
                onClick = { onIntent(PracticeIntent.Start) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = minTarget).testTag(PRACTICE_START_TAG)
            ) { Text(stringResource(R.string.practice_start)) }
        } else {
            Text(
                formatClock(state.elapsedSec),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { contentDescription = timerDescription }.testTag(PRACTICE_TIMER_TAG)
            )
            Button(
                onClick = { onIntent(PracticeIntent.Stop) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = minTarget).testTag(PRACTICE_STOP_TAG)
            ) { Text(stringResource(R.string.practice_stop)) }
        }
    }
}

@Composable
private fun SessionRow(session: PracticeSession, zone: ZoneId, onEdit: () -> Unit, onDelete: () -> Unit) {
    val resources = LocalContext.current.resources
    val date = formatPracticeDate(session.startedAt, LocalConfiguration.current.locales[0], zone)
    Column(Modifier.fillMaxWidth().testTag(practiceItemTag(session.id)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(date, style = MaterialTheme.typography.titleMedium)
        Text("${resources.practiceDuration(session.durationSec)} · ${stringResource(session.instrument.labelRes())}")
        session.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (session.pendingSync) {
            Text(
                stringResource(R.string.practice_pending_sync),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(practiceItemPendingTag(session.id))
            )
        }
        val editDescription = stringResource(R.string.practice_edit_named, date)
        val deleteDescription = stringResource(R.string.practice_delete_named, date)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.heightIn(min = minTarget).semantics { contentDescription = editDescription }
                    .testTag(practiceItemEditTag(session.id))
            ) { Text(stringResource(R.string.practice_edit)) }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = minTarget).semantics { contentDescription = deleteDescription }
                    .testTag(practiceItemDeleteTag(session.id))
            ) { Text(stringResource(R.string.practice_delete)) }
        }
    }
}

@Composable
private fun SaveDialog(state: PracticeState, onIntent: (PracticeIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(PracticeIntent.DismissSave) },
        modifier = Modifier.testTag(PRACTICE_SAVE_DIALOG_TAG),
        title = { Text(stringResource(R.string.practice_save_title)) },
        text = { SaveDialogContent(state, onIntent) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(PracticeIntent.Save) },
                enabled = !state.notesTooLong && !state.busy,
                modifier = Modifier.testTag(PRACTICE_SAVE_TAG)
            ) { Text(stringResource(R.string.practice_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(PracticeIntent.Discard) },
                enabled = !state.busy,
                modifier = Modifier.testTag(PRACTICE_DISCARD_TAG)
            ) { Text(stringResource(R.string.practice_discard)) }
        }
    )
}


/** Cuerpo del diálogo de guardar; aparte para poder capturarlo sin ventana (Roborazzi no cierra con un campo en un Dialog). */
@Composable
internal fun SaveDialogContent(state: PracticeState, onIntent: (PracticeIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotesField(
            value = state.draftNotes,
            onValueChange = { onIntent(PracticeIntent.EditNotes(it)) },
            count = state.notesCount,
            tooLong = state.notesTooLong,
            fieldTag = PRACTICE_NOTES_FIELD_TAG,
            counterTag = PRACTICE_NOTES_COUNTER_TAG
        )
        state.message?.let { DialogMessage(it, PRACTICE_SAVE_MESSAGE_TAG) }
        TextButton(
            onClick = { onIntent(PracticeIntent.DismissSave) },
            modifier = Modifier.testTag(PRACTICE_CONTINUE_TAG)
        ) { Text(stringResource(R.string.practice_continue)) }
    }
}
@Composable
private fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    count: Int,
    tooLong: Boolean,
    fieldTag: String,
    counterTag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.practice_notes_label)) },
        isError = tooLong,
        supportingText = {
            Text(
                stringResource(R.string.practice_notes_counter, count, PracticeRules.NOTES_MAX),
                modifier = Modifier.testTag(counterTag)
            )
        },
        modifier = Modifier.fillMaxWidth().testTag(fieldTag)
    )
}

@Composable
private fun DeleteDialog(onIntent: (PracticeIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(PracticeIntent.CancelDelete) },
        modifier = Modifier.testTag(PRACTICE_DELETE_DIALOG_TAG),
        title = { Text(stringResource(R.string.practice_delete_title)) },
        text = { Text(stringResource(R.string.practice_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(PracticeIntent.ConfirmDelete) },
                modifier = Modifier.testTag(PRACTICE_DELETE_CONFIRM_TAG)
            ) { Text(stringResource(R.string.practice_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(PracticeIntent.CancelDelete) },
                modifier = Modifier.testTag(PRACTICE_DELETE_CANCEL_TAG)
            ) { Text(stringResource(R.string.practice_cancel)) }
        }
    )
}

/** Solo `notes`: duración, inicio e instrumento de una sesión guardada son inmutables (REQ-PRA-08). */
@Composable
private fun EditNotesDialog(
    text: String,
    message: PracticeMessage?,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val count = text.trim().length
    val tooLong = count > PracticeRules.NOTES_MAX
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(PRACTICE_EDIT_DIALOG_TAG),
        title = { Text(stringResource(R.string.practice_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesField(text, onTextChange, count, tooLong, PRACTICE_EDIT_FIELD_TAG, PRACTICE_EDIT_COUNTER_TAG)
                message?.let { DialogMessage(it, PRACTICE_EDIT_MESSAGE_TAG) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !tooLong, modifier = Modifier.testTag(PRACTICE_EDIT_SAVE_TAG)) {
                Text(stringResource(R.string.practice_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(PRACTICE_EDIT_CANCEL_TAG)) {
                Text(stringResource(R.string.practice_cancel))
            }
        }
    )
}

private fun Instrument.labelRes() = when (this) {
    Instrument.VIOLIN -> R.string.practice_instrument_violin
    Instrument.VIOLA -> R.string.practice_instrument_viola
    Instrument.CELLO -> R.string.practice_instrument_cello
    Instrument.DOUBLE_BASS -> R.string.practice_instrument_double_bass
    Instrument.OTHER -> R.string.practice_instrument_other
}

private fun PracticeMessage.textRes() = when (this) {
    PracticeMessage.TOO_SHORT -> R.string.practice_message_too_short
    PracticeMessage.CLAMPED -> R.string.practice_message_clamped
    PracticeMessage.NOTES_TOO_LONG -> R.string.practice_message_notes_too_long
    PracticeMessage.PERMISSION_DENIED -> R.string.practice_message_permission_denied
    PracticeMessage.UNKNOWN -> R.string.practice_message_unknown
}

/** Fallo de la acción del diálogo: se anuncia con prioridad baja y no depende solo del color. */
@Composable
private fun DialogMessage(message: PracticeMessage, tag: String) {
    Text(
        stringResource(message.textRes()),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }
    )
}
