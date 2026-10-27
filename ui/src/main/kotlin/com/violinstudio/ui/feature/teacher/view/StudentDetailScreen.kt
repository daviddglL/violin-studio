package com.violinstudio.ui.feature.teacher.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.ObserveAsEvents
import com.violinstudio.ui.feature.curriculum.view.lessonTexts
import com.violinstudio.ui.feature.practice.view.SessionItem
import com.violinstudio.ui.feature.practice.view.practiceDuration
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailEffect
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailIntent
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailState
import com.violinstudio.ui.feature.teacher.viewmodel.StudentDetailViewModel
import java.time.ZoneId

const val STUDENT_DETAIL_TAG = "student_detail"
const val STUDENT_DETAIL_BACK_TAG = "student_detail_back"
const val STUDENT_DETAIL_NAME_TAG = "student_detail_name"
const val STUDENT_DETAIL_WEEKLY_TAG = "student_detail_weekly"
const val STUDENT_DETAIL_CURRICULUM_TAG = "student_detail_curriculum"
const val STUDENT_DETAIL_EMPTY_TAG = "student_detail_empty"
const val STUDENT_DETAIL_LOADING_TAG = "student_detail_loading"
const val STUDENT_DETAIL_UNAVAILABLE_TAG = "student_detail_unavailable"
const val STUDENT_DETAIL_LOAD_FAILED_TAG = "student_detail_load_failed"
const val STUDENT_DETAIL_ASSIGNMENTS_TAG = "student_detail_assignments"
const val STUDENT_DETAIL_RETRY_TAG = "student_detail_retry"
const val STUDENT_DETAIL_RECORDINGS_TAG = "student_detail_recordings"
const val STUDENT_DETAIL_CHAT_TAG = "student_detail_chat"

/**
 * El rol lo comprueba la navegación antes de componer esta ruta. Si el vínculo se pierde, el ViewModel vacía la vista y
 * pide volver a la lista.
 */
@Composable
fun StudentDetailRoute(
    onOpenAssignments: () -> Unit,
    onOpenRecordings: (() -> Unit)?,
    onBack: () -> Unit,
    onOpenChat: (() -> Unit)? = null,
    viewModel: StudentDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            StudentDetailEffect.NavigateBack -> onBack()
        }
    }
    StudentDetailScreen(
        state,
        viewModel::onIntent,
        onBack,
        onOpenAssignments,
        onOpenRecordings,
        onOpenChat = onOpenChat
    )
}

/** Solo lectura (REQ-TVW-04): ni editar, ni borrar, ni crear sesiones; la zona se inyecta para fechas deterministas. */
@Composable
fun StudentDetailScreen(
    state: StudentDetailState,
    onIntent: (StudentDetailIntent) -> Unit,
    onBack: () -> Unit,
    onOpenAssignments: () -> Unit = {},
    /** Nulo: sin el boton de grabaciones (las capturas de esta pantalla son anteriores a la fase 4b). */
    onOpenRecordings: (() -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Nulo: sin el boton del chat (las capturas de esta pantalla son anteriores a la fase 4c). */
    onOpenChat: (() -> Unit)? = null
) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val weekly = remember(state.weeklyTotalSec, configuration) { resources.practiceDuration(state.weeklyTotalSec) }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(STUDENT_DETAIL_TAG),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.testTag(STUDENT_DETAIL_BACK_TAG)) {
                    Text(stringResource(R.string.link_back))
                }
            }
            when {
                state.isLoading -> item { Loading() }

                state.unavailable -> item {
                    Text(
                        stringResource(R.string.teacher_student_detail_unavailable),
                        modifier = Modifier.testTag(STUDENT_DETAIL_UNAVAILABLE_TAG)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                state.loadFailed -> item { LoadFailed(onIntent) }

                else -> content(state, weekly, zone, onOpenAssignments, onOpenRecordings, onOpenChat)
            }
        }
    }
}

private fun LazyListScope.content(
    state: StudentDetailState,
    weekly: String,
    zone: ZoneId,
    onOpenAssignments: () -> Unit,
    onOpenRecordings: (() -> Unit)?,
    onOpenChat: (() -> Unit)?
) {
    state.link?.let { link ->
        item {
            Text(
                link.studentDisplayName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(STUDENT_DETAIL_NAME_TAG).semantics { heading() }
            )
        }
    }
    item {
        Text(
            stringResource(R.string.teacher_student_detail_readonly),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    item {
        Button(
            onClick = onOpenAssignments,
            modifier = Modifier.fillMaxWidth().testTag(STUDENT_DETAIL_ASSIGNMENTS_TAG)
        ) { Text(stringResource(R.string.assignment_teacher_open)) }
    }
    onOpenRecordings?.let { open ->
        item {
            OutlinedButton(
                onClick = open,
                modifier = Modifier.fillMaxWidth().testTag(STUDENT_DETAIL_RECORDINGS_TAG)
            ) { Text(stringResource(R.string.student_recordings_open)) }
        }
    }
    onOpenChat?.let { open ->
        item {
            OutlinedButton(
                onClick = open,
                modifier = Modifier.fillMaxWidth().testTag(STUDENT_DETAIL_CHAT_TAG)
            ) { Text(stringResource(R.string.chat_open)) }
        }
    }
    state.curriculum?.let { progress ->
        item {
            Column(Modifier.testTag(STUDENT_DETAIL_CURRICULUM_TAG)) {
                Text(stringResource(R.string.teacher_student_curriculum, progress.done, progress.total))
                val next = progress.next?.let { lessonTexts(it.id) }
                Text(
                    if (next != null) {
                        stringResource(R.string.curriculum_next, stringResource(next.title))
                    } else {
                        stringResource(R.string.teacher_student_curriculum_all_done)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    item {
        Text(
            stringResource(R.string.practice_weekly_total, weekly),
            modifier = Modifier.testTag(STUDENT_DETAIL_WEEKLY_TAG)
        )
    }
    if (state.sessions.isEmpty()) {
        item {
            Text(
                stringResource(R.string.teacher_student_detail_empty),
                modifier = Modifier.testTag(STUDENT_DETAIL_EMPTY_TAG)
            )
        }
    }
    items(state.sessions, key = { it.id }) { session ->
        HorizontalDivider()
        SessionItem(session, zone)
    }
}

@Composable
private fun Loading() {
    val loading = stringResource(R.string.link_loading)
    CircularProgressIndicator(
        Modifier.testTag(STUDENT_DETAIL_LOADING_TAG).semantics {
            contentDescription = loading
            liveRegion = LiveRegionMode.Polite
        }
    )
}

@Composable
private fun LoadFailed(onIntent: (StudentDetailIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.teacher_student_detail_load_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(STUDENT_DETAIL_LOAD_FAILED_TAG)
                .semantics { liveRegion = LiveRegionMode.Assertive }
        )
        Button(
            onClick = { onIntent(StudentDetailIntent.Retry) },
            modifier = Modifier.testTag(STUDENT_DETAIL_RETRY_TAG)
        ) { Text(stringResource(R.string.link_retry)) }
    }
}
