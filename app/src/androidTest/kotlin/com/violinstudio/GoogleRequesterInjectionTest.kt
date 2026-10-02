package com.violinstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.GoogleIdTokenResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [A4] E2E: sin Credential Manager, el grafo de pruebas entrega un token de Google falso (sin firmar). */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GoogleRequesterInjectionTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var requester: GoogleIdTokenRequester

    @Before
    fun inject() = hilt.inject()

    @Test
    fun theTestGraphProvidesTheFakeRequesterWithAnUnsignedToken() {
        assertTrue(requester is FakeGoogleIdTokenRequester)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = runBlocking { requester.request(context) }
        assertTrue(result is GoogleIdTokenResult.Token)
        assertEquals(3, (result as GoogleIdTokenResult.Token).token.value.split('.').size)
    }
}
