package com.violinstudio.data.commons.di

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * `@Singleton` es una instancia por componente de Hilt, no por proceso: los tests instrumentados crean un componente
 * por test y un segundo DataStore sobre el mismo fichero lanza `IllegalStateException` al usarse.
 */
class DataStoreModuleTest {
    @Test
    fun `dos componentes comparten el mismo DataStore del proceso`() {
        val dir: File = Files.createTempDirectory("datastore-module").toFile()
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { filesDir } returns dir
        }

        val first = DataStoreModule.provideUserLocalDataStore(context)
        val second = DataStoreModule.provideUserLocalDataStore(context)

        assertSame(first, second)
    }
}
