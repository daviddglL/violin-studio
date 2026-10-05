package com.violinstudio.data.commons.di

import android.content.Context
import android.media.AudioManager
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.usecase.ObservePitchUseCase
import com.violinstudio.domain.feature.tuner.usecase.PlayReferenceToneUseCase
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
annotation class AudioOutputDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    const val AUDIO_INPUT_THREAD = "audio-in"
    const val AUDIO_OUTPUT_THREAD = "audio-out"

    /** Hilo unico y dedicado: `AudioRecord.read` bloquea. */
    @Provides
    @Singleton
    @AudioInputDispatcher
    fun provideAudioInputDispatcher(): CoroutineDispatcher = daemonDispatcher(AUDIO_INPUT_THREAD)

    /** Hilo unico y dedicado: `AudioTrack.write` bloquea. */
    @Provides
    @Singleton
    @AudioOutputDispatcher
    fun provideAudioOutputDispatcher(): CoroutineDispatcher = daemonDispatcher(AUDIO_OUTPUT_THREAD)

    /** Hilo daemon: un bloqueo en el driver de audio nunca debe impedir que el proceso (o el JVM de tests) termine. */
    internal fun daemonDispatcher(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }.asCoroutineDispatcher()

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Provides
    fun providePlayReferenceToneUseCase(output: AudioOutput): PlayReferenceToneUseCase =
        PlayReferenceToneUseCase(output)

    @Provides
    fun provideObservePitchUseCase(
        source: AudioInputSource,
        @DefaultDispatcher dispatcher: CoroutineDispatcher
    ): ObservePitchUseCase = ObservePitchUseCase(source, dispatcher)
}
