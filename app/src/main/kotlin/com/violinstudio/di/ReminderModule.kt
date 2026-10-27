package com.violinstudio.di

import com.violinstudio.WorkManagerPracticeReminderScheduler
import com.violinstudio.domain.feature.practice.repository.PracticeReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds
    abstract fun bindPracticeReminderScheduler(impl: WorkManagerPracticeReminderScheduler): PracticeReminderScheduler
}
