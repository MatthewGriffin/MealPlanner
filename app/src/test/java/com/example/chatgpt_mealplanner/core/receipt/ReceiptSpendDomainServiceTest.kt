package com.example.chatgpt_mealplanner.core.receipt

import com.example.chatgpt_mealplanner.core.database.entity.ReceiptCategoryMappingEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptSpendItemEntity
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.ReceiptSpendDomainServiceImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

class FakeReceiptSpendDao : com.example.chatgpt_mealplanner.core.database.dao.ReceiptSpendDao {
    private val spends = mutableListOf<ReceiptSpendEntity>()
    private val spendItems = mutableListOf<ReceiptSpendItemEntity>()
    private val mappings = mutableListOf<ReceiptCategoryMappingEntity>()

    override suspend fun insertReceiptSpend(spend: ReceiptSpendEntity) {
        spends.removeAll { it.id == spend.id }
        spends.add(spend)
    }

    override suspend fun insertReceiptSpendItems(items: List<ReceiptSpendItemEntity>) {
        spendItems.addAll(items)
    }

    override suspend fun getSpendByFingerprint(fingerprint: String): ReceiptSpendEntity? {
        return spends.firstOrNull { it.fingerprint == fingerprint }
    }

    override fun observeAllReceiptSpends(): Flow<List<ReceiptSpendEntity>> {
        return MutableStateFlow(spends)
    }

    override suspend fun getItemsForSpend(spendId: String): List<ReceiptSpendItemEntity> {
        return spendItems.filter { it.receiptSpendId == spendId }
    }

    override suspend fun getCategoryMapping(retailerId: String, normalizedDescription: String): ReceiptCategoryMappingEntity? {
        return mappings.firstOrNull { it.retailerId == retailerId && it.normalizedDescription == normalizedDescription }
    }

    override suspend fun insertOrUpdateCategoryMapping(mapping: ReceiptCategoryMappingEntity) {
        mappings.removeAll { it.retailerId == mapping.retailerId && it.normalizedDescription == mapping.normalizedDescription }
        mappings.add(mapping)
    }
}

class FakeReceiptRetailBridgeService : com.example.chatgpt_mealplanner.core.service.ReceiptRetailBridgeService {
    val recordedItems = mutableListOf<com.example.chatgpt_mealplanner.core.service.ReceiptObservationItem>()
    override suspend fun recordReceiptObservations(
        receiptId: String,
        retailerIdStr: String,
        observedAtMs: Long,
        items: List<com.example.chatgpt_mealplanner.core.service.ReceiptObservationItem>
    ) {
        recordedItems.addAll(items)
    }
}

@RunWith(JUnit4::class)
class ReceiptSpendDomainServiceTest {

    private lateinit var dao: FakeReceiptSpendDao
    private lateinit var bridge: FakeReceiptRetailBridgeService
    private lateinit var service: ReceiptSpendDomainServiceImpl

    @Before
    fun setup() {
        dao = FakeReceiptSpendDao()
        bridge = FakeReceiptRetailBridgeService()
        val runner = FakeTransactionRunner()
        service = ReceiptSpendDomainServiceImpl(dao, bridge, runner)
    }

