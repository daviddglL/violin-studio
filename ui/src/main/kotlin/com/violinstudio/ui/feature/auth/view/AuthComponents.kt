@file:OptIn(ExperimentalComposeUiApi::class)

package com.violinstudio.ui.feature.auth.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.auth.GoogleIdTokenResult
import com.violinstudio.ui.commons.auth.LocalGoogleIdTokenRequester
import kotlinx.coroutines.launch

/** Estructura común de las pantallas de acceso: fondo del tema, scroll en pantallas pequeñas y título. */
@Composable
internal fun AuthScaffold(tag: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag(tag),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** El campo de contraseña va enmascarado y con teclado de contraseña; nunca se muestra su valor. */
@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    tag: String,
    autofillTypes: List<AutofillType>,
    isPassword: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Email,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth().autofill(autofillTypes, onValueChange).testTag(tag)
    )
    Spacer(Modifier.height(8.dp))
}

/** Mensaje anunciado por los lectores de pantalla: asertivo si es un error, cortés si informa. */
@Composable
internal fun AuthMessage(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 8.dp).testTag(AUTH_MESSAGE_TAG).semantics {
            liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
        }
    )
}

/** Sin indicador infinito: bloquearía la sincronización de los tests de UI. */
@Composable
internal fun AuthSubmitButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(AUTH_SUBMIT_TAG)) {
        Text(label)
    }
}

/**
 * Declara qué rellena el gestor de contraseñas en este campo (Compose 1.7 aún no expone `ContentType` público). El
 * valor rellenado entra por el mismo callback que lo escrito a mano, es decir, como un intent más.
 */
@Composable
private fun Modifier.autofill(types: List<AutofillType>, onFill: (String) -> Unit): Modifier {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val latest by rememberUpdatedState(onFill)
    val node = remember(types) { AutofillNode(autofillTypes = types, onFill = { latest(it) }) }
    DisposableEffect(node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    return onGloballyPositioned { node.boundingBox = it.boundsInWindow() }.onFocusChanged {
        if (it.isFocused) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node)
    }
}

/**
 * Pide el token a Credential Manager (a través del solicitante de `CompositionLocal`, que necesita la Activity). Cancelar
 * la hoja no avisa a nadie: no es un error ni cambia nada.
 */
@Composable
internal fun GoogleSignInButton(enabled: Boolean, onToken: (GoogleIdToken) -> Unit, onFailed: () -> Unit) {
    val requester = LocalGoogleIdTokenRequester.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inFlight by remember { mutableStateOf(false) }
    val currentOnToken by rememberUpdatedState(onToken)
    val currentOnFailed by rememberUpdatedState(onFailed)
    OutlinedButton(
        onClick = {
            // Un segundo toque mientras la hoja de Google sigue abierta no lanza otra petición.
            if (!inFlight) {
                inFlight = true
                scope.launch {
                    try {
                        when (val result = requester.request(context.findActivity())) {
                            is GoogleIdTokenResult.Token -> currentOnToken(result.token)
                            GoogleIdTokenResult.Cancelled -> Unit
                            GoogleIdTokenResult.ProviderUnavailable -> currentOnFailed()
                        }
                    } finally {
                        inFlight = false
                    }
                }
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(AUTH_GOOGLE_TAG)
    ) {
        Text(stringResource(R.string.auth_google_button))
    }
}

// Credential Manager necesita la Activity, no un wrapper de tema; sin Activity (tests) se pasa el contexto tal cual.
private tailrec fun Context.findActivity(): Context = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> this
}
