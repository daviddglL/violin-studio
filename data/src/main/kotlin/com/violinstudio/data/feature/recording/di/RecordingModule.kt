package com.violinstudio.data.feature.recording.di

import com.google.firebase.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import com.violinstudio.data.commons.firebase.EmulatorConfig
import com.violinstudio.data.commons.firebase.EmulatorOnce
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseFinalizeStatusSource
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingAccountSource
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingBytesSource
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingConsentRepository
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingLibrary
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingReviews
import com.violinstudio.data.feature.recording.datasource.firebase.FirebaseRecordingUploader
import com.violinstudio.data.feature.recording.datasource.local.RecordingQueueStore
import com.violinstudio.data.feature.recording.datasource.media.AndroidAudioClipOutput
import com.violinstudio.data.feature.recording.datasource.media.AndroidAudioRecorder
import com.violinstudio.data.feature.recording.datasource.media.AndroidMediaRecorderFactory
import com.violinstudio.data.feature.recording.datasource.media.AudioClipOutput
import com.violinstudio.data.feature.recording.datasource.media.MediaRecorderFactory
import com.violinstudio.data.feature.recording.datasource.media.NoBackupRecordingsDirectory
import com.violinstudio.data.feature.recording.datasource.media.RecordingBytesSource
import com.violinstudio.data.feature.recording.datasource.media.RecordingsDirectory
import com.violinstudio.data.feature.recording.repository.MemoryRecordingPlayer
import com.violinstudio.domain.feature.recording.repository.FinalizeStatusSource
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.Recorder
import com.violinstudio.domain.feature.recording.repository.RecordingAccountSource
import com.violinstudio.domain.feature.recording.repository.RecordingConsentRepository
import com.violinstudio.domain.feature.recording.repository.RecordingLibrary
import com.violinstudio.domain.feature.recording.repository.RecordingPlayer
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import com.violinstudio.domain.feature.recording.repository.RecordingReviews
import com.violinstudio.domain.feature.recording.repository.RecordingUploader
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
    abstract fun bindRecordingUploader(impl: FirebaseRecordingUploader): RecordingUploader

    @Binds
    abstract fun bindRecordingAccountSource(impl: FirebaseRecordingAccountSource): RecordingAccountSource

    @Binds
    abstract fun bindFinalizeStatusSource(impl: FirebaseFinalizeStatusSource): FinalizeStatusSource

    @Binds
    abstract fun bindRecordingLibrary(impl: FirebaseRecordingLibrary): RecordingLibrary

    @Binds
    abstract fun bindRecordingConsentRepository(impl: FirebaseRecordingConsentRepository): RecordingConsentRepository

    @Binds
    abstract fun bindRecordingReviews(impl: FirebaseRecordingReviews): RecordingReviews

    @Binds
    abstract fun bindRecordingPlayer(impl: MemoryRecordingPlayer): RecordingPlayer

    @Binds
    abstract fun bindAudioClipOutput(impl: AndroidAudioClipOutput): AudioClipOutput

    @Binds
    abstract fun bindRecordingBytesSource(impl: FirebaseRecordingBytesSource): RecordingBytesSource

    @Binds
    abstract fun bindMediaRecorderFactory(impl: AndroidMediaRecorderFactory): MediaRecorderFactory

    @Binds
    abstract fun bindRecordingsDirectory(impl: NoBackupRecordingsDirectory): RecordingsDirectory

    companion object {
        @Provides
        @RecordingDispatcher
        fun provideRecordingDispatcher(): CoroutineDispatcher = Dispatchers.IO

        /**
         * Storage solo se usa para las grabaciones (`PracticeAudioSourcesTest` lo vigila), por eso vive aqui y no en
         * `FirebaseModule`. El SDK reintenta los fallos de red hasta [STORAGE_RETRY_MS]: se acota para que la cola
         * recupere el control (y su espera exponencial) en vez de quedarse 10 min en un intento.
         */
        @Provides
        @Singleton
        fun provideStorage(config: EmulatorConfig): FirebaseStorage = Firebase.storage.apply {
            maxUploadRetryTimeMillis = STORAGE_RETRY_MS
            maxOperationRetryTimeMillis = STORAGE_RETRY_MS
            config.storage()?.let { e -> EmulatorOnce.process.apply("storage") { useEmulator(e.host, e.port) } }
        }

        private const val STORAGE_RETRY_MS = 2 * 60_000L
    }
}
