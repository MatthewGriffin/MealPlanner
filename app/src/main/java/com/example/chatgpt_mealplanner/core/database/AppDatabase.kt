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
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity
import com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity
import com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity
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
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptProductMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
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

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rebuild inventory_items table to match Room v16 schema (removing SQLite column default on revision)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `inventory_items_new` (
                `id` TEXT NOT NULL,
                `barcode` TEXT,
                `canonicalIngredientId` TEXT,
                `canonicalName` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `brand` TEXT,
                `category` TEXT NOT NULL,
                `storageLocation` TEXT NOT NULL,
                `quantity` REAL,
                `quantityUnit` TEXT,
                `packCount` INTEGER,
                `packSize` REAL,
                `packSizeUnit` TEXT,
                `quantityConfidence` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `opened` INTEGER,
                `frozen` INTEGER NOT NULL,
                `staple` INTEGER NOT NULL,
                `purchaseDate` TEXT,
                `openedDate` TEXT,
                `useByDate` TEXT,
                `bestBeforeDate` TEXT,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        db.execSQL("""
            INSERT INTO `inventory_items_new` (
                `id`, `barcode`, `canonicalIngredientId`, `canonicalName`, `displayName`,
                `brand`, `category`, `storageLocation`, `quantity`, `quantityUnit`,
                `packCount`, `packSize`, `packSizeUnit`, `quantityConfidence`, `status`,
                `opened`, `frozen`, `staple`, `purchaseDate`, `openedDate`,
                `useByDate`, `bestBeforeDate`, `notes`, `createdAt`, `updatedAt`, `revision`
            )
            SELECT
                `id`, `barcode`, `canonicalIngredientId`, `canonicalName`, `displayName`,
                `brand`, `category`, `storageLocation`, `quantity`, `quantityUnit`,
                `packCount`, `packSize`, `packSizeUnit`, `quantityConfidence`, `status`,
                `opened`, `frozen`, `staple`, `purchaseDate`, `openedDate`,
                `useByDate`, `bestBeforeDate`, `notes`, `createdAt`, `updatedAt`, `revision`
            FROM `inventory_items`
        """.trimIndent())

        db.execSQL("DROP TABLE `inventory_items` ")
        db.execSQL("ALTER TABLE `inventory_items_new` RENAME TO `inventory_items` ")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_barcode` ON `inventory_items` (`barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_canonicalIngredientId` ON `inventory_items` (`canonicalIngredientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_category` ON `inventory_items` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_storageLocation` ON `inventory_items` (`storageLocation`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_status` ON `inventory_items` (`status`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_sync_sequence` (
                `singletonId` INTEGER NOT NULL,
                `nextSequence` INTEGER NOT NULL,
                PRIMARY KEY(`singletonId`)
            )
        """)

        db.execSQL("INSERT OR IGNORE INTO `canonical_sync_sequence` (`singletonId`, `nextSequence`) VALUES (1, 1)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `canonical_sync_operations` (
                `operationId` TEXT NOT NULL,
                `deviceId` TEXT NOT NULL,
                `deviceSequence` INTEGER NOT NULL,
                `protocolVersion` INTEGER NOT NULL,
                `operationType` TEXT NOT NULL,
                `targetItemId` TEXT,
                `baseRevision` INTEGER,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `canonicalSequence` INTEGER NOT NULL,
                `receivedAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `resultCode` TEXT NOT NULL,
                `resultPayloadJson` TEXT,
                PRIMARY KEY(`operationId`)
            )
        """)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_sync_operations_deviceId_deviceSequence` ON `canonical_sync_operations` (`deviceId`, `deviceSequence`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_sync_operations_canonicalSequence` ON `canonical_sync_operations` (`canonicalSequence`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                `conflictId` TEXT NOT NULL,
                `operationId` TEXT NOT NULL,
                `deviceId` TEXT NOT NULL,
                `targetItemId` TEXT NOT NULL,
                `operationType` TEXT NOT NULL,
                `baseRevision` INTEGER NOT NULL,
                `currentRevision` INTEGER NOT NULL,
                `incomingPayloadJson` TEXT NOT NULL,
                `currentItemSnapshotJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `resolvedAt` INTEGER,
                PRIMARY KEY(`conflictId`),
                FOREIGN KEY(`operationId`) REFERENCES `canonical_sync_operations`(`operationId`) ON DELETE RESTRICT
            )
        """)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_conflicts_operationId` ON `sync_conflicts` (`operationId`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_pending_approvals` (
                `approvalId` TEXT NOT NULL,
                `operationId` TEXT NOT NULL,
                `deviceId` TEXT NOT NULL,
                `targetItemId` TEXT NOT NULL,
                `operationType` TEXT NOT NULL,
                `requestedBaseRevision` INTEGER NOT NULL,
                `currentRevisionAtRequest` INTEGER NOT NULL,
                `currentItemSnapshotJson` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`approvalId`),
                FOREIGN KEY(`operationId`) REFERENCES `canonical_sync_operations`(`operationId`) ON DELETE RESTRICT
            )
        """)

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_pending_approvals_operationId` ON `sync_pending_approvals` (`operationId`)")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `meal_change_requests` (
                `id` TEXT NOT NULL,
                `mealId` TEXT NOT NULL,
                `authorDeviceId` TEXT NOT NULL,
                `requestText` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `mainResponse` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_change_requests_mealId` ON `meal_change_requests` (`mealId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_change_requests_authorDeviceId` ON `meal_change_requests` (`authorDeviceId`)")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `basket_comparison_cache` (
                `cacheId` INTEGER NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `generatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`cacheId`)
            )
        """.trimIndent())
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `user_stores` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `isArchived` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_stores_name` ON `user_stores` (`name`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `purchase_price_observations` (
                `id` TEXT NOT NULL,
                `storeId` TEXT NOT NULL,
                `barcode` TEXT,
                `productName` TEXT NOT NULL,
                `brand` TEXT,
                `packQuantity` REAL,
                `packUnit` TEXT,
                `packCount` INTEGER,
                `priceMinorUnits` INTEGER NOT NULL,
                `currencyCode` TEXT NOT NULL,
                `purchasedAt` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`storeId`) REFERENCES `user_stores`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_price_observations_storeId` ON `purchase_price_observations` (`storeId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_price_observations_barcode` ON `purchase_price_observations` (`barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_price_observations_productName` ON `purchase_price_observations` (`productName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_price_observations_purchasedAt` ON `purchase_price_observations` (`purchasedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_price_observations_storeId_barcode_purchasedAt` ON `purchase_price_observations` (`storeId`, `barcode`, `purchasedAt`)")
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Legacy category values duplicated storage concepts. Preserve meaningful
        // product-type categories and reset location-only values to OTHER rather
        // than guessing the product type from where it was stored.
        db.execSQL("""
            UPDATE `inventory_items`
            SET `category` = CASE `category`
                WHEN 'BREAD_BAKERY' THEN 'BAKERY'
                WHEN 'SAUCES_SEASONINGS' THEN 'SAUCES_CONDIMENTS'
                WHEN 'SNACKS' THEN 'SNACKS'
                WHEN 'MEAL_KITS' THEN 'READY_MEALS'
                ELSE 'OTHER'
            END
        """.trimIndent())

        db.execSQL("""
            UPDATE `inventory_items`
            SET `storageLocation` = CASE `storageLocation`
                WHEN 'FRESH_PANTRY' THEN 'PANTRY'
                WHEN 'BREAD_BAKERY' THEN 'CUPBOARD'
                WHEN 'SAUCES_SEASONINGS' THEN 'CUPBOARD'
                WHEN 'SNACKS' THEN 'CUPBOARD'
                WHEN 'MEAL_KITS' THEN 'CUPBOARD'
                ELSE `storageLocation`
            END
        """.trimIndent())

        db.execSQL("""
            UPDATE `leftovers`
            SET `storageLocation` = CASE `storageLocation`
                WHEN 'FRESH_PANTRY' THEN 'PANTRY'
                WHEN 'BREAD_BAKERY' THEN 'CUPBOARD'
                WHEN 'SAUCES_SEASONINGS' THEN 'CUPBOARD'
                WHEN 'SNACKS' THEN 'CUPBOARD'
                WHEN 'MEAL_KITS' THEN 'CUPBOARD'
                ELSE `storageLocation`
            END
        """.trimIndent())

        db.execSQL("""
            UPDATE `staple_rules`
            SET `preferredStorageLocation` = CASE `preferredStorageLocation`
                WHEN 'FRESH_PANTRY' THEN 'PANTRY'
                WHEN 'BREAD_BAKERY' THEN 'CUPBOARD'
                WHEN 'SAUCES_SEASONINGS' THEN 'CUPBOARD'
                WHEN 'SNACKS' THEN 'CUPBOARD'
                WHEN 'MEAL_KITS' THEN 'CUPBOARD'
                ELSE `preferredStorageLocation`
            END
        """.trimIndent())

        db.execSQL("""
            UPDATE `product_barcode_mappings`
            SET `category` = CASE `category`
                WHEN 'BREAD_BAKERY' THEN 'BAKERY'
                WHEN 'SAUCES_SEASONINGS' THEN 'SAUCES_CONDIMENTS'
                WHEN 'SNACKS' THEN 'SNACKS'
                WHEN 'MEAL_KITS' THEN 'READY_MEALS'
                WHEN 'FRESH_PANTRY' THEN 'OTHER'
                WHEN 'CUPBOARD' THEN 'OTHER'
                WHEN 'FRIDGE' THEN 'OTHER'
                WHEN 'FREEZER' THEN 'OTHER'
                ELSE `category`
            END
        """.trimIndent())

        db.execSQL("""
            UPDATE `product_barcode_mappings`
            SET `defaultStorageLocation` = CASE `defaultStorageLocation`
                WHEN 'FRESH_PANTRY' THEN 'PANTRY'
                WHEN 'BREAD_BAKERY' THEN 'CUPBOARD'
                WHEN 'SAUCES_SEASONINGS' THEN 'CUPBOARD'
                WHEN 'SNACKS' THEN 'CUPBOARD'
                WHEN 'MEAL_KITS' THEN 'CUPBOARD'
                ELSE `defaultStorageLocation`
            END
        """.trimIndent())
    }
}


val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `receipt_spend_items` ADD COLUMN `linkedInventoryItemId` TEXT REFERENCES `inventory_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
        db.execSQL("ALTER TABLE `receipt_spend_items` ADD COLUMN `linkedShoppingListItemId` TEXT REFERENCES `shopping_list_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_spend_items_linkedInventoryItemId` ON `receipt_spend_items` (`linkedInventoryItemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_spend_items_linkedShoppingListItemId` ON `receipt_spend_items` (`linkedShoppingListItemId`)")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `purchase_price_observations` ADD COLUMN `canonicalName` TEXT")
        // Older prices can still be compared when their barcode has a known product.
        db.execSQL(
            """
            UPDATE `purchase_price_observations`
            SET `canonicalName` = (
                SELECT COALESCE(`canonicalIngredientId`, `canonicalName`)
                FROM `product_barcode_mappings`
                WHERE `product_barcode_mappings`.`barcode` = `purchase_price_observations`.`barcode`
            )
            WHERE `barcode` IS NOT NULL
            """.trimIndent()
        )
    }
}

/** Nothing has read these since live retailer prices were removed. Receipts keep their own copies. */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Lines reference plans, so they go first.
        listOf(
            "basket_lines",
            "basket_plans",
            "basket_comparison_cache",
            "retail_product_mappings",
            "retail_price_snapshots",
            "retail_products"
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
    }
}

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cooking_preferences` (
                `id` INTEGER NOT NULL,
                `allergiesJson` TEXT NOT NULL,
                `dietsJson` TEXT NOT NULL,
                `dislikesJson` TEXT NOT NULL,
                `equipmentJson` TEXT NOT NULL,
                `householdSize` INTEGER NOT NULL,
                `skillLevel` TEXT,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `meals` ADD COLUMN `rating` TEXT")
        db.execSQL("ALTER TABLE `meals` ADD COLUMN `ratedAt` INTEGER")
    }
}

/** Adds the saved instructions for meal kits and spice kits. */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `kit_instructions` (
                `normalizedName` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `instructions` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `sourceNote` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`normalizedName`)
            )
            """.trimIndent()
        )
    }
}

