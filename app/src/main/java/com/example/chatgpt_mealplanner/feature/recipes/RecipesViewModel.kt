package com.example.chatgpt_mealplanner.feature.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.database.dao.SavedRecipeDao
import com.example.chatgpt_mealplanner.core.database.entity.SavedRecipeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The recipes the household saved from web pages and photos. */
@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val dao: SavedRecipeDao
) : ViewModel() {

    val recipes: StateFlow<List<SavedRecipeEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(recipe: SavedRecipeEntity) {
        viewModelScope.launch { dao.delete(recipe.normalizedTitle) }
    }
}
