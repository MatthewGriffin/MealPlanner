package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.ai.provider.ReceiptReadingParsing
import com.example.chatgpt_mealplanner.core.model.FoodCategory
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptAutoLinkerTest {
    private fun line(raw: String, name: String, generic: String? = null, category: String = "FOOD", qty: Int = 1, id: String = raw) =
        ReceiptSpendItemDraft(id = id, rawDescription = raw, normalizedDescription = name, lineAmountMinorPence = 100L * qty, quantity = qty, category = category, genericName = generic)

    @Test
    fun genericNameDropsTheBrand() {
        assertEquals("White bread", ReceiptAutoLinker.genericNameOf(line("WARBURTONS WHT", "Warburtons Soft White Bread", "White bread")))
        assertEquals("Wedges", ReceiptAutoLinker.genericNameOf(line("WEDGES", "Wedges")))
    }

    @Test
    fun linksToTheShoppingListFirst_thenThePantry() {
        val items = listOf(
            line("a", "Warburtons Soft White Bread", "White bread"),
            line("b", "Heinz Baked Beans", "Baked beans"),
            line("c", "Mystery", "Mystery")
        )
        val out = ReceiptAutoLinker.suggestLinks(
            items,
            pantry = listOf(LinkTarget("p1", "Baked beans"), LinkTarget("p2", "White bread")),
            shopping = listOf(LinkTarget("s1", "white bread"))
        )
        assertEquals("s1", out[0].linkedShoppingListItemId)
        assertNull(out[0].linkedInventoryItemId)
        assertEquals("p1", out[1].linkedInventoryItemId)
        assertTrue(out[0].autoLinked && out[1].autoLinked)
        assertNull(out[2].linkedInventoryItemId); assertNull(out[2].linkedShoppingListItemId)
    }

    @Test
    fun aLinkTheUserChose_orClearedByHand_isNeverChanged() {
        val chosen = line("a", "Hovis Bread", "White bread").copy(linkedInventoryItemId = "mine", linkLocked = true)
        val cleared = line("b", "Beans", "Baked beans").copy(linkLocked = true)
        val out = ReceiptAutoLinker.suggestLinks(listOf(chosen, cleared), listOf(LinkTarget("p", "White bread"), LinkTarget("q", "Baked beans")), emptyList())
        assertEquals("mine", out[0].linkedInventoryItemId)
        assertNull(out[1].linkedInventoryItemId)
    }

    @Test
    fun everyClassifiedProductNotInThePantryNeedsAnItem() {
        val pantry = listOf(LinkTarget("p", "White bread"))
        assertFalse(ReceiptAutoLinker.needsPantryItem(line("a", "Warburtons Soft White Bread", "White bread"), pantry))
        assertTrue(ReceiptAutoLinker.needsPantryItem(line("b", "Heinz Baked Beans", "Baked beans"), pantry))
        assertTrue(ReceiptAutoLinker.needsPantryItem(line("c", "Oral-B Paste", "Toothpaste", category = "PERSONAL_CARE"), pantry))
        assertFalse(ReceiptAutoLinker.needsPantryItem(line("d", "Mystery", "Mystery", category = "UNCATEGORISED"), pantry))
        assertTrue(ReceiptAutoLinker.needsPantryItem(line("e", "Four Loko", "Alcoholic drink", category = "DRINKS"), pantry))
    }

    @Test
    fun newPantryItems_areGeneric_oneEachAndSumQuantities() {
        val items = listOf(
            line("1", "Warburtons Soft White Bread", "White bread").copy(brand = "Warburtons", foodCategory = "BAKERY", storageLocation = "CUPBOARD"),
            line("2", "WEDGES", "Wedges", id = "w1"),
            line("3", "WEDGES", "Wedges", id = "w2"),
            line("4", "Chicken Breast", "Chicken breast").copy(foodCategory = "MEAT_SEAFOOD"),
            line("5", "Oral-B Paste", "Toothpaste", category = "PERSONAL_CARE")
        )
        val created = ReceiptAutoLinker.newPantryItems(items, emptyList(), "2026-11-18")
        assertEquals(listOf("White bread", "Wedges", "Chicken breast", "Toothpaste"), created.map { it.first.displayName })
        assertEquals(FoodCategory.HOUSEHOLD, created[3].first.category)
        assertEquals(StorageLocation.CUPBOARD, created[3].first.storageLocation)
        val bread = created[0].first
        assertEquals("white bread", bread.canonicalName)
        assertEquals("Warburtons", bread.brand)
        assertEquals(FoodCategory.BAKERY, bread.category)
        assertEquals(2.0, created[1].first.quantity!!, 0.001)
        assertEquals(listOf("w1", "w2"), created[1].second)
        assertEquals(StorageLocation.FRIDGE, created[2].first.storageLocation)   // meat defaults to the fridge
        assertEquals("2026-11-18", bread.purchaseDate)
    }

    @Test
    fun statedAmountsBecomeTheQuantity_perItemTimesItemsBought() {
        val twoLoaves = line("1", "Warburtons Bread", "White bread", qty = 2).copy(pantryQuantity = 800.0, pantryUnit = "GRAM")
        val created = ReceiptAutoLinker.newPantryItems(listOf(twoLoaves), emptyList(), null).single().first
        assertEquals(1600.0, created.quantity!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.GRAM, created.quantityUnit)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityConfidence.EXACT, created.quantityConfidence)
    }

    @Test
    fun noStatedAmount_fallsBackToCountingItems() {
        val created = ReceiptAutoLinker.newPantryItems(listOf(line("1", "Eggs", "Eggs", qty = 2)), emptyList(), null).single().first
        assertEquals(2.0, created.quantity!!, 0.001)
        assertEquals(com.example.chatgpt_mealplanner.core.model.QuantityUnit.UNIT, created.quantityUnit)
    }

    @Test
    fun theSameProductAnyNameHasTheSameMemoryKey() {
        assertEquals(
            ReceiptAutoLinker.memoryKey(line("1", "Warburtons Soft White Bread", "White bread")),
            ReceiptAutoLinker.memoryKey(line("2", "Hovis Sort Whit", "white bread"))
        )
    }

    @Test
    fun claudeReplyCarriesTheGenericNameAndBrand() {
        val read = ReceiptReadingParsing.parse("""{"retailer":"Tesco","currency":"GBP","items":[{"printedText":"WARB WHT BRD","name":"Warburtons Soft White Bread","genericName":"White bread","brand":"Warburtons","foodCategory":"BAKERY","storageLocation":"CUPBOARD","category":"FOOD","quantity":1,"amount":"1.50"}],"totalPaid":"1.50"}""")!!
        val line = read.items.single()
        assertEquals("White bread", line.genericName)
        assertEquals("Warburtons", line.brand)
        assertEquals("BAKERY", line.foodCategory)
        val draft = ReceiptReadingDraft.toDraft(read, null)
        assertEquals("White bread", draft.items.single().genericName)
    }
}
