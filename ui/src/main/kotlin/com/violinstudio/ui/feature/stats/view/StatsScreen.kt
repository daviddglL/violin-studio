package com.violinstudio.ui.feature.stats.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.achievement.model.Achievement
import com.violinstudio.domain.feature.achievement.model.AchievementProgress
import com.violinstudio.domain.feature.practice.model.DayTotal
import com.violinstudio.domain.feature.practice.model.PracticeStats
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.practice.view.practiceDuration
import com.violinstudio.ui.feature.stats.viewmodel.StatsIntent
import com.violinstudio.ui.feature.stats.viewmodel.StatsState
import com.violinstudio.ui.feature.stats.viewmodel.StatsViewModel
import java.time.format.TextStyle
import java.util.Locale

const val STATS_TAG = "stats"
const val STATS_BACK_TAG = "stats_back"
const val STATS_RETRY_TAG = "stats_retry"
const val STATS_STREAK_TAG = "stats_streak"
const val STATS_WEEK_TAG = "stats_week"
const val STATS_TRUNCATED_TAG = "stats_truncated"
const val STATS_EMPTY_TAG = "stats_empty"
const val STATS_ACHIEVEMENTS_TAG = "stats_achievements"

fun statsDayTag(index: Int) = "stats_day_$index"

fun achievementTag(achievement: Achievement) = "achievement_${achievement.name.lowercase()}"

private val chartHeight = 120.dp

@Composable
fun StatsRoute(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle()
    StatsScreen(state = state.value, onIntent = viewModel::onIntent, onBack = onBack)
}

@Composable
fun StatsScreen(state: StatsState, onIntent: (StatsIntent) -> Unit, onBack: () -> Unit) {
    AuthScaffold(STATS_TAG, stringResource(R.string.stats_title)) {
        val stats = state.stats
        when {
            state.loading -> LoadingIndicator()
            state.failed || stats == null -> {
                Text(stringResource(R.string.stats_error))
                Button(onClick = { onIntent(StatsIntent.Retry) }, modifier = Modifier.testTag(STATS_RETRY_TAG)) {
                    Text(stringResource(R.string.stats_retry))
                }
            }
            stats.sessionCount == 0 -> Text(
                stringResource(R.string.stats_empty),
                modifier = Modifier.testTag(STATS_EMPTY_TAG)
            )
            else -> StatsContent(stats)
        }
        if (!state.loading && !state.failed && stats != null && state.achievements.isNotEmpty()) {
            AchievementsSection(state.achievements)
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag(STATS_BACK_TAG)) {
            Text(stringResource(R.string.stats_back))
        }
    }
}

@Composable
private fun StatsContent(stats: PracticeStats) {
    val resources = LocalContext.current.resources
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            title = stringResource(R.string.stats_streak_title),
            value = stats.currentStreakDays.toString(),
            detail = stringResource(R.string.stats_streak_best, stats.longestStreakDays),
            tag = STATS_STREAK_TAG
        )
        StatCard(
            title = stringResource(R.string.stats_week_title),
            value = resources.practiceDuration(stats.weekSec),
            detail = stringResource(R.string.stats_week_previous, resources.practiceDuration(stats.previousWeekSec)),
            tag = STATS_WEEK_TAG
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.stats_last_7_days), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                WeekChart(stats.last7Days)
            }
        }
        StatCard(
            title = stringResource(R.string.stats_last_30_days),
            value = resources.practiceDuration(stats.last30DaysSec),
            detail = stringResource(
                R.string.stats_sessions_summary,
                stats.sessionCount,
                resources.practiceDuration(stats.averageSessionSec)
            )
        )
        if (stats.truncated) {
            Text(
                stringResource(R.string.stats_truncated),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(STATS_TRUNCATED_TAG)
            )
        }
    }
}

/** Logros calculados en el dispositivo; cada fila se lee entera (titulo, descripcion y estado). */
@Composable
private fun AchievementsSection(achievements: List<AchievementProgress>) {
    Column(
        Modifier.fillMaxWidth().testTag(STATS_ACHIEVEMENTS_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.achievements_title), style = MaterialTheme.typography.titleMedium)
        achievements.forEach { AchievementRow(it) }
    }
}

@Composable
private fun AchievementRow(progress: AchievementProgress) {
    val achievement = progress.achievement
    val status = if (progress.unlocked) {
        stringResource(R.string.achievement_unlocked)
    } else {
        stringResource(R.string.achievement_progress, progress.current, achievement.target)
    }
    Card(Modifier.fillMaxWidth().testTag(achievementTag(achievement)).semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(achievement.title(), style = MaterialTheme.typography.titleSmall)
                Text(achievement.description(), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = if (progress.unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun Achievement.title(): String = stringResource(
    when (this) {
        Achievement.FIRST_SESSION -> R.string.achievement_first_session
        Achievement.SESSIONS_10 -> R.string.achievement_sessions_10
        Achievement.SESSIONS_50 -> R.string.achievement_sessions_50
        Achievement.STREAK_3 -> R.string.achievement_streak_3
        Achievement.STREAK_7 -> R.string.achievement_streak_7
        Achievement.STREAK_30 -> R.string.achievement_streak_30
        Achievement.HOURS_1 -> R.string.achievement_hours_1
        Achievement.HOURS_10 -> R.string.achievement_hours_10
        Achievement.FIRST_LESSON -> R.string.achievement_first_lesson
        Achievement.ALL_LESSONS -> R.string.achievement_all_lessons
    }
)

@Composable
private fun Achievement.description(): String = when (this) {
    Achievement.FIRST_SESSION -> stringResource(R.string.achievement_first_session_desc)
    Achievement.SESSIONS_10, Achievement.SESSIONS_50 -> stringResource(R.string.achievement_sessions_desc, target)
    Achievement.STREAK_3, Achievement.STREAK_7, Achievement.STREAK_30 ->
        stringResource(R.string.achievement_streak_desc, target)
    Achievement.HOURS_1 -> stringResource(R.string.achievement_hours_1_desc)
    Achievement.HOURS_10 -> stringResource(R.string.achievement_hours_desc, target)
    Achievement.FIRST_LESSON -> stringResource(R.string.achievement_first_lesson_desc)
    Achievement.ALL_LESSONS -> stringResource(R.string.achievement_all_lessons_desc, target)
}

@Composable
private fun StatCard(title: String, value: String, detail: String, tag: String? = null) {
    Card(Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it }) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Barras de los ultimos 7 dias, proporcionales al dia con mas practica; cada barra lleva su lectura accesible. */
@Composable
private fun WeekChart(days: List<DayTotal>) {
    val resources = LocalContext.current.resources
    val locale: Locale = LocalConfiguration.current.locales[0]
    val max = days.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().height(chartHeight + 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEachIndexed { index, day ->
            val weekday = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val spoken = "${day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}: " +
                resources.practiceDuration(day.seconds)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(statsDayTag(index))
                    .clearAndSetSemantics { contentDescription = spoken },
                verticalArrangement = Arrangement.Bottom
            ) {
                val fraction = day.seconds.toFloat() / max
                Box(
                    Modifier.fillMaxWidth(0.6f)
                        .height(chartHeight * fraction.coerceAtLeast(if (day.seconds > 0) 0.04f else 0f))
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
                Text(weekday, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
