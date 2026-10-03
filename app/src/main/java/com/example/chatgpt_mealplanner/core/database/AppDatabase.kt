package com.example.chatgpt_mealplanner.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chatgpt_mealplanner.core.database.dao.BarcodeMappingDao
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.LeftoverDao
import com.example.chatgpt_mealplanner.core.database.dao.MealDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptDao
import com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao
import com.example.chatgpt_mealplanner.core.database.dao.RetailDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity
import com.example.chatgpt_mealplanner.core.database.entity.BasketLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.BasketPlanEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemBarcodeEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryMergeLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.LeftoverEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealIngredientEntity
import com.example.chatgpt_mealplanner.core.database.entity.ProductBarcodeMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptProductMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailPriceSnapshotEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductEntity
import com.example.chatgpt_mealplanner.core.database.entity.RetailProductMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingRequirementSourceEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `meals` (
                `id` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `mealType` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `servings` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `batchPortions` INTEGER NOT NULL,
                `immediatePortions` INTEGER NOT NULL,
                `leftoverPortions` INTEGER NOT NULL,
                `cookInTwoBatches` INTEGER NOT NULL,
                `tags` TEXT,
                `instructions` TEXT,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `meal_ingredients` (
                `id` TEXT NOT NULL,
                `mealId` TEXT NOT NULL,
                `canonicalIngredientId` TEXT,
                `displayName` TEXT NOT NULL,
                `plannedQuantity` REAL NOT NULL,
                `plannedUnit` TEXT NOT NULL,
                `isOptional` INTEGER NOT NULL,
                `deductedFromInventory` INTEGER NOT NULL,
                `inventoryItemId` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `leftovers` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `portions` INTEGER NOT NULL,
                `storageLocation` TEXT NOT NULL,
                `consumeByDate` TEXT,
                `sourceMealId` TEXT,
                `isDiscarded` INTEGER NOT NULL,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_ingredients_mealId` ON `meal_ingredients` (`mealId`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shopping_lists` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `startDate` TEXT,
                `endDate` TEXT,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shopping_list_items` (
                `id` TEXT NOT NULL,
                `shoppingListId` TEXT NOT NULL,
                `canonicalIngredientId` TEXT,
                `displayName` TEXT NOT NULL,
                `requiredQuantity` REAL,
                `requiredUnit` TEXT,
                `suggestedPurchaseQuantity` REAL,
                `suggestedPurchaseUnit` TEXT,
                `packCount` INTEGER,
                `reason` TEXT NOT NULL,
                `priority` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `optional` INTEGER NOT NULL,
                `sourceType` TEXT NOT NULL,
                `hasMealRequirement` INTEGER NOT NULL,
                `hasStapleRestock` INTEGER NOT NULL,
                `mealShortfallQuantity` REAL,
                `stapleReserveTopUpQuantity` REAL,
                `expectedFinalInventory` REAL,
                `expectedFinalInventoryUnit` TEXT,
                `quantityUncertain` INTEGER NOT NULL,
                `uncertaintyReason` TEXT,
                `requirementConfidence` TEXT NOT NULL,
                `wasteRisk` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`shoppingListId`) REFERENCES `shopping_lists`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `shopping_requirement_sources` (
                `id` TEXT NOT NULL,
                `shoppingListItemId` TEXT NOT NULL,
                `mealId` TEXT NOT NULL,
                `mealIngredientId` TEXT,
                `mealTitle` TEXT NOT NULL,
                `mealDate` TEXT NOT NULL,
                `requiredQuantity` REAL NOT NULL,
                `requiredUnit` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`shoppingListItemId`) REFERENCES `shopping_list_items`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `staple_rules` (
                `id` TEXT NOT NULL,
                `canonicalIngredientId` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `mode` TEXT NOT NULL,
                `thresholdQuantity` REAL,
                `thresholdUnit` TEXT,
                `targetQuantity` REAL,
                `targetUnit` TEXT,
                `thresholdPackCount` INTEGER,
                `targetPackCount` INTEGER,
                `preferredStorageLocation` TEXT,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_list_items_shoppingListId` ON `shopping_list_items` (`shoppingListId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_list_items_canonicalIngredientId` ON `shopping_list_items` (`canonicalIngredientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_requirement_sources_shoppingListItemId` ON `shopping_requirement_sources` (`shoppingListItemId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_staple_rules_canonicalIngredientId` ON `staple_rules` (`canonicalIngredientId`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_conversations` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversationId`) REFERENCES `chat_conversations`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `ai_tool_execution_logs` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `toolCallId` TEXT NOT NULL,
                `executionClass` TEXT NOT NULL,
                `executionStatus` TEXT NOT NULL,
                `success` INTEGER NOT NULL,
                `resultPayloadJson` TEXT,
                `errorCode` TEXT,
                `errorMessage` TEXT,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_tool_execution_logs_conversationId_toolCallId` ON `ai_tool_execution_logs` (`conversationId`, `toolCallId`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `retail_products` (
                `id` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `sku` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `brand` TEXT,
                `packSize` REAL,
                `packUnit` TEXT,
                `pricePence` INTEGER NOT NULL,
                `category` TEXT NOT NULL,
                `canonicalCandidateId` TEXT,
                `matchConfidence` REAL NOT NULL,
                `verified` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `retail_price_snapshots` (
                `id` TEXT NOT NULL,
                `productId` TEXT NOT NULL,
                `pricePence` INTEGER NOT NULL,
                `promotionalPricePence` INTEGER,
                `promotionDescription` TEXT,
                `unitPricePence` INTEGER,
                `unitPriceUnit` TEXT,
                `snapshotDate` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`productId`) REFERENCES `retail_products`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `retail_product_mappings` (
                `canonicalIngredientId` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `retailProductId` TEXT NOT NULL,
                `packSize` REAL NOT NULL,
                `packUnit` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `manualOverride` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`canonicalIngredientId`, `retailerId`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `basket_plans` (
                `id` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `totalPence` INTEGER NOT NULL,
                `missingItemsCount` INTEGER NOT NULL,
                `fulfillmentScore` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `basket_lines` (
                `id` TEXT NOT NULL,
                `basketPlanId` TEXT NOT NULL,
                `shoppingListItemId` TEXT NOT NULL,
                `retailProductId` TEXT,
                `quantityToBuy` INTEGER NOT NULL,
                `linePricePence` INTEGER NOT NULL,
                `isMissing` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`basketPlanId`) REFERENCES `basket_plans`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_retail_price_snapshots_productId` ON `retail_price_snapshots` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_basket_lines_basketPlanId` ON `basket_lines` (`basketPlanId`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_imports` (
                `id` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `purchasedAt` TEXT,
                `rawOcrText` TEXT NOT NULL,
                `importStatus` TEXT NOT NULL,
                `totalMinorPence` INTEGER,
                `confidenceScore` REAL NOT NULL,
                `imagePath` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_import_lines` (
                `id` TEXT NOT NULL,
                `receiptImportId` TEXT NOT NULL,
                `rawDescription` TEXT NOT NULL,
                `normalizedDescription` TEXT NOT NULL,
                `parsedPriceMinorPence` INTEGER,
                `quantity` INTEGER NOT NULL,
                `confidenceScore` REAL NOT NULL,
                `matchedCanonicalIngredientId` TEXT,
                `mappedStorageLocation` TEXT,
                `mappedCategory` TEXT,
                `resolutionStatus` TEXT NOT NULL,
                `userNote` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`receiptImportId`) REFERENCES `receipt_imports`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_product_mappings` (
                `id` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `normalizedReceiptDescription` TEXT NOT NULL,
                `canonicalIngredientId` TEXT,
                `preferredStorageLocation` TEXT,
                `preferredCategory` TEXT,
                `lastUsedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_import_lines_receiptImportId` ON `receipt_import_lines` (`receiptImportId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_product_mappings_retailer_desc` ON `receipt_product_mappings` (`retailerId`, `normalizedReceiptDescription`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_spend` (
                `id` TEXT NOT NULL,
                `retailerId` TEXT NOT NULL,
                `purchasedAt` TEXT,
                `totalMinorPence` INTEGER,
                `calculatedItemsSumMinorPence` INTEGER NOT NULL,
                `discountMinorPence` INTEGER NOT NULL,
                `itemCount` INTEGER NOT NULL,
                `expectedItemCount` INTEGER,
                `discrepancyMinorPence` INTEGER NOT NULL,
                `summaryArithmeticValid` INTEGER NOT NULL,
                `isReconciled` INTEGER NOT NULL,
                `needsReview` INTEGER NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `sourceImagePath` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_spend_items` (
                `id` TEXT NOT NULL,
                `receiptSpendId` TEXT NOT NULL,
                `rawDescription` TEXT NOT NULL,
                `normalizedDescription` TEXT NOT NULL,
                `lineAmountMinorPence` INTEGER NOT NULL,
                `quantity` INTEGER NOT NULL,
                `category` TEXT NOT NULL,
                `isDiscount` INTEGER NOT NULL,
                `confidence` REAL NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`receiptSpendId`) REFERENCES `receipt_spend`(`id`) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `receipt_category_mappings` (
                `normalizedDescription` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`normalizedDescription`)
            )
        """)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_spend_fingerprint` ON `receipt_spend` (`fingerprint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_spend_items_receiptSpendId` ON `receipt_spend_items` (`receiptSpendId`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `inventory_item_barcodes` (
                `id` TEXT NOT NULL,
                `inventoryItemId` TEXT NOT NULL,
                `rawBarcode` TEXT NOT NULL,
                `normalizedBarcode` TEXT NOT NULL,
                `addedAt` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`inventoryItemId`) REFERENCES `inventory_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
            )
        """)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_item_barcodes_normalizedBarcode` ON `inventory_item_barcodes` (`normalizedBarcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_item_barcodes_inventoryItemId` ON `inventory_item_barcodes` (`inventoryItemId`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `inventory_merge_logs` (
                `id` TEXT NOT NULL,
                `survivorItemId` TEXT NOT NULL,
                `mergedItemId` TEXT NOT NULL,
                `mergedItemSnapshotJson` TEXT NOT NULL,
                `mergedRuleSnapshotJson` TEXT,
                `transferredBarcodesJson` TEXT NOT NULL,
                `transferredQuantity` REAL NOT NULL,
                `transferredPackCount` INTEGER NOT NULL,
                `mergedAt` INTEGER NOT NULL,
                `unmerged` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_merge_logs_survivorItemId` ON `inventory_merge_logs` (`survivorItemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_merge_logs_mergedItemId` ON `inventory_merge_logs` (`mergedItemId`)")

        // Non-destructive backfill of existing non-null barcodes into inventory_item_barcodes
        db.execSQL("""
            INSERT OR IGNORE INTO `inventory_item_barcodes` (`id`, `inventoryItemId`, `rawBarcode`, `normalizedBarcode`, `addedAt`, `source`)
            SELECT
                'bc_' || `id`,
                `id`,
                `barcode`,
                `barcode`,
                `createdAt`,
                'INITIAL_MIGRATION'
            FROM `inventory_items`
            WHERE `barcode` IS NOT NULL AND TRIM(`barcode`) != ''
        """)
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE `inventory_merge_logs` ADD COLUMN `mergedRuleSnapshotJson` TEXT")
        } catch (_: Exception) {}
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v10 step
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE `staple_rules` ADD COLUMN `archivedByMerge` INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) {}

        // Backfill existing physical database state:
        // Set archivedByMerge = 1 and restore original enabled = 1 for rules belonging to merged or discarded items
        db.execSQL("""
            UPDATE `staple_rules`
            SET `archivedByMerge` = 1, `enabled` = 1
            WHERE `canonicalIngredientId` IN (SELECT mergedItemId FROM inventory_merge_logs WHERE unmerged = 0)
               OR `canonicalIngredientId` IN (SELECT 'item_' || mergedItemId FROM inventory_merge_logs WHERE unmerged = 0)
               OR `canonicalIngredientId` IN (SELECT 'item_item_' || mergedItemId FROM inventory_merge_logs WHERE unmerged = 0)
               OR `canonicalIngredientId` IN (SELECT id FROM inventory_items WHERE status = 'DISCARDED')
               OR `canonicalIngredientId` IN (SELECT 'item_' || id FROM inventory_items WHERE status = 'DISCARDED')
        """)
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `modelId` TEXT")
        } catch (_: Exception) {}
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pending_ai_mutations` (
                `conversationId` TEXT NOT NULL,
                `mutationType` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `date` TEXT,
                `mealType` TEXT,
                `servings` INTEGER NOT NULL,
                `ingredientsJson` TEXT NOT NULL,
                `sourceUserMessageId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `active_proposals` (
                `conversationId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `mealType` TEXT,
                `suggestedDate` TEXT,
                `servings` INTEGER NOT NULL,
                `proposalJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`)
            )
        """)
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pending_ai_mutations` ADD COLUMN `instructions` TEXT")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `inventory_items` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `household_identity` (
                `householdId` TEXT NOT NULL,
                `householdName` TEXT NOT NULL,
                `mainDeviceId` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`householdId`)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `paired_devices` (
                `deviceId` TEXT NOT NULL,
                `deviceName` TEXT NOT NULL,
                `devicePublicKey` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `pairedAt` INTEGER NOT NULL,
                `lastSyncTimestamp` INTEGER NOT NULL,
                PRIMARY KEY(`deviceId`)
            )
        """)
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15
)

@Database(
    entities = [
        InventoryItemEntity::class,
        InventoryTransactionEntity::class,
        InventoryItemBarcodeEntity::class,
        InventoryMergeLogEntity::class,
        ProductBarcodeMappingEntity::class,
        MealEntity::class,
        MealIngredientEntity::class,
        LeftoverEntity::class,
        ShoppingListEntity::class,
        ShoppingListItemEntity::class,
        ShoppingRequirementSourceEntity::class,
        StapleRuleEntity::class,
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        AiToolExecutionLogEntity::class,
        PendingAiMutationEntity::class,
        ActiveProposalEntity::class,
        RetailProductEntity::class,
        RetailPriceSnapshotEntity::class,
        RetailProductMappingEntity::class,
        BasketPlanEntity::class,
        BasketLineEntity::class,
        ReceiptImportEntity::class,
        ReceiptImportLineEntity::class,
        ReceiptProductMappingEntity::class,
        ReceiptSpendEntity::class,
        ReceiptSpendItemEntity::class,
        ReceiptCategoryMappingEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity::class
    ],
    version = 15,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inventoryDao(): InventoryDao
    abstract fun inventoryBarcodeDao(): InventoryBarcodeDao
    abstract fun transactionDao(): TransactionDao
    abstract fun barcodeMappingDao(): BarcodeMappingDao
    abstract fun mealDao(): MealDao
    abstract fun leftoverDao(): LeftoverDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun stapleRuleDao(): StapleRuleDao
    abstract fun chatDao(): ChatDao
    abstract fun retailDao(): RetailDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun receiptSpendDao(): ReceiptSpendDao
    abstract fun householdIdentityDao(): com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
    abstract fun pairedDeviceDao(): com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
}
