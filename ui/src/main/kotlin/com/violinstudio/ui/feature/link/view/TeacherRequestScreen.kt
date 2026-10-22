package com.violinstudio.ui.feature.link.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestError
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestIntent
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestState
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestViewModel

const val TEACHER_REQUEST_TAG = "teacher_request"
const val TEACHER_REQUEST_SUBMIT_TAG = "teacher_request_submit"
const val TEACHER_REQUEST_STATUS_TAG = "teacher_request_status"
const val TEACHER_REQUEST_ERROR_TAG = "teacher_request_error"
const val TEACHER_REQUEST_BACK_TAG = "teacher_request_back"

/** El rol (`independent`, adulto) lo comprueba la navegación antes de componer esta ruta. */
@Composable
fun TeacherRequestRoute(onBack: () -> Unit, viewModel: TeacherRequestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TeacherRequestScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun TeacherRequestScreen(state: TeacherRequestState, onIntent: (TeacherRequestIntent) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(TEACHER_REQUEST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TEACHER_REQUEST_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.teacher_request_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.teacher_request_intro), style = MaterialTheme.typography.bodyLarge)
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.teacher_request_review_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            state.status?.let { StatusText(it) }
            state.error?.let { ErrorText(it) }
            if (state.status == null) {
                Button(
                    onClick = { onIntent(TeacherRequestIntent.Submit) },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag(TEACHER_REQUEST_SUBMIT_TAG)
                ) {
                    val label = if (state.isSending) {
                        R.string.teacher_request_sending
                    } else {
                        R.string.teacher_request_submit
                    }
                    Text(stringResource(label))
                }
            }
        }
    }
}

@Composable
private fun StatusText(status: TeacherRequestStatus) {
    val text = when (status) {
        TeacherRequestStatus.PENDING -> R.string.teacher_request_pending
        TeacherRequestStatus.APPROVED -> R.string.teacher_request_approved
        TeacherRequestStatus.REJECTED -> R.string.teacher_request_rejected
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(TEACHER_REQUEST_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun ErrorText(error: TeacherRequestError) {
    val text = when (error) {
        TeacherRequestError.NotEligible -> R.string.teacher_request_not_eligible
        TeacherRequestError.Offline -> R.string.link_error_offline
        TeacherRequestError.Unknown -> R.string.link_error_unknown
    }
    Text(
        stringResource(text),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(TEACHER_REQUEST_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
    )
}
