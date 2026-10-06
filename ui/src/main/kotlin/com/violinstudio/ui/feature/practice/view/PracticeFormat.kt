package com.violinstudio.ui.feature.practice.view

import android.content.res.Resources
import com.violinstudio.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

/** Cronómetro `H:MM:SS` (o `M:SS` bajo la hora): neutro, no depende del locale. */
fun formatClock(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val secs = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, secs)
    }
}

/** Duración legible según el idioma de los recursos: `1 h 2 min`, `5 min` o `45 s`. */
fun Resources.practiceDuration(seconds: Int): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    return when {
        hours > 0 -> getString(R.string.practice_duration_hours_minutes, hours, minutes)
        minutes > 0 -> getString(R.string.practice_duration_minutes, minutes)
        else -> getString(R.string.practice_duration_seconds, seconds)
    }
}

/** Fecha y hora cortas según el [locale] y la [zone] del dispositivo. */
fun formatPracticeDate(instant: Instant, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
        .format(instant)
