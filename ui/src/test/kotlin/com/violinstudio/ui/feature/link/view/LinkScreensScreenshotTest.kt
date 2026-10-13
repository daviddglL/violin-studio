package com.violinstudio.ui.feature.link.view

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherError
import com.violinstudio.ui.feature.link.viewmodel.MyTeacherState
import com.violinstudio.ui.feature.link.viewmodel.RedeemCodeState
import com.violinstudio.ui.feature.link.viewmodel.RedeemError
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "es-rES-w411dp-h1200dp-xxhdpi")
class LinkScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val link =
        TeacherLink("t1", "s1", "Marta", "Ana", Instrument.VIOLIN, 1, Instant.parse("2026-10-01T12:00:00Z"))

    private fun redeem(state: RedeemCodeState, name: String) {
        compose.setContent { ViolinStudioTheme { RedeemCodeScreen(state, onIntent = {}, onBack = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun teacher(state: MyTeacherState, name: String) {
        compose.setContent { ViolinStudioTheme { MyTeacherScreen(state, onIntent = {}, onBack = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun redeemNotice() = redeem(RedeemCodeState(code = "ABCD2345"), "link_redeem_notice")

    @Test fun redeemReady() = redeem(RedeemCodeState(code = "ABCD2345", accepted = true), "link_redeem_ready")

    @Test fun redeemInvalid() = redeem(
        RedeemCodeState(code = "ABCD2345", accepted = true, error = RedeemError.InvalidCode),
        "link_redeem_invalid"
    )

    @Test fun redeemRateLimited() = redeem(
        RedeemCodeState(code = "ABCD2345", accepted = true, error = RedeemError.RateLimited(7)),
        "link_redeem_rate_limited"
    )

    @Test fun redeemOffline() = redeem(
        RedeemCodeState(code = "ABCD2345", accepted = true, error = RedeemError.Offline),
        "link_redeem_offline"
    )

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h1200dp-xxhdpi")
    fun redeemNoticeEn() = redeem(RedeemCodeState(code = "ABCD2345"), "link_redeem_notice_en")

    @Test fun myTeacher() = teacher(MyTeacherState(isLoading = false, teacher = link), "link_my_teacher")

    @Test fun myTeacherNone() = teacher(MyTeacherState(isLoading = false), "link_my_teacher_none")

    @Test fun myTeacherLoading() = teacher(MyTeacherState(), "link_my_teacher_loading")

    @Test fun myTeacherUnlinkError() = teacher(
        MyTeacherState(isLoading = false, teacher = link, error = MyTeacherError.Offline),
        "link_my_teacher_unlink_error"
    )

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h1200dp-xxhdpi")
    fun myTeacherEn() = teacher(MyTeacherState(isLoading = false, teacher = link), "link_my_teacher_en")
}
