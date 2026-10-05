package com.violinstudio.data.commons.erasure

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DataStoreUserDataEraserTest {
    private class RecordingRepo : TunerConfigRepository {
        val cleared = mutableListOf<String>()

        override fun observe(uid: String): Flow<TunerConfig> = emptyFlow()

        override suspend fun update(uid: String, transform: (TunerConfig) -> TunerConfig) = Unit

        override suspend fun clear(uid: String) {
            if (uid.contains('.')) throw IllegalArgumentException("uid con punto")
            cleared += uid
        }
    }

    private val repo = RecordingRepo()
    private val eraser = DataStoreUserDataEraser(repo)

    @Test
    fun `borra los datos locales del uid delegando en clear`() = runTest {
        eraser.erase("u1")
        assertEquals(listOf("u1"), repo.cleared)
    }

    @Test
    fun `un uid con punto no rompe el borrado`() = runTest {
        eraser.erase("a.b")
        assertEquals(emptyList<String>(), repo.cleared)
    }

    @Test
    fun `un fallo de almacenamiento se propaga para que el llamante lo registre`() = runTest {
        val failing = DataStoreUserDataEraser(object : TunerConfigRepository by repo {
            override suspend fun clear(uid: String) = throw TunerFailure.StorageUnavailable
        })
        assertThrows<TunerFailure.StorageUnavailable> { failing.erase("u1") }
    }
}
