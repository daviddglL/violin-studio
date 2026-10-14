package com.violinstudio.ui.feature.teacher.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.link.view.formatted
import com.violinstudio.ui.feature.onboarding.view.labelRes
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsError
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsIntent
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsState
import com.violinstudio.ui.feature.teacher.viewmodel.StudentsViewModel

const val STUDENTS_TAG = "students"
const val STUDENTS_LOADING_TAG = "students_loading"
const val STUDENTS_COUNT_TAG = "students_count"
const val STUDENTS_EMPTY_TAG = "students_empty"
const val STUDENTS_CODES_CTA_TAG = "students_codes_cta"
const val STUDENTS_LOAD_FAILED_TAG = "students_load_failed"
const val STUDENTS_RETRY_TAG = "students_retry"
const val STUDENTS_ERROR_TAG = "students_error"
const val STUDENTS_BACK_TAG = "students_back"
const val STUDENT_ROW_TAG = "student_row"
const val STUDENT_UNLINK_TAG = "student_unlink"
const val STUDENT_UNLINK_DIALOG_TAG = "student_unlink_dialog"
const val STUDENT_UNLINK_CONFIRM_TAG = "student_unlink_confirm"
const val STUDENT_UNLINK_CANCEL_TAG = "student_unlink_cancel"

/** El rol lo comprueba la navegación antes de componer esta ruta; aquí no hay lógica de sesión. */
@Composable
fun StudentsRoute(
    onOpenCodes: () -> Unit,
    onOpenStudent: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: StudentsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudentsScreen(state, viewModel::onIntent, onOpenCodes, onOpenStudent, onBack)
}

@Composable
fun StudentsScreen(
    state: StudentsState,
    onIntent: (StudentsIntent) -> Unit,
    onOpenCodes: () -> Unit,
    onOpenStudent: (String) -> Unit,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(STUDENTS_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(STUDENTS_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.teacher_students_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            when {
                state.isLoading -> {
                    val loading = stringResource(R.string.link_loading)
                    CircularProgressIndicator(
                        Modifier.testTag(STUDENTS_LOADING_TAG).semantics {
                            contentDescription = loading
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
                state.loadFailed -> LoadFailed(onIntent)
                state.students.isEmpty() -> Empty(state.error, onOpenCodes)
                else -> StudentList(state, onIntent, onOpenStudent)
            }
        }
    }
    state.unlinkTarget?.let { UnlinkDialog(it, state.isUnlinking, onIntent) }
}

@Composable
private fun StudentList(state: StudentsState, onIntent: (StudentsIntent) -> Unit, onOpenStudent: (String) -> Unit) {
    Text(
        stringResource(R.string.teacher_students_count, state.students.size, StudentsState.MAX_STUDENTS),
        modifier = Modifier.testTag(STUDENTS_COUNT_TAG)
    )
    state.error?.let { ErrorText(it) }
    state.students.forEach { link ->
        val unlinkDesc = stringResource(R.string.teacher_student_unlink_desc, link.studentDisplayName)
        val openDesc = stringResource(R.string.teacher_student_open_desc, link.studentDisplayName)
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().testTag(STUDENT_ROW_TAG)
                .clickable(onClickLabel = openDesc, role = Role.Button) { onOpenStudent(link.studentUid) },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(link.studentDisplayName, style = MaterialTheme.typography.titleMedium)
            link.studentInstrument?.let { Text(stringResource(it.labelRes())) }
            Text(
                stringResource(R.string.teacher_student_since, link.createdAt.formatted()),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = { onIntent(StudentsIntent.RequestUnlink(link)) },
                enabled = !state.isUnlinking,
                modifier = Modifier.testTag(STUDENT_UNLINK_TAG).semantics { contentDescription = unlinkDesc }
            ) { Text(stringResource(R.string.teacher_student_unlink)) }
        }
    }
}

@Composable
private fun Empty(error: StudentsError?, onOpenCodes: () -> Unit) {
    Text(stringResource(R.string.teacher_students_empty), modifier = Modifier.testTag(STUDENTS_EMPTY_TAG))
    error?.let { ErrorText(it) }
    Button(onClick = onOpenCodes, modifier = Modifier.fillMaxWidth().testTag(STUDENTS_CODES_CTA_TAG)) {
        Text(stringResource(R.string.teacher_students_empty_cta))
    }
}

@Composable
private fun LoadFailed(onIntent: (StudentsIntent) -> Unit) {
    Text(
        stringResource(R.string.teacher_students_load_failed),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(STUDENTS_LOAD_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
    Button(onClick = { onIntent(StudentsIntent.Retry) }, modifier = Modifier.testTag(STUDENTS_RETRY_TAG)) {
        Text(stringResource(R.string.link_retry))
    }
}

@Composable
private fun ErrorText(error: StudentsError) {
    Text(
        stringResource(
            when (error) {
                StudentsError.Offline -> R.string.link_unlink_error_offline
                StudentsError.TemporarilyUnavailable -> R.string.link_unlink_error_temporarily_unavailable
                StudentsError.Unknown -> R.string.link_unlink_error_unknown
            }
        ),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(STUDENTS_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
}

@Composable
private fun UnlinkDialog(link: TeacherLink, busy: Boolean, onIntent: (StudentsIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(StudentsIntent.CancelUnlink) },
        modifier = Modifier.testTag(STUDENT_UNLINK_DIALOG_TAG),
        title = { Text(stringResource(R.string.teacher_unlink_dialog_title, link.studentDisplayName)) },
        text = { Text(stringResource(R.string.teacher_unlink_dialog_body)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(StudentsIntent.ConfirmUnlink) },
                enabled = !busy,
                modifier = Modifier.testTag(STUDENT_UNLINK_CONFIRM_TAG)
            ) { Text(stringResource(R.string.link_unlink_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(StudentsIntent.CancelUnlink) },
                enabled = !busy,
                modifier = Modifier.testTag(STUDENT_UNLINK_CANCEL_TAG)
            ) { Text(stringResource(R.string.link_unlink_cancel)) }
        }
    )
}
