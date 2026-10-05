package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeTunerConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunerConfigUseCasesTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakeTunerConfigRepository()
    private val observe = ObserveTunerConfigUseCase(auth, repo)
    private val update = UpdateTunerConfigUseCase(auth, repo)
    private val save = SaveTuningPresetUseCase(auth, repo)
    private val delete = DeleteTuningPresetUseCase(auth, repo)
    private val select = SelectTuningPresetUseCase(auth, repo)

    private fun invalid(result: Result<*>, field: TunerField) =
        assertEquals(TunerFailure.InvalidConfig(field), result.exceptionOrNull())

    @Test
    fun `sin configuracion se observan los defectos 440 y 50 y sin sesion tambien`() = runTest {
        assertEquals(TunerConfig(), observe().first())
        auth.user = null
        assertEquals(TunerConfig(), observe().first())
    }

    @Test
    fun `actualizar valida los rangos y persiste por el uid de la sesion`() = runTest {
        invalid(update(414.9, 50), TunerField.REFERENCE_PITCH)
        invalid(update(440.0, 201), TunerField.MAX_CENTS)
        assertTrue(update(415.0, 200).isSuccess)
        val saved = observe().first()
        assertEquals(ReferencePitch(415.0), saved.referencePitch)
        assertEquals(MaxCents(200), saved.maxCents)
    }

    @Test
    fun `guardar crea y edita presets, valida la etiqueta y respeta el limite de 20`() = runTest {
        invalid(save(null, "", 440.0, 50), TunerField.LABEL)
        val id = save(null, "Barroco", 415.0, 50).getOrThrow()
        assertTrue(save(id, "Barroco 2", 415.0, 60).isSuccess)
        assertEquals(listOf("Barroco 2"), observe().first().presets.map { it.label })
        repeat(19) { save(null, "p$it", 440.0, 50).getOrThrow() }
        assertEquals(TunerFailure.PresetLimitReached, save(null, "extra", 440.0, 50).exceptionOrNull())
    }

    @Test
    fun `seleccionar copia ref y maxCents a la config activa y borrar el activo vuelve a defectos`() = runTest {
        val id = save(null, "Barroco", 415.0, 75).getOrThrow()
        assertTrue(select(id).isSuccess)
        observe().first().let {
            assertEquals(ReferencePitch(415.0), it.referencePitch)
            assertEquals(MaxCents(75), it.maxCents)
            assertEquals(id, it.selectedPresetId)
        }
        delete(id)
        assertEquals(TunerConfig(), observe().first())
        assertNull(observe().first().selectedPresetId)
    }

    @Test
    fun `sin sesion las escrituras fallan sin tocar el repositorio`() = runTest {
        auth.user = null
        assertTrue(update(440.0, 50).isFailure)
        assertTrue(select("x").isFailure)
        assertTrue(repo.cleared.isEmpty())
    }
}
