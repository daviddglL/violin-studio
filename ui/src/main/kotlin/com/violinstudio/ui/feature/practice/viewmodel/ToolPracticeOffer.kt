package com.violinstudio.ui.feature.practice.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.ToolPractice
import com.violinstudio.domain.feature.practice.model.ToolPracticeTimer
import com.violinstudio.domain.feature.practice.usecase.LogToolPracticeUseCase
import java.time.Clock

/**
 * Oferta de guardar como practica el tiempo de una herramienta. [offer] `null`: no hay nada que ofrecer. [saved]: se
 * acaba de guardar. [error]: no se pudo guardar ([PracticeFailure.AlreadyRunning] si hay una sesion manual en curso).
 */
data class ToolPracticeUi(
    val offer: ToolPractice? = null,
    val saved: Boolean = false,
    val error: ToolPracticeError? = null
)

enum class ToolPracticeError { SESSION_RUNNING, FAILED }

/**
 * Lo que comparten el metronomo y el ejercicio de afinacion: cronometran mientras la herramienta esta activa y, al
 * pararla con 1 min o mas, ofrecen guardarlo. Nada se guarda sin [save] (REQ-PRA-02). Se usa desde el hilo de los
 * intents del ViewModel, como el [ToolPracticeTimer].
 */
class ToolPracticeOffer(clock: Clock, private val log: LogToolPracticeUseCase) {
    private val timer = ToolPracticeTimer(clock)

    fun started(current: ToolPracticeUi): ToolPracticeUi {
        timer.start()
        return current.copy(offer = null, saved = false, error = null)
    }

    fun stopped(current: ToolPracticeUi): ToolPracticeUi {
        timer.stop()
        return current.copy(offer = timer.offer())
    }

    suspend fun save(current: ToolPracticeUi): ToolPracticeUi {
        val offer = current.offer ?: return current
        return log(offer).fold(
            onSuccess = {
                timer.reset()
                ToolPracticeUi(saved = true)
            },
            onFailure = {
                val error = if (it == PracticeFailure.AlreadyRunning) {
                    ToolPracticeError.SESSION_RUNNING
                } else {
                    ToolPracticeError.FAILED
                }
                current.copy(error = error)
            }
        )
    }

    fun dismiss(): ToolPracticeUi {
        timer.reset()
        return ToolPracticeUi()
    }
}
