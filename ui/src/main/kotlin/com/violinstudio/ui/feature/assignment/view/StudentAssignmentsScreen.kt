package com.violinstudio.ui.feature.assignment.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsError
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsIntent
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsState
import com.violinstudio.ui.feature.assignment.viewmodel.StudentAssignmentsViewModel
import java.time.ZoneId

const val STUDENT_TASKS_TAG = "student_tasks"
const val STUDENT_TASKS_BACK_TAG = "student_tasks_back"
const val STUDENT_TASKS_EMPTY_TAG = "student_tasks_empty"
const val STUDENT_TASKS_LOADING_TAG = "student_tasks_loading"
const val STUDENT_TASKS_LOAD_FAILED_TAG = "student_tasks_load_failed"
const val STUDENT_TASKS_RETRY_TAG = "student_tasks_retry"
const val STUDENT_TASKS_ERROR_TAG = "student_tasks_error"
const val STUDENT_TASKS_ERROR_DISMISS_TAG = "student_tasks_error_dismiss"

fun studentTaskTag(id: String) = "student_task_$id"

fun studentTaskToggleTag(id: String) = "student_task_toggle_$id"

fun studentTaskPendingTag(id: String) = "student_task_pending_$id"

/** El rol lo comprueba la navegación antes de componer esta ruta. */
@Composable
fun StudentAssignmentsRoute(onBack: () -> Unit, viewModel: StudentAssignmentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudentAssignmentsScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun StudentAssignmentsScreen(
    state: StudentAssignmentsState,
    onIntent: (StudentAssignmentsIntent) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(STUDENT_TASKS_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(STUDENT_TASKS_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.assignment_student_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            when {
                state.isLoading -> AssignmentLoading(STUDENT_TASKS_LOADING_TAG)
                state.loadFailed -> LoadFailed(onIntent)
                else -> Content(state, onIntent, zone)
            }
        }
    }
}

@Composable
private fun Content(state: StudentAssignmentsState, onIntent: (StudentAssignmentsIntent) -> Unit, zone: ZoneId) {
    state.error?.let {
        DismissibleMessage(
            stringResource(
                if (it == StudentAssignmentsError.PERMISSION_DENIED) {
                    R.string.assignment_error_permission
                } else {
                    R.string.assignment_error_unknown
                }
            ),
            STUDENT_TASKS_ERROR_TAG,
            STUDENT_TASKS_ERROR_DISMISS_TAG,
            isError = true
        ) { onIntent(StudentAssignmentsIntent.DismissError) }
    }
    if (state.assignments.isEmpty()) {
        Text(stringResource(R.string.assignment_student_empty), modifier = Modifier.testTag(STUDENT_TASKS_EMPTY_TAG))
    }
    state.assignments.forEach { task ->
        HorizontalDivider()
        TaskRow(task, task.id in state.busyIds, onIntent, zone)
    }
}

@Composable
private fun TaskRow(task: Assignment, busy: Boolean, onIntent: (StudentAssignmentsIntent) -> Unit, zone: ZoneId) {
    val done = task.status == AssignmentStatus.DONE
    val description = stringResource(
        if (done) R.string.assignment_reopen_named else R.string.assignment_mark_done_named,
        task.title
    )
    Column(
        Modifier.fillMaxWidth().testTag(studentTaskTag(task.id)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(task.title, style = MaterialTheme.typography.titleMedium)
        task.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(task.statusLabel())
        Text(dueLabel(task.dueAt, zone), style = MaterialTheme.typography.bodyMedium)
        if (task.pendingSync) PendingBadge(studentTaskPendingTag(task.id))
        val intent = if (done) StudentAssignmentsIntent.Reopen(task.id) else StudentAssignmentsIntent.Complete(task.id)
        val modifier = Modifier.testTag(studentTaskToggleTag(task.id)).semantics { contentDescription = description }
        if (done) {
            OutlinedButton(onClick = { onIntent(intent) }, enabled = !busy, modifier = modifier) {
                Text(stringResource(R.string.assignment_reopen))
            }
        } else {
            Button(onClick = { onIntent(intent) }, enabled = !busy, modifier = modifier) {
                Text(stringResource(R.string.assignment_mark_done))
            }
        }
    }
}

@Composable
private fun LoadFailed(onIntent: (StudentAssignmentsIntent) -> Unit) {
    Text(
        stringResource(R.string.assignment_load_failed),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(STUDENT_TASKS_LOAD_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
    Button(
        onClick = { onIntent(StudentAssignmentsIntent.Retry) },
        modifier = Modifier.testTag(STUDENT_TASKS_RETRY_TAG)
    ) { Text(stringResource(R.string.link_retry)) }
}
