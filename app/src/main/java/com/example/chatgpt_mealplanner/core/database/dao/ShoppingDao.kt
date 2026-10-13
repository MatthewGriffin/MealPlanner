package com.example.chatgpt_mealplanner.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingRequirementSourceEntity
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import kotlinx.coroutines.flow.Flow

data class ShoppingListItemWithSourceEntities(
    @androidx.room.Embedded val item: ShoppingListItemEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "shoppingListItemId"
    )
    val sources: List<ShoppingRequirementSourceEntity>
)

data class ShoppingListWithItemEntities(
    @androidx.room.Embedded val shoppingList: ShoppingListEntity,
    @androidx.room.Relation(
        entity = ShoppingListItemEntity::class,
        parentColumn = "id",
        entityColumn = "shoppingListId"
    )
    val items: List<ShoppingListItemWithSourceEntities>
)

@Dao
interface ShoppingDao {

    @Query("SELECT * FROM shopping_lists WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveShoppingList(): ShoppingListEntity?

    @Transaction
    @Query("SELECT * FROM shopping_lists WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActiveShoppingListWithItems(): Flow<ShoppingListWithItemEntities?>

    @Transaction
    @Query("SELECT * FROM shopping_lists WHERE id = :listId")
    suspend fun getShoppingListWithItems(listId: String): ShoppingListWithItemEntities?

    @Transaction
    @Query("SELECT * FROM shopping_lists ORDER BY createdAt ASC")
    suspend fun getAllListsWithItemsAndSourcesSync(): List<ShoppingListWithItemEntities>

    @Query("SELECT * FROM shopping_list_items WHERE id = :itemId")
    suspend fun getShoppingListItemById(itemId: String): ShoppingListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingList(list: ShoppingListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingListItems(items: List<ShoppingListItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingListItem(item: ShoppingListItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequirementSources(sources: List<ShoppingRequirementSourceEntity>)

    @Query("DELETE FROM shopping_list_items WHERE shoppingListId = :listId AND sourceType IN ('MEAL_REQUIREMENT', 'STAPLE_RESTOCK', 'MEAL_AND_STAPLE')")
    suspend fun deleteGeneratedItemsForList(listId: String)

    @Query("DELETE FROM shopping_list_items WHERE id = :itemId")
    suspend fun deleteShoppingListItem(itemId: String)

    @Query("UPDATE shopping_list_items SET status = :status WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: String)

    @Query("UPDATE shopping_lists SET status = :status WHERE id = :listId")
    suspend fun updateListStatus(listId: String, status: String)
}
