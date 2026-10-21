package com.violinstudio.ui.feature.recording.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.recording.model.PlaybackState
import com.violinstudio.domain.feature.recording.model.RecordingSummary
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.recording.viewmodel.AssignmentDemosIntent
import com.violinstudio.ui.feature.recording.viewmodel.AssignmentDemosState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class AssignmentDemosScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<AssignmentDemosIntent>()
    private var back = 0
    private val d0 = RecordingSummary("d0", null, 65_000, ready = true, isDemo = true, objectPath = "p")

    private fun show(state: AssignmentDemosState) {
        compose.setContent { ViolinStudioTheme { AssignmentDemosScreen(state, { sent += it }, { back++ }) } }
    }

    @Test
    fun `lista con escuchar y parar`() {
        show(AssignmentDemosState(isLoading = false, demos = listOf(d0)))
        compose.onNodeWithText("Demostraciones del profesor").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithTag(playTag("d0")).performClick()
        compose.onNodeWithTag(ASSIGNMENT_DEMOS_BACK_TAG).performClick()
        assertEquals(listOf<AssignmentDemosIntent>(AssignmentDemosIntent.Play("d0")), sent)
        assertEquals(1, back)
    }

    @Test
    fun `sonando se puede parar`() {
        show(AssignmentDemosState(isLoading = false, demos = listOf(d0), playback = PlaybackState.Playing("d0")))
        compose.onNodeWithTag(stopPlaybackTag("d0")).performClick()
        assertEquals(listOf<AssignmentDemosIntent>(AssignmentDemosIntent.Stop), sent)
    }

    @Test
    fun `sin demos lo dice`() {
        show(AssignmentDemosState(isLoading = false))
        compose.onNodeWithTag(ASSIGNMENT_DEMOS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `si falla la carga se puede reintentar`() {
        show(AssignmentDemosState(isLoading = false, loadFailed = true))
        compose.onNodeWithTag(ASSIGNMENT_DEMOS_FAILED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ASSIGNMENT_DEMOS_RETRY_TAG).performClick()
        assertEquals(listOf<AssignmentDemosIntent>(AssignmentDemosIntent.Retry), sent)
    }
}
