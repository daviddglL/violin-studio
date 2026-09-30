package com.violinstudio.ui.commons

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ObserveAsEventsTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private val events = Channel<String>(Channel.UNLIMITED)
    private val handled = mutableListOf<String>()

    private fun observe(onEvent: suspend (String) -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ObserveAsEvents(events.receiveAsFlow(), onEvent)
        }
    }

    @Test
    fun unEfectoEnCursoTerminaAunqueLaPantallaSeDetenga() {
        val release = CompletableDeferred<Unit>()
        observe {
            release.await()
            handled += it
        }

        events.trySend("error")
        compose.waitForIdle()
        owner.registry.currentState = Lifecycle.State.CREATED // p. ej. la app pasa a segundo plano
        release.complete(Unit)
        compose.waitForIdle()

        assertEquals(listOf("error"), handled)
    }

    @Test
    fun losEfectosEmitidosConLaPantallaDetenidaEsperanHastaQueVuelva() {
        observe { handled += it }
        owner.registry.currentState = Lifecycle.State.CREATED

        events.trySend("a")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), handled)

        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.waitForIdle()
        assertEquals(listOf("a"), handled)
    }

    @Test
    fun losEfectosSeProcesanDeUnoEnUnoYEnOrden() {
        val release = CompletableDeferred<Unit>()
        observe {
            if (it == "a") release.await()
            handled += it
        }

        events.trySend("a")
        events.trySend("b")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), handled)

        release.complete(Unit)
        compose.waitForIdle()
        assertEquals(listOf("a", "b"), handled)
    }
}
