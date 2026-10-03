package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeReceiptDao
import com.example.chatgpt_mealplanner.core.service.FakeRetailDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ReceiptImportDomainServiceImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ReceiptImportDomainServiceTest {

    private lateinit var receiptDao: FakeReceiptDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var inventoryBarcodeDao: FakeInventoryBarcodeDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var shoppingDao: FakeShoppingDao
    private lateinit var stapleRuleDao: FakeStapleRuleDao
    private lateinit var retailDao: FakeRetailDao
    private lateinit var runner: FakeTransactionRunner

    private lateinit var inventoryDomainService: InventoryDomainServiceImpl
    private lateinit var service: ReceiptImportDomainServiceImpl

    @Before
    fun setup() {
        receiptDao = FakeReceiptDao()
        inventoryDao = FakeInventoryDao()
        inventoryBarcodeDao = FakeInventoryBarcodeDao()
        transactionDao = FakeTransactionDao()
        shoppingDao = FakeShoppingDao()
        stapleRuleDao = FakeStapleRuleDao()
        retailDao = FakeRetailDao()

        runner = FakeTransactionRunner(
            fakeInventoryDao = inventoryDao,
            fakeTransactionDao = transactionDao,
            fakeShoppingDao = shoppingDao,
            fakeReceiptDao = receiptDao,
            fakeRetailDao = retailDao
        )

        inventoryDomainService = InventoryDomainServiceImpl(
            inventoryDao = inventoryDao,
            inventoryBarcodeDao = inventoryBarcodeDao,
            transactionDao = transactionDao,
            stapleRuleDao = stapleRuleDao,
            transactionRunner = runner
        )

        service = ReceiptImportDomainServiceImpl(
            receiptDao = receiptDao,
            inventoryDomainService = inventoryDomainService,
            shoppingDao = shoppingDao,
            receiptRetailBridgeService = FakeReceiptRetailBridgeService(),
            transactionRunner = runner
        )
    }

    @Test
    fun processReceiptOcr_validOcrResult_createsDraft() = runTest {
        val ocrResult = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA SUPERSTORE", 0.9f, 0),
                ReceiptOcrLine("PIZZA £3.74", 0.9f, 1)
            ),
            fullText = "ASDA SUPERSTORE PIZZA £3.74"
        )

        val result = service.processReceiptOcr(
            ocrResult = ocrResult,
            sourceImagePath = "/images/receipt_1.jpg"
        )

        assertTrue(result is DomainResult.Success)
        val draft = (result as DomainResult.Success).value
        assertEquals("ASDA", draft.retailerId)
    }
}
