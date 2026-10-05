package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.metronome.usecase.RunMetronomeUseCase
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.PlayReferenceToneUseCase
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** REQ-PRA-02: usar el afinador o el metronomo nunca registra practica (ni siquiera puede: no la reciben). */
class NoImplicitLoggingTest {
    @Test
    fun `los casos de uso de afinador y metronomo no dependen del registro de practica`() {
        val forbidden = listOf<KClass<*>>(PracticeLogRepository::class, RunningSessionStore::class)
        val types = listOf(ObservePitchUseCase::class, PlayReferenceToneUseCase::class, RunMetronomeUseCase::class)
        types.forEach { type ->
            type.java.constructors.flatMap { it.parameterTypes.toList() }.forEach { param ->
                assertTrue(forbidden.none { it.java.isAssignableFrom(param) }, "${type.simpleName} depende de practica")
            }
        }
    }
}
