package com.violinstudio.ui.feature.reminder.view

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.ui.R
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.reminder.viewmodel.ReminderIntent
import com.violinstudio.ui.feature.reminder.viewmodel.ReminderState
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es-rES")
class ReminderSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val intents = mutableListOf<ReminderIntent>()
    private var asked = 0

    private fun show(
        state: ReminderState,
        enabled: Boolean = true,
        allowed: Boolean = true,
        request: (() -> Unit)? = null
    ) {
        compose.setContent {
            ViolinStudioTheme { ReminderSection(state, enabled, allowed, request) { intents += it } }
        }
    }

    @Test
    fun encenderPideElPermisoUnaVezYGuarda() {
        show(ReminderState(loaded = true), request = { asked++ })
        compose.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsOff().performClick()
        assertEquals(1, asked)
        assertEquals(listOf<ReminderIntent>(ReminderIntent.Toggle(true)), intents)
    }

    @Test
    fun encendidoSinAvisosPermitidosLoExplica() {
        show(ReminderState(PracticeReminder(true, LocalTime.of(18, 0)), loaded = true), allowed = false)
        compose.onNodeWithText(app.getString(R.string.settings_reminder_permission)).assertExists()
        compose.onNodeWithTag(REMINDER_TIME_TAG).assertExists()
    }

    @Test
    fun laHoraSeEligeEnElDialogo() {
        show(ReminderState(PracticeReminder(true, LocalTime.of(7, 30)), loaded = true))
        compose.onNodeWithTag(REMINDER_TIME_TAG).performClick()
        compose.onNodeWithTag(REMINDER_TIME_OK_TAG).performClick()
        assertEquals(listOf<ReminderIntent>(ReminderIntent.SetTime(LocalTime.of(7, 30))), intents)
    }

    @Test
    fun bloqueadoMientrasLosAjustesEstanOcupados() {
        show(ReminderState(loaded = true), enabled = false)
        compose.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsNotEnabled()
    }
}
