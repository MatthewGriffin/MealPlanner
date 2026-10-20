package com.example.chatgpt_mealplanner.core.repository

import com.example.chatgpt_mealplanner.core.database.dao.ShoppingDao
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingListItemWithSourceEntities
import com.example.chatgpt_mealplanner.core.database.dao.ShoppingListWithItemEntities
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingList
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.model.ShoppingListWithItems
import com.example.chatgpt_mealplanner.core.model.ShoppingRequirementSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepositoryImpl @Inject constructor(
    private val shoppingDao: ShoppingDao
) : ShoppingRepository {

    override suspend fun getActiveShoppingList(): ShoppingList? =
        shoppingDao.getActiveShoppingList()?.toDomainModel()

    override fun observeActiveShoppingListWithItems(): Flow<ShoppingListWithItems?> =
        shoppingDao.observeActiveShoppingListWithItems().map { it?.toDomainModel() }

    override suspend fun getShoppingListWithItems(listId: String): ShoppingListWithItems? =
        shoppingDao.getShoppingListWithItems(listId)?.toDomainModel()

    override suspend fun insertShoppingList(list: ShoppingList) {
        shoppingDao.insertShoppingList(list.toEntity())
    }

    override suspend fun insertShoppingListItem(item: ShoppingListItem) {
        shoppingDao.insertShoppingListItem(item.toEntity())
    }

    override suspend fun insertShoppingListItems(items: List<ShoppingListItem>) {
        shoppingDao.insertShoppingListItems(items.map { it.toEntity() })
    }

    override suspend fun insertRequirementSources(sources: List<ShoppingRequirementSource>) {
        shoppingDao.insertRequirementSources(sources.map { it.toEntity() })
    }

    override suspend fun deleteGeneratedItemsForList(listId: String) {
        shoppingDao.deleteGeneratedItemsForList(listId)
    }

    override suspend fun deleteShoppingListItem(itemId: String) {
        shoppingDao.deleteShoppingListItem(itemId)
    }

    override suspend fun deleteCompletedItems(listId: String): Int =
        shoppingDao.deleteCompletedItems(listId)

    override suspend fun updateItemStatus(itemId: String, status: ShoppingItemStatus) {
        shoppingDao.updateItemStatus(itemId, status.name)
    }

    private fun ShoppingListWithItemEntities.toDomainModel(): ShoppingListWithItems =
        ShoppingListWithItems(
            shoppingList = shoppingList.toDomainModel(),
            items = items.map { it.toDomainModel() }
        )

    private fun ShoppingListItemWithSourceEntities.toDomainModel(): ShoppingListItemWithSources =
        ShoppingListItemWithSources(
            item = item.toDomainModel(),
            sources = sources.map { it.toDomainModel() }
        )
}
