package com.violinstudio.ui.commons.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Qualifier

/** Reloj monotono (no salta con el reloj de pared): para medir intervalos, como el tap tempo. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MonotonicClock

/** [millis] es `SystemClock.elapsedRealtime()`; [instant] solo vale para diferencias, no como fecha. */
class ElapsedRealtimeClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun millis(): Long = SystemClock.elapsedRealtime()
}

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @MonotonicClock
    fun provideMonotonicClock(): Clock = ElapsedRealtimeClock()
}
