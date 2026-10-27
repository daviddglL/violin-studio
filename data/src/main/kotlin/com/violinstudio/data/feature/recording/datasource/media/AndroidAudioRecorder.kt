package com.violinstudio.data.feature.recording.datasource.media

import com.violinstudio.data.feature.recording.di.RecordingDispatcher
import com.violinstudio.domain.feature.recording.failure.RecordingFailure
import com.violinstudio.domain.feature.recording.model.RecordedTake
import com.violinstudio.domain.feature.recording.model.RecordingLimits
import com.violinstudio.domain.feature.recording.model.RecordingOutcome
import com.violinstudio.domain.feature.recording.model.RecordingSpec
import com.violinstudio.domain.feature.recording.model.RecordingUploadContract
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingHandle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Captura una toma en `{dir}/{id}.part` y, al cerrarla bien, la renombra a `{id}.m4a` (REQ-REC-03). Sin servicio en
 * primer plano ni logs: el fallo viaja como tipo ([RecordingFailure]); el parcial se borra siempre que algo falla.
 *
 * - Una sola toma a la vez (el micro es unico).
 * - El trabajo bloqueante (`start`/`stop`/`release` del MediaRecorder) corre en [dispatcher], en una corrutina propia
 *   que NO depende del llamador: si el llamador se cancela no espera a un `stop` bloqueado; la toma se descarta y el
 *   micro se libera desde ese otro hilo cuando el recorder responde (leccion 4).
 * - La parada automatica es un temporizador en [dispatcher] a `spec.autoStopMs` (<= limite - tolerancia del servidor);
 *   el limite de la plataforma (`setMaxDuration`) se fija al mismo valor como segundo cinturon.
 */
