package com.example.chatgpt_mealplanner.di

import android.content.Context
import androidx.room.Room
import com.example.chatgpt_mealplanner.core.sync.follower.database.FollowerDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FollowerDatabaseModule {

    @Provides
    @Singleton
    fun provideFollowerDatabase(
        @ApplicationContext context: Context
    ): FollowerDatabase {
        return Room.databaseBuilder(
            context,
            FollowerDatabase::class.java,
            "pantry_follower_local.db"
        ).addMigrations(com.example.chatgpt_mealplanner.core.sync.follower.database.FOLLOWER_MIGRATION_1_2).build()
    }

    @Provides
    fun provideFollowerDao(database: FollowerDatabase): FollowerDao {
        return database.followerDao()
    }
}
