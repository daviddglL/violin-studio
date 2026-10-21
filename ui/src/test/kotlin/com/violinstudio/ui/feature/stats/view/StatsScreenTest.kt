package com.violinstudio.ui.feature.stats.view

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.practiceStats
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.stats.viewmodel.StatsIntent
import com.violinstudio.ui.feature.stats.viewmodel.StatsState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "es")
class StatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<StatsIntent>()
    private var back = 0
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun session(at: String, sec: Int) = PracticeSession(
        at,
        Instant.parse(at),
        sec,
        Instrument.VIOLIN,
        null,
        false
    )

    private fun show(state: StatsState) {
        compose.setContent { ViolinStudioTheme { StatsScreen(state, { sent += it }, onBack = { back++ }) } }
    }

    @Test
    fun `muestra racha, semana y la lectura accesible de cada dia`() {
        val stats = listOf(
            session("2026-10-14T09:00:00Z", 1_800),
            session("2026-10-13T09:00:00Z", 600),
            session("2026-10-05T09:00:00Z", 300)
        ).practiceStats(now, ZoneOffset.UTC)
        show(StatsState(loading = false, stats = stats))
        compose.onNodeWithTag(STATS_STREAK_TAG).assertIsDisplayed()
        compose.onNodeWithText("Racha actual (días seguidos)").assertIsDisplayed()
        compose.onNodeWithText("Mejor racha: 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("40 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Semana pasada: 5 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("miércoles: 30 min").assertExists()
        compose.onNodeWithTag(STATS_BACK_TAG).performScrollTo().performClick()
        assertEquals(1, back)
    }

    @Test
    fun `sin sesiones muestra el aviso vacio`() {
        show(StatsState(loading = false, stats = emptyList<PracticeSession>().practiceStats(now, ZoneOffset.UTC)))
        compose.onNodeWithTag(STATS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `con el historial al limite avisa de que se usan las ultimas 200 sesiones`() {
        val many = listOf(session("2026-10-14T09:00:00Z", 60)).practiceStats(now, ZoneOffset.UTC, limit = 1)
        show(StatsState(loading = false, stats = many))
        compose.onNodeWithTag(STATS_TRUNCATED_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tras un fallo ofrece reintentar`() {
        show(StatsState(loading = false, failed = true))
        compose.onNodeWithText("No se pudieron cargar tus estadísticas.").assertIsDisplayed()
        compose.onNodeWithTag(STATS_RETRY_TAG).performClick()
        assertEquals(listOf<StatsIntent>(StatsIntent.Retry), sent)
    }
}
