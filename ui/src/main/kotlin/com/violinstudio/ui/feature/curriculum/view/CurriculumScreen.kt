package com.violinstudio.ui.feature.curriculum.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.curriculum.model.LessonProgress
import com.violinstudio.domain.feature.curriculum.model.LessonTool
import com.violinstudio.domain.feature.curriculum.model.LevelProgress
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumIntent
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumMessage
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumState
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumViewModel

const val CURRICULUM_TAG = "curriculum"
const val CURRICULUM_BACK_TAG = "curriculum_back"
const val CURRICULUM_SUMMARY_TAG = "curriculum_summary"
const val CURRICULUM_MESSAGE_TAG = "curriculum_message"

fun lessonTag(id: String) = "lesson_$id"

fun lessonDoneTag(id: String) = "lesson_done_$id"

fun lessonToolTag(id: String) = "lesson_tool_$id"

fun lessonIntonationTag(id: String) = "lesson_intonation_$id"

/** Accesos a las herramientas desde una leccion; [onOpenIntonation] recibe el id del ejercicio de afinacion. */
data class CurriculumNavigation(
    val onOpenTuner: () -> Unit,
    val onOpenMetronome: () -> Unit,
    val onBack: () -> Unit,
    val onOpenIntonation: (String) -> Unit = {}
)

@Composable
fun CurriculumRoute(navigation: CurriculumNavigation, viewModel: CurriculumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CurriculumScreen(state = state, onIntent = viewModel::onIntent, navigation = navigation)
}

@Composable
fun CurriculumScreen(state: CurriculumState, onIntent: (CurriculumIntent) -> Unit, navigation: CurriculumNavigation) {
    AuthScaffold(CURRICULUM_TAG, stringResource(R.string.curriculum_title)) {
        val progress = state.progress
        if (progress == null) {
            LoadingIndicator()
        } else {
            Text(
                stringResource(R.string.curriculum_summary, progress.done, progress.total),
                modifier = Modifier.testTag(CURRICULUM_SUMMARY_TAG)
            )
            val next = progress.next?.let { lessonTexts(it.id) }
            Text(
                if (next != null) {
                    stringResource(R.string.curriculum_next, stringResource(next.title))
                } else {
                    stringResource(R.string.curriculum_all_done)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            state.message?.let { message ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag(CURRICULUM_MESSAGE_TAG)
                ) {
                    Text(
                        when (message) {
                            CurriculumMessage.SAVE_FAILED -> stringResource(R.string.curriculum_save_failed)
                        },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onIntent(CurriculumIntent.DismissMessage) }) {
                        Text(stringResource(R.string.curriculum_dismiss))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
                progress.levels.forEach { level -> LevelSection(level, state.expandedId, onIntent, navigation) }
            }
        }
        TextButton(onClick = navigation.onBack, modifier = Modifier.testTag(CURRICULUM_BACK_TAG)) {
            Text(stringResource(R.string.curriculum_back))
        }
    }
}

@Composable
private fun LevelSection(
    level: LevelProgress,
    expandedId: String?,
    onIntent: (CurriculumIntent) -> Unit,
    navigation: CurriculumNavigation
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(levelTitle(level.level)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        val levelDone = stringResource(R.string.curriculum_level_progress, level.done, level.total)
        LinearProgressIndicator(
            progress = { if (level.total == 0) 0f else level.done.toFloat() / level.total },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = levelDone }
        )
        Text(levelDone, style = MaterialTheme.typography.bodySmall)
        level.lessons.forEach { lesson ->
            lessonTexts(lesson.lesson.id)?.let { texts ->
                LessonCard(lesson, texts, expandedId == lesson.lesson.id, onIntent, navigation)
            }
        }
    }
}

@Composable
private fun LessonCard(
    item: LessonProgress,
    texts: LessonTexts,
    expanded: Boolean,
    onIntent: (CurriculumIntent) -> Unit,
    navigation: CurriculumNavigation
) {
    val id = item.lesson.id
    val title = stringResource(texts.title)
    val doneLabel =
        stringResource(if (item.done) R.string.curriculum_lesson_done else R.string.curriculum_lesson_pending)
    Card(Modifier.fillMaxWidth().testTag(lessonTag(id))) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.curriculum_open_lesson)) {
                        onIntent(CurriculumIntent.Toggle(id))
                    }
                    .semantics { stateDescription = doneLabel }
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.curriculum_minutes, item.lesson.minutes) + " · " + doneLabel,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (expanded) {
                Text(stringResource(texts.goal), style = MaterialTheme.typography.bodyMedium)
                stringResource(texts.steps).split(" · ").forEach { step ->
                    Text("• $step", style = MaterialTheme.typography.bodyMedium)
                }
                ToolButton(id, item.lesson.tool, navigation)
                IntonationExercises.forLesson(id)?.let { exercise ->
                    OutlinedButton(
                        onClick = { navigation.onOpenIntonation(exercise.id) },
                        modifier = Modifier.testTag(lessonIntonationTag(id))
                    ) {
                        Text(stringResource(R.string.curriculum_open_intonation))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.done,
                        onCheckedChange = { onIntent(CurriculumIntent.SetDone(id, it)) },
                        modifier = Modifier.testTag(lessonDoneTag(id))
                    )
                    Text(stringResource(R.string.curriculum_mark_done))
                }
            }
        }
    }
}

@Composable
private fun ToolButton(id: String, tool: LessonTool, navigation: CurriculumNavigation) {
    when (tool) {
        LessonTool.None -> Unit
        LessonTool.Tuner -> OutlinedButton(
            onClick = navigation.onOpenTuner,
            modifier = Modifier.testTag(lessonToolTag(id))
        ) {
            Text(stringResource(R.string.curriculum_open_tuner))
        }
        is LessonTool.Metronome -> OutlinedButton(
            onClick = navigation.onOpenMetronome,
            modifier = Modifier.testTag(lessonToolTag(id))
        ) {
            Text(stringResource(R.string.curriculum_open_metronome, tool.bpm))
        }
    }
}
