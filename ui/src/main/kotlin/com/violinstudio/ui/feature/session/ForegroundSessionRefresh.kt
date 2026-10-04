package com.violinstudio.ui.feature.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import javax.inject.Inject
import javax.inject.Singleton

/** Cada vez que la app vuelve a primer plano pide reevaluar la sesion (politica o consentimiento pudieron cambiar). */
@Singleton
class ForegroundSessionRefresh @Inject constructor(
    private val trigger: SessionRefreshTrigger
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = trigger.requestRefresh()

    /** Se registra una sola vez desde `Application.onCreate`. */
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)
}
