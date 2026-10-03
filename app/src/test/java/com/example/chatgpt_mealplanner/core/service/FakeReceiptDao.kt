package com.example.chatgpt_mealplanner.core.service

import com.example.chatgpt_mealplanner.core.database.dao.ReceiptDao
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptImportLineEntity
import com.example.chatgpt_mealplanner.core.database.entity.ReceiptProductMappingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeReceiptDao : ReceiptDao {

    private val imports = mutableListOf<ReceiptImportEntity>()
    private val importLines = mutableListOf<ReceiptImportLineEntity>()
    private val mappings = mutableListOf<ReceiptProductMappingEntity>()

    private val importsFlow = MutableStateFlow<List<ReceiptImportEntity>>(emptyList())

    override suspend fun insertReceiptImport(importEntity: ReceiptImportEntity) {
        imports.removeAll { it.id == importEntity.id }
        imports.add(importEntity)
        importsFlow.value = imports.toList()
    }

    override suspend fun insertReceiptLines(lines: List<ReceiptImportLineEntity>) {
        importLines.addAll(lines)
    }

    override suspend fun getImportByFingerprint(fingerprint: String): ReceiptImportEntity? {
        return imports.firstOrNull { it.fingerprint == fingerprint }
    }

    override fun observeAllReceiptImports(): Flow<List<ReceiptImportEntity>> {
        return importsFlow
    }

    override suspend fun getLinesForImport(importId: String): List<ReceiptImportLineEntity> {
        return importLines.filter { it.receiptImportId == importId }
    }

    override suspend fun getMapping(
        retailerId: String,
        normalizedDescription: String
    ): ReceiptProductMappingEntity? {
        return mappings.firstOrNull { it.retailerId == retailerId && it.normalizedDescription == normalizedDescription }
    }

    override suspend fun insertOrUpdateMapping(mapping: ReceiptProductMappingEntity) {
        mappings.removeAll { it.retailerId == mapping.retailerId && it.normalizedDescription == mapping.normalizedDescription }
        mappings.add(mapping)
    }
}
