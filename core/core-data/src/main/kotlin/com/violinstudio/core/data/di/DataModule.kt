package com.violinstudio.core.data.di

import com.violinstudio.core.data.health.HealthRemoteSource
import com.violinstudio.core.data.remote.firebase.FirebaseHealthRemoteSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindHealthRemoteSource(impl: FirebaseHealthRemoteSource): HealthRemoteSource
}
