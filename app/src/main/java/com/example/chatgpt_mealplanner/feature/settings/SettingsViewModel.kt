package com.example.chatgpt_mealplanner.feature.settings

import com.example.chatgpt_mealplanner.core.util.rethrowIfCancelled
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.provider.GEMINI_MODEL_ID
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.backup.BackupManager
import com.example.chatgpt_mealplanner.core.backup.MainEncryptedBackupSaveCoordinator
import com.example.chatgpt_mealplanner.core.backup.SafBackupOutcome
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.database.dao.PurchasePriceDao
import com.example.chatgpt_mealplanner.core.database.entity.UserStoreEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceObservationEntity
import com.example.chatgpt_mealplanner.core.database.entity.PurchasePriceWithStore
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Arrays
import java.util.UUID
import kotlin.math.roundToLong
import javax.inject.Inject

data class SettingsUiState(
    val isApiKeyConfigured: Boolean = false,
    val credentialStorageError: String? = null,
    val connectionTestResult: String? = null,
    val isConnectionTestInProgress: Boolean = false,
    val exportMessage: String? = null,
    val syncStatusMessage: String? = null,
    val isMainRole: Boolean = false,
    val isRoleResolved: Boolean = false,
    val backupStatusMessage: String? = null,
    val isBackupInProgress: Boolean = false,
    val isPassphraseDialogVisible: Boolean = false,
    val pendingRestoreUri: android.net.Uri? = null,
    val isRestoreInProgress: Boolean = false,
    val restartToFinishRestore: Boolean = false,
    val pendingBackupUri: android.net.Uri? = null,
    val userStores: List<UserStoreEntity> = emptyList(),
    val recentPriceHistory: List<PurchasePriceWithStore> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context?,
    private val credentialStore: CredentialStore,
    private val provider: MealPlannerAiProvider,
    private val chatDao: ChatDao,
    private val backupManager: BackupManager,
    private val followerSyncScheduler: FollowerSyncScheduler,
    private val appRoleResolver: AppRoleResolver,
    private val mainBackupSaveCoordinator: MainEncryptedBackupSaveCoordinator,
    private val purchasePriceDao: PurchasePriceDao? = null,
    private val fullBackupManager: com.example.chatgpt_mealplanner.core.backup.FullBackupManager? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(backupStatusMessage = fullBackupManager?.consumeLastRestoreResult())
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        purchasePriceDao?.let { dao ->
            viewModelScope.launch {
                dao.observeActiveStores().collect { stores ->
                    _uiState.update { it.copy(userStores = stores) }
                }
            }
            viewModelScope.launch {
                dao.observeRecentPriceHistory().collect { history ->
                    _uiState.update { it.copy(recentPriceHistory = history) }
                }
            }
        }
        checkCredentialStatus()
        resolveAppRole()
    }

    fun addUserStore(name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _uiState.update { it.copy(syncStatusMessage = "Enter a shop name.") }
            return
        }
        viewModelScope.launch {
            try {
                val dao = purchasePriceDao ?: throw IllegalStateException("Local purchase storage is unavailable.")
                val duplicate = dao.getAllStores().any {
                    !it.isArchived && it.name.equals(cleanName, ignoreCase = true)
                }
                if (duplicate) {
                    _uiState.update { it.copy(syncStatusMessage = "That shop is already in your list.") }
                    return@launch
                }
                val now = System.currentTimeMillis()
                dao.insertStore(
                    UserStoreEntity(
                        id = UUID.randomUUID().toString(),
                        name = cleanName,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                _uiState.update { it.copy(syncStatusMessage = "Added shop: $cleanName") }
            } catch (e: Exception) {
                rethrowIfCancelled()
                _uiState.update { it.copy(syncStatusMessage = "Could not add shop: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun renameUserStore(storeId: String, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            try {
                val dao = purchasePriceDao ?: throw IllegalStateException("Local purchase storage is unavailable.")
                val store = dao.getStore(storeId) ?: return@launch
                val duplicate = dao.getAllStores().any {
                    it.id != storeId && !it.isArchived && it.name.equals(cleanName, ignoreCase = true)
                }
                if (duplicate) {
                    _uiState.update { it.copy(syncStatusMessage = "Another shop already has that name.") }
                    return@launch
                }
                dao.updateStore(store.copy(name = cleanName, updatedAt = System.currentTimeMillis()))
                _uiState.update { it.copy(syncStatusMessage = "Shop renamed to $cleanName") }
            } catch (e: Exception) {
                rethrowIfCancelled()
                _uiState.update { it.copy(syncStatusMessage = "Could not rename shop: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun recordPurchasePrice(
        storeId: String,
        productName: String,
        barcode: String?,
        brand: String?,
        packQuantityText: String?,
        packUnit: String?,
        packCountText: String?,
        priceText: String
    ) {
        val cleanName = productName.trim()
        val pricePounds = priceText.trim().replace(",", ".").toDoubleOrNull()
        val packQuantity = packQuantityText?.trim()?.takeIf { it.isNotEmpty() }?.replace(",", ".")?.toDoubleOrNull()
        val packCount = packCountText?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (storeId.isBlank() || cleanName.isBlank() || pricePounds == null || !pricePounds.isFinite() || pricePounds < 0.0) {
            _uiState.update { it.copy(syncStatusMessage = "Choose a shop, enter a product name and enter a valid price.") }
            return
        }
        if (packQuantityText?.isNotBlank() == true && (packQuantity == null || !packQuantity.isFinite() || packQuantity <= 0.0)) {
            _uiState.update { it.copy(syncStatusMessage = "Pack quantity must be a positive number.") }
            return
        }
        if (packCountText?.isNotBlank() == true && (packCount == null || packCount <= 0)) {
            _uiState.update { it.copy(syncStatusMessage = "Pack count must be a positive whole number.") }
            return
        }
        viewModelScope.launch {
            try {
                val dao = purchasePriceDao ?: throw IllegalStateException("Local purchase storage is unavailable.")
                val store = dao.getStore(storeId)
                if (store == null || store.isArchived) {
                    _uiState.update { it.copy(syncStatusMessage = "Choose an active shop.") }
                    return@launch
                }
                val priceMinorUnits = (pricePounds * 100.0).roundToLong()
                dao.insertPriceObservation(
                    PurchasePriceObservationEntity(
                        id = UUID.randomUUID().toString(),
                        storeId = storeId,
                        barcode = barcode?.trim()?.takeIf { it.isNotEmpty() },
                        productName = cleanName,
                        brand = brand?.trim()?.takeIf { it.isNotEmpty() },
                        packQuantity = packQuantity,
                        packUnit = normalizePurchaseUnit(packUnit),
                        packCount = packCount,
                        priceMinorUnits = priceMinorUnits,
                        purchasedAt = System.currentTimeMillis(),
                        createdAt = System.currentTimeMillis(),
                        source = "MANUAL"
                    )
                )
                _uiState.update { it.copy(syncStatusMessage = "Saved £${"%.2f".format(priceMinorUnits / 100.0)} for $cleanName at ${store.name}.") }
            } catch (e: Exception) {
                rethrowIfCancelled()
                _uiState.update { it.copy(syncStatusMessage = "Could not save price: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun normalizePurchaseUnit(raw: String?): String? {
        val unit = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (unit) {
            "G", "GRAM", "GRAMS" -> "GRAM"
            "KG", "KILOGRAM", "KILOGRAMS" -> "KILOGRAM"
            "ML", "MILLILITRE", "MILLILITRES", "MILLILITER", "MILLILITERS" -> "MILLILITRE"
            "L", "LITRE", "LITRES", "LITER", "LITERS" -> "LITRE"
            "EACH", "UNIT", "UNITS", "PIECE", "PIECES" -> "UNIT"
            else -> unit
        }
    }

    fun archiveUserStore(storeId: String) {
        viewModelScope.launch {
            try {
                val dao = purchasePriceDao ?: throw IllegalStateException("Local purchase storage is unavailable.")
                dao.archiveStore(storeId)
                _uiState.update { it.copy(syncStatusMessage = "Shop archived. Its purchase history is retained.") }
            } catch (e: Exception) {
                rethrowIfCancelled()
                _uiState.update { it.copy(syncStatusMessage = "Could not archive shop: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun checkCredentialStatus() {
        try {
            _uiState.update {
                it.copy(
                    isApiKeyConfigured = credentialStore.hasKey(),
                    credentialStorageError = null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AI_MEALPLANNER", "[SETTINGS_CREDENTIAL_STATUS_ERROR]", e)
            _uiState.update {
                it.copy(
                    isApiKeyConfigured = false,
                    credentialStorageError = "Could not access secure Gemini key storage. Your saved key has not been deleted. Details: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun resolveAppRole() {
        viewModelScope.launch {
            val isMain = appRoleResolver.isMainAsync()
            _uiState.update { it.copy(isMainRole = isMain, isRoleResolved = true) }
        }
    }

    fun triggerManualSync() {
        val scheduler = followerSyncScheduler
        val shouldEnqueue = scheduler.recordManualSyncIntent()
        if (!shouldEnqueue) {
            return
        }
        viewModelScope.launch {
            scheduler.scheduleSyncWorkAsync(context, "MANUAL_SYNC", intentAlreadyRecorded = true)
        }
    }

    fun saveApiKey(key: String) {
        try {
            credentialStore.saveKey(key)
            checkCredentialStatus()
            _uiState.update { it.copy(connectionTestResult = "Key saved. Tap Test Connection to verify.") }
        } catch (e: Exception) {
            android.util.Log.e("AI_MEALPLANNER", "[SETTINGS_SAVE_GEMINI_KEY_ERROR]", e)
            _uiState.update {
                it.copy(connectionTestResult = "Could not save Gemini API key securely: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    fun replaceApiKey(key: String) {
        try {
            credentialStore.replaceKey(key)
            checkCredentialStatus()
            _uiState.update { it.copy(connectionTestResult = "Replacement key saved. Tap Test Connection to verify.") }
        } catch (e: Exception) {
            android.util.Log.e("AI_MEALPLANNER", "[SETTINGS_REPLACE_GEMINI_KEY_ERROR]", e)
            _uiState.update {
                it.copy(connectionTestResult = "Could not replace Gemini API key securely: " + (e.localizedMessage ?: e.javaClass.simpleName))
            }
        }
    }

    fun deleteApiKey() {
        try {
            credentialStore.deleteKey()
            checkCredentialStatus()
            _uiState.update { it.copy(connectionTestResult = "Key deleted.") }
        } catch (e: Exception) {
            android.util.Log.e("AI_MEALPLANNER", "[SETTINGS_DELETE_GEMINI_KEY_ERROR]", e)
            _uiState.update {
                it.copy(connectionTestResult = "Could not delete Gemini API key securely: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    fun testAiConnection() {
        if (_uiState.value.isConnectionTestInProgress) return
        _uiState.update {
            it.copy(
                isConnectionTestInProgress = true,
                connectionTestResult = "Testing connection to Gemini API ($GEMINI_MODEL_ID)..."
            )
        }
        viewModelScope.launch {
            try {
                val result = provider.testConnection()
                val resultText = when (result) {
                    is AiProviderResponse.FinalResponse -> "Connected to Gemini! Model: $GEMINI_MODEL_ID"
                    is AiProviderResponse.Error -> result.message
                    else -> "Response: ${result.javaClass.simpleName}"
                }
                _uiState.update { it.copy(connectionTestResult = resultText) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connectionTestResult = "Connection test failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                }
            } finally {
                _uiState.update { it.copy(isConnectionTestInProgress = false) }
            }
        }
    }


    fun clearChatHistory() {
        viewModelScope.launch {
            val conversations = chatDao.observeAllConversations().first()
            conversations.forEach { chatDao.deleteConversation(it.id) }
        }
    }

    fun exportInventoryToUri(
        appContext: Context,
        uri: android.net.Uri?,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        if (!appRoleResolver.isMain()) {
            _uiState.update {
                it.copy(exportMessage = "Export failed: Inventory export is available on Main device only.")
            }
            return
        }

        if (uri == null) {
            _uiState.update { it.copy(exportMessage = "Export cancelled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(exportMessage = "Saving inventory export...") }
            try {
                withContext(ioDispatcher) {
                    val jsonContent = backupManager.createExportJson(includeChatHistory = false)
                    val outputStream = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open selected document for writing.")

                    outputStream.use { stream ->
                        stream.write(jsonContent.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }

                val displayName = getDocumentDisplayName(appContext, uri) ?: "Selected file"
                _uiState.update {
                    it.copy(exportMessage = "Inventory export saved successfully to $displayName.")
                }
            } catch (e: Exception) {
                rethrowIfCancelled()
                _uiState.update {
                    it.copy(exportMessage = "Export failed: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    fun onBackupDestinationSelected(uri: android.net.Uri?) {
        if (!appRoleResolver.isMain()) {
            _uiState.update {
                it.copy(backupStatusMessage = "No document I/O attempted: Backup creation is available on Main device only.")
            }
            return
        }

        if (uri == null) {
            _uiState.update {
                it.copy(
                    backupStatusMessage = "No document I/O attempted: Backup cancelled (no document destination selected).",
                    isPassphraseDialogVisible = false,
                    pendingBackupUri = null
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                pendingBackupUri = uri,
                isPassphraseDialogVisible = true,
                backupStatusMessage = null
            )
        }
    }

    fun onRestoreFileSelected(uri: android.net.Uri?) {
        if (uri == null) return
        if (!appRoleResolver.isMain()) {
            _uiState.update { it.copy(backupStatusMessage = "This phone is joined to a household. Only the Main phone can restore a backup.") }
            return
        }
        _uiState.update { it.copy(pendingRestoreUri = uri, backupStatusMessage = null) }
    }

    fun dismissRestoreDialog() {
        _uiState.update { it.copy(pendingRestoreUri = null) }
    }

    fun executeRestore(passphrase: CharArray, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
        val uri = _uiState.value.pendingRestoreUri
        val manager = fullBackupManager
        if (uri == null || manager == null || _uiState.value.isRestoreInProgress) {
            Arrays.fill(passphrase, ' ')
            return
        }
        _uiState.update {
            it.copy(pendingRestoreUri = null, isRestoreInProgress = true, backupStatusMessage = "Checking backup...")
        }
        viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { manager.stageRestore(uri, passphrase) }) {
                    is com.example.chatgpt_mealplanner.core.model.DomainResult.Success ->
                        _uiState.update { it.copy(restartToFinishRestore = true) }
                    is com.example.chatgpt_mealplanner.core.model.DomainResult.Failure ->
                        _uiState.update {
                            val cause = (result.error as? com.example.chatgpt_mealplanner.core.model.DomainError.TransactionFailed)?.cause
                            it.copy(isRestoreInProgress = false, backupStatusMessage = cause ?: "Restore failed. Your data hasn't changed.")
                        }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isRestoreInProgress = false, backupStatusMessage = "Restore failed. Your data hasn't changed.") }
            } finally {
                Arrays.fill(passphrase, ' ')
            }
        }
    }

    fun dismissPassphraseDialog() {
        _uiState.update {
            it.copy(
                isPassphraseDialogVisible = false,
                pendingBackupUri = null
            )
        }
    }

    fun executeEncryptedBackup(
        appContext: Context?,
        passphraseCharArray: CharArray,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) {
        val destinationUri = _uiState.value.pendingBackupUri
        if (!appRoleResolver.isMain()) {
            Arrays.fill(passphraseCharArray, ' ')
            _uiState.update {
                it.copy(
                    backupStatusMessage = "No document I/O attempted: Backup creation is available on Main device only.",
                    isPassphraseDialogVisible = false,
                    pendingBackupUri = null
                )
            }
            return
        }

        if (passphraseCharArray.size < 8) {
            Arrays.fill(passphraseCharArray, ' ')
            _uiState.update {
                it.copy(backupStatusMessage = "No document I/O attempted: Backup passphrase MUST be at least 8 characters long.")
            }
            return
        }

        if (destinationUri == null) {
            Arrays.fill(passphraseCharArray, ' ')
            _uiState.update {
                it.copy(
                    backupStatusMessage = "No document I/O attempted: Backup cancelled (no document destination selected).",
                    isPassphraseDialogVisible = false,
                    pendingBackupUri = null
                )
            }
            return
        }

        if (_uiState.value.isBackupInProgress) {
            Arrays.fill(passphraseCharArray, ' ')
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBackupInProgress = true,
                    isPassphraseDialogVisible = false,
                    backupStatusMessage = "Encrypting and saving household backup..."
                )
            }

            try {
                val outcome = withContext(ioDispatcher) {
                    mainBackupSaveCoordinator.executeMainBackupSave(
                        appContext = appContext,
                        destinationUri = destinationUri,
                        passphrase = passphraseCharArray
                    )
                }

                val displayName = getDocumentDisplayName(appContext, destinationUri) ?: "Selected file"
                val resultText = when (outcome) {
                    is SafBackupOutcome.VerifiedFullBackup -> {
                        "Full household backup encrypted, verified and saved to $displayName."
                    }
                    is SafBackupOutcome.VerifiedSuccess -> {
                        "Household backup encrypted and verified successfully saved to $displayName!"
                    }
                    is SafBackupOutcome.NoDocumentIoAttempted -> {
                        outcome.cause.cause
                    }
                    is SafBackupOutcome.DocumentIoAttemptedUnverified -> {
                        val causeText = outcome.cause.cause
                        "$causeText. Warning: An incomplete or unverified backup document may remain at $displayName."
                    }
                }

                _uiState.update {
                    it.copy(
                        backupStatusMessage = resultText,
                        isBackupInProgress = false,
                        pendingBackupUri = null
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        backupStatusMessage = "Backup failed: ${e.message ?: "Unknown error"}",
                        isBackupInProgress = false,
                        pendingBackupUri = null
                    )
                }
            } finally {
                // Immediate zeroization of sensitive passphrase array
                Arrays.fill(passphraseCharArray, ' ')
            }
        }
    }

    private fun getDocumentDisplayName(appContext: Context?, uri: android.net.Uri): String? {
        if (appContext == null) return null
        return try {
            appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
