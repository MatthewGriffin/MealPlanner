package com.example.chatgpt_mealplanner.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.ALL_MIGRATIONS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PantryPhase5MainDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "test_main_migration.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun realRoom_mainDatabase_migrationFromV15ToCurrent_preservesInventoryAndIdentity() = runBlocking {
        // 1. Create a raw SQLite database at v15 with the exact v15 schema
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `inventory_items` (
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
                                `revision` INTEGER NOT NULL DEFAULT 1,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_barcode` ON `inventory_items` (`barcode`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_canonicalIngredientId` ON `inventory_items` (`canonicalIngredientId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_category` ON `inventory_items` (`category`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_storageLocation` ON `inventory_items` (`storageLocation`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_status` ON `inventory_items` (`status`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `inventory_transactions` (
                                `id` TEXT NOT NULL,
                                `inventoryItemId` TEXT NOT NULL,
                                `type` TEXT NOT NULL,
                                `quantityChange` REAL,
                                `unit` TEXT,
                                `mealId` TEXT,
                                `shoppingTripId` TEXT,
                                `note` TEXT,
                                `timestamp` INTEGER NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`inventoryItemId`) REFERENCES `inventory_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_transactions_inventoryItemId` ON `inventory_transactions` (`inventoryItemId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_transactions_timestamp` ON `inventory_transactions` (`timestamp`)")

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
                        """.trimIndent())
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
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_merge_logs_survivorItemId` ON `inventory_merge_logs` (`survivorItemId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_merge_logs_mergedItemId` ON `inventory_merge_logs` (`mergedItemId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `product_barcode_mappings` (
                                `barcode` TEXT NOT NULL,
                                `canonicalIngredientId` TEXT,
                                `canonicalName` TEXT,
                                `displayName` TEXT,
                                `brand` TEXT,
                                `category` TEXT,
                                `defaultStorageLocation` TEXT,
                                `netQuantity` REAL,
                                `netQuantityUnit` TEXT,
                                `packCount` INTEGER,
                                `packSize` REAL,
                                `packSizeUnit` TEXT,
                                `imageUrl` TEXT,
                                `confidence` TEXT NOT NULL,
                                `source` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `lastVerifiedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`barcode`)
                            )
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `household_identity` (
                                `householdId` TEXT NOT NULL,
                                `householdName` TEXT NOT NULL,
                                `mainDeviceId` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`householdId`)
                            )
                        """.trimIndent())

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
                        """.trimIndent())

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
                                `tags` TEXT NOT NULL,
                                `instructions` TEXT,
                                `notes` TEXT,
                                `cookedAt` INTEGER,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meals_date` ON `meals` (`date`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `meal_ingredients` (
                                `id` TEXT NOT NULL,
                                `mealId` TEXT NOT NULL,
                                `canonicalIngredientId` TEXT,
                                `displayName` TEXT NOT NULL,
                                `plannedQuantity` REAL NOT NULL,
                                `plannedUnit` TEXT NOT NULL,
                                `actualQuantity` REAL,
                                `actualUnit` TEXT,
                                `optional` INTEGER NOT NULL,
                                `substitutionAllowed` INTEGER NOT NULL,
                                `substitutedCanonicalIngredientId` TEXT,
                                `substitutedDisplayName` TEXT,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_ingredients_mealId` ON `meal_ingredients` (`mealId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_ingredients_canonicalIngredientId` ON `meal_ingredients` (`canonicalIngredientId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `leftovers` (
                                `id` TEXT NOT NULL,
                                `sourceMealId` TEXT,
                                `name` TEXT NOT NULL,
                                `initialPortions` INTEGER NOT NULL,
                                `portions` INTEGER NOT NULL,
                                `storageLocation` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `createdDate` TEXT NOT NULL,
                                `consumeByDate` TEXT,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`sourceMealId`) REFERENCES `meals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_leftovers_sourceMealId` ON `leftovers` (`sourceMealId`)")

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
                        """.trimIndent())

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
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_list_items_shoppingListId` ON `shopping_list_items` (`shoppingListId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_list_items_canonicalIngredientId` ON `shopping_list_items` (`canonicalIngredientId`)")

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
                                FOREIGN KEY(`shoppingListItemId`) REFERENCES `shopping_list_items`(`id`) ON DELETE CASCADE,
                                FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_requirement_sources_shoppingListItemId` ON `shopping_requirement_sources` (`shoppingListItemId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_requirement_sources_mealId` ON `shopping_requirement_sources` (`mealId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_requirement_sources_mealIngredientId` ON `shopping_requirement_sources` (`mealIngredientId`)")

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
                                `archivedByMerge` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_staple_rules_canonicalIngredientId` ON `staple_rules` (`canonicalIngredientId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `chat_conversations` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `chat_messages` (
                                `id` TEXT NOT NULL,
                                `conversationId` TEXT NOT NULL,
                                `role` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                `modelId` TEXT,
                                `structuredPayloadJson` TEXT,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`conversationId`) REFERENCES `chat_conversations`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")

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
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`conversationId`) REFERENCES `chat_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_tool_execution_logs_conversationId` ON `ai_tool_execution_logs` (`conversationId`)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_tool_execution_logs_conversationId_toolCallId` ON `ai_tool_execution_logs` (`conversationId`, `toolCallId`)")

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
                                `instructions` TEXT,
                                PRIMARY KEY(`conversationId`)
                            )
                        """.trimIndent())

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
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `retail_products` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `retailerProductId` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `brand` TEXT,
                                `canonicalIngredientId` TEXT,
                                `barcode` TEXT,
                                `category` TEXT,
                                `imageUrl` TEXT,
                                `packageType` TEXT,
                                `netQuantity` REAL,
                                `netQuantityUnit` TEXT,
                                `packCount` INTEGER,
                                `packSize` REAL,
                                `packSizeUnit` TEXT,
                                `storageClass` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_retail_products_retailerId_retailerProductId` ON `retail_products` (`retailerId`, `retailerProductId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_retail_products_canonicalIngredientId` ON `retail_products` (`canonicalIngredientId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `retail_price_snapshots` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `retailerProductId` TEXT NOT NULL,
                                `priceMinorPence` INTEGER NOT NULL,
                                `currencyCode` TEXT NOT NULL,
                                `promotionJson` TEXT,
                                `availability` TEXT NOT NULL,
                                `observedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_retail_price_snapshots_retailerProductId` ON `retail_price_snapshots` (`retailerProductId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_retail_price_snapshots_observedAt` ON `retail_price_snapshots` (`observedAt`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `retail_product_mappings` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `retailerProductId` TEXT NOT NULL,
                                `canonicalIngredientId` TEXT NOT NULL,
                                `decision` TEXT NOT NULL,
                                `userConfirmed` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_retail_product_mappings_retailerId_retailerProductId_canonicalIngredientId` ON `retail_product_mappings` (`retailerId`, `retailerProductId`, `canonicalIngredientId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `basket_plans` (
                                `planId` TEXT NOT NULL,
                                `storesCsv` TEXT NOT NULL,
                                `feasibilityTier` TEXT NOT NULL,
                                `basketSubtotalMinorPence` INTEGER NOT NULL,
                                `estimatedTripPenaltyMinorPence` INTEGER NOT NULL,
                                `estimatedWastePenaltyScore` REAL NOT NULL,
                                `unmatchedRequirementsCsv` TEXT NOT NULL,
                                `uncertainItemsCsv` TEXT NOT NULL,
                                `explanationText` TEXT NOT NULL,
                                `generatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`planId`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_basket_plans_generatedAt` ON `basket_plans` (`generatedAt`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `basket_lines` (
                                `id` TEXT NOT NULL,
                                `basketPlanId` TEXT NOT NULL,
                                `shoppingRequirementId` TEXT NOT NULL,
                                `requirementDisplayName` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `retailerProductId` TEXT NOT NULL,
                                `productName` TEXT NOT NULL,
                                `packCountToBuy` INTEGER NOT NULL,
                                `lineTotalCostMinorPence` INTEGER NOT NULL,
                                `requirementCovered` REAL NOT NULL,
                                `expectedRemainder` REAL NOT NULL,
                                `matchConfidence` TEXT NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`basketPlanId`) REFERENCES `basket_plans`(`planId`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_basket_lines_basketPlanId` ON `basket_lines` (`basketPlanId`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_basket_lines_basketPlanId` ON `basket_lines` (`basketPlanId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_imports` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `purchasedAt` TEXT NOT NULL,
                                `totalAmountMinorPence` INTEGER,
                                `itemCount` INTEGER NOT NULL,
                                `fingerprint` TEXT NOT NULL,
                                `sourceImagePath` TEXT,
                                `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_imports_fingerprint` ON `receipt_imports` (`fingerprint`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_import_lines` (
                                `id` TEXT NOT NULL,
                                `receiptImportId` TEXT NOT NULL,
                                `rawText` TEXT NOT NULL,
                                `normalizedText` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `quantityPurchased` INTEGER NOT NULL,
                                `unitPriceMinorPence` INTEGER,
                                `lineTotalMinorPence` INTEGER,
                                `candidateCanonicalIngredientId` TEXT,
                                `netQuantity` REAL,
                                `netQuantityUnit` TEXT,
                                `packCount` INTEGER,
                                `confidence` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`receiptImportId`) REFERENCES `receipt_imports`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_import_lines_receiptImportId` ON `receipt_import_lines` (`receiptImportId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_product_mappings` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `normalizedDescription` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `canonicalIngredientId` TEXT,
                                `knownNetQuantity` REAL,
                                `knownNetUnit` TEXT,
                                `knownPackCount` INTEGER,
                                `storageLocation` TEXT,
                                `userConfirmed` INTEGER NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_product_mappings_retailerId_normalizedDescription` ON `receipt_product_mappings` (`retailerId`, `normalizedDescription`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_spend` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `purchasedAt` TEXT NOT NULL,
                                `totalMinorPence` INTEGER NOT NULL,
                                `subtotalMinorPence` INTEGER,
                                `discountMinorPence` INTEGER NOT NULL,
                                `itemCount` INTEGER NOT NULL,
                                `fingerprint` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_spend_fingerprint` ON `receipt_spend` (`fingerprint`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_spend_items` (
                                `id` TEXT NOT NULL,
                                `receiptSpendId` TEXT NOT NULL,
                                `rawDescription` TEXT NOT NULL,
                                `normalizedDescription` TEXT NOT NULL,
                                `lineAmountMinorPence` INTEGER NOT NULL,
                                `quantity` INTEGER NOT NULL,
                                `unitPriceMinorPence` INTEGER,
                                `category` TEXT NOT NULL,
                                `isDiscount` INTEGER NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`receiptSpendId`) REFERENCES `receipt_spend`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_spend_items_receiptSpendId` ON `receipt_spend_items` (`receiptSpendId`)")

                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `receipt_category_mappings` (
                                `id` TEXT NOT NULL,
                                `retailerId` TEXT NOT NULL,
                                `normalizedDescription` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipt_category_mappings_retailerId_normalizedDescription` ON `receipt_category_mappings` (`retailerId`, `normalizedDescription`)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val v15Db = openHelper.writableDatabase

        // 2. Insert v15 historical data
        v15Db.execSQL("""
            INSERT INTO `inventory_items` (
                `id`, `canonicalName`, `displayName`, `category`, `storageLocation`,
                `quantity`, `quantityUnit`, `quantityConfidence`, `status`, `opened`,
                `frozen`, `staple`, `createdAt`, `updatedAt`, `revision`
            ) VALUES (
                'item-v15-1', 'olive oil', 'Olive Oil', 'CUPBOARD', 'CUPBOARD',
                3.5, 'LITRE', 'EXACT', 'AVAILABLE', 1, 0, 0, 1000, 1000, 5
            )
        """.trimIndent())

        v15Db.execSQL("""
            INSERT INTO `household_identity` (`householdId`, `householdName`, `mainDeviceId`, `createdAt`)
            VALUES ('house-v15-1', 'Family Pantry', 'main-dev-100', 1000)
        """.trimIndent())

        v15Db.execSQL("""
            INSERT INTO `paired_devices` (`deviceId`, `deviceName`, `devicePublicKey`, `status`, `pairedAt`, `lastSyncTimestamp`)
            VALUES ('follower-dev-1', 'Follower Phone', 'pub-key-1', 'ACTIVE', 1000, 1000)
        """.trimIndent())

        v15Db.close()

        // 3. Open through Room with the same migration list the app uses
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        // 4. Assert data preservation across the full migration chain
        val itemAfter = migratedDb.inventoryDao().getItemById("item-v15-1")
        assertNotNull("Historical item-v15-1 MUST survive migration to the current version", itemAfter)
        assertEquals("Olive Oil", itemAfter!!.displayName)
        assertEquals(3.5, itemAfter.quantity!!, 0.001)
        assertEquals(5L, itemAfter.revision)

        val identityAfter = migratedDb.householdIdentityDao().getHouseholdIdentity()
        assertNotNull("Household identity MUST survive migration to the current version", identityAfter)
        assertEquals("house-v15-1", identityAfter!!.householdId)
        assertEquals("Family Pantry", identityAfter.householdName)

        val pairedAfter = migratedDb.pairedDeviceDao().getDeviceById("follower-dev-1")
        assertNotNull("Paired device MUST survive migration to the current version", pairedAfter)
        assertEquals("ACTIVE", pairedAfter!!.status)

        // 5. Assert v16 sync tables created and initialized
        val seqEntity = migratedDb.canonicalSyncDao().getSequenceEntity()
        assertNotNull("v16 canonical_sync_sequence MUST be initialized", seqEntity)
        assertEquals(1L, seqEntity!!.nextSequence)

        migratedDb.close()
    }
}
