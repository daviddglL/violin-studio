package com.violinstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.domain.feature.consent.usecase.GetIdentityConfigUseCase
import com.violinstudio.domain.feature.recording.model.BlurredVideo
import com.violinstudio.domain.feature.recording.model.FaceBlurPolicy
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.VideoBlurrer
import com.violinstudio.domain.feature.recording.repository.VideoFiles
import com.violinstudio.domain.feature.recording.usecase.FinishVideoUseCase
import com.violinstudio.domain.feature.recording.usecase.GrantRecordingConsentUseCase
import com.violinstudio.domain.feature.recording.usecase.RunUploadQueueUseCase
import com.violinstudio.domain.feature.recording.usecase.StartVideoUseCase
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grabacion de video (fase 4c) de punta a punta contra los emuladores: un adulto concede el consentimiento de
 * grabacion, la app prepara la toma con `videoEnabled` del servidor ([StartVideoUseCase]), [FinishVideoUseCase] borra
 * el original y encola solo el difuminado, el motor de subida lo sube como `.mp4` con el sello `blurApplied` /
 * `blurPolicyVersion` (reglas de Storage reales) y `finalizeRecording` escribe el doc `ready` de tipo `video`.
 *
 * El emulador no tiene camara real ni una toma H.264 que difuminar, asi que el difuminado lo sustituye [CopyBlurrer]:
 * copia un MP4 minimo (marca `isom`, [minimalMp4]) con la version de politica vigente. El difuminado real
 * (`FaceBlurVideoProcessor` con Media3) queda fuera de este test. Como en el de audio, `HiltTestApplication` no
 * ejecuta `ViolinStudioApp.onCreate` y el test arranca el vigia de la cola el mismo.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VideoUploadE2ETest : E2eTest() {
    @Inject
    lateinit var identityConfig: GetIdentityConfigUseCase

    @Inject
    lateinit var grant: GrantRecordingConsentUseCase

    @Inject
    lateinit var accounts: RecordingAccountSource

    @Inject
    lateinit var start: StartVideoUseCase

    @Inject
    lateinit var files: VideoFiles

    @Inject
    lateinit var queue: RecordingQueue

    @Inject
    lateinit var runQueue: RunUploadQueueUseCase

    @Test
    fun aBlurredVideoIsUploadedAndFinalizedAsReady() = runBlocking {
        val email = uniqueEmail("vid")
        journey.registerUpToHome(email)
        val uid = checkNotNull(Emulators.authUser(email)).getString("localId")

        val config = identityConfig().getOrThrow()
        assertTrue("el emulador de Functions debe tener el video encendido", config.videoEnabled)
        assertTrue(grant(config.recordingConsentVersion).isSuccess)
        assertTrue(accounts.current(uid).getOrThrow().recordingConsentGranted)

        val spec = start().getOrThrow()
        val raw = File(spec.rawPath)
        raw.parentFile!!.mkdirs()
        raw.writeBytes(minimalMp4(brand = "isom", durationMs = DURATION_MS))
        assertTrue(FinishVideoUseCase(CopyBlurrer, files, queue)(spec).isSuccess)
        assertFalse("el original sin difuminar no sobrevive a la toma", raw.exists())
        val blurred = File(spec.outputPath)
        assertTrue(blurred.exists())

        val engine = launch(Dispatchers.Default) { runQueue() }
        try {
            awaitUploaded(uid, spec.id, blurred)
        } finally {
            engine.cancelAndJoin()
        }
    }

    private fun awaitUploaded(uid: String, id: String, file: File) {
        awaitBackend("video ready", UPLOAD_TIMEOUT_MS) {
            Emulators.stringField("users/$uid/recordings/$id", "status") == "ready"
        }
        val doc = checkNotNull(Emulators.docFields("users/$uid/recordings/$id"))
        assertEquals("video", doc.optJSONObject("kind")?.optString("stringValue"))
        assertEquals(
            FaceBlurPolicy.VERSION.toString(),
            doc.optJSONObject("blurPolicyVersion")?.optString("integerValue")
        )
        assertEquals("users/$uid/recordings/$id.mp4", doc.optJSONObject("objectPath")?.optString("stringValue"))
        awaitBackend("video fuera de la cola local", UPLOAD_TIMEOUT_MS) {
            runBlocking { queue.observe(uid).first() }.none { it.id == id }
        }
        awaitBackend("fichero local borrado tras subir") { !file.exists() }
    }

    /** Sustituto del difuminado: copia el original al destino y declara la politica vigente, como el real al terminar. */
    private object CopyBlurrer : VideoBlurrer {
        override suspend fun blur(sourcePath: String, outputPath: String): Result<BlurredVideo> {
            val output = File(sourcePath).copyTo(File(outputPath), overwrite = true)
            val video = BlurredVideo(output.path, DURATION_MS, output.length(), FaceBlurPolicy.VERSION)
            return Result.success(video)
        }
    }

    private companion object {
        const val DURATION_MS = 5_000L
        const val UPLOAD_TIMEOUT_MS = 120_000L
    }
}
