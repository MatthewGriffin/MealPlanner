package com.example.chatgpt_mealplanner.di

import com.example.chatgpt_mealplanner.core.ai.provider.ClaudeMealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.receipt.MlKitReceiptOcrService
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.security.ClaudeCredentialStore
import com.example.chatgpt_mealplanner.core.service.InventoryDomainService
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainService
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ReceiptImportDomainService
import com.example.chatgpt_mealplanner.core.service.ReceiptImportDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainService
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainService
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindInventoryDomainService(
        impl: InventoryDomainServiceImpl
    ): InventoryDomainService

    @Binds
    @Singleton
    abstract fun bindMealDomainService(
        impl: MealDomainServiceImpl
    ): MealDomainService

    @Binds
    @Singleton
    abstract fun bindShoppingDomainService(
        impl: ShoppingDomainServiceImpl
    ): ShoppingDomainService

    @Binds
    @Singleton
    abstract fun bindReceiptOcrService(
        impl: MlKitReceiptOcrService
    ): ReceiptOcrService

    @Binds
    @Singleton
    abstract fun bindReceiptImportDomainService(
        impl: ReceiptImportDomainServiceImpl
    ): ReceiptImportDomainService

    @Binds
    @Singleton
    abstract fun bindReceiptSpendDomainService(
        impl: ReceiptSpendDomainServiceImpl
    ): ReceiptSpendDomainService

    @Binds
    @Singleton
    abstract fun bindCredentialStore(
        impl: ClaudeCredentialStore
    ): CredentialStore

    @Binds
    @Singleton
    abstract fun bindMealPlannerAiProvider(
        impl: ClaudeMealPlannerAiProvider
    ): MealPlannerAiProvider

    @Binds
    @Singleton
    abstract fun bindMealChangeRequestRepository(
        impl: com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepositoryImpl
    ): com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepository

    @Binds
    @Singleton
    abstract fun bindSafDocumentStreamProvider(
        impl: com.example.chatgpt_mealplanner.core.backup.DefaultSafDocumentStreamProvider
    ): com.example.chatgpt_mealplanner.core.backup.SafDocumentStreamProvider

    companion object {
        @Provides
        @Singleton
        fun provideIoDispatcher(): CoroutineDispatcher {
            return Dispatchers.IO
        }
    }
}
