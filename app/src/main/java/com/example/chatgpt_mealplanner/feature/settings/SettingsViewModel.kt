package com.example.chatgpt_mealplanner.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.provider.GEMINI_MODEL_ID
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.backup.BackupManager
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isApiKeyConfigured: Boolean = false,
    val connectionTestResult: String? = null,
    val minimumSavingsInput: String = "3.00",
    val exportMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val provider: MealPlannerAiProvider,
    private val chatDao: ChatDao,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        checkCredentialStatus()
    }

    private fun checkCredentialStatus() {
        _uiState.update { it.copy(isApiKeyConfigured = credentialStore.hasKey()) }
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

    fun exportData() {
        viewModelScope.launch {
            _uiState.update { it.copy(exportMessage = "Exporting...") }
            try {
                val json = backupManager.createExportJson(includeChatHistory = true)
                _uiState.update { it.copy(exportMessage = "Export successful. Size: ${json.length} bytes.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportMessage = "Export failed: ${e.message}") }
            }
        }
    }
}