    @Test
    fun summaryArithmeticMismatch_showsWarning_butAllowsSavingSpend() = runTest {
        val ocr = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026", 0,0,0,0,0))),
                ReceiptOcrLine("TOTAL £42.48", 0.9f, 1, elements = listOf(ReceiptOcrElement("TOTAL", 0,0,0,0,0), ReceiptOcrElement("£42.48", 0,0,0,0,0))),
                ReceiptOcrLine("TOTAL SAVINGS -£3.60", 0.9f, 2, elements = listOf(ReceiptOcrElement("TOTAL SAVINGS", 0,0,0,0,0), ReceiptOcrElement("-£3.60", 0,0,0,0,0))),
                ReceiptOcrLine("TOTAL £50.00", 0.9f, 3, elements = listOf(ReceiptOcrElement("TOTAL", 0,0,0,0,0), ReceiptOcrElement("£50.00", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 TOTAL £42.48 TOTAL SAVINGS -£3.60 TOTAL £50.00"
        )

        val draft = (service.processReceiptOcr(ocr, null) as DomainResult.Success).value
        assertTrue(draft.needsReview)
        assertTrue(draft.canSave)
    }

    @Test
    fun twoSameDayShops_withDifferentTransactionRefs_produceDistinctFingerprints() = runTest {
        val ocr1 = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026 TRX 1111", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026 TRX 1111", 0,0,0,0,0))),
                ReceiptOcrLine("TOTAL £20.00", 0.9f, 1, elements = listOf(ReceiptOcrElement("TOTAL", 0,0,0,0,0), ReceiptOcrElement("£20.00", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 TRX 1111 TOTAL £20.00"
        )

        val ocr2 = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026 TRX 2222", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026 TRX 2222", 0,0,0,0,0))),
                ReceiptOcrLine("TOTAL £20.00", 0.9f, 1, elements = listOf(ReceiptOcrElement("TOTAL", 0,0,0,0,0), ReceiptOcrElement("£20.00", 0,0,0,0,0)))
            ),
            fullText = "ASDA 27/09/2026 TRX 2222 TOTAL £20.00"
        )

        val draft1 = (service.processReceiptOcr(ocr1, null) as DomainResult.Success).value
        val draft2 = (service.processReceiptOcr(ocr2, null) as DomainResult.Success).value

        assertNotEquals(draft1.fingerprint, draft2.fingerprint)
    }

    @Test
    fun probableDuplicate_showsWarning_butDoesNotHardBlock() = runTest {
        val ocr = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026 BREAD £1.20", 0.9f, 0, elements = listOf(ReceiptOcrElement("BREAD", 10,10,100,20,15), ReceiptOcrElement("£1.20", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £1.20", 0.9f, 1, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£1.20", 250,10,300,20,15)))
            ),
            fullText = "ASDA 27/09/2026 BREAD £1.20 TOTAL £1.20"
        )

        val draft = (service.processReceiptOcr(ocr, null) as DomainResult.Success).value
        service.saveReceiptSpend(draft)

        // Rescan same receipt -> probable duplicate warning
        val secondDraft = (service.processReceiptOcr(ocr, null) as DomainResult.Success).value
        assertTrue(secondDraft.isProbableDuplicate)
        assertTrue(secondDraft.canSave)
    }

    @Test
    fun recognizedOverage_flagsNeedsReviewAndCalculatesOverage() = runTest {
        val ocr = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026", 0,0,0,0,0))),
                ReceiptOcrLine("CLEANING £50.00", 0.9f, 1, elements = listOf(ReceiptOcrElement("CLEANING", 10,10,100,20,15), ReceiptOcrElement("£50.00", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £42.48", 0.9f, 2, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£42.48", 250,10,300,20,15)))
            ),
            fullText = "ASDA 27/09/2026 CLEANING £50.00 TOTAL £42.48"
        )

        val draft = (service.processReceiptOcr(ocr, null) as DomainResult.Success).value
        assertEquals(752L, draft.recognisedGrossOverageMinorPence) // 5000p - 4248p = 752p overage!
        assertTrue(draft.needsReview)
    }

    @Test
    fun validAsdaReceipt_reconcilesWithZeroOverage() = runTest {
        val ocr = ReceiptOcrResult(
            lines = listOf(
                ReceiptOcrLine("ASDA 27/09/2026", 0.9f, 0, elements = listOf(ReceiptOcrElement("ASDA", 0,0,0,0,0), ReceiptOcrElement("27/09/2026", 0,0,0,0,0))),
                ReceiptOcrLine("CLEANING £12.00", 0.9f, 1, elements = listOf(ReceiptOcrElement("CLEANING", 10,10,100,20,15), ReceiptOcrElement("£12.00", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £42.48", 0.9f, 2, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£42.48", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL SAVINGS -£3.60", 0.9f, 3, elements = listOf(ReceiptOcrElement("TOTAL SAVINGS", 10,10,100,20,15), ReceiptOcrElement("-£3.60", 250,10,300,20,15))),
                ReceiptOcrLine("TOTAL £38.88", 0.9f, 4, elements = listOf(ReceiptOcrElement("TOTAL", 10,10,100,20,15), ReceiptOcrElement("£38.88", 250,10,300,20,15)))
            ),
            fullText = "ASDA 27/09/2026 CLEANING £12.00 TOTAL £42.48 TOTAL SAVINGS -£3.60 TOTAL £38.88"
        )

        val draft = (service.processReceiptOcr(ocr, null) as DomainResult.Success).value
        assertEquals(0L, draft.recognisedGrossOverageMinorPence)
        assertTrue(draft.summaryArithmeticValid)
        assertTrue(draft.canSave)
    }
}
