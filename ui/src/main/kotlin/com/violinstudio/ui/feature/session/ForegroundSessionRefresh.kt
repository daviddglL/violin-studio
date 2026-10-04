package com.violinstudio.ui.feature.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cada vez que la app vuelve a primer plano pide reevaluar la sesion (politica o consentimiento pudieron cambiar). El
 * limite de frecuencia y la regla de no degradar un Ready viven en el trigger y el caso de uso de sesion.
 */
@Singleton
class ForegroundSessionRefresh @Inject constructor(
    private val trigger: SessionRefreshTrigger
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = trigger.requestForegroundRefresh()

    /** Se registra una sola vez desde `Application.onCreate`; [lifecycle] solo se cambia en tests. */
    fun install(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle) = lifecycle.addObserver(this)
}
