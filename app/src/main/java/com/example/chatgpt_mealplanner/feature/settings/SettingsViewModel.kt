package com.example.chatgpt_mealplanner.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.backend.retail.ApiKeyConfigurableStoreDirectoryProvider
import com.example.chatgpt_mealplanner.backend.retail.OpenStreetMapStoreDirectory
import com.example.chatgpt_mealplanner.backend.retail.StoreDirectoryProvider
import com.example.chatgpt_mealplanner.backend.retail.StoreDirectoryProgressProvider
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
import com.example.chatgpt_mealplanner.core.security.StoreDirectoryCredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.service.RetailerPreferenceStore
import com.example.chatgpt_mealplanner.core.service.RetailStorePreferenceStore
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectoryEntry
import com.example.chatgpt_mealplanner.retail.contract.StoreDirectorySearchRequest
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
    val connectionTestResult: String? = null,
    val exportMessage: String? = null,
    val syncStatusMessage: String? = null,
    val isMainRole: Boolean = false,
    val isRoleResolved: Boolean = false,
    val backupStatusMessage: String? = null,
    val isBackupInProgress: Boolean = false,
    val isPassphraseDialogVisible: Boolean = false,
    val pendingBackupUri: android.net.Uri? = null,
    val enabledRetailers: Set<RetailerId> = RetailerPreferenceStore.DEFAULT_ENABLED_RETAILERS,
    val userStores: List<UserStoreEntity> = emptyList(),
    val recentPriceHistory: List<PurchasePriceWithStore> = emptyList(),
    val preferredStores: List<RetailStoreLocation> = emptyList(),
    val isStoreSearchInProgress: Boolean = false,
    val storeSearchMessage: String? = null,
    val discoveredStores: List<StoreDirectoryEntry> = emptyList(),
    val branchSearchRadiusMeters: Int = RetailStorePreferenceStore.DEFAULT_BRANCH_SEARCH_RADIUS_METERS,
    val isStoreDirectoryApiKeyConfigured: Boolean = false,
    val storeDirectoryApiKeyMessage: String? = null
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
    private val retailerPreferenceStore: RetailerPreferenceStore = RetailerPreferenceStore(null),
    private val retailStorePreferenceStore: RetailStorePreferenceStore = RetailStorePreferenceStore(null),
    private val storeDirectoryProvider: StoreDirectoryProvider = OpenStreetMapStoreDirectory(),
    private val purchasePriceDao: PurchasePriceDao? = null
) : ViewModel() {

    private val storeDirectoryCredentialStore = context?.let { StoreDirectoryCredentialStore(it) }
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val savedDirectoryKey = storeDirectoryCredentialStore?.loadKey()
        (storeDirectoryProvider as? ApiKeyConfigurableStoreDirectoryProvider)?.configureApiKey(savedDirectoryKey)
        _uiState.update {
            it.copy(
                enabledRetailers = retailerPreferenceStore.getEnabledRetailers(),
                branchSearchRadiusMeters = retailStorePreferenceStore.getBranchSearchRadiusMeters(),
                isStoreDirectoryApiKeyConfigured = !savedDirectoryKey.isNullOrBlank()
            )
        }
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
        _uiState.update { it.copy(preferredStores = retailStorePreferenceStore.getPreferredStores()) }
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
                        packUnit = packUnit?.trim()?.takeIf { it.isNotEmpty() },
                        packCount = packCount,
                        priceMinorUnits = priceMinorUnits,
                        purchasedAt = System.currentTimeMillis(),
                        createdAt = System.currentTimeMillis(),
                        source = "MANUAL"
                    )
                )
                _uiState.update { it.copy(syncStatusMessage = "Saved £${"%.2f".format(priceMinorUnits / 100.0)} for $cleanName at ${store.name}.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(syncStatusMessage = "Could not save price: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun archiveUserStore(storeId: String) {
        viewModelScope.launch {
            try {
                val dao = purchasePriceDao ?: throw IllegalStateException("Local purchase storage is unavailable.")
                dao.archiveStore(storeId)
                _uiState.update { it.copy(syncStatusMessage = "Shop archived. Its purchase history is retained.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(syncStatusMessage = "Could not archive shop: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun setRetailerEnabled(retailer: RetailerId, enabled: Boolean) {
        val updated = _uiState.value.enabledRetailers.toMutableSet().apply {
            if (enabled) add(retailer) else remove(retailer)
        }
        if (updated.isEmpty()) {
            _uiState.update { it.copy(syncStatusMessage = "Keep at least one store selected for price comparisons.") }
            return
        }
        if (retailerPreferenceStore.setEnabledRetailers(updated)) {
            _uiState.update { it.copy(enabledRetailers = updated, syncStatusMessage = null) }
        } else {
            _uiState.update { it.copy(syncStatusMessage = "Could not save store preferences.") }
        }
    }

    fun addPreferredStore(retailerId: RetailerId, name: String, postcode: String, format: RetailStoreFormat) {
        val cleanName = name.trim()
        val cleanPostcode = postcode.trim().uppercase()
        if (cleanName.isBlank() || cleanPostcode.isBlank()) return
        val updated = retailStorePreferenceStore.addPreferredStore(
            _uiState.value.preferredStores, retailerId, cleanName, cleanPostcode, format
        )
        persistPreferredStores(updated)
    }

    fun saveStoreDirectoryApiKey(key: String) {
        try {
            val store = storeDirectoryCredentialStore
                ?: throw IllegalStateException("Secure key storage is unavailable on this device.")
            store.saveKey(key)
            (storeDirectoryProvider as? ApiKeyConfigurableStoreDirectoryProvider)?.configureApiKey(store.loadKey())
            _uiState.update {
                it.copy(
                    isStoreDirectoryApiKeyConfigured = true,
                    storeDirectoryApiKeyMessage = "Overpass.ch API key saved securely on this device."
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(storeDirectoryApiKeyMessage = "Could not save API key: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun deleteStoreDirectoryApiKey() {
        try {
            val store = storeDirectoryCredentialStore
                ?: throw IllegalStateException("Secure key storage is unavailable on this device.")
            store.deleteKey()
            (storeDirectoryProvider as? ApiKeyConfigurableStoreDirectoryProvider)?.configureApiKey(null)
            _uiState.update {
                it.copy(
                    isStoreDirectoryApiKeyConfigured = false,
                    storeDirectoryApiKeyMessage = "Overpass.ch API key removed. Public directory services may still be tried."
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(storeDirectoryApiKeyMessage = "Could not remove API key: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun setBranchSearchRadiusMeters(radiusMeters: Int) {
        if (retailStorePreferenceStore.setBranchSearchRadiusMeters(radiusMeters)) {
            _uiState.update { it.copy(branchSearchRadiusMeters = radiusMeters, discoveredStores = emptyList()) }
        } else {
            _uiState.update { it.copy(storeSearchMessage = "Could not save branch search distance.") }
        }
    }

    fun searchStoreDirectory(postcode: String) {
        val cleanPostcode = postcode.trim().uppercase()
        if (cleanPostcode.isBlank()) {
            _uiState.update { it.copy(storeSearchMessage = "Enter a UK postcode to find nearby branches.") }
            return
        }
        (storeDirectoryProvider as? ApiKeyConfigurableStoreDirectoryProvider)
            ?.configureApiKey(storeDirectoryCredentialStore?.loadKey())
        _uiState.update {
            it.copy(
                isStoreSearchInProgress = true,
                storeSearchMessage = "Step 1 of 2: Preparing postcode lookup…",
                discoveredStores = emptyList(),
            )
        }
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val request = StoreDirectorySearchRequest(
                        retailerId = RetailerId.ASDA,
                        postcode = cleanPostcode,
                        searchAllRetailers = true,
                        radiusMeters = _uiState.value.branchSearchRadiusMeters
                    )
                    val progressProvider = storeDirectoryProvider as? StoreDirectoryProgressProvider
                    if (progressProvider != null) {
                        progressProvider.search(request) { status ->
                            _uiState.update { it.copy(storeSearchMessage = status) }
                        }
                    } else {
                        storeDirectoryProvider.search(request)
                    }
                }
                _uiState.update {
                    it.copy(
                        isStoreSearchInProgress = false,
                        discoveredStores = response.stores,
                        storeSearchMessage = when {
                            response.stores.isEmpty() -> response.warnings.firstOrNull() ?: "No matching branches found."
                            response.warnings.isNotEmpty() -> "${response.warnings.first()} Found ${response.stores.size} possible branches; check the details before adding."
                            else -> "Found ${response.stores.size} possible branches. Check the details before adding."
                        }
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isStoreSearchInProgress = false,
                        discoveredStores = emptyList(),
                        storeSearchMessage = buildString {
                            val currentStage = _uiState.value.storeSearchMessage.orEmpty()
                            val errorMessage = e.message.orEmpty()
                            // Prefer the actual failing operation over potentially stale progress text.
                            // A directory timeout can occur after postcode lookup has already succeeded.
                            val isDirectoryFailure =
                                errorMessage.contains("All branch directory services failed", ignoreCase = true) ||
                                errorMessage.contains("branch directory", ignoreCase = true) ||
                                errorMessage.contains("Overpass.ch", ignoreCase = true)
                            append(
                                if (!isDirectoryFailure && currentStage.contains("postcode", ignoreCase = true)) {
                                    "Postcode lookup failed"
                                } else {
                                    "Branch directory search failed"
                                }
                            )
                            append(": ${e.message ?: "service unavailable"}. You can still add a branch manually.")
                        }
                    )
                }
            }
        }
    }

    fun clearStoreSearchResults() {
        _uiState.update { it.copy(discoveredStores = emptyList(), storeSearchMessage = null) }
    }

    fun addDiscoveredStore(store: StoreDirectoryEntry) {
        val current = _uiState.value.preferredStores
        val normalizedPostcode = store.postcode.orEmpty().replace(" ", "").uppercase()
        val existingIndex = current.indexOfFirst {
            it.retailerId == store.retailerId && (
                (it.directorySource == store.directorySource && it.directorySourceId == store.directorySourceId) ||
                (it.name.trim().equals(store.name.trim(), ignoreCase = true) &&
                    it.postcode.replace(" ", "").uppercase() == normalizedPostcode)
            )
        }
        val updated = current.toMutableList()
        if (existingIndex >= 0) {
            val existing = updated[existingIndex]
            updated[existingIndex] = existing.copy(
                directorySource = store.directorySource,
                directorySourceId = store.directorySourceId,
                address = store.address.ifBlank { existing.address }
            )
        } else {
            updated += RetailStoreLocation(
                id = UUID.randomUUID().toString(),
                retailerId = store.retailerId,
                name = store.name,
                postcode = store.postcode.orEmpty().uppercase(),
                format = store.format,
                isPreferred = true,
                retailerStoreId = null,
                directorySource = store.directorySource,
                directorySourceId = store.directorySourceId,
                address = store.address
            )
        }
        if (retailStorePreferenceStore.savePreferredStores(updated)) {
            _uiState.update {
                it.copy(
                    preferredStores = updated,
                    syncStatusMessage = null,
                    storeSearchMessage = "Branch saved to preferred locations. Branch-specific prices and stock are not confirmed."
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    syncStatusMessage = "Could not save preferred branches.",
                    storeSearchMessage = "Could not save this branch. Your existing preferences have been left unchanged."
                )
            }
        }
    }

    fun setPreferredStore(storeId: String, preferred: Boolean) {
        persistPreferredStores(retailStorePreferenceStore.setPreferred(
            _uiState.value.preferredStores, storeId, preferred
        ))
    }

    fun removePreferredStore(storeId: String) {
        persistPreferredStores(retailStorePreferenceStore.removePreferred(_uiState.value.preferredStores, storeId))
    }

    private fun persistPreferredStores(stores: List<RetailStoreLocation>) {
        if (retailStorePreferenceStore.savePreferredStores(stores)) {
            _uiState.update { it.copy(preferredStores = stores, syncStatusMessage = null) }
        } else {
            _uiState.update { it.copy(syncStatusMessage = "Could not save preferred branches.") }
        }
    }

    private fun checkCredentialStatus() {
        _uiState.update { it.copy(isApiKeyConfigured = credentialStore.hasKey()) }
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
        credentialStore.saveKey(key)
        checkCredentialStatus()
        _uiState.update { it.copy(connectionTestResult = "Key saved. Tap Test Connection to verify.") }
    }

    fun deleteApiKey() {
        credentialStore.deleteKey()
        checkCredentialStatus()
        _uiState.update { it.copy(connectionTestResult = "Key deleted.") }
    }

    fun testAiConnection() {
        _uiState.update { it.copy(connectionTestResult = "Testing connection to Gemini API ($GEMINI_MODEL_ID)...") }
        viewModelScope.launch {
            val result = provider.testConnection()
            val resultText = when (result) {
                is AiProviderResponse.FinalResponse -> "Connected to Gemini! Model: $GEMINI_MODEL_ID"
                is AiProviderResponse.Error -> result.message
                else -> "Response: ${result.javaClass.simpleName}"
            }
            _uiState.update { it.copy(connectionTestResult = resultText) }
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
