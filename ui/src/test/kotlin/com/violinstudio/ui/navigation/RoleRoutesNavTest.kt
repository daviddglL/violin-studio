package com.violinstudio.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.profile.model.ConsentStatus
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.Role
import com.violinstudio.domain.feature.profile.model.UserProfile
import com.violinstudio.domain.feature.session.SessionFeatures
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RoleRoutesNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun ready(role: Role, features: SessionFeatures = SessionFeatures(recordings = true, chat = true)) =
        SessionState.Ready(
            UserProfile("u1", "Ana", Instrument.VIOLIN, "es", role, false, ConsentStatus.GRANTED, 1, null, false),
            features
        )

    private val session = mutableStateOf<SessionState>(SessionState.Loading)
    private var redeemComposed = false
    private var myTeacherComposed = false
    private var studentsComposed = false
    private var codesComposed = false
    private var detailComposed = false
    private var assignmentsComposed = false
    private var teacherAssignmentsComposed = false
    private var studentRecordingsComposed = false
    private var teacherDemoComposed = false
    private var assignmentDemosComposed = false
    private var editComposed = false
    private var chatComposed = false
    private lateinit var nav: NavHostController

    private fun start(initial: SessionState) {
        session.value = initial
        redeemComposed = false
        myTeacherComposed = false
        studentsComposed = false
        codesComposed = false
        detailComposed = false
        assignmentsComposed = false
        teacherAssignmentsComposed = false
        studentRecordingsComposed = false
        teacherDemoComposed = false
        assignmentDemosComposed = false
        editComposed = false
        chatComposed = false
        compose.setContent {
            ViolinStudioTheme {
                nav = rememberNavController()
                SessionNavHost(
                    session = session.value,
                    onSignOut = {},
                    navController = nav,
                    auth = { Text("auth") },
                    home = { navigation ->
                        Column(Modifier.testTag("home")) {
                            navigation.onOpenRedeem?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_redeem")) { Text("r") }
                            }
                            navigation.onOpenStudents?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_students")) { Text("s") }
                            }
                            navigation.onOpenCodes?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_codes")) { Text("c") }
                            }
                            navigation.onOpenAssignments?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_assignments")) { Text("a") }
                            }
                            navigation.onOpenMyTeacher?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_my_teacher")) { Text("t") }
                            }
                            navigation.onOpenRecordings?.let {
                                Button(onClick = it, modifier = Modifier.testTag("open_recordings")) { Text("g") }
                            }
                        }
                    },
                    redeemCode = { onBack ->
                        redeemComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("redeem_screen")) { Text("b") }
                    },
                    students = { onOpenCodes, onOpenStudent, onBack ->
                        studentsComposed = true
                        Column(Modifier.testTag("students_screen")) {
                            Button(
                                onClick = { onOpenStudent("s1") },
                                modifier = Modifier.testTag("students_to_detail")
                            ) {
                                Text("d")
                            }
                            Button(onClick = onOpenCodes, modifier = Modifier.testTag("students_to_codes")) {
                                Text("c")
                            }
                            Button(onClick = onBack, modifier = Modifier.testTag("students_back")) { Text("b") }
                        }
                    },
                    studentDetail = { onOpenAssignments, onOpenRecordings, onOpenChat, onBack ->
                        detailComposed = true
                        Column {
                            Button(onClick = onBack, modifier = Modifier.testTag("detail_screen")) { Text("b") }
                            Button(onClick = onOpenAssignments, modifier = Modifier.testTag("detail_to_tasks")) {
                                Text("t")
                            }
                            onOpenRecordings?.let {
                                Button(onClick = it, modifier = Modifier.testTag("detail_to_recordings")) { Text("g") }
                            }
                            onOpenChat?.let {
                                Button(onClick = it, modifier = Modifier.testTag("detail_to_chat")) { Text("m") }
                            }
                        }
                    },
                    studentRecordings = { onBack ->
                        studentRecordingsComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("recordings_screen")) { Text("b") }
                    },
                    assignments = { onOpenDemos, onBack ->
                        assignmentsComposed = true
                        Column {
                            Button(onClick = onBack, modifier = Modifier.testTag("assignments_screen")) { Text("b") }
                            Button(
                                onClick = { onOpenDemos("t1", "a1") },
                                modifier = Modifier.testTag("assignments_demos")
                            ) { Text("d") }
                        }
                    },
                    teacherAssignments = { onCreate, onEdit, onDemo, onBack ->
                        teacherAssignmentsComposed = true
                        Column {
                            Button(onClick = onBack, modifier = Modifier.testTag("tasks_screen")) { Text("b") }
                            Button(onClick = onCreate, modifier = Modifier.testTag("tasks_create")) { Text("c") }
                            Button(onClick = { onEdit("a1") }, modifier = Modifier.testTag("tasks_edit")) { Text("e") }
                            Button(onClick = { onDemo("a1") }, modifier = Modifier.testTag("tasks_demo")) { Text("d") }
                        }
                    },
                    teacherDemo = { onRecordVideo, onBack ->
                        teacherDemoComposed = true
                        Column {
                            Button(onClick = onBack, modifier = Modifier.testTag("teacher_demo_screen")) { Text("b") }
                            Button(onClick = onRecordVideo, modifier = Modifier.testTag("teacher_demo_video")) {
                                Text("v")
                            }
                        }
                    },
                    videoCapture = { onBack ->
                        Button(onClick = onBack, modifier = Modifier.testTag("video_screen")) { Text("b") }
                    },
                    assignmentDemos = { onBack ->
                        assignmentDemosComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("assignment_demos_screen")) { Text("b") }
                    },
                    assignmentEdit = { onBack ->
                        editComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("edit_screen")) { Text("b") }
                    },
                    teacherCodes = { onBack ->
                        codesComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("codes_screen")) { Text("b") }
                    },
                    myTeacher = { onOpenChat, onBack ->
                        myTeacherComposed = true
                        Column {
                            Button(onClick = onBack, modifier = Modifier.testTag("my_teacher_screen")) { Text("b") }
                            onOpenChat?.let { open ->
                                Button(onClick = { open("t9") }, modifier = Modifier.testTag("my_teacher_chat")) {
                                    Text("m")
                                }
                            }
                        }
                    },
                    chat = { onBack ->
                        chatComposed = true
                        Button(onClick = onBack, modifier = Modifier.testTag("chat_screen")) { Text("b") }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertAt(route: Any) {
        compose.waitForIdle()
        val here = nav.currentDestination
        assertTrue("expected $route but at $here", here?.hasRoute(route::class) == true)
    }

    @Test
    fun routesAreBusinessRoutesAndOutsideTheSessionAwareFeatures() {
        assertTrue(RedeemCodeDestination::class in BUSINESS_ROUTES)
        assertTrue(MyTeacherDestination::class in BUSINESS_ROUTES)
        assertTrue(StudentsDestination::class in BUSINESS_ROUTES)
        assertTrue(TeacherCodesDestination::class in BUSINESS_ROUTES)
        assertTrue(StudentDetailDestination::class in BUSINESS_ROUTES)
        assertFalse("link" in GatingIsolationTest.SESSION_AWARE_FEATURES)
    }

    @Test
    fun roleTableAllowsOnlyTheRightRole() {
        assertTrue(Role.INDEPENDENT.allows(RedeemCodeDestination::class))
        assertFalse(Role.STUDENT.allows(RedeemCodeDestination::class))
        assertFalse(Role.TEACHER.allows(RedeemCodeDestination::class))
        assertTrue(Role.STUDENT.allows(MyTeacherDestination::class))
        assertFalse(Role.INDEPENDENT.allows(MyTeacherDestination::class))
        assertFalse(Role.TEACHER.allows(MyTeacherDestination::class))
        assertTrue(Role.TEACHER.allows(HomeDestination::class))
        for (route in listOf(
            StudentsDestination::class,
            TeacherCodesDestination::class,
            StudentDetailDestination::class,
            StudentRecordingsDestination::class
        )) {
            assertTrue(Role.TEACHER.allows(route))
            assertFalse(Role.STUDENT.allows(route))
            assertFalse(Role.INDEPENDENT.allows(route))
        }
    }

    @Test
    fun homeOffersRedeemToIndependentOnly() {
        start(ready(Role.INDEPENDENT))
        compose.onNodeWithTag("open_redeem").assertIsDisplayed()
        compose.onNodeWithTag("open_my_teacher").assertDoesNotExist()
    }

    @Test
    fun homeOffersMyTeacherToStudentOnly() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_my_teacher").assertIsDisplayed()
        compose.onNodeWithTag("open_redeem").assertDoesNotExist()
    }

    @Test
    fun homeOffersStudentsAndCodesToTeacherOnly() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").assertIsDisplayed()
        compose.onNodeWithTag("open_codes").assertIsDisplayed()
        compose.onNodeWithTag("open_redeem").assertDoesNotExist()
        compose.onNodeWithTag("open_my_teacher").assertDoesNotExist()
    }

    @Test
    fun homeHidesStudentsAndCodesFromStudent() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_students").assertDoesNotExist()
        compose.onNodeWithTag("open_codes").assertDoesNotExist()
    }

    @Test
    fun homeHidesStudentsAndCodesFromIndependent() {
        start(ready(Role.INDEPENDENT))
        compose.onNodeWithTag("open_students").assertDoesNotExist()
        compose.onNodeWithTag("open_codes").assertDoesNotExist()
    }

    @Test
    fun teacherReachesStudentsThenCodesAndBackReturnsToTheirOrigin() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").performClick()
        assertAt(StudentsDestination)
        compose.onNodeWithTag("students_to_codes").performClick()
        assertAt(TeacherCodesDestination)
        compose.onNodeWithTag("codes_screen").performClick()
        assertAt(StudentsDestination)
        compose.onNodeWithTag("students_back").performClick()
        assertAt(HomeDestination)
        compose.onNodeWithTag("open_codes").performClick()
        assertAt(TeacherCodesDestination)
    }

    @Test
    fun studentDeepLinksToTeacherRoutesRedirectHomeWithoutComposing() {
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(StudentsDestination) }
        assertAt(HomeDestination)
        compose.runOnUiThread { nav.navigate(TeacherCodesDestination) }
        assertAt(HomeDestination)
        assertFalse(studentsComposed || codesComposed)
    }

    @Test
    fun independentDeepLinksToTeacherRoutesRedirectHomeWithoutComposing() {
        start(ready(Role.INDEPENDENT))
        compose.runOnUiThread { nav.navigate(StudentsDestination) }
        assertAt(HomeDestination)
        compose.runOnUiThread { nav.navigate(TeacherCodesDestination) }
        assertAt(HomeDestination)
        assertFalse(studentsComposed || codesComposed)
    }

    @Test
    fun losingTheTeacherRoleWhileOnTheRouteRedirectsHome() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_codes").performClick()
        assertAt(TeacherCodesDestination)
        session.value = ready(Role.INDEPENDENT)
        assertAt(HomeDestination)
    }

    @Test
    fun teacherOpensAStudentDetailWithTheUidAsAnArgumentAndBackReturnsToTheList() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").performClick()
        compose.onNodeWithTag("students_to_detail").performClick()
        assertAt(StudentDetailDestination("s1"))
        assertEquals("s1", nav.currentBackStackEntry?.toRoute<StudentDetailDestination>()?.studentUid)
        assertTrue(detailComposed)
        compose.onNodeWithTag("detail_screen").performClick()
        assertAt(StudentsDestination)
    }

    @Test
    fun studentDetailRouteSerializesItsArgumentAsADataClass() {
        val route = StudentDetailDestination("abc")
        assertEquals("abc", route.studentUid)
        assertEquals(route, StudentDetailDestination("abc"))
    }

    private fun assertDeepLinkToDetailRedirectsHome(role: Role) {
        start(ready(role))
        compose.runOnUiThread { nav.navigate(StudentDetailDestination("s1")) }
        assertAt(HomeDestination)
        assertFalse(detailComposed)
    }

    @Test
    fun studentDeepLinkToAStudentDetailRedirectsHomeWithoutComposing() =
        assertDeepLinkToDetailRedirectsHome(Role.STUDENT)

    @Test
    fun independentDeepLinkToAStudentDetailRedirectsHomeWithoutComposing() =
        assertDeepLinkToDetailRedirectsHome(Role.INDEPENDENT)

    @Test
    fun losingTheTeacherRoleWhileOnAStudentDetailRedirectsHome() {
        start(ready(Role.TEACHER))
        compose.runOnUiThread { nav.navigate(StudentDetailDestination("s1")) }
        assertAt(StudentDetailDestination("s1"))
        session.value = ready(Role.INDEPENDENT)
        assertAt(HomeDestination)
    }

    @Test
    fun withoutReadyAStudentDetailRedirectsToTheStateRoot() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(StudentDetailDestination("s1")) }
        assertAt(AuthDestination)
        assertFalse(detailComposed)
    }

    @Test
    fun independentReachesRedeemAndBackReturnsHome() {
        start(ready(Role.INDEPENDENT))
        compose.onNodeWithTag("open_redeem").performClick()
        assertAt(RedeemCodeDestination)
        compose.onNodeWithTag("redeem_screen").performClick()
        assertAt(HomeDestination)
    }

    @Test
    fun studentDeepLinkToRedeemRedirectsHomeWithoutComposingIt() {
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(RedeemCodeDestination) }
        assertAt(HomeDestination)
        assertFalse(redeemComposed)
    }

    @Test
    fun independentDeepLinkToMyTeacherRedirectsHomeWithoutComposingIt() {
        start(ready(Role.INDEPENDENT))
        compose.runOnUiThread { nav.navigate(MyTeacherDestination) }
        assertAt(HomeDestination)
        assertFalse(myTeacherComposed)
    }

    @Test
    fun roleChangeWhileOnTheRouteRedirectsHome() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_my_teacher").performClick()
        assertAt(MyTeacherDestination)
        session.value = ready(Role.INDEPENDENT)
        assertAt(HomeDestination)
        assertEquals(null, nav.previousBackStackEntry)
    }

    @Test
    fun withoutReadyTheRoutesRedirectToTheStateRoot() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(RedeemCodeDestination) }
        assertAt(AuthDestination)
        compose.runOnUiThread { nav.navigate(MyTeacherDestination) }
        assertAt(AuthDestination)
        assertFalse(redeemComposed || myTeacherComposed)
    }

    @Test
    fun assignmentRoutesAreBusinessRoutesWithTheRightRoles() {
        for (route in listOf(
            AssignmentsDestination::class,
            TeacherAssignmentsDestination::class,
            AssignmentEditDestination::class
        )) {
            assertTrue(route in BUSINESS_ROUTES)
        }
        assertTrue(Role.STUDENT.allows(AssignmentsDestination::class))
        assertFalse(Role.TEACHER.allows(AssignmentsDestination::class))
        assertFalse(Role.INDEPENDENT.allows(AssignmentsDestination::class))
        for (route in listOf(TeacherAssignmentsDestination::class, AssignmentEditDestination::class)) {
            assertTrue(Role.TEACHER.allows(route))
            assertFalse(Role.STUDENT.allows(route))
            assertFalse(Role.INDEPENDENT.allows(route))
        }
    }

    @Test
    fun homeOffersAssignmentsToStudentOnly() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_assignments").assertIsDisplayed()
        compose.onNodeWithTag("open_assignments").performClick()
        assertAt(AssignmentsDestination)
        compose.onNodeWithTag("assignments_screen").performClick()
        assertAt(HomeDestination)
    }

    @Test
    fun homeHidesAssignmentsFromTeacherAndIndependent() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_assignments").assertDoesNotExist()
        session.value = ready(Role.INDEPENDENT)
        compose.waitForIdle()
        compose.onNodeWithTag("open_assignments").assertDoesNotExist()
    }

    @Test
    fun teacherEditsFromTheDetailListAndBackReturnsStepByStep() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").performClick()
        compose.onNodeWithTag("students_to_detail").performClick()
        compose.onNodeWithTag("detail_to_tasks").performClick()
        assertAt(TeacherAssignmentsDestination("s1"))
        assertEquals("s1", nav.currentBackStackEntry?.toRoute<TeacherAssignmentsDestination>()?.studentUid)
        compose.onNodeWithTag("tasks_edit").performClick()
        assertAt(AssignmentEditDestination("s1", "a1"))
        val edit = nav.currentBackStackEntry?.toRoute<AssignmentEditDestination>()
        assertEquals("s1", edit?.studentUid)
        assertEquals("a1", edit?.assignmentId)
        compose.onNodeWithTag("edit_screen").performClick()
        assertAt(TeacherAssignmentsDestination("s1"))
        compose.onNodeWithTag("tasks_create").performClick()
        assertAt(AssignmentEditDestination("s1", null))
        assertEquals(null, nav.currentBackStackEntry?.toRoute<AssignmentEditDestination>()?.assignmentId)
        compose.onNodeWithTag("edit_screen").performClick()
        compose.onNodeWithTag("tasks_screen").performClick()
        assertAt(StudentDetailDestination("s1"))
    }

    @Test
    fun teacherOpensStudentRecordingsFromTheDetailAndBackReturnsThere() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").performClick()
        compose.onNodeWithTag("students_to_detail").performClick()
        compose.onNodeWithTag("detail_to_recordings").performClick()
        assertAt(StudentRecordingsDestination("s1"))
        assertEquals("s1", nav.currentBackStackEntry?.toRoute<StudentRecordingsDestination>()?.studentUid)
        compose.onNodeWithTag("recordings_screen").performClick()
        assertAt(StudentDetailDestination("s1"))
    }

    @Test
    fun nonTeachersDeepLinkingToStudentRecordingsRedirectHomeWithoutComposing() {
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(StudentRecordingsDestination("s1")) }
        assertAt(HomeDestination)
        assertFalse(studentRecordingsComposed)
    }

    @Test
    fun teacherRecordsADemoFromTheTaskListAndBackReturnsThere() {
        start(ready(Role.TEACHER))
        compose.runOnUiThread { nav.navigate(TeacherAssignmentsDestination("s1")) }
        compose.onNodeWithTag("tasks_demo").performClick()
        assertAt(TeacherDemoDestination("s1", "a1"))
        val demo = nav.currentBackStackEntry?.toRoute<TeacherDemoDestination>()
        assertEquals("s1", demo?.studentUid)
        assertEquals("a1", demo?.assignmentId)
        compose.onNodeWithTag("teacher_demo_screen").performClick()
        assertAt(TeacherAssignmentsDestination("s1"))
    }

    @Test
    fun teacherRecordsAVideoDemoForTheSameStudentAndTaskAndBackReturnsToTheDemo() {
        start(ready(Role.TEACHER))
        compose.runOnUiThread { nav.navigate(TeacherDemoDestination("s1", "a1")) }
        compose.onNodeWithTag("teacher_demo_video").performClick()
        assertAt(VideoCaptureDestination("s1", "a1"))
        val video = nav.currentBackStackEntry?.toRoute<VideoCaptureDestination>()
        assertEquals("s1", video?.studentUid)
        assertEquals("a1", video?.assignmentId)
        assertTrue(VideoCaptureDestination::class in BUSINESS_ROUTES)
        compose.onNodeWithTag("video_screen").performClick()
        assertAt(TeacherDemoDestination("s1", "a1"))
    }

    @Test
    fun studentOpensTheTeacherDemosOfATaskAndBackReturnsThere() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_assignments").performClick()
        compose.onNodeWithTag("assignments_demos").performClick()
        assertAt(AssignmentDemosDestination("t1", "a1"))
        val demos = nav.currentBackStackEntry?.toRoute<AssignmentDemosDestination>()
        assertEquals("t1", demos?.teacherUid)
        assertEquals("a1", demos?.assignmentId)
        compose.onNodeWithTag("assignment_demos_screen").performClick()
        assertAt(AssignmentsDestination)
    }

    @Test
    fun demoRoutesOnlyComposeForTheirRole() {
        assertTrue(TeacherDemoDestination::class in BUSINESS_ROUTES)
        assertTrue(AssignmentDemosDestination::class in BUSINESS_ROUTES)
        assertTrue(Role.TEACHER.allows(TeacherDemoDestination::class))
        assertFalse(Role.STUDENT.allows(TeacherDemoDestination::class))
        assertFalse(Role.INDEPENDENT.allows(TeacherDemoDestination::class))
        assertTrue(Role.STUDENT.allows(AssignmentDemosDestination::class))
        assertFalse(Role.TEACHER.allows(AssignmentDemosDestination::class))
        assertFalse(Role.INDEPENDENT.allows(AssignmentDemosDestination::class))
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(TeacherDemoDestination("s1", "a1")) }
        assertAt(HomeDestination)
        session.value = ready(Role.TEACHER)
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(AssignmentDemosDestination("t1", "a1")) }
        assertAt(HomeDestination)
        assertFalse(teacherDemoComposed || assignmentDemosComposed)
    }

    @Test
    fun teacherOpensTheChatFromTheDetailWithBothUidsAndBackReturnsThere() {
        start(ready(Role.TEACHER))
        compose.onNodeWithTag("open_students").performClick()
        compose.onNodeWithTag("students_to_detail").performClick()
        compose.onNodeWithTag("detail_to_chat").performClick()
        assertAt(ChatDestination("u1", "s1"))
        val chat = nav.currentBackStackEntry?.toRoute<ChatDestination>()
        assertEquals("u1", chat?.teacherUid)
        assertEquals("s1", chat?.studentUid)
        compose.onNodeWithTag("chat_screen").performClick()
        assertAt(StudentDetailDestination("s1"))
    }

    @Test
    fun studentOpensTheChatFromMyTeacherWithBothUidsAndBackReturnsThere() {
        start(ready(Role.STUDENT))
        compose.onNodeWithTag("open_my_teacher").performClick()
        compose.onNodeWithTag("my_teacher_chat").performClick()
        assertAt(ChatDestination("t9", "u1"))
        val chat = nav.currentBackStackEntry?.toRoute<ChatDestination>()
        assertEquals("t9", chat?.teacherUid)
        assertEquals("u1", chat?.studentUid)
        compose.onNodeWithTag("chat_screen").performClick()
        assertAt(MyTeacherDestination)
    }

    @Test
    fun withRecordingsAndChatOffTheStudentDetailHidesTheirEntries() {
        start(ready(Role.TEACHER, SessionFeatures()))
        compose.onNodeWithTag("open_students").performClick()
        compose.onNodeWithTag("students_to_detail").performClick()
        assertAt(StudentDetailDestination("s1"))
        compose.onNodeWithTag("detail_to_recordings").assertDoesNotExist()
        compose.onNodeWithTag("detail_to_chat").assertDoesNotExist()
    }

    @Test
    fun homeShowsRecordingsOnlyWithTheSwitchOn() {
        start(ready(Role.INDEPENDENT, SessionFeatures()))
        compose.onNodeWithTag("open_recordings").assertDoesNotExist()
        session.value = ready(Role.INDEPENDENT, SessionFeatures(recordings = true))
        compose.waitForIdle()
        compose.onNodeWithTag("open_recordings").assertIsDisplayed()
    }

    @Test
    fun withChatOffMyTeacherHidesTheChatEntry() {
        start(ready(Role.STUDENT, SessionFeatures()))
        compose.onNodeWithTag("open_my_teacher").performClick()
        assertAt(MyTeacherDestination)
        compose.onNodeWithTag("my_teacher_chat").assertDoesNotExist()
    }

    @Test
    fun chatIsForTeachersAndStudentsOnly() {
        assertTrue(ChatDestination::class in BUSINESS_ROUTES)
        assertTrue(Role.TEACHER.allows(ChatDestination::class))
        assertTrue(Role.STUDENT.allows(ChatDestination::class))
        assertFalse(Role.INDEPENDENT.allows(ChatDestination::class))
        assertEquals("ChatDestination", ChatDestination("secret", "x").toString())
        start(ready(Role.INDEPENDENT))
        compose.runOnUiThread { nav.navigate(ChatDestination("t1", "u1")) }
        assertAt(HomeDestination)
        assertFalse(chatComposed)
    }

    @Test
    fun destinationsHideTheirUidsInToString() {
        assertEquals("TeacherDemoDestination", TeacherDemoDestination("secret", "x").toString())
        assertEquals("AssignmentDemosDestination", AssignmentDemosDestination("secret", "x").toString())
        assertEquals("StudentRecordingsDestination", StudentRecordingsDestination("secret").toString())
        assertEquals("TeacherAssignmentsDestination", TeacherAssignmentsDestination("secret").toString())
        assertEquals("AssignmentEditDestination", AssignmentEditDestination("secret", "x").toString())
    }

    @Test
    fun nonRolesDeepLinkingToAssignmentRoutesRedirectHomeWithoutComposing() {
        start(ready(Role.STUDENT))
        compose.runOnUiThread { nav.navigate(TeacherAssignmentsDestination("s1")) }
        assertAt(HomeDestination)
        compose.runOnUiThread { nav.navigate(AssignmentEditDestination("s1", null)) }
        assertAt(HomeDestination)
        session.value = ready(Role.TEACHER)
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(AssignmentsDestination) }
        assertAt(HomeDestination)
        assertFalse(assignmentsComposed || teacherAssignmentsComposed || editComposed)
    }

    @Test
    fun withoutReadyAssignmentRoutesRedirectToTheStateRoot() {
        start(SessionState.LoggedOut)
        compose.runOnUiThread { nav.navigate(AssignmentsDestination) }
        assertAt(AuthDestination)
        compose.runOnUiThread { nav.navigate(TeacherAssignmentsDestination("s1")) }
        assertAt(AuthDestination)
        compose.runOnUiThread { nav.navigate(AssignmentEditDestination("s1", "a1")) }
        assertAt(AuthDestination)
        assertFalse(assignmentsComposed || teacherAssignmentsComposed || editComposed)
    }

    @Test
    fun losingTheRoleWhileOnAnEditRouteRedirectsHome() {
        start(ready(Role.TEACHER))
        compose.runOnUiThread { nav.navigate(AssignmentEditDestination("s1", null)) }
        assertAt(AssignmentEditDestination("s1", null))
        session.value = ready(Role.INDEPENDENT)
        assertAt(HomeDestination)
    }
}
