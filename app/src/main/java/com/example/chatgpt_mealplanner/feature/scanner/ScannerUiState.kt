package com.example.chatgpt_mealplanner.feature.scanner

import com.example.chatgpt_mealplanner.core.barcode.model.ConfirmedProductInput
import com.example.chatgpt_mealplanner.core.barcode.model.ProductMetadata
import com.example.chatgpt_mealplanner.core.model.InventoryItem

sealed interface ScannerState {
    object RequestingPermission : ScannerState
    object PermissionDenied : ScannerState
    object Ready : ScannerState
    object Scanning : ScannerState
    data class BarcodeDetected(val barcode: String) : ScannerState
    data class LookingUp(val barcode: String) : ScannerState
    data class ProductFound(
        val barcode: String,
        val metadata: ProductMetadata,
        val isCached: Boolean
    ) : ScannerState
    data class NotFound(val barcode: String) : ScannerState
    data class Identifying(val barcode: String) : ScannerState
    data class Error(val barcode: String?, val message: String) : ScannerState
    data class Confirming(
        val barcode: String?,
        val editableMetadata: ProductMetadata
    ) : ScannerState
    data class Saving(val itemInput: ConfirmedProductInput) : ScannerState
    data class Saved(val item: InventoryItem) : ScannerState
}

sealed interface ScannerUiEvent {
    object PermissionGranted : ScannerUiEvent
    object PermissionDenied : ScannerUiEvent
    data class BarcodeScanned(val barcode: String) : ScannerUiEvent
    data class ConfirmProduct(val input: ConfirmedProductInput) : ScannerUiEvent
    object ScanAnother : ScannerUiEvent
    object ManualAddRequested : ScannerUiEvent
    data class IdentifyFromPhoto(val photo: android.net.Uri) : ScannerUiEvent
    data class PhotoProblem(val message: String) : ScannerUiEvent
    object ClearError : ScannerUiEvent
}
