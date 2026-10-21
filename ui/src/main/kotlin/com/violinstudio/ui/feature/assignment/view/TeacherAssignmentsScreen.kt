package com.violinstudio.ui.feature.assignment.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsEffect
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsError
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsIntent
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsState
import com.violinstudio.ui.feature.assignment.viewmodel.TeacherAssignmentsViewModel
import java.time.ZoneId

const val TEACHER_TASKS_TAG = "teacher_tasks"
const val TEACHER_TASKS_BACK_TAG = "teacher_tasks_back"
const val TEACHER_TASKS_CREATE_TAG = "teacher_tasks_create"
const val TEACHER_TASKS_EMPTY_TAG = "teacher_tasks_empty"
const val TEACHER_TASKS_LOADING_TAG = "teacher_tasks_loading"
const val TEACHER_TASKS_UNAVAILABLE_TAG = "teacher_tasks_unavailable"
const val TEACHER_TASKS_LOAD_FAILED_TAG = "teacher_tasks_load_failed"
const val TEACHER_TASKS_RETRY_TAG = "teacher_tasks_retry"
const val TEACHER_TASKS_ERROR_TAG = "teacher_tasks_error"
const val TEACHER_TASKS_ERROR_DISMISS_TAG = "teacher_tasks_error_dismiss"
const val TEACHER_TASKS_NOTICE_TAG = "teacher_tasks_notice"
const val TEACHER_TASKS_NOTICE_DISMISS_TAG = "teacher_tasks_notice_dismiss"
const val TEACHER_TASKS_DELETE_DIALOG_TAG = "teacher_tasks_delete_dialog"
const val TEACHER_TASKS_DELETE_CONFIRM_TAG = "teacher_tasks_delete_confirm"
const val TEACHER_TASKS_DELETE_CANCEL_TAG = "teacher_tasks_delete_cancel"

fun teacherTaskTag(id: String) = "teacher_task_$id"

fun teacherTaskStatusTag(id: String) = "teacher_task_status_$id"

fun teacherTaskPendingTag(id: String) = "teacher_task_pending_$id"

fun teacherTaskEditTag(id: String) = "teacher_task_edit_$id"

fun teacherTaskDeleteTag(id: String) = "teacher_task_delete_$id"

/** El rol lo comprueba la navegación. Si el vínculo se pierde, el ViewModel vacía la lista y pide volver. */
@Composable
fun TeacherAssignmentsRoute(
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TeacherAssignmentsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            TeacherAssignmentsEffect.NavigateBack -> onBack()
        }
    }
    TeacherAssignmentsScreen(state, viewModel::onIntent, onCreate, onEdit, onBack)
}

@Composable
fun TeacherAssignmentsScreen(
    state: TeacherAssignmentsState,
    onIntent: (TeacherAssignmentsIntent) -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(TEACHER_TASKS_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TEACHER_TASKS_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.assignment_teacher_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            when {
                state.isLoading -> AssignmentLoading(TEACHER_TASKS_LOADING_TAG)
                state.unavailable -> Text(
                    stringResource(R.string.assignment_teacher_unavailable),
                    modifier = Modifier.testTag(TEACHER_TASKS_UNAVAILABLE_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
                state.loadFailed -> LoadFailed(onIntent)
                else -> Content(state, onIntent, onCreate, onEdit, zone)
            }
        }
    }
    if (state.deleteTarget != null) DeleteDialog(state.isDeleting, onIntent)
}

@Composable
private fun Content(
    state: TeacherAssignmentsState,
    onIntent: (TeacherAssignmentsIntent) -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    zone: ZoneId
) {
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().testTag(TEACHER_TASKS_CREATE_TAG)) {
        Text(stringResource(R.string.assignment_teacher_new))
    }
    state.notice?.let {
        DismissibleMessage(
            stringResource(R.string.assignment_notice_rejected),
            TEACHER_TASKS_NOTICE_TAG,
            TEACHER_TASKS_NOTICE_DISMISS_TAG,
            isError = false
        ) { onIntent(TeacherAssignmentsIntent.DismissNotice) }
    }
    state.error?.let {
        DismissibleMessage(
            stringResource(
                if (it == TeacherAssignmentsError.PERMISSION_DENIED) {
                    R.string.assignment_error_permission
                } else {
                    R.string.assignment_error_unknown
                }
            ),
            TEACHER_TASKS_ERROR_TAG,
            TEACHER_TASKS_ERROR_DISMISS_TAG,
            isError = true
        ) { onIntent(TeacherAssignmentsIntent.DismissError) }
    }
    if (state.assignments.isEmpty()) {
        Text(stringResource(R.string.assignment_teacher_empty), modifier = Modifier.testTag(TEACHER_TASKS_EMPTY_TAG))
    }
    state.assignments.forEach { task ->
        HorizontalDivider()
        TaskRow(task, state.isDeleting, onIntent, onEdit, zone)
    }
}

@Composable
private fun TaskRow(
    task: Assignment,
    busy: Boolean,
    onIntent: (TeacherAssignmentsIntent) -> Unit,
    onEdit: (String) -> Unit,
    zone: ZoneId
) {
    val editDesc = stringResource(R.string.assignment_edit_named, task.title)
    val deleteDesc = stringResource(R.string.assignment_delete_named, task.title)
    Column(
        Modifier.fillMaxWidth().testTag(teacherTaskTag(task.id)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(task.title, style = MaterialTheme.typography.titleMedium)
        Text(task.statusLabel(), modifier = Modifier.testTag(teacherTaskStatusTag(task.id)))
        Text(dueLabel(task.dueAt, zone), style = MaterialTheme.typography.bodyMedium)
        if (task.pendingSync) PendingBadge(teacherTaskPendingTag(task.id))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onEdit(task.id) },
                modifier = Modifier.testTag(teacherTaskEditTag(task.id)).semantics { contentDescription = editDesc }
            ) { Text(stringResource(R.string.assignment_edit)) }
            TextButton(
                onClick = { onIntent(TeacherAssignmentsIntent.RequestDelete(task.id)) },
                enabled = !busy,
                modifier = Modifier.testTag(teacherTaskDeleteTag(task.id))
                    .semantics { contentDescription = deleteDesc }
            ) { Text(stringResource(R.string.assignment_delete)) }
        }
    }
}

@Composable
private fun LoadFailed(onIntent: (TeacherAssignmentsIntent) -> Unit) {
    Text(
        stringResource(R.string.assignment_load_failed),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(TEACHER_TASKS_LOAD_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
    Button(
        onClick = { onIntent(TeacherAssignmentsIntent.Retry) },
        modifier = Modifier.testTag(TEACHER_TASKS_RETRY_TAG)
    ) { Text(stringResource(R.string.link_retry)) }
}

@Composable
private fun DeleteDialog(busy: Boolean, onIntent: (TeacherAssignmentsIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(TeacherAssignmentsIntent.CancelDelete) },
        modifier = Modifier.testTag(TEACHER_TASKS_DELETE_DIALOG_TAG),
        title = { Text(stringResource(R.string.assignment_delete_title)) },
        text = { Text(stringResource(R.string.assignment_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(TeacherAssignmentsIntent.ConfirmDelete) },
                enabled = !busy,
                modifier = Modifier.testTag(TEACHER_TASKS_DELETE_CONFIRM_TAG)
            ) { Text(stringResource(R.string.assignment_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(TeacherAssignmentsIntent.CancelDelete) },
                enabled = !busy,
                modifier = Modifier.testTag(TEACHER_TASKS_DELETE_CANCEL_TAG)
            ) { Text(stringResource(R.string.assignment_cancel)) }
        }
    )
}
