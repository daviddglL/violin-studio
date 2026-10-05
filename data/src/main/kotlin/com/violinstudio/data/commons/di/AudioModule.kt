package com.violinstudio.data.commons.di

import android.content.Context
import android.media.AudioManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioInputDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    const val AUDIO_INPUT_THREAD = "audio-in"

    /** Hilo unico y dedicado: `AudioRecord.read` bloquea. */
    @Provides
    @Singleton
    @AudioInputDispatcher
    fun provideAudioInputDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, AUDIO_INPUT_THREAD) }.asCoroutineDispatcher()

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
