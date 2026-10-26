package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.IngredientAvailability
import com.example.chatgpt_mealplanner.contract.MealProposal
import com.example.chatgpt_mealplanner.contract.ProposedIngredient
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.database.entity.InventoryItemEntity
import com.example.chatgpt_mealplanner.core.database.entity.MealEntity
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.ItemStatus
import com.example.chatgpt_mealplanner.core.model.MealStatus
import com.example.chatgpt_mealplanner.core.model.MealType
import com.example.chatgpt_mealplanner.core.model.QuantityConfidence
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RecipeValidatorTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var mealDao: FakeMealDao
    private lateinit var validator: RecipeValidator

    @Before
    fun setup() {
        inventoryDao = FakeInventoryDao()
        mealDao = FakeMealDao()
        validator = RecipeValidator(inventoryDao, mealDao)
    }

    @Test
    fun validatesPortionInvariants_invalidWhenMismatch() = runTest {
        val proposal = MealProposal(
            title = "Test Dish",
            servings = 2,
            batchPortions = 4,
            immediatePortions = 2,
            leftoverPortions = 1, // 2 + 1 != 4!
            ingredients = listOf(ProposedIngredient("C1", "Salt", 5.0, "GRAM")),
            reasoningSummary = "Reasoning"
        )

        val result = validator.validateProposal(proposal)
        assertTrue(result.issues.any { it.contains("Batch portions") })
    }

    @Test
    fun derivesAvailabilityAndShoppingNeeded_independently() = runTest {
        val lot = createLot("l1", "CHICKEN", "Chicken Breast", 400.0, QuantityUnit.GRAM)
        inventoryDao.insertItem(lot)

        val proposal = MealProposal(
            title = "Chicken Curry",
            servings = 2,
            batchPortions = 2,
            immediatePortions = 2,
            leftoverPortions = 0,
            ingredients = listOf(
                ProposedIngredient("CHICKEN", "Chicken Breast", 400.0, "GRAM"),
                ProposedIngredient("CURRY_PASTE", "Curry Paste", 50.0, "GRAM")
            ),
            reasoningSummary = "Tasty curry"
        )

        val result = validator.validateProposal(proposal)

        val chickenVal = result.validatedIngredients.first { it.ingredient.canonicalIngredientId == "CHICKEN" }
        assertEquals(IngredientAvailability.AVAILABLE, chickenVal.availability)

        val curryVal = result.validatedIngredients.first { it.ingredient.canonicalIngredientId == "CURRY_PASTE" }
        assertEquals(IngredientAvailability.MISSING, curryVal.availability)

        assertTrue(result.shoppingNeeded) // Missing curry paste triggers shoppingNeeded
    }

    @Test
    fun emitsWarning_whenRecentCookedRepetition() = runTest {
        val meal = MealEntity(
            id = "m_recent",
            date = "2026-09-25",
            mealType = MealType.DINNER,
            title = "Chicken Curry",
            servings = 2,
            status = MealStatus.COOKED,
            batchPortions = 2,
            immediatePortions = 2,
            leftoverPortions = 0,
            cookInTwoBatches = false,
            tags = emptySet(),
            instructions = null,
            notes = null,
            cookedAt = System.currentTimeMillis() - 100000L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        mealDao.insertMeal(meal)

        val proposal = MealProposal(
            title = "Chicken Curry",
            servings = 2,
            batchPortions = 2,
            immediatePortions = 2,
            leftoverPortions = 0,
            ingredients = listOf(ProposedIngredient("CHICKEN", "Chicken", 400.0, "GRAM")),
            reasoningSummary = "Reasoning"
        )

        val result = validator.validateProposal(proposal)
        assertTrue(result.warnings.any { it.contains("cooked 'Chicken Curry' within the last 14 days") })
    }

    @Test
    fun rejectsVagueHerbsIngredient_thatCannotBeAddedToShoppingList() = runTest {
        val proposal = MealProposal(
            title = "Pasta",
            servings = 2,
            batchPortions = 2,
            immediatePortions = 2,
            leftoverPortions = 0,
            ingredients = listOf(
                ProposedIngredient(null, "Herbs", 1.0, "TEASPOON"),
                ProposedIngredient("TOMATO", "Chopped tomatoes", 400.0, "GRAM")
            ),
            reasoningSummary = "Test recipe"
        )

        val result = validator.validateProposal(proposal)

        assertTrue(result.issues.any { it.contains("Herbs") && it.contains("too vague to shop for") })
    }

    private fun createLot(id: String, canonicalId: String, name: String, qty: Double, unit: QuantityUnit) = InventoryItemEntity(
        id = id,
        barcode = null,
        canonicalIngredientId = canonicalId,
        canonicalName = name,
        displayName = name,
        brand = null,
        category = FoodCategory.FRIDGE,
        storageLocation = StorageLocation.FRIDGE,
        quantity = qty,
        quantityUnit = unit,
        packCount = null,
        packSize = null,
        packSizeUnit = null,
        quantityConfidence = QuantityConfidence.EXACT,
        status = ItemStatus.AVAILABLE,
        opened = false,
        frozen = false,
        staple = false,
        purchaseDate = null,
        openedDate = null,
        useByDate = null,
        bestBeforeDate = null,
        notes = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}
