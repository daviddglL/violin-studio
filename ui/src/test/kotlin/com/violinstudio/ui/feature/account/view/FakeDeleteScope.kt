package com.violinstudio.ui.feature.account.view

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

const val FAKE_DELETE_TAG = "fake_delete_entry"

/**
 * Sustituto del flujo compartido para probar pantallas sueltas: pinta un boton con el `enabled` que la pantalla
 * concede y deja que el test fije `active`. El flujo real se prueba en `DeleteAccountEntryTest` y en los de host.
 */
fun fakeDeleteScope(active: Boolean = false) = DeleteAccountScope(active) { enabled ->
    OutlinedButton(onClick = {}, enabled = enabled, modifier = Modifier.testTag(FAKE_DELETE_TAG)) { Text("delete") }
}

/** El flujo real en reposo: lo que ven las capturas de las pantallas anfitrionas. */
fun idleDeleteScope() = DeleteAccountScope(active = false) { enabled ->
    DeleteAccountEntry(com.violinstudio.ui.feature.account.viewmodel.DeleteAccountState(), {}, enabled)
}
