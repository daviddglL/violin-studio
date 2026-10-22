package com.violinstudio.ui.feature.link.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.home.view.HomeNavigation
import com.violinstudio.ui.feature.home.view.HomeScreen
import com.violinstudio.ui.feature.home.viewmodel.HomeState
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestError
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestIntent
import com.violinstudio.ui.feature.link.viewmodel.TeacherRequestState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TeacherRequestScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val intents = mutableListOf<TeacherRequestIntent>()

    private fun show(state: TeacherRequestState) = compose.setContent {
        ViolinStudioTheme { TeacherRequestScreen(state, { intents += it }, onBack = {}) }
    }

    @Test
    fun `sin solicitud se puede enviar`() {
        show(TeacherRequestState())
        compose.onNodeWithTag(TEACHER_REQUEST_SUBMIT_TAG).performScrollTo().performClick()
        assertEquals(listOf(TeacherRequestIntent.Submit), intents)
        compose.onNodeWithTag(TEACHER_REQUEST_STATUS_TAG).assertDoesNotExist()
    }

    @Test
    fun `pendiente muestra el estado y no ofrece enviar`() {
        show(TeacherRequestState(status = TeacherRequestStatus.PENDING))
        compose.onNodeWithTag(TEACHER_REQUEST_STATUS_TAG).performScrollTo()
            .assertTextContains(context.getString(R.string.teacher_request_pending))
        compose.onNodeWithTag(TEACHER_REQUEST_SUBMIT_TAG).assertDoesNotExist()
    }

    @Test
    fun `rechazada explica el plazo`() {
        show(TeacherRequestState(status = TeacherRequestStatus.REJECTED))
        compose.onNodeWithTag(TEACHER_REQUEST_STATUS_TAG).performScrollTo()
            .assertTextContains(context.getString(R.string.teacher_request_rejected))
    }

    @Test
    fun `no elegible muestra el motivo y bloquea el boton`() {
        show(TeacherRequestState(error = TeacherRequestError.NotEligible))
        compose.onNodeWithTag(TEACHER_REQUEST_ERROR_TAG).performScrollTo()
            .assertTextContains(context.getString(R.string.teacher_request_not_eligible))
        compose.onNodeWithTag(TEACHER_REQUEST_SUBMIT_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `home muestra la entrada solo cuando la navegacion la ofrece`() {
        var opened = 0
        compose.setContent {
            ViolinStudioTheme {
                HomeScreen(
                    HomeState(),
                    onIntent = {},
                    navigation = HomeNavigation({}, {}, {}, {}, onOpenTeacherRequest = { opened++ })
                )
            }
        }
        compose.onNodeWithTag("home_teacher_request").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }
}
