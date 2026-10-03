package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner

class FakeTransactionRunner(
    private val fakeMealDao: FakeMealDao? = null,
    private val fakeInventoryDao: FakeInventoryDao? = null,
    private val fakeTransactionDao: FakeTransactionDao? = null,
    private val fakeLeftoverDao: FakeLeftoverDao? = null,
    private val fakeShoppingDao: FakeShoppingDao? = null,
    private val fakeStapleRuleDao: FakeStapleRuleDao? = null,
    private val fakeReceiptDao: FakeReceiptDao? = null,
    private val fakeRetailDao: FakeRetailDao? = null
) : DatabaseTransactionRunner {

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val mealBackup = fakeMealDao?.mealsMap?.toMutableMap()
        val ingBackup = fakeMealDao?.ingredientsMap?.mapValues { it.value.toMutableList() }?.toMutableMap()
        val invBackup = fakeInventoryDao?.itemsMap?.toMutableMap()
        val txBackup = fakeTransactionDao?.transactions?.toMutableList()
        val leftoverBackup = fakeLeftoverDao?.leftoversMap?.toMutableMap()
        val listBackup = fakeShoppingDao?.listsMap?.toMutableMap()
        val itemBackup = fakeShoppingDao?.itemsMap?.toMutableMap()
        val sourceBackup = fakeShoppingDao?.sourcesMap?.mapValues { it.value.toMutableList() }?.toMutableMap()
        val stapleBackup = fakeStapleRuleDao?.rulesMap?.toMutableMap()

        try {
            return block()
        } catch (e: Exception) {
            fakeMealDao?.let {
                it.mealsMap.clear()
                if (mealBackup != null) it.mealsMap.putAll(mealBackup)
                it.ingredientsMap.clear()
                if (ingBackup != null) it.ingredientsMap.putAll(ingBackup)
            }
            fakeInventoryDao?.let {
                it.itemsMap.clear()
                if (invBackup != null) it.itemsMap.putAll(invBackup)
            }
            fakeTransactionDao?.let {
                it.transactions.clear()
                if (txBackup != null) it.transactions.addAll(txBackup)
            }
            fakeLeftoverDao?.let {
                it.leftoversMap.clear()
                if (leftoverBackup != null) it.leftoversMap.putAll(leftoverBackup)
            }
            fakeShoppingDao?.let {
                it.listsMap.clear()
                if (listBackup != null) it.listsMap.putAll(listBackup)
                it.itemsMap.clear()
                if (itemBackup != null) it.itemsMap.putAll(itemBackup)
                it.sourcesMap.clear()
                if (sourceBackup != null) it.sourcesMap.putAll(sourceBackup)
            }
            fakeStapleRuleDao?.let {
                it.rulesMap.clear()
                if (stapleBackup != null) it.rulesMap.putAll(stapleBackup)
            }
            throw e
        }
    }
}
