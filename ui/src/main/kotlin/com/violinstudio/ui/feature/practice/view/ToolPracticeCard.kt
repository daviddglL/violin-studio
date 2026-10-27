package com.violinstudio.ui.feature.practice.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.practice.viewmodel.ToolPracticeError
import com.violinstudio.ui.feature.practice.viewmodel.ToolPracticeUi

const val TOOL_PRACTICE_SAVE_TAG = "tool_practice_save"
const val TOOL_PRACTICE_DISMISS_TAG = "tool_practice_dismiss"

/** Oferta de guardar el tiempo de la herramienta como practica; no pinta nada si no hay oferta ni aviso. */
@Composable
fun ToolPracticeCard(ui: ToolPracticeUi, onSave: () -> Unit, onDismiss: () -> Unit) {
    val offer = ui.offer
    if (offer != null) {
        AuthMessage(pluralStringResource(R.plurals.tool_practice_offer, offer.minutes, offer.minutes), isError = false)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.testTag(TOOL_PRACTICE_SAVE_TAG)) {
                Text(stringResource(R.string.tool_practice_save))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TOOL_PRACTICE_DISMISS_TAG)) {
                Text(stringResource(R.string.tool_practice_dismiss))
            }
        }
    }
    ui.error?.let {
        val text = when (it) {
            ToolPracticeError.SESSION_RUNNING -> R.string.tool_practice_error_running
            ToolPracticeError.FAILED -> R.string.tool_practice_error
        }
        AuthMessage(stringResource(text), isError = true)
    }
    if (ui.saved) AuthMessage(stringResource(R.string.tool_practice_saved), isError = false)
}
