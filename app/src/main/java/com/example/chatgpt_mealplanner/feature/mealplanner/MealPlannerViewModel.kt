package com.example.chatgpt_mealplanner.feature.mealplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.model.ActualIngredientOverride
import com.example.chatgpt_mealplanner.core.model.DomainError
import com.example.chatgpt_mealplanner.core.model.DomainResult
import com.example.chatgpt_mealplanner.core.model.IngredientSubstitution
import com.example.chatgpt_mealplanner.core.model.Leftover
import com.example.chatgpt_mealplanner.core.model.Meal
import com.example.chatgpt_mealplanner.core.model.MealIngredient
import com.example.chatgpt_mealplanner.core.model.MealWithIngredients
import com.example.chatgpt_mealplanner.core.model.StorageLocation
import com.example.chatgpt_mealplanner.core.repository.LeftoverRepository
import com.example.chatgpt_mealplanner.core.repository.MealChangeRequestRepository
import com.example.chatgpt_mealplanner.core.repository.MealRepository
import com.example.chatgpt_mealplanner.core.service.MealDomainService
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MealPlannerViewModel @Inject constructor(
    private val mealDomainService: MealDomainService,
    private val mealRepository: MealRepository,
    leftoverRepository: LeftoverRepository,
    private val mealChangeRequestRepository: MealChangeRequestRepository,
    private val appRoleResolver: AppRoleResolver
) : ViewModel() {

    private val todayIsoDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private val selectedDateFlow = MutableStateFlow(todayIsoDate)
    private val activeTabFlow = MutableStateFlow(MealPlannerTab.PLANNER)
    private val userMessageFlow = MutableStateFlow<String?>(null)
    private val isLoadingFlow = MutableStateFlow(false)
    private val mealToCookFlow = MutableStateFlow<MealWithIngredients?>(null)
    private val mealToAddOrEditFlow = MutableStateFlow<MealWithIngredients?>(null)
    private val showAddDialogFlow = MutableStateFlow(false)

    private val mealsForSelectedDateFlow = selectedDateFlow.flatMapLatest { date ->
        mealRepository.observeMealsForDate(date).onEach { meals ->
            val ids = meals.map { it.meal.id }
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            logDiagnostic("PLANNER_QUERY", "selectedDate=\"$date\" returnedMealIds=$ids")
            logDiagnostic("PLANNER_UI_STATE", "selectedDate=\"$date\" today=\"$today\" visibleMealIds=$ids")
        }
    }

    private val availableLeftoversFlow = leftoverRepository.observeAvailableLeftovers()

    private val recentCookedMealsFlow = flow {
        emit(mealRepository.getRecentCookedMeals(14))
    }

    private val allChangeRequestsFlow = mealChangeRequestRepository.observeAllRequests()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MealPlannerUiState> = combine(
        selectedDateFlow,
        activeTabFlow,
        mealsForSelectedDateFlow,
        availableLeftoversFlow,
        recentCookedMealsFlow,
        userMessageFlow,
        isLoadingFlow,
        mealToCookFlow,
        mealToAddOrEditFlow,
        showAddDialogFlow,
        allChangeRequestsFlow
    ) { flows ->
        MealPlannerUiState(
            selectedDate = flows[0] as String,
            activeTab = flows[1] as MealPlannerTab,
            mealsForSelectedDate = flows[2] as List<MealWithIngredients>,
            leftovers = flows[3] as List<Leftover>,
            recentCookedMeals = flows[4] as List<MealWithIngredients>,
            userMessage = flows[5] as String?,
            isLoading = flows[6] as Boolean,
            mealToCook = flows[7] as MealWithIngredients?,
            mealToAddOrEdit = flows[8] as MealWithIngredients?,
            showAddDialog = flows[9] as Boolean,
            allChangeRequests = flows[10] as List<com.example.chatgpt_mealplanner.core.database.entity.MealChangeRequestEntity>,
            isFollower = appRoleResolver.isFollower()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MealPlannerUiState(selectedDate = todayIsoDate, isFollower = appRoleResolver.isFollower())
    )

    fun submitMealChangeRequest(mealId: String, requestText: String) {
        viewModelScope.launch {
            try {
                mealChangeRequestRepository.submitRequest(mealId, requestText)
                userMessageFlow.value = "Change request submitted successfully"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                userMessageFlow.value = e.message ?: "Invalid meal change request"
            } catch (e: IllegalStateException) {
                userMessageFlow.value = e.message ?: "Meal change requests are not available"
            } catch (e: Exception) {
                android.util.Log.e("MealPlannerViewModel", "Failed to submit meal change request", e)
                userMessageFlow.value = "Could not save the meal change request. Please try again."
            }
        }
    }

    fun updateRequestStatus(requestId: String, status: String, mainResponse: String?) {
        viewModelScope.launch {
            try {
                mealChangeRequestRepository.updateRequestStatus(requestId, status, mainResponse)
                userMessageFlow.value = "Request status updated: $status"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                userMessageFlow.value = e.message ?: "Invalid request status"
            } catch (e: IllegalStateException) {
                userMessageFlow.value = e.message ?: "Cannot update request status"
            } catch (e: Exception) {
                android.util.Log.e("MealPlannerViewModel", "Failed to update meal request status", e)
                userMessageFlow.value = "Could not update the request. Please try again."
            }
        }
    }

    fun onEvent(event: MealPlannerUiEvent) {
        when (event) {
            is MealPlannerUiEvent.SelectDate -> selectedDateFlow.value = event.date
            is MealPlannerUiEvent.SelectTab -> activeTabFlow.value = event.tab
            is MealPlannerUiEvent.RequestAddMeal -> {
                mealToAddOrEditFlow.value = null
                showAddDialogFlow.value = true
            }
            is MealPlannerUiEvent.RequestEditMeal -> {
                mealToAddOrEditFlow.value = event.mealWithIngredients
                showAddDialogFlow.value = true
            }
            is MealPlannerUiEvent.DismissAddEditDialog -> {
                mealToAddOrEditFlow.value = null
                showAddDialogFlow.value = false
            }
            is MealPlannerUiEvent.SaveMeal -> saveMeal(event.meal, event.ingredients)
            is MealPlannerUiEvent.DeleteMeal -> deleteMeal(event.mealId)
            is MealPlannerUiEvent.RequestCookMeal -> mealToCookFlow.value = event.mealWithIngredients
            is MealPlannerUiEvent.DismissCookDialog -> mealToCookFlow.value = null
            is MealPlannerUiEvent.ConfirmCookMeal -> cookMeal(
                event.mealId,
                event.actualIngredientOverrides,
                event.substitutions,
                event.leftoverStorageLocation
            )
            is MealPlannerUiEvent.SkipMeal -> skipMeal(event.mealId)
            is MealPlannerUiEvent.CancelMeal -> cancelMeal(event.mealId)
            is MealPlannerUiEvent.ConsumeLeftover -> consumeLeftover(event.leftoverId, event.portions)
            is MealPlannerUiEvent.DiscardLeftover -> discardLeftover(event.leftoverId)
            MealPlannerUiEvent.ClearUserMessage -> userMessageFlow.value = null
        }
    }

    private fun saveMeal(meal: Meal, ingredients: List<MealIngredient>) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val existing = mealRepository.getMealWithIngredients(meal.id)
            val result = if (existing != null) {
                mealDomainService.replaceMeal(meal.id, meal, ingredients)
            } else {
                mealDomainService.createPlannedMeal(meal, ingredients)
            }
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success -> {
                    userMessageFlow.value = "Meal saved successfully"
                    mealToAddOrEditFlow.value = null
                    showAddDialogFlow.value = false
                }
                is DomainResult.Failure -> {
                    userMessageFlow.value = formatDomainError(result.error)
                }
            }
        }
    }

    private fun deleteMeal(mealId: String) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.deletePlannedMeal(mealId)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Meal deleted"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun cookMeal(
        mealId: String,
        overrides: List<ActualIngredientOverride>,
        substitutions: List<IngredientSubstitution>,
        leftoverStorageLocation: StorageLocation?
    ) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.markMealCooked(
                mealId = mealId,
                actualIngredientOverrides = overrides,
                substitutions = substitutions,
                leftoverStorageLocation = leftoverStorageLocation ?: StorageLocation.FREEZER
            )
            isLoadingFlow.value = false
            mealToCookFlow.value = null

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Meal cooked successfully! Inventory deducted."
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun skipMeal(mealId: String) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.markMealSkipped(mealId)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Meal skipped"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun cancelMeal(mealId: String) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.markMealCancelled(mealId)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Meal cancelled"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun consumeLeftover(leftoverId: String, portions: Int) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.consumeLeftoverPortions(leftoverId, portions)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Leftover portion consumed"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun discardLeftover(leftoverId: String) {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val result = mealDomainService.discardLeftover(leftoverId)
            isLoadingFlow.value = false

            when (result) {
                is DomainResult.Success<*> -> userMessageFlow.value = "Leftover discarded"
                is DomainResult.Failure -> userMessageFlow.value = formatDomainError(result.error)
            }
        }
    }

    private fun formatDomainError(error: DomainError): String {
        return when (error) {
            is DomainError.InsufficientQuantity -> "Insufficient stock: ${error.requested} ${error.unit?.name ?: ""} required, only ${error.available} available."
            is DomainError.InvalidUnitConversion -> "Unit conversion error between ${error.from} and ${error.to}."
            is DomainError.NeedsConfirmation -> "Confirmation required for ${error.displayName}."
            is DomainError.DuplicateOperation -> "Already completed: ${error.message}"
            is DomainError.TransactionFailed -> "Transaction error: ${error.cause}"
            is DomainError.ItemNotFound -> "Item not found: ${error.itemId}"
            is DomainError.InvalidQuantity -> "Invalid quantity: ${error.quantity}"
            is DomainError.ItemNotUsable -> "Item not available: ${error.itemId}"
        }
    }

    private fun logDiagnostic(tag: String, message: String) {
        println("[$tag] $message")
        try {
            android.util.Log.d(tag, message)
        } catch (_: Throwable) {
            // Unstubbed in JVM unit tests
        }
    }
}
