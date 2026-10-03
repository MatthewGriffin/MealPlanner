package com.example.chatgpt_mealplanner.feature.receipt

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.receipt.ReceiptImportDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptLineDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptOcrService
import com.example.chatgpt_mealplanner.core.service.ReceiptImportDomainService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiptScannerUiState(
    val isScanning: Boolean = false,
    val draft: ReceiptImportDraft? = null,
    val errorMessage: String? = null,
    val isImportSuccess: Boolean = false,
    val importedItemCount: Int = 0
)

@HiltViewModel
class ReceiptScannerViewModel @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptImportDomainService: ReceiptImportDomainService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptScannerUiState())
    val uiState: StateFlow<ReceiptScannerUiState> = _uiState.asStateFlow()

    fun processReceiptImage(uri: Uri) {
        _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        viewModelScope.launch {
            val ocrResult = ocrService.recognizeTextFromUri(uri)
            when (val result = receiptImportDomainService.processReceiptOcr(ocrResult, uri.toString())) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(isScanning = false, draft = result.value) }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(isScanning = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun updateLineDraft(updatedLine: ReceiptLineDraft) {
        _uiState.update { state ->
            val currentDraft = state.draft ?: return@update state
            val updatedLines = currentDraft.lines.map { if (it.id == updatedLine.id) updatedLine else it }
            state.copy(draft = currentDraft.copy(lines = updatedLines))
        }
    }

    fun importReceiptPurchases() {
        val currentDraft = _uiState.value.draft ?: return
        viewModelScope.launch {
            when (val result = receiptImportDomainService.confirmAndImportReceipt(currentDraft)) {
                is DomainResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isImportSuccess = true,
                            importedItemCount = result.value.size,
                            draft = null
                        )
                    }
                }
                is DomainResult.Failure -> {
                    _uiState.update { it.copy(errorMessage = result.error.toString()) }
                }
            }
        }
    }
}
