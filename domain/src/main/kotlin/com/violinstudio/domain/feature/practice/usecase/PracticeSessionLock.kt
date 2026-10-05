package com.violinstudio.domain.feature.practice.usecase

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Serializa iniciar y parar. Debe haber UNA instancia por proceso: se provee como `@Singleton` y se inyecta en
 * [StartPracticeSessionUseCase] y [StopPracticeSessionUseCase] (los casos de uso en sí pueden no ser singleton).
 */
@Singleton
class PracticeSessionLock @Inject constructor() {
    internal val mutex = Mutex()
}
