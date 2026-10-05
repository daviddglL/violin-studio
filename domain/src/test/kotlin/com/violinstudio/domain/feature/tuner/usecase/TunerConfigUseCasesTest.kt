package com.violinstudio.domain.feature.tuner.usecase

import app.cash.turbine.test
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
    fun `sin sesion las escrituras devuelven NoSession`() = runTest {
        auth.user = null
        assertEquals(TunerFailure.NoSession, update(440.0, 50).exceptionOrNull())
        assertEquals(TunerFailure.NoSession, select("x").exceptionOrNull())
        assertTrue(repo.cleared.isEmpty())
    }

    @Test
    fun `seleccionar un preset desconocido falla y un id en blanco se rechaza`() = runTest {
        assertEquals(TunerFailure.PresetNotFound, select("nope").exceptionOrNull())
        invalid(save(" ", "Barroco", 415.0, 50), TunerField.ID)
    }

    @Test
    fun `editar el preset activo actualiza la config activa`() = runTest {
        val id = save(null, "Barroco", 415.0, 75).getOrThrow()
        select(id)
        save(id, "Barroco", 430.0, 100)
        observe().first().let {
            assertEquals(ReferencePitch(430.0), it.referencePitch)
            assertEquals(MaxCents(100), it.maxCents)
            assertEquals(id, it.selectedPresetId)
        }
    }

    @Test
    fun `un fallo de almacenamiento del repositorio llega como Result failure`() = runTest {
        repo.updateFailure = TunerFailure.StorageUnavailable
        assertEquals(TunerFailure.StorageUnavailable, update(440.0, 50).exceptionOrNull())
    }

    @Test
    fun `observar sigue el cambio de uid`() = runTest {
        repo.update("u1") { it.copy(maxCents = MaxCents(100)) }
        observe().test {
            assertEquals(MaxCents(100), awaitItem().maxCents)
            auth.user = verifiedUser.copy(uid = "u2")
            assertEquals(MaxCents.DEFAULT, awaitItem().maxCents)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
