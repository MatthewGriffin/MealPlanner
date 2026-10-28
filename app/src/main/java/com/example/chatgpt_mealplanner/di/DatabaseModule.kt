package com.example.chatgpt_mealplanner.di

import android.content.Context
import androidx.room.Room
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.RoomTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.MIGRATION_10_11
import com.example.chatgpt_mealplanner.core.database.MIGRATION_11_12
import com.example.chatgpt_mealplanner.core.database.MIGRATION_1_2
import com.example.chatgpt_mealplanner.core.database.MIGRATION_2_3
import com.example.chatgpt_mealplanner.core.database.MIGRATION_3_4
import com.example.chatgpt_mealplanner.core.database.MIGRATION_4_5
import com.example.chatgpt_mealplanner.core.database.MIGRATION_5_6
import com.example.chatgpt_mealplanner.core.database.MIGRATION_6_7
import com.example.chatgpt_mealplanner.core.database.MIGRATION_7_8
import com.example.chatgpt_mealplanner.core.database.MIGRATION_8_9
import com.example.chatgpt_mealplanner.core.database.MIGRATION_9_10
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pantry_planner.db"
        )
            .addMigrations(*com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS)
            .build()
    }

    @Provides
    fun provideInventoryDao(database: AppDatabase): InventoryDao {
        return database.inventoryDao()
    }

    @Provides
    fun provideInventoryBarcodeDao(database: AppDatabase): InventoryBarcodeDao {
        return database.inventoryBarcodeDao()
    }

    @Provides
    fun provideTransactionDao(database: AppDatabase): TransactionDao {
        return database.transactionDao()
    }

    @Provides
    fun provideBarcodeMappingDao(database: AppDatabase): BarcodeMappingDao {
        return database.barcodeMappingDao()
    }

    @Provides
    fun provideMealDao(database: AppDatabase): MealDao {
        return database.mealDao()
    }

    @Provides
    fun provideLeftoverDao(database: AppDatabase): LeftoverDao {
        return database.leftoverDao()
    }

    @Provides
    fun provideShoppingDao(database: AppDatabase): ShoppingDao {
        return database.shoppingDao()
    }

    @Provides
    fun provideStapleRuleDao(database: AppDatabase): StapleRuleDao {
        return database.stapleRuleDao()
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    fun provideRetailDao(database: AppDatabase): RetailDao {
        return database.retailDao()
    }

    @Provides
    fun providePurchasePriceDao(database: AppDatabase): PurchasePriceDao {
        return database.purchasePriceDao()
    }

    @Provides
    fun provideReceiptDao(database: AppDatabase): ReceiptDao {
        return database.receiptDao()
    }

    @Provides
    fun provideReceiptSpendDao(database: AppDatabase): ReceiptSpendDao {
        return database.receiptSpendDao()
    }

    @Provides
    fun provideHouseholdIdentityDao(database: AppDatabase): com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao {
        return database.householdIdentityDao()
    }

    @Provides
    fun providePairedDeviceDao(database: AppDatabase): com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao {
        return database.pairedDeviceDao()
    }

    @Provides
    fun provideCanonicalSyncDao(database: AppDatabase): com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao {
        return database.canonicalSyncDao()
    }

    @Provides
    fun provideMealChangeRequestDao(database: AppDatabase): com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao {
        return database.mealChangeRequestDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransactionRunnerModule {

    @Binds
    @Singleton
    abstract fun bindTransactionRunner(
        impl: RoomTransactionRunner
    ): DatabaseTransactionRunner
}
