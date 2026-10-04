package com.violinstudio.ui.feature.auth.view

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.SessionNavHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class VerifyEmailSlotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSlotKeepsTheEmailStableWhileTheScreenLeaves() {
        val session = mutableStateOf<SessionState>(SessionState.EmailUnverified("ana@example.test"))
        val seen = mutableListOf<String?>()
        compose.setContent {
            ViolinStudioTheme {
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    auth = {},
                    onboarding = {},
                    verifyEmail = { seen += it }
                )
            }
        }
        compose.waitForIdle()
        session.value = SessionState.LoggedOut
        compose.waitForIdle()
        assertTrue("the slot was never composed", seen.isNotEmpty())
        assertEquals(setOf<String?>("ana@example.test"), seen.toSet())
    }
}
