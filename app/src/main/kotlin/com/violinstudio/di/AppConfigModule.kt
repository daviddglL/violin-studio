package com.violinstudio.di

import com.violinstudio.BuildConfig
import com.violinstudio.core.firebase.EmulatorConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    @Provides
    fun provideEmulatorConfig(): EmulatorConfig =
        EmulatorConfig(enabled = BuildConfig.USE_EMULATORS, host = BuildConfig.EMULATOR_HOST)
}
