package com.violinstudio.ui.commons.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectSettledTest {
    @Test
    fun `una emision vacia repetida no reinicia el margen de asentamiento`() = runTest {
        val source = MutableSharedFlow<List<Int>>()
        val seen = mutableListOf<List<Int>>()
        val job = launch { source.collectSettled(isEmpty = { it.isEmpty() }) { seen += it } }
        runCurrent()
        source.emit(emptyList())
        advanceTimeBy(SETTLE_MS - 500)
        source.emit(emptyList())
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `una emision con datos dentro del margen se entrega al instante y cancela la espera`() = runTest {
        val source = MutableSharedFlow<List<Int>>()
        val seen = mutableListOf<List<Int>>()
        val job = launch { source.collectSettled(isEmpty = { it.isEmpty() }) { seen += it } }
        runCurrent()
        source.emit(emptyList())
        advanceTimeBy(100)
        source.emit(listOf(1))
        runCurrent()
        assertEquals(listOf(listOf(1)), seen)
        advanceTimeBy(SETTLE_MS * 2)
        assertEquals(listOf(listOf(1)), seen)
        job.cancel()
    }

    @Test
    fun `tras haber visto algo el vacio es inmediato`() = runTest {
        val source = MutableSharedFlow<List<Int>>()
        val seen = mutableListOf<List<Int>>()
        val job = launch { source.collectSettled(isEmpty = { it.isEmpty() }) { seen += it } }
        runCurrent()
        source.emit(listOf(1))
        source.emit(emptyList())
        runCurrent()
        assertEquals(listOf(listOf(1), emptyList()), seen)
        job.cancel()
    }

    @Test
    fun `si el flujo termina mientras espera se entrega al vencer el margen`() = runTest {
        val seen = mutableListOf<List<Int>>()
        val job = launch {
            flow { emit(emptyList<Int>()) }.collectSettled(isEmpty = { it.isEmpty() }) { seen += it }
        }
        runCurrent()
        advanceTimeBy(SETTLE_MS - 1)
        assertTrue(seen.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(emptyList<Int>()), seen)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `si el flujo falla mientras espera se descarta la entrega pendiente`() = runTest {
        val seen = mutableListOf<List<Int>>()
        var failure: Throwable? = null
        val job = launch {
            try {
                flow<List<Int>> {
                    emit(emptyList())
                    throw IllegalStateException("boom")
                }.collectSettled(isEmpty = { it.isEmpty() }) { seen += it }
            } catch (e: IllegalStateException) {
                failure = e
            }
        }
        advanceTimeBy(SETTLE_MS * 2)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(failure is IllegalStateException)
        assertTrue(seen.isEmpty())
    }
}
