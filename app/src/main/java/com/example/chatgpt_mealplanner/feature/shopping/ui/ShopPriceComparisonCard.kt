package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.core.service.ShopPriceOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ShopPriceComparisonCard(
    items: List<ShoppingListItemWithSources>,
    optionsByItemId: Map<String, List<ShopPriceOption>>,
    modifier: Modifier = Modifier
) {
    val itemsWithPrices = items.filter { !optionsByItemId[it.item.id].isNullOrEmpty() }
    // Until prices have been recorded there is nothing to compare, so the card stays out of the way.
    if (itemsWithPrices.isEmpty()) return
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
            title = { Text("Best shop") },
            text = {
                Text(
                    "The shop with the lowest total cost that covers what you need, using only prices you recorded " +
                        "(no live shop prices), which may be out of date."
                )
            }
        )
    }
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Best shop from your recorded prices", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Default.Info, contentDescription = "How the best shop is worked out")
                }
            }
            itemsWithPrices.forEach { entry ->
                Spacer(modifier = Modifier.height(8.dp))
                val needed = entry.item.suggestedPurchaseQuantity?.let { quantity ->
                    formatAmount(quantity, entry.item.suggestedPurchaseUnit)
                }
                Text(
                    if (needed != null) "${entry.item.displayName} · need $needed" else entry.item.displayName,
                    fontWeight = FontWeight.Medium
                )
                optionsByItemId[entry.item.id].orEmpty().forEach { option -> ShopPriceOptionRow(option) }
            }
        }
    }
}

@Composable
private fun ShopPriceOptionRow(option: ShopPriceOption) {
    val observation = option.price.observation
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (option.isBest) "${option.price.storeName} · Best" else option.price.storeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (option.isBest) FontWeight.SemiBold else FontWeight.Normal,
                color = if (option.isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(purchaseDescription(option), style = MaterialTheme.typography.bodySmall)
            Text(
                "Recorded ${SimpleDateFormat("dd MMM yyyy", Locale.UK).format(Date(observation.purchasedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatPounds(option.totalCostMinorUnits ?: observation.priceMinorUnits),
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun purchaseDescription(option: ShopPriceOption): String {
    val observation = option.price.observation
    val packQuantity = observation.packQuantity
    val packLabel = if (packQuantity != null && !observation.packUnit.isNullOrBlank()) {
        val size = "${formatNumber(packQuantity)} ${observation.packUnit.lowercase()}"
        observation.packCount?.takeIf { it > 1 }?.let { "$it x $size" } ?: size
    } else {
        null
    }
    val packs = option.packsToBuy
    return when {
        packs != null && packLabel != null -> "$packs x $packLabel · ${formatPounds(observation.priceMinorUnits)} each"
        packLabel != null -> "$packLabel · can't compare with the amount needed"
        else -> "Pack size unknown · can't compare"
    }
}

private fun formatAmount(quantity: Double, unit: QuantityUnit?): String =
    listOfNotNull(formatNumber(quantity), unit?.name?.lowercase()).joinToString(" ")

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.UK, value).trimEnd('0').trimEnd('.')

private fun formatPounds(minorUnits: Long): String = "£%.2f".format(Locale.UK, minorUnits / 100.0)
