package com.example.chatgpt_mealplanner.core.sync.follower.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chatgpt_mealplanner.core.sync.follower.database.dao.FollowerDao
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerDeviceSequenceEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerInventoryItemEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncMetadataEntity
import com.example.chatgpt_mealplanner.core.sync.follower.database.entity.FollowerSyncOutboxEntity

val FOLLOWER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedDisplayName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedBrand` TEXT")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedCategory` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedStorageLocation` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedQuantity` REAL")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedQuantityUnit` TEXT")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedNotes` TEXT")
        db.execSQL("ALTER TABLE `follower_inventory_items` ADD COLUMN `confirmedStatus` TEXT NOT NULL DEFAULT 'AVAILABLE'")

        db.execSQL("""
            UPDATE `follower_inventory_items` SET
                `confirmedDisplayName` = `displayName`,
                `confirmedBrand` = `brand`,
                `confirmedCategory` = `category`,
                `confirmedStorageLocation` = `storageLocation`,
                `confirmedQuantity` = `quantity`,
                `confirmedQuantityUnit` = `quantityUnit`,
                `confirmedNotes` = `notes`,
                `confirmedStatus` = `status`
        """)
    }
}

val FOLLOWER_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `follower_sync_metadata` (
                `singletonId` INTEGER NOT NULL,
                `lastReconciledCanonicalSequence` INTEGER NOT NULL,
                `lastSuccessfulPullAt` INTEGER,
                PRIMARY KEY(`singletonId`)
            )
        """.trimIndent())
        db.execSQL("INSERT OR IGNORE INTO `follower_sync_metadata` (`singletonId`, `lastReconciledCanonicalSequence`) VALUES (1, 0)")
    }
}

@Database(
    entities = [
        FollowerInventoryItemEntity::class,
        FollowerSyncOutboxEntity::class,
        FollowerDeviceSequenceEntity::class,
        FollowerSyncMetadataEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FollowerDatabase : RoomDatabase() {
    abstract fun followerDao(): FollowerDao
}
