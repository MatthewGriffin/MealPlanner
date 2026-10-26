package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.retail.contract.BasketComparisonResult
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.PackAlternative
import com.example.chatgpt_mealplanner.retail.contract.RetailerId

@Composable
fun ShoppingItemDetailDialog(
    itemWithSources: ShoppingListItemWithSources,
    basketComparisonResult: BasketComparisonResult? = null,
    retailerOptions: List<PackAlternative> = emptyList(),
    isLoadingRetailerOptions: Boolean = false,
    onOpenMeal: (mealId: String, mealDate: String) -> Unit = { _, _ -> },
    onReassignRetailer: (itemId: String, retailerId: RetailerId) -> Unit = { _, _ -> },
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

                val comparisonPlans = basketComparisonResult?.let { result ->
                    (listOfNotNull(result.selectedPlan, result.bestSingleStorePlan, result.bestMultiStorePlan) + result.alternativePlans)
                        .distinctBy { it.planId }
                }.orEmpty()
                // Requirement IDs are unique. Name fallback can attach another
                // same-named shopping row's price to this item.
                fun isThisRequirement(line: com.example.chatgpt_mealplanner.retail.contract.BasketLine): Boolean =
                    line.shoppingRequirementId == item.id
                val selectedLine = basketComparisonResult?.selectedPlan?.lines?.firstOrNull(::isThisRequirement)
                val perStoreLines: Map<RetailerId, BasketLine> = comparisonPlans
                    .flatMap { plan -> plan.lines.filter(::isThisRequirement) }
                    .groupBy { it.retailerId }
                    .mapValues { (_, lines) -> lines.minByOrNull { it.lineTotalCost }!! }

                val optionByRetailer = retailerOptions.associateBy {
                    it.productWithOffer.product.retailerId
                }
                val retailersToDisplay = (perStoreLines.keys + optionByRetailer.keys).distinct().sortedBy { it.name }

                if (retailersToDisplay.isNotEmpty() || isLoadingRetailerOptions) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Price and pack comparison by store", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Pack counts and costs are estimates. Choose a store to change this item's assignment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLoadingRetailerOptions) {
                        Text("Checking cached retailer matches…", style = MaterialTheme.typography.bodySmall)
                    }
                    retailersToDisplay.forEach { retailer ->
                        val option = optionByRetailer[retailer]
                        val line = perStoreLines[retailer]
                        val product = option?.productWithOffer?.product ?: line?.productWithOffer?.product
                        val packDescription = if (option != null && product != null) {
                            buildString {
                                val packCount = product.packCount
                                val size = product.packSize ?: product.netQuantity
                                val unit = product.packSizeUnit ?: product.netQuantityUnit
                                if (packCount != null && packCount > 1) append("$packCount × ")
                                if (size != null) append(size.toString()).append(" ").append(unit ?: "")
                                else append("Pack size unknown")
                                append(" · ").append(option.packCountToBuy)
                                    .append(if (option.packCountToBuy == 1) " pack" else " packs")
                            }
                        } else if (product != null && line != null) {
                            buildString {
                                val size = product.packSize ?: product.netQuantity
                                val unit = product.packSizeUnit ?: product.netQuantityUnit
                                if (size != null) append(size.toString()).append(" ").append(unit ?: "")
                                else append("Pack size unknown")
                                append(" · ").append(line.packCountToBuy)
                                    .append(if (line.packCountToBuy == 1) " pack" else " packs")
                            }
                        } else "Pack size unknown"
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(retailer.displayNameForShopping(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(product?.name ?: "No compatible product found", style = MaterialTheme.typography.bodySmall)
                                Text(packDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (selectedLine?.retailerId == retailer) {
                                    Text("Current assignment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val price = option?.totalCost?.toFormattedString() ?: line?.lineTotalCost?.toFormattedString()
                                if (price != null) Text(price, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                TextButton(
                                    onClick = { onReassignRetailer(item.id, retailer) },
                                    enabled = selectedLine?.retailerId != retailer
                                ) {
                                    Text(if (selectedLine?.retailerId == retailer) "Selected" else "Use store")
                                }
                            }
                        }
                    }
                    if (retailersToDisplay.isEmpty() && !isLoadingRetailerOptions) {
                        Text("No compatible products found in the retailer cache.", style = MaterialTheme.typography.bodyMedium)
                    }
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
                                    .clickable { onOpenMeal(src.mealId, src.mealDate) }
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
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Open recipe",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
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


private fun RetailerId.displayNameForShopping(): String = when (this) {
    RetailerId.ASDA -> "ASDA"
    RetailerId.ICELAND -> "Iceland"
    RetailerId.FOOD_WAREHOUSE -> "The Food Warehouse"
    RetailerId.TESCO -> "Tesco"
    RetailerId.MORRISONS -> "Morrisons"
    RetailerId.SAINSBURYS -> "Sainsbury's"
    RetailerId.ALDI -> "Aldi"
    RetailerId.LIDL -> "Lidl"
}
