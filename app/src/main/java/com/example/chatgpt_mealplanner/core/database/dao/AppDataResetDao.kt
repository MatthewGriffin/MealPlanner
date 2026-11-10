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
 * Starts the household from a clean slate: chat history, meals and meal plans, inventory and the
 * shopping items worked out from them are deleted. Settings, API keys, cooking preferences, shops
 * and recorded prices, receipts and spending, product knowledge, and household and pairing
 * details are kept. Each delete is a Room query so screens watching these tables update.
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

    /** Shopping items that only existed because of meals or staples. Items the user added by hand stay. */
    @Query("DELETE FROM shopping_list_items WHERE sourceType != 'MANUAL'")
    abstract suspend fun deleteDerivedShoppingItems()

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
        deleteDerivedShoppingItems()
    }
}
