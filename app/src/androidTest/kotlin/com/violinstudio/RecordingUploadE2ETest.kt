package com.violinstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.violinstudio.domain.feature.consent.usecase.GetIdentityConfigUseCase
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.usecase.FinishRecordingUseCase
import com.violinstudio.domain.feature.recording.usecase.GrantRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.RunUploadQueueUseCase
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grabacion de audio (fase 4b) de punta a punta contra los emuladores (Auth, Firestore, Storage y el trigger
 * `finalizeRecording` con las reglas reales): un adulto concede el consentimiento de grabacion, encola una toma y el
 * motor de subida de la app la sube a Storage con el sello de la epoca; el servidor la valida y escribe el doc `ready`.
 *
 * El emulador de Android arranca sin audio (`-noaudio`), asi que la toma no se graba con el micro: es un `.m4a`
 * minimo ([minimalMp4]) que el parser del servidor acepta, en la carpeta real de las tomas.
 * `HiltTestApplication` no ejecuta `ViolinStudioApp.onCreate`, asi que el test arranca el vigia de la cola el mismo; la
 * subida la hace el trabajo real de WorkManager (`UploadQueueWorker`).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordingUploadE2ETest : E2eTest() {
    @Inject
    lateinit var identityConfig: GetIdentityConfigUseCase

    @Inject
    lateinit var grant: GrantRecordingConsentUseCase

    @Inject
    lateinit var accounts: RecordingAccountSource

    @Inject
    lateinit var finish: FinishRecordingUseCase

    @Inject
    lateinit var queue: RecordingQueue

    @Inject
    lateinit var runQueue: RunUploadQueueUseCase

    @Test
    fun aQueuedTakeIsUploadedAndFinalizedAsReady() = runBlocking {
        val email = uniqueEmail("rec")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")

        val version = identityConfig().getOrThrow().recordingConsentVersion
        assertTrue(grant(version).isSuccess)
        val account = accounts.current(uid).getOrThrow()
        assertTrue(account.recordingConsentGranted)

        val id = UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(File(context.noBackupFilesDir, "recordings"), uid), "$id.m4a")
        file.parentFile!!.mkdirs()
        file.writeBytes(minimalMp4(brand = "M4A ", durationMs = DURATION_MS))
        val take = RecordedTake(id, uid, file.path, DURATION_MS, file.length(), account.consentEpoch)
        assertTrue(finish(take).isSuccess)

        val engine = launch(Dispatchers.Default) { runQueue() }
        try {
            awaitUploaded(uid, id, file)
        } finally {
            engine.cancelAndJoin()
        }
    }

    private fun awaitUploaded(uid: String, id: String, file: File) {
        awaitBackend("grabacion ready", UPLOAD_TIMEOUT_MS) {
            Emulators.stringField("users/$uid/recordings/$id", "status") == "ready"
        }
        val doc = checkNotNull(Emulators.docFields("users/$uid/recordings/$id"))
        assertEquals("audio", doc.optJSONObject("kind")?.optString("stringValue"))
        awaitBackend("toma fuera de la cola local", UPLOAD_TIMEOUT_MS) {
            runBlocking { queue.observe(uid).first() }.none { it.id == id }
        }
        awaitBackend("fichero local borrado tras subir") { !file.exists() }
    }

    private companion object {
        const val DURATION_MS = 5_000L
        const val UPLOAD_TIMEOUT_MS = 120_000L
    }
}
