package com.violinstudio.data.commons.di

import com.violinstudio.data.commons.erasure.CachePurgeFlag
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.commons.erasure.LocalUserDataEraser
import com.violinstudio.data.commons.erasure.SharedPrefsCachePurgeFlag
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Para anadir un eraser de otra feature: `@Binds @IntoSet abstract fun bindX(impl: XEraser): LocalUserDataEraser`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ErasureModule {
    @Multibinds
    abstract fun erasers(): Set<LocalUserDataEraser>

    @Binds
    @IntoSet
    abstract fun bindDataStoreEraser(impl: DataStoreUserDataEraser): LocalUserDataEraser

    @Binds
    abstract fun bindCachePurgeFlag(impl: SharedPrefsCachePurgeFlag): CachePurgeFlag
}
