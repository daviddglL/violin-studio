package com.violinstudio.ui.feature.link.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherError
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherIntent
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherState
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val MY_TEACHER_TAG = "my_teacher"
const val MY_TEACHER_LOADING_TAG = "my_teacher_loading"
const val MY_TEACHER_NAME_TAG = "my_teacher_name"
const val MY_TEACHER_NONE_TAG = "my_teacher_none"
const val MY_TEACHER_UNLINK_TAG = "my_teacher_unlink"
const val MY_TEACHER_ERROR_TAG = "my_teacher_error"
const val MY_TEACHER_BACK_TAG = "my_teacher_back"
const val UNLINK_DIALOG_TAG = "unlink_dialog"
const val UNLINK_CONFIRM_TAG = "unlink_confirm"
const val UNLINK_CANCEL_TAG = "unlink_cancel"

/** El rol lo comprueba la navegación; al desvincular el rol cambia y la navegación saca al usuario de aquí. */
@Composable
fun MyTeacherRoute(onBack: () -> Unit, viewModel: MyTeacherViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyTeacherScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun MyTeacherScreen(state: MyTeacherState, onIntent: (MyTeacherIntent) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag(MY_TEACHER_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(MY_TEACHER_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.link_my_teacher_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            val teacher = state.teacher
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.testTag(MY_TEACHER_LOADING_TAG))
                teacher == null -> Text(
                    stringResource(R.string.link_my_teacher_none),
                    modifier = Modifier.testTag(MY_TEACHER_NONE_TAG)
                )
                else -> TeacherContent(teacher, state, onIntent)
            }
        }
    }
    if (state.confirmingUnlink) UnlinkDialog(state, onIntent)
}

@Composable
private fun TeacherContent(teacher: TeacherLink, state: MyTeacherState, onIntent: (MyTeacherIntent) -> Unit) {
    val name = stringResource(R.string.link_my_teacher_name, teacher.teacherDisplayName)
    Text(
        name,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag(MY_TEACHER_NAME_TAG).semantics { contentDescription = name }
    )
    Text(stringResource(R.string.link_my_teacher_since, teacher.createdAt.formatted()))
    Text(stringResource(R.string.link_my_teacher_sees), style = MaterialTheme.typography.bodyMedium)
    state.error?.let {
        Text(
            it.message(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(MY_TEACHER_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
        )
    }
    Button(
        onClick = { onIntent(MyTeacherIntent.RequestUnlink) },
        enabled = !state.isUnlinking,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        modifier = Modifier.fillMaxWidth().testTag(MY_TEACHER_UNLINK_TAG)
    ) { Text(stringResource(R.string.link_my_teacher_unlink)) }
}

@Composable
private fun UnlinkDialog(state: MyTeacherState, onIntent: (MyTeacherIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(MyTeacherIntent.CancelUnlink) },
        modifier = Modifier.testTag(UNLINK_DIALOG_TAG),
        title = { Text(stringResource(R.string.link_unlink_dialog_title)) },
        text = { Text(stringResource(R.string.link_unlink_dialog_body)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(MyTeacherIntent.ConfirmUnlink) },
                enabled = !state.isUnlinking,
                modifier = Modifier.testTag(UNLINK_CONFIRM_TAG)
            ) { Text(stringResource(R.string.link_unlink_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(MyTeacherIntent.CancelUnlink) },
                enabled = !state.isUnlinking,
                modifier = Modifier.testTag(UNLINK_CANCEL_TAG)
            ) { Text(stringResource(R.string.link_unlink_cancel)) }
        }
    )
}

@Composable
private fun MyTeacherError.message(): String = when (this) {
    MyTeacherError.Offline -> stringResource(R.string.link_unlink_error_offline)
    MyTeacherError.TemporarilyUnavailable -> stringResource(R.string.link_unlink_error_temporarily_unavailable)
    MyTeacherError.Unknown -> stringResource(R.string.link_unlink_error_unknown)
}

@Composable
private fun java.time.Instant.formatted(): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        .format(atZone(ZoneId.systemDefault()))
}
