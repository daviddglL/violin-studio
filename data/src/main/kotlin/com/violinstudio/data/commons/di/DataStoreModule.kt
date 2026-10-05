package com.violinstudio.data.commons.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.violinstudio.data.commons.datastore.USER_LOCAL_STORE_NAME
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    /** DataStore exige una sola instancia por fichero en el proceso. */
    @Provides
    @Singleton
    fun provideUserLocalDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createUserLocalDataStore(
            context.preferencesDataStoreFile(USER_LOCAL_STORE_NAME),
            CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
}
