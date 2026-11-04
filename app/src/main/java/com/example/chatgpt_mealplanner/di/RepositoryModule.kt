package com.example.chatgpt_mealplanner.di

import com.example.chatgpt_mealplanner.core.repository.ChatRepository
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.InventoryRepository
import com.example.chatgpt_mealplanner.core.repository.InventoryRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.LeftoverRepository
import com.example.chatgpt_mealplanner.core.repository.LeftoverRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.MealRepository
import com.example.chatgpt_mealplanner.core.repository.MealRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.ShoppingRepository
import com.example.chatgpt_mealplanner.core.repository.ShoppingRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.StapleRuleRepository
import com.example.chatgpt_mealplanner.core.repository.StapleRuleRepositoryImpl
import com.example.chatgpt_mealplanner.core.repository.TransactionRepository
import com.example.chatgpt_mealplanner.core.repository.TransactionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInventoryRepository(
        impl: InventoryRepositoryImpl
    ): InventoryRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(
        impl: TransactionRepositoryImpl
    ): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindMealRepository(
        impl: MealRepositoryImpl
    ): MealRepository

    @Binds
    @Singleton
    abstract fun bindLeftoverRepository(
        impl: LeftoverRepositoryImpl
    ): LeftoverRepository

    @Binds
    @Singleton
    abstract fun bindShoppingRepository(
        impl: ShoppingRepositoryImpl
    ): ShoppingRepository

    @Binds
    @Singleton
    abstract fun bindStapleRuleRepository(
        impl: StapleRuleRepositoryImpl
    ): StapleRuleRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository
}
