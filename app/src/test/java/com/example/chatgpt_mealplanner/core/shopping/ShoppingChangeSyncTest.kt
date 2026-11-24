package com.example.chatgpt_mealplanner.core.shopping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chatgpt_mealplanner.core.database.AppDatabase
import com.example.chatgpt_mealplanner.core.database.entity.ShoppingListEntity
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingSourceType
import com.example.chatgpt_mealplanner.sync.contract.ShoppingChangeKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ShoppingChangeSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var applier: ShoppingChangeApplier

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        applier = ShoppingChangeApplier(db.shoppingDao())
        runBlocking {
            db.shoppingDao().insertShoppingList(
                ShoppingListEntity("list1", "Active", null, null, ShoppingListStatus.ACTIVE, 1L, 1L)
            )
        }
    }

    @After fun tearDown() { db.close() }

    private fun change(kind: ShoppingChangeKind, id: String = "i1", name: String? = null, why: String? = null, status: String? = null) =
        ShoppingChangeQueue.newChange(kind, id, displayName = name, reason = why, status = status)

    @Test fun anAddedItemRemembersWhoAskedAndWhy() = runBlocking {
        applier.apply(change(ShoppingChangeKind.ADD, name = "Dish soap", why = "Ran out"), "SM-G965F")
        val item = db.shoppingDao().getShoppingListItemById("i1")!!
        assertEquals("Dish soap", item.displayName)
        assertEquals("SM-G965F", item.requestedBy)
        assertEquals("Ran out", item.reason)
        assertEquals(ShoppingSourceType.MANUAL, item.sourceType)
        assertEquals("list1", item.shoppingListId)
    }

    @Test fun applyingTheSameChangeTwiceOrTheSameNameAgainAddsNothingNew() = runBlocking {
        val add = change(ShoppingChangeKind.ADD, name = "Dish soap")
        applier.apply(add, "A"); applier.apply(add, "A")
        applier.apply(change(ShoppingChangeKind.ADD, id = "i2", name = "dish SOAP"), "B")
        assertEquals(1, db.shoppingDao().getShoppingListWithItems("list1")!!.items.size)
    }

    @Test fun renameWhyAndTickAreAppliedToHandAddedItems() = runBlocking {
        applier.apply(change(ShoppingChangeKind.ADD, name = "Soap"), "A")
        applier.apply(change(ShoppingChangeKind.EDIT, name = "Dish soap", why = "For Sam"), "A")
        applier.apply(change(ShoppingChangeKind.SET_STATUS, status = ShoppingItemStatus.CHECKED.name), "A")
        val item = db.shoppingDao().getShoppingListItemById("i1")!!
        assertEquals("Dish soap", item.displayName)
        assertEquals("For Sam", item.reason)
        assertEquals("A", item.requestedBy)
        assertEquals(ShoppingItemStatus.CHECKED, item.status)
        applier.apply(change(ShoppingChangeKind.REMOVE), "A")
        assertNull(db.shoppingDao().getShoppingListItemById("i1"))
    }

    @Test fun itemsWorkedOutFromMealsAreNeverTouchedByAFollower() = runBlocking {
        val meal = ShoppingChangeApplier.newManualItem("m1", "list1", "Chicken", 1.0, null, null, null, 1L)
            .copy(sourceType = ShoppingSourceType.MEAL_REQUIREMENT)
        db.shoppingDao().insertShoppingListItem(meal)
        applier.apply(change(ShoppingChangeKind.REMOVE, id = "m1"), "A")
        applier.apply(change(ShoppingChangeKind.EDIT, id = "m1", name = "Hacked"), "A")
        assertEquals("Chicken", db.shoppingDao().getShoppingListItemById("m1")!!.displayName)
    }

    @Test fun theQueueFoldsChangesToTheSameItem() {
        val add = change(ShoppingChangeKind.ADD, name = "Soap", why = "a")
        val edit = change(ShoppingChangeKind.EDIT, name = "Dish soap", why = "b")
        var q = ShoppingChangeQueue.fold(emptyList(), add)
        q = ShoppingChangeQueue.fold(q, edit)
        assertEquals(1, q.size)
        assertEquals("Dish soap", q.single().displayName)
        assertEquals(ShoppingChangeKind.ADD, q.single().kind)
        assertEquals("b", q.single().reason)

        // Added then removed before Main heard of it: nothing to send.
        assertTrue(ShoppingChangeQueue.fold(q, change(ShoppingChangeKind.REMOVE)).isEmpty())

        // An item Main already has: a remove replaces earlier edits and ticks.
        val onMain = ShoppingChangeQueue.fold(ShoppingChangeQueue.fold(emptyList(), edit), change(ShoppingChangeKind.SET_STATUS, status = "CHECKED"))
        val removed = ShoppingChangeQueue.fold(onMain, change(ShoppingChangeKind.REMOVE))
        assertEquals(listOf(ShoppingChangeKind.REMOVE), removed.map { it.kind })

        // Only the latest tick matters.
        val ticks = ShoppingChangeQueue.fold(ShoppingChangeQueue.fold(emptyList(), change(ShoppingChangeKind.SET_STATUS, status = "CHECKED")), change(ShoppingChangeKind.SET_STATUS, status = "NEEDED"))
        assertEquals("NEEDED", ticks.single().status)
    }

    @Test fun theQueueSurvivesRestartsAndForgetsWhatMainConfirmed() {
        val queue = ShoppingChangeQueue(context)
        queue.clear()
        val a = change(ShoppingChangeKind.ADD, id = "x", name = "Soap")
        val b = change(ShoppingChangeKind.ADD, id = "y", name = "Milk")
        queue.record(a); queue.record(b)
        assertEquals(2, ShoppingChangeQueue(context).pending().size)
        queue.acknowledge(listOf(a.changeId))
        assertEquals(listOf("y"), ShoppingChangeQueue(context).pending().map { it.itemId })
        assertNotNull(queue.pending().firstOrNull())
        queue.clear()
    }
}
