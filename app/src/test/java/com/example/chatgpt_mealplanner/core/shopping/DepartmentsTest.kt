package com.example.chatgpt_mealplanner.core.shopping

import org.junit.Assert.assertEquals
import org.junit.Test

class DepartmentsTest {
    private fun d(name: String) = Department.classify(name)

    @Test fun commonItemsLandInTheRightDepartment() {
        assertEquals(Department.PRODUCE, d("Sliced green jalapeños"))
        assertEquals(Department.PRODUCE, d("bananas"))
        assertEquals(Department.MEAT_FISH, d("Chicken drumsticks and thighs"))
        assertEquals(Department.DAIRY_EGGS, d("Semi-skimmed milk"))
        assertEquals(Department.BAKERY, d("Wholemeal bread"))
        assertEquals(Department.FROZEN, d("Frozen peas"))
        assertEquals(Department.DRINKS, d("Orange juice"))
        assertEquals(Department.SNACKS, d("Cornitos nacho crisps"))
        assertEquals(Department.HOUSEHOLD, d("Toilet roll"))
        assertEquals(Department.OTHER, d("Zzyzx"))
    }

    @Test fun tinnedAndNutButterGoToTheCupboard() {
        assertEquals(Department.CUPBOARD, d("Tinned tomatoes"))
        assertEquals(Department.CUPBOARD, d("Peanut butter"))
        assertEquals(Department.CUPBOARD, d("Spaghetti pasta"))
    }

    @Test fun aBunchIsNotABun() = assertEquals(Department.PRODUCE, d("bunch of bananas"))
}
