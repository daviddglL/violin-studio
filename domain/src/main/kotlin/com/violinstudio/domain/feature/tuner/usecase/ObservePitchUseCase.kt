package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import com.violinstudio.domain.feature.tuner.audio.FrameAssembler
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.pitch.DetectorProfile
import com.violinstudio.domain.feature.tuner.pitch.PitchDetector
import com.violinstudio.domain.feature.tuner.pitch.PitchEstimate
import com.violinstudio.domain.feature.tuner.pitch.PitchSmoother
import com.violinstudio.domain.feature.tuner.pitch.TuningResolver
import com.violinstudio.domain.feature.tuner.pitch.YinPitchDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Observa el tono del micro: chunks -> frames solapados -> deteccion (en [dispatcher]) -> suavizado ->
 * objetivo/cents. `conflate` acota la cola a una lectura: un consumidor lento recibe la mas reciente.
 * Cada coleccion crea su propio detector y suavizador (no son thread-safe). Los fallos de la fuente
 * que no sean [TunerFailure] se notifican como [TunerFailure.MicUnavailable].
 */
class ObservePitchUseCase(
    private val source: AudioInputSource,
    private val dispatcher: CoroutineDispatcher,
    private val detectorFactory: (DetectorProfile) -> PitchDetector = { YinPitchDetector(it) }
) {
    operator fun invoke(instrument: Instrument, config: TunerConfig, selected: Int?): Flow<TunerReading> {
        val profile = DetectorProfile.of(instrument)
        return flow {
            val detector = detectorFactory(profile)
            val assembler = FrameAssembler(profile.frameSize, profile.hop)
            val smoother = PitchSmoother()
            source.frames(profile.hop)
                .assemble(assembler)
                .map { detector.detect(it) }
                .flowOn(dispatcher)
                .map { smoother.update(it) }
                .map { reading(it, instrument, config, selected) }
                .catch { throw if (it is TunerFailure) it else TunerFailure.MicUnavailable }
                .collect { emit(it) }
        }.conflate()
    }

    private fun reading(estimate: PitchEstimate?, instrument: Instrument, config: TunerConfig, selected: Int?) =
        estimate
            ?.let { TuningResolver.resolve(it.frequency, instrument, config.referencePitch, selected) }
            ?.let { TunerReading.Pitch(estimate.frequency, it.target, it.cents, estimate.confidence) }
            ?: TunerReading.NoPitch

    /** Los frames son el buffer reutilizado del ensamblador: se consumen antes del siguiente. */
    private fun Flow<FloatArray>.assemble(assembler: FrameAssembler): Flow<FloatArray> = flow {
        collect { chunk ->
            assembler.offer(chunk)
            while (true) emit(assembler.poll() ?: break)
        }
    }
}