/** Adds the recipes the household saved from web pages and photos. */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_recipes` (
                `normalizedTitle` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `servings` INTEGER,
                `recipeText` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `sourceNote` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`normalizedTitle`)
            )
            """.trimIndent()
        )
    }
}

/** Each receipt now remembers its currency; receipts saved before this were all pounds. */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `receipt_spend` ADD COLUMN `currencyCode` TEXT NOT NULL DEFAULT 'GBP'")
    }
}

/** Remembers the amount of each product a household says it holds, so the next receipt with it can be filled in. */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `receipt_quantity_memory` (`id` TEXT NOT NULL, `retailerId` TEXT NOT NULL, `productKey` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_quantity_memory_retailerId_productKey` ON `receipt_quantity_memory` (`retailerId`, `productKey`)")
    }
}

/** Learned shop order: which items a household finds first in each shop, from the order they tick things off. */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `store_item_order` (`id` TEXT NOT NULL, `storeId` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `avgRank` REAL NOT NULL, `samples` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_store_item_order_storeId_itemKey` ON `store_item_order` (`storeId`, `itemKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_sessions` (`id` TEXT NOT NULL, `storeId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_sessions_endedAt` ON `shopping_sessions` (`endedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_tick_events` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `tickedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_tick_events_sessionId` ON `shopping_tick_events` (`sessionId`)")
    }
}

const val MAIN_DATABASE_NAME = "pantry_planner.db"

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
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21,
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30
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
        ReceiptImportEntity::class,
        ReceiptImportLineEntity::class,
        ReceiptProductMappingEntity::class,
        ReceiptSpendEntity::class,
        ReceiptSpendItemEntity::class,
        ReceiptCategoryMappingEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.ReceiptQuantityMemoryEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.StoreItemOrderEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.ShoppingSessionEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.ShoppingTickEventEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncSequenceEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.CanonicalSyncOperationEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity::class,
        UserStoreEntity::class,
        PurchasePriceObservationEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.CookingPreferencesEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.KitInstructionEntity::class,
        com.example.chatgpt_mealplanner.core.database.entity.SavedRecipeEntity::class
    ],
    version = 30,
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
    abstract fun purchasePriceDao(): PurchasePriceDao
    abstract fun cookingPreferencesDao(): com.example.chatgpt_mealplanner.core.database.dao.CookingPreferencesDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun receiptSpendDao(): ReceiptSpendDao
    abstract fun storeOrderDao(): com.example.chatgpt_mealplanner.core.database.dao.StoreOrderDao
    abstract fun householdIdentityDao(): com.example.chatgpt_mealplanner.core.database.dao.HouseholdIdentityDao
    abstract fun pairedDeviceDao(): com.example.chatgpt_mealplanner.core.database.dao.PairedDeviceDao
    abstract fun canonicalSyncDao(): com.example.chatgpt_mealplanner.core.database.dao.CanonicalSyncDao
    abstract fun mealChangeRequestDao(): com.example.chatgpt_mealplanner.core.database.dao.MealChangeRequestDao
    abstract fun appDataResetDao(): com.example.chatgpt_mealplanner.core.database.dao.AppDataResetDao
    abstract fun kitInstructionDao(): com.example.chatgpt_mealplanner.core.database.dao.KitInstructionDao
    abstract fun savedRecipeDao(): com.example.chatgpt_mealplanner.core.database.dao.SavedRecipeDao
}

