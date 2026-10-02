package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requiere el emulador de Functions en el PC:
 *   firebase emulators:start --only functions --project violin-app-dev-f0b55
 * Ejecutar con: ./gradlew :app:connectedDevDebugAndroidTest
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeHealthE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun comprobarServidorMuestraOkConLaVersion() {
        compose.onNodeWithText("Comprobar servidor").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("health_ok"), timeoutMillis = 30_000)
        compose.onNodeWithText("Servidor OK · v0.1.0").assertExists()
    }
}
