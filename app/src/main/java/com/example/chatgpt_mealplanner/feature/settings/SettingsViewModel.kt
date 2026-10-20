package com.example.chatgpt_mealplanner.feature.settings

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
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.core.service.RetailerPreferenceStore
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreLocation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject

data class SettingsUiState(
    val isApiKeyConfigured: Boolean = false,
    val connectionTestResult: String? = null,
    val minimumSavingsInput: String = "3.00",
    val exportMessage: String? = null,
    val syncStatusMessage: String? = null,
    val isMainRole: Boolean = false,
    val isRoleResolved: Boolean = false,
    val backupStatusMessage: String? = null,
    val isBackupInProgress: Boolean = false,
    val isPassphraseDialogVisible: Boolean = false,
    val pendingBackupUri: android.net.Uri? = null,
    val enabledRetailers: Set<RetailerId> = RetailerPreferenceStore.DEFAULT_ENABLED_RETAILERS,
    val preferredStores: List<RetailStoreLocation> = emptyList()
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
    private val retailerPreferenceStore: RetailerPreferenceStore = RetailerPreferenceStore(null)
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val storesJson = Json { ignoreUnknownKeys = true }

    init {
        _uiState.update { it.copy(enabledRetailers = retailerPreferenceStore.getEnabledRetailers()) }
        checkCredentialStatus()
        resolveAppRole()
        _uiState.update { it.copy(preferredStores = getPreferredStores()) }
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
        val newStore = RetailStoreLocation(
            id = UUID.randomUUID().toString(),
            retailerId = retailerId,
            name = cleanName,
            postcode = cleanPostcode,
            format = format
        )
        val updated = (_uiState.value.preferredStores + newStore)
            .distinctBy { "${it.retailerId}:${it.name.lowercase()}:${it.postcode.replace(" ", "")}" }
        persistPreferredStores(updated)
    }

    fun setPreferredStore(storeId: String, preferred: Boolean) {
        persistPreferredStores(_uiState.value.preferredStores.map {
            if (it.id == storeId) it.copy(isPreferred = preferred) else it
        })
    }

    fun removePreferredStore(storeId: String) {
        persistPreferredStores(_uiState.value.preferredStores.filterNot { it.id == storeId })
    }

    private fun persistPreferredStores(stores: List<RetailStoreLocation>) {
        context?.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(STORE_KEY, storesJson.encodeToString(ListSerializer(RetailStoreLocation.serializer()), stores))
            ?.apply()
        _uiState.update { it.copy(preferredStores = stores) }
    }

    private fun getPreferredStores(): List<RetailStoreLocation> = runCatching {
        val raw = context?.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)?.getString(STORE_KEY, null)
            ?: return emptyList()
        storesJson.decodeFromString(ListSerializer(RetailStoreLocation.serializer()), raw)
    }.getOrDefault(emptyList())

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

    private companion object {
        const val STORE_PREFS = "retail_store_preferences_v1"
        const val STORE_KEY = "preferred_stores"
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

    fun updateMinimumSavingsInput(value: String) {
        _uiState.update { it.copy(minimumSavingsInput = value) }
    }

    fun saveMinimumSavings() {
        // DataStore persistence logic
    }

    fun refreshIcelandPrices() {
        // Manual Iceland refresh
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            chatDao.observeAllConversations().collect { convs ->
                convs.forEach { chatDao.deleteConversation(it.id) }
            }
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
