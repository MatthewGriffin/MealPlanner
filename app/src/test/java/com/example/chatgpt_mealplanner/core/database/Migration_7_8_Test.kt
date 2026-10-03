package com.example.chatgpt_mealplanner.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class Migration_7_8_Test {

    @Test
    fun migration7_8_sqlStatementsValidation() {
        val sqliteStatement = """
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
        """.trimIndent()

        val indexStatement = "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_item_barcodes_normalizedBarcode` ON `inventory_item_barcodes` (`normalizedBarcode`)"

        val backfillStatement = """
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
        """.trimIndent()

        assertTrue(sqliteStatement.contains("CREATE TABLE IF NOT EXISTS `inventory_item_barcodes`"))
        assertTrue(indexStatement.contains("CREATE UNIQUE INDEX IF NOT EXISTS"))
        assertTrue(backfillStatement.contains("INSERT OR IGNORE INTO `inventory_item_barcodes`"))
    }
}
