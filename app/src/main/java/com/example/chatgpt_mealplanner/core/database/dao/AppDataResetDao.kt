package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** What "Clear all data" will remove, so the confirmation can say so. */
data class ClearableDataCounts(
    val chatConversations: Int,
    val meals: Int,
    val inventoryItems: Int
)

/**
 * Starts the household from a clean slate. Every piece of data is deleted: chat history, meals and
 * meal plans, inventory, shopping lists, receipts and spending, recorded prices and the products the
 * app has learned. Only configuration is kept: the API key (stored outside the database), cooking
 * preferences, the shops listed in Settings, and household, pairing and sync details. Each delete
 * is a Room query so screens watching these tables update.
 */
@Dao
abstract class AppDataResetDao {

    @Query("SELECT COUNT(*) FROM chat_conversations")
    abstract suspend fun countConversations(): Int

    @Query("SELECT COUNT(*) FROM meals")
    abstract suspend fun countMeals(): Int

    @Query("SELECT COUNT(*) FROM inventory_items")
    abstract suspend fun countInventoryItems(): Int

    suspend fun counts() = ClearableDataCounts(countConversations(), countMeals(), countInventoryItems())

    // Chat
    @Query("DELETE FROM ai_tool_execution_logs") abstract suspend fun deleteToolLogs()
    @Query("DELETE FROM chat_messages") abstract suspend fun deleteChatMessages()
    @Query("DELETE FROM chat_conversations") abstract suspend fun deleteConversations()
    @Query("DELETE FROM pending_ai_mutations") abstract suspend fun deletePendingAiMutations()
    @Query("DELETE FROM active_proposals") abstract suspend fun deleteActiveProposals()

    // Meals and plans
    @Query("DELETE FROM meal_change_requests") abstract suspend fun deleteMealChangeRequests()
    @Query("DELETE FROM shopping_requirement_sources") abstract suspend fun deleteShoppingRequirementSources()
    @Query("DELETE FROM leftovers") abstract suspend fun deleteLeftovers()
    @Query("DELETE FROM meal_ingredients") abstract suspend fun deleteMealIngredients()
    @Query("DELETE FROM meals") abstract suspend fun deleteMeals()

    // Inventory
    @Query("DELETE FROM inventory_merge_logs") abstract suspend fun deleteMergeLogs()
    @Query("DELETE FROM inventory_transactions") abstract suspend fun deleteInventoryTransactions()
    @Query("DELETE FROM inventory_item_barcodes") abstract suspend fun deleteInventoryItemBarcodes()
    @Query("DELETE FROM staple_rules") abstract suspend fun deleteStapleRules()
    @Query("DELETE FROM inventory_items") abstract suspend fun deleteInventoryItems()

    // Shopping
    @Query("DELETE FROM shopping_list_items") abstract suspend fun deleteShoppingListItems()
    @Query("DELETE FROM shopping_lists") abstract suspend fun deleteShoppingLists()

    // Receipts and spending
    @Query("DELETE FROM receipt_import_lines") abstract suspend fun deleteReceiptImportLines()
    @Query("DELETE FROM receipt_imports") abstract suspend fun deleteReceiptImports()
    @Query("DELETE FROM receipt_product_mappings") abstract suspend fun deleteReceiptProductMappings()
    @Query("DELETE FROM receipt_spend_items") abstract suspend fun deleteReceiptSpendItems()
    @Query("DELETE FROM receipt_spend") abstract suspend fun deleteReceiptSpend()
    @Query("DELETE FROM receipt_category_mappings") abstract suspend fun deleteReceiptCategoryMappings()

    // Prices and learned products. The shops themselves are configuration and stay.
    @Query("DELETE FROM purchase_price_observations") abstract suspend fun deletePriceObservations()
    @Query("DELETE FROM product_barcode_mappings") abstract suspend fun deleteProductMappings()

    /** All of it, or none of it. */
    @Transaction
    open suspend fun clearHouseholdData() {
        deleteToolLogs()
        deleteChatMessages()
        deleteConversations()
        deletePendingAiMutations()
        deleteActiveProposals()
        deleteMealChangeRequests()
        deleteShoppingRequirementSources()
        deleteLeftovers()
        deleteMealIngredients()
        deleteMeals()
        deleteMergeLogs()
        deleteInventoryTransactions()
        deleteInventoryItemBarcodes()
        deleteStapleRules()
        deleteInventoryItems()
        deleteShoppingListItems()
        deleteShoppingLists()
        deleteReceiptImportLines()
        deleteReceiptImports()
        deleteReceiptProductMappings()
        deleteReceiptSpendItems()
        deleteReceiptSpend()
        deleteReceiptCategoryMappings()
        deletePriceObservations()
        deleteProductMappings()
    }
}
