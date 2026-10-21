package com.violinstudio.data.feature.recording.di

import com.violinstudio.data.feature.recording.datasource.local.RecordingQueueStore
import com.violinstudio.data.feature.recording.datasource.media.AndroidAudioRecorder
import com.violinstudio.data.feature.recording.datasource.media.AndroidMediaRecorderFactory
import com.violinstudio.data.feature.recording.datasource.media.MediaRecorderFactory
import com.violinstudio.data.feature.recording.datasource.media.NoBackupRecordingsDirectory
import com.violinstudio.data.feature.recording.datasource.media.RecordingsDirectory
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
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

    @Binds
    abstract fun bindRecordingQueue(impl: RecordingQueueStore): RecordingQueue

    @Binds
    abstract fun bindPendingUploads(impl: RecordingQueueStore): PendingUploads

    @Binds
    abstract fun bindMediaRecorderFactory(impl: AndroidMediaRecorderFactory): MediaRecorderFactory

    @Binds
    abstract fun bindRecordingsDirectory(impl: NoBackupRecordingsDirectory): RecordingsDirectory

    companion object {
        @Provides
        @RecordingDispatcher
        fun provideRecordingDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
