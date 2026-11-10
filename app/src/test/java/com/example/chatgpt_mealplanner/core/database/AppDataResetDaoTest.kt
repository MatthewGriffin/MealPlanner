package com.example.chatgpt_mealplanner.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity
import com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity
import com.example.chatgpt_mealplanner.core.database.entity.CookingPreferencesEntity
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.InventoryTransaction
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingList
import com.example.chatgpt_mealplanner.core.model.ShoppingListItem
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room database with foreign keys on, since the deletes cascade and the order matters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AppDataResetDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed() = runBlocking {
        db.chatDao().insertOrUpdateConversation(ChatConversationEntity("c1", "Chat", 1L, 1L))
        db.chatDao().insertMessage(ChatMessageEntity(id = "m1", conversationId = "c1", role = "USER", content = "hi", timestamp = 1L))

        db.mealDao().insertMeal(Meal(id = "meal1", date = "2026-11-10", title = "Dinner").toEntity())
        db.mealDao().insertMealIngredients(listOf(MealIngredient("i1", "meal1", displayName = "Rice", plannedQuantity = 100.0, plannedUnit = QuantityUnit.GRAM).toEntity()))

        val item = InventoryItem(
            id = "inv1", canonicalName = "rice", displayName = "Rice", category = FoodCategory.DRY_GOODS,
            storageLocation = StorageLocation.CUPBOARD, quantity = 1000.0, quantityUnit = QuantityUnit.GRAM
        )
        db.inventoryDao().insertItem(item.toEntity())
        db.transactionDao().insertTransaction(InventoryTransaction(inventoryItemId = "inv1", type = TransactionType.RESTOCK).toEntity())

        db.shoppingDao().insertShoppingList(ShoppingList("list1", "Shopping").toEntity())
        db.shoppingDao().insertShoppingListItems(
            listOf(
                ShoppingListItem(id = "derived", shoppingListId = "list1", displayName = "Milk", reason = "meal", sourceType = ShoppingSourceType.MEAL_REQUIREMENT).toEntity(),
                ShoppingListItem(id = "manual", shoppingListId = "list1", displayName = "Kitchen towels", reason = "added", sourceType = ShoppingSourceType.MANUAL).toEntity()
            )
        )

        // Things that must survive.
        db.cookingPreferencesDao().save(CookingPreferencesEntity(allergiesJson = "[\"Peanuts\"]", dietsJson = "[]", dislikesJson = "[]", equipmentJson = "[]", householdSize = 3, skillLevel = null, updatedAt = 1L))
        db.purchasePriceDao().insertStore(UserStoreEntity("store1", "Asda"))
    }

    @Test
    fun clearsChatMealsAndInventory_butKeepsPreferencesAndShops() = runBlocking {
        seed()
        val dao = db.appDataResetDao()
        val counts = dao.counts()
        assertEquals(1, counts.chatConversations)
        assertEquals(1, counts.meals)
        assertEquals(1, counts.inventoryItems)

        dao.clearHouseholdData()

        val after = dao.counts()
        assertEquals(0, after.chatConversations)
        assertEquals(0, after.meals)
        assertEquals(0, after.inventoryItems)
        assertEquals(0, db.chatDao().getMessagesForConversation("c1").size)
        assertEquals(null, db.mealDao().getMealWithIngredients("meal1"))
        assertEquals(emptyList<Any>(), db.transactionDao().getTransactionsForItem("inv1").first())

        // The shopping item that was worked out from meals goes; the one added by hand stays.
        assertEquals(null, db.shoppingDao().getShoppingListItemById("derived"))
        assertNotNull(db.shoppingDao().getShoppingListItemById("manual"))

        // Settings-like data is untouched.
        assertEquals(3, db.cookingPreferencesDao().get()?.householdSize)
        assertNotNull(db.purchasePriceDao().getAllStores().firstOrNull { it.name == "Asda" })
    }

    @Test
    fun clearingAnEmptyDatabaseIsHarmless() = runBlocking {
        db.appDataResetDao().clearHouseholdData()
        assertEquals(0, db.appDataResetDao().counts().meals)
    }
}
