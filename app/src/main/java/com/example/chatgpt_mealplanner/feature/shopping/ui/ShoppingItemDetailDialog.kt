package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources

@Composable
fun ShoppingItemDetailDialog(
    itemWithSources: ShoppingListItemWithSources,
    onDismiss: () -> Unit
) {
    val item = itemWithSources.item
    val sources = itemWithSources.sources

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = item.displayName)
        },
        text = {
            Column {
                Text(
                    text = "Source Breakdown & Reasoning",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Suggested Purchase: ${item.suggestedPurchaseQuantity ?: 0.0} ${item.suggestedPurchaseUnit?.name ?: ""}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val ownedText = itemWithSources.ownedUsableQuantity?.let { "$it ${itemWithSources.ownedUsableUnit?.name ?: ""}" } ?: "None"
                Text(
                    text = "Owned Usable Stock: $ownedText",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Total Planned Meal Demand: ${item.requiredQuantity ?: 0.0} ${item.requiredUnit?.name ?: ""}",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (item.mealShortfallQuantity != null && item.mealShortfallQuantity > 0) {
                    Text(
                        text = "• Meal Shortfall: ${item.mealShortfallQuantity} ${item.requiredUnit?.name ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.stapleReserveTopUpQuantity != null && item.stapleReserveTopUpQuantity > 0) {
                    Text(
                        text = "• Staple Reserve Top-up: ${item.stapleReserveTopUpQuantity} ${item.requiredUnit?.name ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.expectedFinalInventory != null) {
                    Text(
                        text = "Expected Final Stock After Cooking: ${item.expectedFinalInventory} ${item.expectedFinalInventoryUnit?.name ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (item.quantityUncertain && item.uncertaintyReason != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Warning: ${item.uncertaintyReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Contributing Planned Meals:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        items(sources, key = { it.id }) { src ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = src.mealTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Date: ${src.mealDate}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "${src.requiredQuantity} ${src.requiredUnit.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
