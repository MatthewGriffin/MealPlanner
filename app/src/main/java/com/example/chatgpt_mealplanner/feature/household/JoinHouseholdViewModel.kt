package com.example.chatgpt_mealplanner.feature.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.sync.enrollment.FollowerEnrollmentOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface JoinHouseholdState {
    object Idle : JoinHouseholdState
    object Enrolling : JoinHouseholdState
    data class Success(val householdId: String) : JoinHouseholdState
    data class Error(val errorCode: String, val errorMessage: String) : JoinHouseholdState
}

data class JoinHouseholdUiState(
    val state: JoinHouseholdState = JoinHouseholdState.Idle
)

@HiltViewModel
class JoinHouseholdViewModel @Inject constructor(
    private val orchestrator: FollowerEnrollmentOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinHouseholdUiState())
    val uiState: StateFlow<JoinHouseholdUiState> = _uiState.asStateFlow()

    private var isProcessingScan = false

    fun onQrCodeScanned(qrJsonContent: String) {
        if (isProcessingScan) return
        isProcessingScan = true

        _uiState.update { it.copy(state = JoinHouseholdState.Enrolling) }

        viewModelScope.launch {
            val response = orchestrator.processQrEnrollment(qrJsonContent)
            if (response.success) {
                _uiState.update {
                    it.copy(state = JoinHouseholdState.Success(response.householdId))
                }
            } else {
                isProcessingScan = false
                _uiState.update {
                    it.copy(
                        state = JoinHouseholdState.Error(
                            errorCode = response.errorCode ?: "ENROLLMENT_FAILED",
                            errorMessage = response.errorMessage ?: "Enrollment request failed"
                        )
                    )
                }
            }
        }
    }

    fun retryScan() {
        isProcessingScan = false
        _uiState.update { it.copy(state = JoinHouseholdState.Idle) }
    }
}
