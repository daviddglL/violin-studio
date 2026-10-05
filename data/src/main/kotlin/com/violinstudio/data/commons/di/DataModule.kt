package com.violinstudio.data.commons.di

import com.violinstudio.data.commons.audio.AndroidPcmTrackFactory
import com.violinstudio.data.commons.audio.AudioTrackOutput
import com.violinstudio.data.commons.audio.PcmTrackFactory
import com.violinstudio.data.feature.account.repository.AccountRepositoryImpl
import com.violinstudio.data.feature.auth.datasource.AuthRemoteDataSource
import com.violinstudio.data.feature.auth.datasource.firebase.FirebaseAuthRemoteDataSource
import com.violinstudio.data.feature.auth.repository.AuthRepositoryImpl
import com.violinstudio.data.feature.consent.repository.ConsentRepositoryImpl
import com.violinstudio.data.feature.health.datasource.HealthRemoteDataSource
import com.violinstudio.data.feature.health.datasource.firebase.FirebaseHealthRemoteDataSource
import com.violinstudio.data.feature.health.repository.HealthRepositoryImpl
import com.violinstudio.data.feature.profile.datasource.IdentityFunctionsDataSource
import com.violinstudio.data.feature.profile.datasource.ProfileRemoteDataSource
import com.violinstudio.data.feature.profile.datasource.firebase.FirebaseIdentityFunctionsDataSource
import com.violinstudio.data.feature.profile.datasource.firebase.FirebaseProfileRemoteDataSource
import com.violinstudio.data.feature.profile.repository.ProfileRepositoryImpl
import com.violinstudio.data.feature.tuner.datasource.audio.AndroidPcmRecorderFactory
import com.violinstudio.data.feature.tuner.datasource.audio.AudioRecordSource
import com.violinstudio.data.feature.tuner.datasource.audio.ContextMicPermission
import com.violinstudio.data.feature.tuner.datasource.audio.MicPermission
import com.violinstudio.data.feature.tuner.datasource.audio.PcmRecorderFactory
import com.violinstudio.domain.feature.account.repository.AccountRepository
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.health.repository.HealthRepository
import com.violinstudio.domain.feature.profile.repository.ProfileRepository
import com.violinstudio.domain.feature.tuner.audio.AudioInputSource
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
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

    @Binds
    abstract fun bindAuthRemoteDataSource(impl: FirebaseAuthRemoteDataSource): AuthRemoteDataSource

    @Binds
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun bindProfileRemoteDataSource(impl: FirebaseProfileRemoteDataSource): ProfileRemoteDataSource

    @Binds
    abstract fun bindIdentityFunctionsDataSource(impl: FirebaseIdentityFunctionsDataSource): IdentityFunctionsDataSource

    @Binds
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    abstract fun bindConsentRepository(impl: ConsentRepositoryImpl): ConsentRepository

    @Binds
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    abstract fun bindPcmRecorderFactory(impl: AndroidPcmRecorderFactory): PcmRecorderFactory

    @Binds
    abstract fun bindMicPermission(impl: ContextMicPermission): MicPermission

    @Binds
    abstract fun bindAudioInputSource(impl: AudioRecordSource): AudioInputSource

    @Binds
    abstract fun bindPcmTrackFactory(impl: AndroidPcmTrackFactory): PcmTrackFactory

    @Binds
    abstract fun bindAudioOutput(impl: AudioTrackOutput): AudioOutput
}
