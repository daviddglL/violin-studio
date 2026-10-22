package com.violinstudio.di

import com.violinstudio.WorkManagerUploadScheduler
import com.violinstudio.domain.feature.recording.repository.UploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds
    abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler
}
