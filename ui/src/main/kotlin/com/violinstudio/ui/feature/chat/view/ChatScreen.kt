package com.violinstudio.ui.feature.chat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.domain.feature.chat.model.ChatRules
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.components.LoadingIndicator
import com.violinstudio.ui.feature.chat.viewmodel.ChatError
import com.violinstudio.ui.feature.chat.viewmodel.ChatIntent
import com.violinstudio.ui.feature.chat.viewmodel.ChatState
import com.violinstudio.ui.feature.chat.viewmodel.ChatViewModel

const val CHAT_TAG = "chat"
const val CHAT_BACK_TAG = "chat_back"
const val CHAT_DISABLED_TAG = "chat_disabled"
const val CHAT_FAILED_TAG = "chat_failed"
const val CHAT_RETRY_TAG = "chat_retry"
const val CHAT_EMPTY_TAG = "chat_empty"
const val CHAT_INPUT_TAG = "chat_input"
const val CHAT_SEND_TAG = "chat_send"
const val CHAT_ERROR_TAG = "chat_error"
const val CHAT_DELETE_DIALOG_TAG = "chat_delete_dialog"
const val CHAT_DELETE_CONFIRM_TAG = "chat_delete_confirm"
const val CHAT_DELETE_CANCEL_TAG = "chat_delete_cancel"

fun chatMessageTag(id: String) = "chat_message_$id"
fun chatDeleteTag(id: String) = "chat_delete_$id"

/** El rol lo comprueba la navegación; el vínculo y el consentimiento, las reglas. */
@Composable
fun ChatRoute(onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun ChatScreen(state: ChatState, onIntent: (ChatIntent) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().padding(16.dp).testTag(CHAT_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(CHAT_BACK_TAG)) {
                Text(stringResource(R.string.link_back))
            }
            Text(
                stringResource(R.string.chat_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            when {
                state.isLoading -> LoadingIndicator()

                state.disabled -> Text(
                    stringResource(R.string.chat_disabled),
                    modifier = Modifier.testTag(CHAT_DISABLED_TAG)
                )

                state.loadFailed -> {
                    Text(
                        stringResource(R.string.chat_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(CHAT_FAILED_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    OutlinedButton(
                        onClick = { onIntent(ChatIntent.Retry) },
                        modifier = Modifier.testTag(CHAT_RETRY_TAG)
                    ) {
                        Text(stringResource(R.string.link_retry))
                    }
                }

                else -> Conversation(state, onIntent, Modifier.weight(1f))
            }
        }
    }
    if (state.confirmingDelete != null) DeleteDialog(onIntent)
}

@Composable
private fun Conversation(state: ChatState, onIntent: (ChatIntent) -> Unit, modifier: Modifier) {
    Text(stringResource(R.string.chat_retention), style = MaterialTheme.typography.bodySmall)
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.messages.isEmpty()) {
            item { Text(stringResource(R.string.chat_empty), modifier = Modifier.testTag(CHAT_EMPTY_TAG)) }
        }
        items(state.messages, key = { it.id }) { message ->
            Bubble(message, mine = message.senderUid == state.myUid, onIntent)
        }
    }
    state.error?.let {
        Text(
            it.message(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CHAT_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Assertive }
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { onIntent(ChatIntent.DraftChanged(it)) },
            label = { Text(stringResource(R.string.chat_input_label)) },
            supportingText = { Text("${state.draft.length}/${ChatRules.MAX_TEXT}") },
            maxLines = 4,
            modifier = Modifier.weight(1f).testTag(CHAT_INPUT_TAG)
        )
        Button(
            onClick = { onIntent(ChatIntent.Send) },
            enabled = state.canSend,
            modifier = Modifier.testTag(CHAT_SEND_TAG)
        ) { Text(stringResource(R.string.chat_send)) }
    }
}

@Composable
private fun Bubble(message: ChatMessage, mine: Boolean, onIntent: (ChatIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = if (mine) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
            modifier = Modifier.widthIn(max = 300.dp).testTag(chatMessageTag(message.id))
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(if (mine) R.string.chat_from_me else R.string.chat_from_other),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(message.text)
                if (message.pendingSync) {
                    Text(stringResource(R.string.chat_pending), style = MaterialTheme.typography.labelSmall)
                }
                if (mine) {
                    TextButton(
                        onClick = { onIntent(ChatIntent.RequestDelete(message.id)) },
                        modifier = Modifier.testTag(chatDeleteTag(message.id))
                    ) { Text(stringResource(R.string.chat_delete)) }
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(onIntent: (ChatIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(ChatIntent.CancelDelete) },
        modifier = Modifier.testTag(CHAT_DELETE_DIALOG_TAG),
        title = { Text(stringResource(R.string.chat_delete_title)) },
        text = { Text(stringResource(R.string.chat_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = { onIntent(ChatIntent.ConfirmDelete) },
                modifier = Modifier.testTag(CHAT_DELETE_CONFIRM_TAG)
            ) { Text(stringResource(R.string.chat_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(ChatIntent.CancelDelete) },
                modifier = Modifier.testTag(CHAT_DELETE_CANCEL_TAG)
            ) { Text(stringResource(R.string.link_unlink_cancel)) }
        }
    )
}

@Composable
private fun ChatError.message(): String = stringResource(
    when (this) {
        ChatError.TooLong -> R.string.chat_error_too_long
        ChatError.Denied -> R.string.chat_error_denied
        ChatError.Unknown -> R.string.chat_error_unknown
    }
)
