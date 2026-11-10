package com.example.chatgpt_mealplanner.feature.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.database.entity.SavedRecipeEntity

/**
 * The saved recipes. Adding goes through the assistant chat, which reads the link or the photo; this screen
 * lists, shows, deletes and asks the chat to read a recipe again.
 */
@Composable
fun RecipesScreen(
    onAddFromLink: (url: String) -> Unit,
    onAddFromPhoto: () -> Unit,
    onRescanPhoto: (title: String) -> Unit,
    onRescanLink: (title: String, url: String) -> Unit,
    onPlanThis: (title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipesViewModel = hiltViewModel()
) {
    val recipes by viewModel.recipes.collectAsState()
    var showLinkDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<SavedRecipeEntity?>(null) }
    var deleting by remember { mutableStateOf<SavedRecipeEntity?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Give the assistant a recipe link or a photo of a recipe page and it keeps the recipe, so it can use it when it plans meals.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showLinkDialog = true }, modifier = Modifier.testTag("recipe-add-link")) { Text("Add from link") }
            OutlinedButton(onClick = onAddFromPhoto, modifier = Modifier.testTag("recipe-add-photo")) { Text("Add from photo") }
        }
        if (recipes.size > 5) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search recipes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("recipe-search")
            )
        }
        val shown = if (query.isBlank()) recipes else recipes.filter { it.title.contains(query.trim(), ignoreCase = true) }
        if (recipes.isEmpty()) {
            Text("No saved recipes yet.", style = MaterialTheme.typography.bodyLarge)
        } else if (shown.isEmpty()) {
            Text("No recipes match \"${query.trim()}\".", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.normalizedTitle }) { recipe ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { viewing = recipe }) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    recipe.servings?.let { "Serves $it" },
                                    sourceLabel(recipe)
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLinkDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Add a recipe from a link") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Web address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("recipe-link-field")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showLinkDialog = false; onAddFromLink(url.trim()) },
                    enabled = url.trim().startsWith("http", ignoreCase = true)
                ) { Text("Read it") }
            },
            dismissButton = { TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") } }
        )
    }

    viewing?.let { recipe ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(recipe.title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOfNotNull(recipe.servings?.let { "Serves $it" }, sourceLabel(recipe)).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                    Text(recipe.recipeText, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            viewing = null
                            val link = recipe.sourceNote?.takeIf { recipe.source == "URL" && it.startsWith("http", ignoreCase = true) }
                            if (link != null) onRescanLink(recipe.title, link) else onRescanPhoto(recipe.title)
                        }, modifier = Modifier.testTag("recipe-rescan")) { Text("Scan again") }
                        OutlinedButton(onClick = { deleting = recipe; viewing = null }) { Text("Delete") }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewing = null; onPlanThis(recipe.title) }, modifier = Modifier.testTag("recipe-plan-this")) { Text("Plan this") } },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("Close") } }
        )
    }

    deleting?.let { recipe ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${recipe.title}\"?") },
            text = { Text("The assistant will no longer know this recipe. You can add it again from its link or a photo.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(recipe); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

private fun sourceLabel(recipe: SavedRecipeEntity): String = when (recipe.source) {
    "URL" -> recipe.sourceNote?.let { "From $it" } ?: "From a web page"
    "PHOTO" -> recipe.sourceNote?.let { "From a photo: $it" } ?: "From a photo"
    else -> "Typed in"
}
