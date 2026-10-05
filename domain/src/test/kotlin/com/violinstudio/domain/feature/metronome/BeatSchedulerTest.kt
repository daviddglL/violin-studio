package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BeatSchedulerTest {
    private val sr = 44_100

    private fun exact(k: Long, bpm: Int, origin: Long = 0): Long =
        origin + BigDecimal(k * 60L * sr).divide(BigDecimal(bpm), 0, RoundingMode.HALF_UP).toLong()

    @Test
    fun `los intervalos a 30, 120 y 250 BPM son 88200, 22050 y 10584 muestras`() {
        listOf(30 to 88_200L, 120 to 22_050L, 250 to 10_584L).forEach { (bpm, interval) ->
            val scheduler = BeatScheduler(Tempo(bpm), TimeSignature.FOUR_FOUR)
            (0L..16L).forEach { assertEquals(it * interval, scheduler.sampleOf(it)) }
        }
    }

    @Test
    fun `tras 10000 tiempos el indice es exacto sin deriva, en 4-4 y 6-8, con origen`() {
        listOf(30, 120, 137, 247, 250).forEach { bpm ->
            TimeSignature.entries.forEach { signature ->
                val scheduler = BeatScheduler(Tempo(bpm), signature, originSample = 1_234)
                ((0L..10_000L step 7) + 10_000L).forEach {
                    assertEquals(exact(it, bpm, 1_234), scheduler.sampleOf(it), "bpm=$bpm k=$it")
                }
            }
        }
    }

    @Test
    fun `el acento cae en k mod tiempos == 0 para cada compas`() {
        TimeSignature.entries.forEach { signature ->
            val scheduler = BeatScheduler(Tempo(120), signature)
            (0L until 24L).forEach {
                assertEquals(it % signature.beats == 0L, scheduler.isAccent(it), "$signature k=$it")
            }
        }
    }

    @Test
    fun `beatAt devuelve el ultimo tiempo ya sonado y firstBeatAtOrAfter el siguiente`() {
        val scheduler = BeatScheduler(Tempo(137), TimeSignature.THREE_FOUR, originSample = 500)
        (0L..50L).forEach { k ->
            val at = scheduler.sampleOf(k)
            assertEquals(k, scheduler.beatAt(at))
            assertEquals(k, scheduler.beatAt(at + 1))
            assertEquals(k - 1, scheduler.beatAt(at - 1))
            assertEquals(k, scheduler.firstBeatAtOrAfter(at))
            assertEquals(k + 1, scheduler.firstBeatAtOrAfter(at + 1))
        }
        assertEquals(-1, scheduler.beatAt(499))
        assertEquals(0, scheduler.firstBeatAtOrAfter(0))
    }

    @Test
    fun `retempo conserva el siguiente clic y usa el nuevo intervalo despues, sin saltar de fase`() {
        val old = BeatScheduler(Tempo(100), TimeSignature.FOUR_FOUR)
        val atSample = old.sampleOf(2) + 1
        val next = old.sampleOf(3)
        val changed = old.retempo(Tempo(120), atSample)
        assertEquals(next, changed.sampleOf(3))
        assertEquals(next + 22_050, changed.sampleOf(4))
        assertEquals(next + 2 * 22_050, changed.sampleOf(5))
        assertEquals(true, changed.isAccent(4))
        assertEquals(3, changed.firstBeatAtOrAfter(atSample))
    }

    @Test
    fun `k enorme con un origen Long grande sigue siendo exacto`() {
        val origin = 5_000_000_000_000L
        listOf(30, 137, 250).forEach { bpm ->
            val scheduler = BeatScheduler(Tempo(bpm), TimeSignature.FOUR_FOUR, origin)
            val k = 1_000_000_000L
            assertEquals(exact(k, bpm, origin), scheduler.sampleOf(k))
            assertEquals(k, scheduler.beatAt(scheduler.sampleOf(k)))
            assertEquals(k - 1, scheduler.beatAt(scheduler.sampleOf(k) - 1))
        }
    }

    @Test
    fun `beatAt es la inversa de sampleOf para todos los BPM hasta k 10000`() {
        (Tempo.MIN_BPM..Tempo.MAX_BPM).forEach { bpm ->
            val scheduler = BeatScheduler(Tempo(bpm), TimeSignature.SIX_EIGHT)
            ((0L..10_000L step 97) + 10_000L).forEach { k ->
                assertEquals(k, scheduler.beatAt(scheduler.sampleOf(k)), "bpm=$bpm k=$k")
                assertEquals(k - 1, scheduler.beatAt(scheduler.sampleOf(k) - 1), "bpm=$bpm k=$k")
            }
        }
    }

    @Test
    fun `retempo exactamente sobre un clic lo conserva como nuevo origen`() {
        val old = BeatScheduler(Tempo(100), TimeSignature.FOUR_FOUR)
        val changed = old.retempo(Tempo(120), old.sampleOf(3))
        assertEquals(3, changed.firstBeat)
        assertEquals(old.sampleOf(3), changed.sampleOf(3))
    }

    @Test
    fun `retempos encadenados componen las posiciones`() {
        val first = BeatScheduler(Tempo(100), TimeSignature.THREE_FOUR)
        val second = first.retempo(Tempo(120), first.sampleOf(1) + 5)
        val third = second.retempo(Tempo(60), second.sampleOf(4) - 1)
        assertEquals(second.sampleOf(4), third.sampleOf(4))
        assertEquals(second.sampleOf(4) + 44_100, third.sampleOf(5))
        assertEquals(first.sampleOf(2), second.sampleOf(2))
        assertEquals(true, third.isAccent(6))
    }

    @Test
    fun `sampleOf rechaza tiempos anteriores al primero`() {
        assertThrows(IllegalArgumentException::class.java) {
            BeatScheduler(Tempo(120), TimeSignature.FOUR_FOUR, 0, firstBeat = 3).sampleOf(2)
        }
    }
}
