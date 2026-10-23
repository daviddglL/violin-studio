package com.violinstudio.ui.feature.assignment.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.ui.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Fecha límite (solo día) según el [locale] y la [zone] del dispositivo. */
fun formatDueDate(instant: Instant, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

/** Día elegido -> inicio de ese día en la [zone] local (la fecha límite es un día, no una hora). */
fun dueAtOfLocalDate(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

fun localDateOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

/** Milisegundos UTC a medianoche del día, como los entiende el selector de fecha de Material. */
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
internal fun rememberLocale(): Locale {
    val configuration = LocalConfiguration.current
    // La lista de locales de Configuration nunca está vacía.
    return remember(configuration) { configuration.locales[0] }
}

@Composable
internal fun Assignment.statusLabel(): String = stringResource(
    if (status == AssignmentStatus.DONE) R.string.assignment_status_done else R.string.assignment_status_open
)

@Composable
internal fun dueLabel(dueAt: Instant?, zone: ZoneId): String {
    val locale = rememberLocale()
    return if (dueAt == null) {
        stringResource(R.string.assignment_no_due)
    } else {
        stringResource(R.string.assignment_due, formatDueDate(dueAt, locale, zone))
    }
}

@Composable
internal fun PendingBadge(tag: String) {
    Text(
        stringResource(R.string.assignment_pending_sync),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.testTag(tag)
    )
}

@Composable
internal fun AssignmentLoading(tag: String) {
    val loading = stringResource(R.string.link_loading)
    CircularProgressIndicator(
        Modifier.testTag(tag).semantics {
            contentDescription = loading
            liveRegion = LiveRegionMode.Polite
        }
    )
}

/** Texto de error o aviso con su acción de descartar; se anuncia al aparecer. */
@Composable
internal fun DismissibleMessage(
    text: String,
    tag: String,
    dismissTag: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).testTag(tag).semantics { liveRegion = LiveRegionMode.Assertive }
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) {
            Text(stringResource(R.string.assignment_dismiss))
        }
    }
}
