package com.violinstudio.data.feature.recording.di

import com.violinstudio.data.feature.recording.datasource.media.AndroidAudioRecorder
import com.violinstudio.domain.feature.recording.repository.Recorder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatcher del grabador: hilos de E/S (los `stop`/`release` del MediaRecorder pueden bloquear). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RecordingDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds
    @Singleton
    abstract fun bindRecorder(impl: AndroidAudioRecorder): Recorder

    companion object {
        @Provides
        @RecordingDispatcher
        fun provideRecordingDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
