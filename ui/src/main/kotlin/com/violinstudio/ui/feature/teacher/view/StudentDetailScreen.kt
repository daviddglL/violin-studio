package com.violinstudio.ui.feature.teacher.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
const val STUDENT_DETAIL_EMPTY_TAG = "student_detail_empty"
const val STUDENT_DETAIL_LOADING_TAG = "student_detail_loading"
const val STUDENT_DETAIL_UNAVAILABLE_TAG = "student_detail_unavailable"
const val STUDENT_DETAIL_LOAD_FAILED_TAG = "student_detail_load_failed"
const val STUDENT_DETAIL_RETRY_TAG = "student_detail_retry"

/**
 * El rol lo comprueba la navegación antes de componer esta ruta. Si el vínculo se pierde, el ViewModel vacía la vista y
 * pide volver a la lista.
 */
@Composable
fun StudentDetailRoute(onBack: () -> Unit, viewModel: StudentDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            StudentDetailEffect.NavigateBack -> onBack()
        }
    }
    StudentDetailScreen(state, viewModel::onIntent, onBack)
}

/** Solo lectura (REQ-TVW-04): ni editar, ni borrar, ni crear sesiones; la zona se inyecta para fechas deterministas. */
@Composable
fun StudentDetailScreen(
    state: StudentDetailState,
    onIntent: (StudentDetailIntent) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
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
                    )
                }
                state.loadFailed -> item { LoadFailed(onIntent) }
                else -> content(state, weekly, zone)
            }
        }
    }
}

private fun LazyListScope.content(state: StudentDetailState, weekly: String, zone: ZoneId) {
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
