package com.violinstudio.ui.feature.curriculum.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.curriculum.model.CurriculumProgress
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumIntent
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumMessage
import com.violinstudio.ui.feature.curriculum.viewmodel.CurriculumState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class CurriculumScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<CurriculumIntent>()
    private var tuner = 0
    private var metronome = 0
    private var back = 0

    private fun show(state: CurriculumState) {
        val navigation = CurriculumNavigation({ tuner++ }, { metronome++ }, { back++ })
        compose.setContent { ViolinStudioTheme { CurriculumScreen(state, { sent += it }, navigation) } }
    }

    @Test
    fun `todas las lecciones del curriculo tienen textos`() {
        Curriculum.lessons.forEach { assertNotNull(it.id, lessonTexts(it.id)) }
    }

    @Test
    fun `muestra el resumen y la siguiente leccion`() {
        show(CurriculumState(progress = CurriculumProgress.of(setOf("b1_posture"))))
        compose.onNodeWithTag(CURRICULUM_SUMMARY_TAG).assertIsDisplayed()
        compose.onNodeWithText("Has completado 1 de 12 lecciones").assertIsDisplayed()
        compose.onNodeWithText("Siguiente: Sujeción del arco").assertIsDisplayed()
    }

    @Test
    fun `tocar una leccion la abre`() {
        show(CurriculumState(progress = CurriculumProgress.of(emptySet())))
        compose.onNodeWithText("Postura y sujeción del violín").performClick()
        assertEquals(listOf<CurriculumIntent>(CurriculumIntent.Toggle("b1_posture")), sent)
    }

    @Test
    fun `una leccion abierta muestra los pasos, abre su herramienta y se marca como hecha`() {
        show(CurriculumState(progress = CurriculumProgress.of(emptySet()), expandedId = "b3_open_strings"))
        compose.onNodeWithText("• Pon el metrónomo a 60").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(lessonToolTag("b3_open_strings")).performScrollTo().performClick()
        assertEquals(1, metronome)
        compose.onNodeWithTag(lessonDoneTag("b3_open_strings")).performScrollTo().performClick()
        assertEquals(listOf<CurriculumIntent>(CurriculumIntent.SetDone("b3_open_strings", true)), sent)
    }

    @Test
    fun `las lecciones con afinador lo abren`() {
        show(CurriculumState(progress = CurriculumProgress.of(emptySet()), expandedId = "b4_tuning"))
        compose.onNodeWithTag(lessonToolTag("b4_tuning")).performScrollTo().performClick()
        assertEquals(1, tuner)
    }

    @Test
    fun `con todo hecho lo dice y el aviso de error se puede cerrar`() {
        val all = Curriculum.lessons.map { it.id }.toSet()
        show(CurriculumState(progress = CurriculumProgress.of(all), message = CurriculumMessage.SAVE_FAILED))
        compose.onNodeWithText("Has completado todas las lecciones.").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        assertEquals(listOf<CurriculumIntent>(CurriculumIntent.DismissMessage), sent)
    }

    @Test
    fun `volver`() {
        show(CurriculumState(progress = CurriculumProgress.of(emptySet())))
        compose.onNodeWithTag(CURRICULUM_BACK_TAG).performScrollTo().performClick()
        assertEquals(1, back)
    }
}
