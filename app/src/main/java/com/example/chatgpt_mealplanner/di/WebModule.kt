package com.example.chatgpt_mealplanner.di

import com.example.chatgpt_mealplanner.core.web.WebPageFetcher
import com.example.chatgpt_mealplanner.core.web.WebPageSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WebModule {
    @Binds
    abstract fun bindWebPageSource(fetcher: WebPageFetcher): WebPageSource
}
