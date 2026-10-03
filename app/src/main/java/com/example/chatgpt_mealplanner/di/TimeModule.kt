package com.example.chatgpt_mealplanner.di

import com.example.chatgpt_mealplanner.retail.contract.SystemTimeProvider
import com.example.chatgpt_mealplanner.retail.contract.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider {
        return SystemTimeProvider
    }
}
