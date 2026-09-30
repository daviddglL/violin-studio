package com.violinstudio.data.commons.di

import com.violinstudio.data.feature.health.datasource.HealthRemoteDataSource
import com.violinstudio.data.feature.health.datasource.firebase.FirebaseHealthRemoteDataSource
import com.violinstudio.data.feature.health.repository.HealthRepositoryImpl
import com.violinstudio.domain.feature.health.repository.HealthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindHealthRemoteDataSource(impl: FirebaseHealthRemoteDataSource): HealthRemoteDataSource

    @Binds
    abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository
}
