package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.feature.shopping.ShopChoice

/** Asks which shop the household is in, so the list can follow that shop's order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartShoppingDialog(
    shops: List<ShopChoice>,
    motion: com.example.chatgpt_mealplanner.feature.shopping.MotionOptions,
    onDismiss: () -> Unit,
    onStart: (storeId: String, learnLayout: Boolean) -> Unit
) {
    var selected by remember { mutableStateOf(shops.firstOrNull()) }
    var learnLayout by remember { mutableStateOf(motion.enabled) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start shopping") },
        text = {
            Column {
                if (shops.isEmpty()) {
                    Text("Add the shops you use under Settings first. The app learns the order of each shop's aisles from how you tick items off.")
                } else {
                    Text(
                        "Which shop are you in? The list follows the order you found things in last time, and learns as you tick items off.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selected?.name.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Shop") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            shops.forEach { shop ->
                                DropdownMenuItem(text = { Text(shop.name) }, onClick = { selected = shop; expanded = false })
                            }
                        }
                    }
                    if (motion.available) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = learnLayout, onCheckedChange = { learnLayout = it })
                            Column {
                                Text("Also learn the shop's layout", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Uses this phone's step counter and compass while you shop, so the list can follow your route. It needs the Physical activity permission and works best with the app open. What it senses stays in your household.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onStart(it.id, learnLayout && motion.available) } }, enabled = selected != null) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
