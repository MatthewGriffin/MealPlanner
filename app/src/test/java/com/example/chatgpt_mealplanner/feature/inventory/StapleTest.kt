package com.example.chatgpt_mealplanner.feature.inventory

import com.example.chatgpt_mealplanner.contract.ActionAuthorizationContext
import com.example.chatgpt_mealplanner.contract.AiToolCall
import com.example.chatgpt_mealplanner.contract.AiToolId
import com.example.chatgpt_mealplanner.contract.AuthorizedMutationScope
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.database.dao.InventoryDao
import com.example.chatgpt_mealplanner.core.database.dao.StapleRuleDao
import com.example.chatgpt_mealplanner.core.database.dao.TransactionDao
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.InventoryTransactionEntity
import com.example.chatgpt_mealplanner.core.database.entity.StapleRuleEntity
import com.example.chatgpt_mealplanner.core.database.entity.toDomainModel
import com.example.chatgpt_mealplanner.core.database.entity.toEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.InventoryItem
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class StapleTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var domainService: InventoryDomainServiceImpl
    private lateinit var intentResolver: UserMutationIntentResolver

    @Before
    fun setup() {
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        stapleRuleDao = FakeStapleRuleDao()
        val runner = FakeTransactionRunner()
        domainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = object : TransactionDao {
                override fun getTransactionsForItem(itemId: String): Flow<List<InventoryTransactionEntity>> = MutableStateFlow(emptyList())
                override fun getAllTransactions(): Flow<List<InventoryTransactionEntity>> = MutableStateFlow(emptyList())
                override suspend fun hasCookedTransactionForMeal(mealId: String): Boolean = false
                override suspend fun getTransactionsForMeal(mealId: String): List<InventoryTransactionEntity> = emptyList()
                override suspend fun insertTransaction(transaction: InventoryTransactionEntity) {}
                override suspend fun insertTransactions(transactions: List<InventoryTransactionEntity>) {}
            },
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )
        intentResolver = UserMutationIntentResolver()
    }

    @Test
    fun markStaple_createsOrActivatesRequiredStapleRule_evenWhenCanonicalIdIsNull() = runTest {
        val bread = InventoryItem(
            id = "bread_999",
            canonicalIngredientId = null,
            canonicalName = "Bread",
            displayName = "White Sliced Bread",
            quantity = 1.0,
            quantityUnit = QuantityUnit.PACK,
            storageLocation = StorageLocation.CUPBOARD,
            category = FoodCategory.BREAD_BAKERY,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            staple = false,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        inventoryDao.insertItem(bread.toEntity())

        domainService.setStapleStatus("bread_999", true)

        val rule = stapleRuleDao.getStapleRuleByCanonicalIngredientId("item_bread_999")
        assertNotNull(rule)
        assertTrue(rule!!.enabled)
        assertEquals("White Sliced Bread", rule.displayName)

        domainService.setStapleStatus("bread_999", false)
        val disabledRule = stapleRuleDao.getStapleRuleByCanonicalIngredientId("item_bread_999")
        assertNotNull(disabledRule)
        assertFalse(disabledRule!!.enabled)
    }

    @Test
    fun ui_canMarkAndUnmarkItemAsStaple() = runTest {
        val milk = InventoryItem(
            id = "milk_1",
            canonicalIngredientId = "MILK",
            canonicalName = "Milk",
            displayName = "Whole Milk",
            quantity = 2.0,
            quantityUnit = QuantityUnit.LITRE,
            storageLocation = StorageLocation.FRIDGE,
            category = FoodCategory.FRIDGE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            staple = false,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        inventoryDao.insertItem(milk.toEntity())

        val setRes = domainService.setStapleStatus("milk_1", true)
        assertTrue(setRes is DomainResult.Success)
        assertTrue((setRes as DomainResult.Success).value)

        val updatedMilk = inventoryDao.getItemById("milk_1")
        assertNotNull(updatedMilk)
        assertTrue(updatedMilk!!.staple)

        val unsetRes = domainService.setStapleStatus("milk_1", false)
        assertTrue(unsetRes is DomainResult.Success)
        assertFalse((unsetRes as DomainResult.Success).value)

        val unstackedMilk = inventoryDao.getItemById("milk_1")
        assertFalse(unstackedMilk!!.staple)
    }

    @Test
    fun stapleState_persistsAndIdempotent() = runTest {
        val eggs = InventoryItem(
            id = "eggs_1",
            canonicalIngredientId = "EGGS",
            canonicalName = "Eggs",
            displayName = "Free Range Eggs",
            quantity = 12.0,
            quantityUnit = QuantityUnit.PACK,
            storageLocation = StorageLocation.FRIDGE,
            category = FoodCategory.FRIDGE,
            quantityConfidence = QuantityConfidence.EXACT,
            status = ItemStatus.AVAILABLE,
            staple = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        inventoryDao.insertItem(eggs.toEntity())

        val res1 = domainService.setStapleStatus("eggs_1", true)
        assertTrue((res1 as DomainResult.Success).value)

        val res2 = domainService.setStapleStatus("eggs_1", false)
        assertFalse((res2 as DomainResult.Success).value)
        val res3 = domainService.setStapleStatus("eggs_1", false)
        assertFalse((res3 as DomainResult.Success).value)
    }

    @Test
    fun intentResolver_parsesStaplePhrases() {
        val ctx1 = intentResolver.resolveAuthorizationContext("m1", "Mark milk as a staple")
        assertTrue(ctx1.scopes.any { it is AuthorizedMutationScope.SetStapleScope && (it as AuthorizedMutationScope.SetStapleScope).isStaple })

        val ctx2 = intentResolver.resolveAuthorizationContext("m2", "Make milk a staple")
        assertTrue(ctx2.scopes.any { it is AuthorizedMutationScope.SetStapleScope && (it as AuthorizedMutationScope.SetStapleScope).isStaple })

        val ctx3 = intentResolver.resolveAuthorizationContext("m3", "Add milk to my staples")
        assertTrue(ctx3.scopes.any { it is AuthorizedMutationScope.SetStapleScope && (it as AuthorizedMutationScope.SetStapleScope).isStaple })

        val ctx4 = intentResolver.resolveAuthorizationContext("m4", "Milk isn't a staple anymore")
        assertTrue(ctx4.scopes.any { it is AuthorizedMutationScope.SetStapleScope && !(it as AuthorizedMutationScope.SetStapleScope).isStaple })

        val ctx5 = intentResolver.resolveAuthorizationContext("m5", "Remove eggs from my staples")
        assertTrue(ctx5.scopes.any { it is AuthorizedMutationScope.SetStapleScope && !(it as AuthorizedMutationScope.SetStapleScope).isStaple })
    }

    @Test
    fun aiToolId_containsSetStapleStatus_andIsDiscoverable() {
        val tool = AiToolId.fromToolName("set_staple_status")
        assertNotNull(tool)
        assertEquals(AiToolId.SET_STAPLE_STATUS, tool)
        assertEquals("set_staple_status", tool!!.toolName)
    }
}
