package com.violinstudio

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Base de los recorridos E2E contra los emuladores de Firebase (functions, auth, firestore). Antes de abrir la
 * actividad cierra la sesion que haya dejado otro test (el estado de Auth persiste en el proceso) para arrancar en Login.
 */
abstract class E2eTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var signOut: SignOutUseCase

    protected lateinit var journey: Journey
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launchSignedOut() {
        hilt.inject()
        runBlocking { signOut() }
        journey = Journey(compose)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeApp() {
        runCatching { scenario?.close() }
        runCatching { runBlocking { signOut() } }
    }
}