@Singleton
class AndroidAudioRecorder(
    private val factory: MediaRecorderFactory,
    private val directories: RecordingsDirectory,
    dispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long
) : Recorder {
    @Inject
    constructor(
        factory: MediaRecorderFactory,
        directories: RecordingsDirectory,
        @RecordingDispatcher dispatcher: CoroutineDispatcher
    ) : this(factory, directories, dispatcher, { System.nanoTime() / NANOS_PER_MILLI })

    private val ioScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val busy = AtomicBoolean(false)

    override suspend fun start(spec: RecordingSpec): Result<RecordingHandle> {
        if (!RecordingNames.ID.matches(spec.id) || !RecordingNames.UID.matches(spec.uid)) {
            return Result.failure(RecordingFailure.RecorderFailed)
        }
        if (!busy.compareAndSet(false, true)) return Result.failure(RecordingFailure.Busy)
        val opening = ioScope.async { open(spec) }
        return try {
            opening.await()
        } catch (e: CancellationException) {
            // El llamador se fue mientras el micro se abria: la toma que salga se descarta, nadie la va a parar.
            opening.invokeOnCompletion { if (it == null) opening.getCompleted().getOrNull().let(::discardOrphan) }
            throw e
        }
    }

    override suspend fun deleteTake(take: RecordedTake) {
        if (!RecordingNames.ID.matches(take.id) || !RecordingNames.UID.matches(take.uid)) return
        val own = File(directories.forUid(take.uid), "${take.id}.${RecordingUploadContract.extension(take)}")
        // Solo la ruta propia de la toma: una ruta ajena (o con `..`) no se toca.
        if (own.path != take.path) return
        ioScope.launch { own.delete() }.join()
    }

    private fun discardOrphan(handle: RecordingHandle?) {
        (handle as? ActiveHandle)?.discardNow(force = true)
    }

    private fun open(spec: RecordingSpec): Result<RecordingHandle> {
        val dir = directories.forUid(spec.uid)
        val part = File(dir, "${spec.id}.part")
        val finished = File(dir, "${spec.id}.m4a")
        var session: MediaRecorderSession? = null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw DirectoryUnavailable()
            // Huerfanos de una toma anterior (proceso matado, cierre fallido): ninguna toma viva comparte el uid aqui.
            directories.sweepPartials(spec.uid)
            part.delete()
            finished.delete()
            val created = factory.create().also { session = it }
            val handle = ActiveHandle(spec, created, part, finished)
            // El inicio se fija ANTES de arrancar: un aviso temprano del MediaRecorder ya mide desde aqui.
            handle.markStarted(nowMs())
            created.start(part, spec.autoStopMs, handle)
            handle.arm()
            Result.success(handle)
        } catch (e: Exception) {
            runCatching { session?.release() }
            part.delete()
            busy.set(false)
            Result.failure(
                when (e) {
                    is SecurityException -> RecordingFailure.PermissionDenied
                    is DirectoryUnavailable -> RecordingFailure.RecorderFailed
                    else -> RecordingFailure.MicUnavailable
                }
            )
        }
    }

    private class DirectoryUnavailable : Exception()

    private enum class State { RECORDING, DONE, CLOSED }

    private inner class ActiveHandle(
        private val spec: RecordingSpec,
        private val session: MediaRecorderSession,
        private val part: File,
        private val finished: File
    ) : RecordingHandle,
        MediaRecorderSession.Listener {
        private val lock = Any()
        private var state = State.RECORDING
        private var take: RecordedTake? = null

        @Volatile
        private var startedAt = 0L
        private var timer: Job? = null
        private val mutableOutcome = MutableStateFlow<RecordingOutcome?>(null)
        override val outcome: StateFlow<RecordingOutcome?> = mutableOutcome.asStateFlow()

        fun markStarted(now: Long) {
            startedAt = now
        }

        /** Arma la parada automatica, salvo que un aviso temprano ya haya cerrado la toma. */
        fun arm() = synchronized(lock) {
            if (state != State.RECORDING) return@synchronized
            timer = ioScope.launch {
                delay(spec.autoStopMs)
                finish(auto = true)
            }
        }

        override fun onMaxDurationReached() {
            ioScope.launch { finish(auto = true) }
        }

        override fun onError() {
            ioScope.launch { fault() }
        }

        override suspend fun stop(): Result<RecordedTake> {
            val closing = ioScope.async { finish(auto = false) }
            return try {
                closing.await()
            } catch (e: CancellationException) {
                // Nadie recogera la toma: se descarta cuando el recorder termine de cerrarla (otro hilo).
                closing.invokeOnCompletion { discardNow(force = true) }
                throw e
            }
        }

        override suspend fun discard() {
            ioScope.launch { discardNow(force = false) }.join()
        }

        private fun finish(auto: Boolean): Result<RecordedTake> = synchronized(lock) {
            when (state) {
                State.DONE -> return Result.success(take!!)
                State.CLOSED -> return Result.failure(RecordingFailure.RecorderFailed)
                State.RECORDING -> Unit
            }
            timer?.cancel()
            val result = try {
                session.stop()
                closeFile()
            } catch (e: Exception) {
                Result.failure(RecordingFailure.RecorderFailed)
            }
            result.onSuccess {
                take = it
                state = State.DONE
            }.onFailure {
                deleteFiles()
                state = State.CLOSED
            }
            endSession()
            if (auto) {
                mutableOutcome.value = result.fold(
                    { RecordingOutcome.AutoStopped(it) },
                    { RecordingOutcome.Failed(it as? RecordingFailure ?: RecordingFailure.RecorderFailed) }
                )
            }
            result
        }

        private fun closeFile(): Result<RecordedTake> {
            val size = if (part.isFile) part.length() else 0L
            if (size <= 0L || size > RecordingLimits.MAX_AUDIO_BYTES) {
                return Result.failure(RecordingFailure.RecorderFailed)
            }
            val raw = nowMs() - startedAt
            if (raw < RecordingLimits.MIN_DURATION_MS) return Result.failure(RecordingFailure.TooShort)
            if (!part.renameTo(finished)) return Result.failure(RecordingFailure.RecorderFailed)
            val elapsed = raw.coerceAtMost(spec.maxDurationMs)
            return Result.success(
                RecordedTake(spec.id, spec.uid, finished.path, elapsed, size, spec.consentEpoch)
            )
        }

        private fun fault() = synchronized(lock) {
            if (state != State.RECORDING) return@synchronized
            timer?.cancel()
            runCatching { session.stop() }
            deleteFiles()
            state = State.CLOSED
            endSession()
            mutableOutcome.value = RecordingOutcome.Failed(RecordingFailure.RecorderFailed)
        }

        /**
         * [force] = false es el `discard()` publico: tras entregar la toma (DONE) no toca el `.m4a`, ya es de su receptor.
         * [force] = true es la limpieza interna de una toma que nadie va a recoger (llamador cancelado).
         */
        fun discardNow(force: Boolean): Unit = synchronized(lock) {
            if (state == State.CLOSED || (state == State.DONE && !force)) return@synchronized
            timer?.cancel()
            if (state == State.RECORDING) runCatching { session.stop() }
            deleteFiles()
            state = State.CLOSED
            endSession()
        }

        private var ended = false

        /** Libera el micro y el turno exactamente una vez. */
        private fun endSession() {
            if (ended) return
            ended = true
            runCatching { session.release() }
            busy.set(false)
        }

        private fun deleteFiles() {
            part.delete()
            finished.delete()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
