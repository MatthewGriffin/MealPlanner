package com.example.chatgpt_mealplanner.feature.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.database.entity.HouseholdIdentityEntity
import com.example.chatgpt_mealplanner.core.sync.HouseholdIdentityManager
import com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HouseholdUiState(
    val householdIdentity: HouseholdIdentityEntity? = null,
    val isFollowerPaired: Boolean = false,
    val hostIp: String? = null
)

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val identityManager: HouseholdIdentityManager,
    private val followerPairingStore: FollowerPairingStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(HouseholdUiState())
    val uiState: StateFlow<HouseholdUiState> = _uiState.asStateFlow()

    init {
        loadHouseholdInfo()
    }

    fun loadHouseholdInfo() {
        viewModelScope.launch {
            val identity = identityManager.getOrCreateHouseholdIdentity()
            val isPaired = followerPairingStore.isPaired()
            val config = followerPairingStore.getPairingConfig()
            _uiState.update {
                it.copy(
                    householdIdentity = identity,
                    isFollowerPaired = isPaired,
                    hostIp = config?.hostIp
                )
            }
        }
    }
}
