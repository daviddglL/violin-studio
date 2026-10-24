package com.violinstudio.ui.feature.home.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class PracticeTipsCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(tips: List<PracticeTip>) = compose.setContent { ViolinStudioTheme { PracticeTipsCard(tips) } }

    @Test
    fun `muestra cada consejo con su texto`() {
        show(
            listOf(
                PracticeTip.KeepStreak(3),
                PracticeTip.CatchUpWeek(1),
                PracticeTip.LongerSessions(6),
                PracticeTip.NextLesson(Curriculum.lessons.first { it.id == "b2_bow_hold" })
            )
        )
        compose.onNodeWithText("Consejos para hoy").assertIsDisplayed()
        compose.onNodeWithText("• Llevas 3 días seguidos: practica hoy para no perder la racha.").assertIsDisplayed()
        compose.onNodeWithText("• Te falta 1 minuto para igualar la semana pasada.").assertIsDisplayed()
        compose.onNodeWithText("• Tus sesiones duran 6 minutos de media: intenta llegar a 10.").assertIsDisplayed()
        compose.onNodeWithText("• Siguiente lección: Sujeción del arco (10 min al día).").assertIsDisplayed()
    }

    @Test
    fun `primera sesion y volver`() {
        show(listOf(PracticeTip.FirstSession, PracticeTip.ComeBack))
        compose.onNodeWithText("• Empieza con tu primera sesión: con 10 minutos basta.").assertIsDisplayed()
        compose.onNodeWithText("• Llevas más de una semana sin practicar: vuelve hoy con una sesión corta.")
            .assertIsDisplayed()
    }

    @Test
    fun `sin consejos no hay tarjeta`() {
        show(emptyList())
        compose.onNodeWithTag(HOME_TIPS_TAG).assertDoesNotExist()
    }
}
