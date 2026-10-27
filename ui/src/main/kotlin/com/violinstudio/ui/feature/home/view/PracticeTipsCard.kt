package com.violinstudio.ui.feature.home.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.curriculum.view.lessonTexts

const val HOME_TIPS_TAG = "home_tips"

/** Tarjeta de consejos de inicio; sin consejos no se muestra. */
@Composable
fun PracticeTipsCard(tips: List<PracticeTip>, modifier: Modifier = Modifier) {
    if (tips.isEmpty()) return
    Card(modifier.fillMaxWidth().testTag(HOME_TIPS_TAG)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_tips_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            tips.forEach { tip -> tipText(tip)?.let { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}

/** `null` para una leccion que la UI no conoce. */
@Composable
private fun tipText(tip: PracticeTip): String? = when (tip) {
    PracticeTip.FirstSession -> stringResource(R.string.tip_first_session)

    PracticeTip.ComeBack -> stringResource(R.string.tip_come_back)

    is PracticeTip.KeepStreak -> pluralStringResource(R.plurals.tip_keep_streak, tip.days, tip.days)

    is PracticeTip.CatchUpWeek -> pluralStringResource(R.plurals.tip_catch_up_week, tip.minutes, tip.minutes)

    is PracticeTip.LongerSessions ->
        pluralStringResource(R.plurals.tip_longer_sessions, tip.averageMinutes, tip.averageMinutes)

    is PracticeTip.NextLesson -> lessonTexts(tip.lesson.id)?.let {
        stringResource(R.string.tip_next_lesson, stringResource(it.title), tip.lesson.minutes)
    }
}
