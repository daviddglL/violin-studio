package com.violinstudio.ui.feature.chat.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.violinstudio.domain.feature.chat.failure.ChatFailure
import com.violinstudio.domain.feature.chat.model.ChatMessage
import com.violinstudio.domain.feature.chat.model.ChatThread
import com.violinstudio.domain.feature.chat.usecase.DeleteChatMessageUseCase
import com.violinstudio.domain.feature.chat.usecase.IsChatEnabledUseCase
import com.violinstudio.domain.feature.chat.usecase.ObserveChatUseCase
import com.violinstudio.domain.feature.chat.usecase.SendChatMessageUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class ChatViewModelTest {
    private val mine = ChatMessage("m1", "u1", "hola", null, pendingSync = true)
    private val theirs = ChatMessage("m2", "t1", "que tal", null, pendingSync = false)
    private val thread = MutableStateFlow(ChatThread("u1", listOf(mine, theirs)))
    private val enabled = mockk<IsChatEnabledUseCase> { coEvery { this@mockk.invoke() } returns Result.success(true) }
    private val observe = mockk<ObserveChatUseCase> { every { this@mockk.invoke("t1", "u1") } returns thread }
    private val send = mockk<SendChatMessageUseCase> {
        coEvery { this@mockk.invoke(any(), any(), any()) } returns Result.success(Unit)
    }
    private val delete = mockk<DeleteChatMessageUseCase> {
        coEvery { this@mockk.invoke(any()) } returns Result.success(Unit)
    }

    private fun TestScope.viewModel(teacher: String? = "t1", student: String? = "u1"): ChatViewModel {
        val args = buildMap {
            teacher?.let { put(ChatViewModel.TEACHER_UID_KEY, it) }
            student?.let { put(ChatViewModel.STUDENT_UID_KEY, it) }
        }
        return ChatViewModel(SavedStateHandle(args), enabled, observe, send, delete).also { runCurrent() }
    }

    private val ChatViewModel.now get() = state.value

    private fun TestScope.intent(vm: ChatViewModel, vararg intents: ChatIntent) {
        intents.forEach(vm::onIntent)
        runCurrent()
    }

    @Test
    fun `carga la conversacion del par de la ruta`() = runTest {
        val vm = viewModel()
        assertFalse(vm.now.isLoading)
        assertEquals("u1", vm.now.myUid)
        assertEquals(listOf(mine, theirs), vm.now.messages)
        thread.value = ChatThread("u1", emptyList())
        runCurrent()
        assertTrue(vm.now.messages.isEmpty())
    }

    @Test
    fun `con el interruptor apagado no se observa nada`() = runTest {
        coEvery { enabled() } returns Result.success(false)
        val vm = viewModel()
        assertTrue(vm.now.disabled)
        intent(vm, ChatIntent.DraftChanged("hola"), ChatIntent.Send)
        verify(exactly = 0) { observe(any(), any()) }
        coVerify(exactly = 0) { send(any(), any(), any()) }
    }

    @Test
    fun `sin argumentos, sin config o si las reglas deniegan muestra el fallo y reintenta`() = runTest {
        assertTrue(viewModel(teacher = null).now.loadFailed)
        assertTrue(viewModel(student = null).now.loadFailed)
        coEvery { enabled() } returns Result.failure(IllegalStateException("x"))
        assertTrue(viewModel().now.loadFailed)
        coEvery { enabled() } returns Result.success(true)
        every { observe("t1", "u1") } returns flow { throw IllegalStateException("denied") }
        val vm = viewModel()
        assertTrue(vm.now.loadFailed)
        every { observe("t1", "u1") } returns thread
        intent(vm, ChatIntent.Retry)
        assertFalse(vm.now.loadFailed)
        assertEquals(2, vm.now.messages.size)
        // Con la conversacion cargada, reintentar no vuelve a suscribirse.
        intent(vm, ChatIntent.Retry)
        verify(exactly = 2) { observe("t1", "u1") }
    }

    @Test
    fun `enviar manda el borrador al par y lo vacia`() = runTest {
        val vm = viewModel()
        intent(vm, ChatIntent.Send)
        coVerify(exactly = 0) { send(any(), any(), any()) }
        intent(vm, ChatIntent.DraftChanged(" hola "), ChatIntent.Send)
        coVerify(exactly = 1) { send("t1", "u1", " hola ") }
        assertEquals("", vm.now.draft)
        assertFalse(vm.now.isSending)
    }

    @Test
    fun `un fallo al enviar conserva el borrador y se muestra sin detalle`() = runTest {
        val vm = viewModel()
        coEvery { send(any(), any(), any()) } returns Result.failure(ChatFailure.PermissionDenied)
        intent(vm, ChatIntent.DraftChanged("hola"), ChatIntent.Send)
        assertEquals(ChatError.Denied, vm.now.error)
        assertEquals("hola", vm.now.draft)
        coEvery { send(any(), any(), any()) } returns Result.failure(ChatFailure.TooLong)
        intent(vm, ChatIntent.Send)
        assertEquals(ChatError.TooLong, vm.now.error)
        coEvery { send(any(), any(), any()) } throws IllegalStateException("boom")
        intent(vm, ChatIntent.Send)
        assertEquals(ChatError.Unknown, vm.now.error)
    }

    @Test
    fun `solo se pueden borrar los propios y tras confirmar`() = runTest {
        val vm = viewModel()
        intent(vm, ChatIntent.RequestDelete("m2"))
        assertNull(vm.now.confirmingDelete)
        intent(vm, ChatIntent.RequestDelete("m1"))
        assertEquals("m1", vm.now.confirmingDelete)
        intent(vm, ChatIntent.CancelDelete)
        assertNull(vm.now.confirmingDelete)
        coVerify(exactly = 0) { delete(any()) }
        intent(vm, ChatIntent.RequestDelete("m1"), ChatIntent.ConfirmDelete)
        coVerify(exactly = 1) { delete("m1") }
        assertNull(vm.now.confirmingDelete)
        // Confirmar sin dialogo no borra nada.
        intent(vm, ChatIntent.ConfirmDelete)
        coVerify(exactly = 1) { delete(any()) }
    }

    @Test
    fun `un fallo al borrar se muestra`() = runTest {
        coEvery { delete(any()) } returns Result.failure(ChatFailure.Unknown)
        val vm = viewModel()
        intent(vm, ChatIntent.RequestDelete("m1"), ChatIntent.ConfirmDelete)
        assertEquals(ChatError.Unknown, vm.now.error)
    }
}
