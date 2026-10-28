package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.clickable
import com.example.chatgpt_mealplanner.core.model.QuantityUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.RequirementConfidence
import com.example.chatgpt_mealplanner.core.model.ShoppingItemStatus
import com.example.chatgpt_mealplanner.core.model.ShoppingListItemWithSources
import com.example.chatgpt_mealplanner.feature.shopping.RetailerGroup
import com.example.chatgpt_mealplanner.feature.shopping.ShoppingTab
import com.example.chatgpt_mealplanner.feature.shopping.ShoppingUiEvent
import com.example.chatgpt_mealplanner.feature.shopping.ShoppingUiState
import com.example.chatgpt_mealplanner.retail.contract.BasketLine
import com.example.chatgpt_mealplanner.retail.contract.PriceFreshness
import com.example.chatgpt_mealplanner.retail.contract.RetailFreshnessPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShoppingScreen(
    uiState: ShoppingUiState,
    onEvent: (ShoppingUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMeal: (mealId: String, mealDate: String) -> Unit = { _, _ -> }
) {
    LaunchedEffect(Unit) {
        println("[AI_MEALPLANNER] [SHOPPING_SCREEN_ENTRY] buildAudit=SHOPPING_AUDIT_20261001_02")
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // Store sections start collapsed so large multi-store lists stay compact.
    // State is keyed by retailer identity so expanding one section doesn't affect another.
    val expandedRetailerGroups = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onEvent(ShoppingUiEvent.ClearUserMessage)
        }
    }

    if (uiState.selectedItemForDetail != null) {
        ShoppingItemDetailDialog(
            itemWithSources = uiState.selectedItemForDetail,
            basketComparisonResult = uiState.basketComparisonResult,
            retailerOptions = uiState.selectedItemOptions,
            isLoadingRetailerOptions = uiState.isLoadingItemOptions,
            onOpenMeal = { mealId, mealDate -> onOpenMeal(mealId, mealDate) },
            onReassignRetailer = { itemId, retailerId -> onEvent(ShoppingUiEvent.ReassignItemRetailer(itemId, retailerId)) },
            onDismiss = { onEvent(ShoppingUiEvent.SelectItemDetail(null)) }
        )
    }

    if (uiState.showAddManualDialog) {
        AddManualItemDialog(
            initialName = uiState.manualItemName,
            initialNotes = uiState.manualItemNotes,
            onDismiss = { onEvent(ShoppingUiEvent.DismissAddManualDialog) },
            onConfirmAdd = { name, qty, unit, notes ->
                onEvent(ShoppingUiEvent.AddManualItem(name, qty, unit, notes))
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.activeTab == ShoppingTab.SHOPPING_LIST) {
                FloatingActionButton(
                    onClick = { onEvent(ShoppingUiEvent.RequestAddManualItem) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Manual Item")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(
                selectedTabIndex = uiState.activeTab.ordinal
            ) {
                ShoppingTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.activeTab == tab,
                        onClick = { onEvent(ShoppingUiEvent.SelectTab(tab)) },
                        text = { Text(tab.displayName) }
                    )
                }
            }

            when (uiState.activeTab) {
                ShoppingTab.SHOPPING_LIST -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.activeListWithItems?.shoppingList?.name ?: "Active Shopping List",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val completedCount = uiState.activeListWithItems?.items.orEmpty().count {
                                it.item.status == ShoppingItemStatus.CHECKED ||
                                    it.item.status == ShoppingItemStatus.PURCHASED_CONFIRMED
                            }
                            if (!uiState.isFollower && completedCount > 0) {
                                TextButton(onClick = { onEvent(ShoppingUiEvent.ClearCompletedItems) }) {
                                    Text("Clear completed")
                                }
                            }
                            if (!uiState.isFollower) {
                                IconButton(onClick = { onEvent(ShoppingUiEvent.RegenerateShoppingList) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate List")
                                }
                            }
                        }
                    }

                    val items = uiState.activeListWithItems?.items ?: emptyList()
                    if (items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Shopping list is empty",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Planned meals and staple rules automatically populate this list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { onEvent(ShoppingUiEvent.RegenerateShoppingList) }) {
                                Text("Generate Shopping List")
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.retailerGroups.forEach { group ->
                                val groupCheckedCount = group.items.count {
                                    it.item.status == ShoppingItemStatus.CHECKED ||
                                        it.item.status == ShoppingItemStatus.PURCHASED_CONFIRMED
                                }
                                val groupKey = group.retailerId?.name ?: group.retailerName
                                // With retailer comparisons disabled, keep the ordinary shopping-list group visible by default.
                                val isExpanded = expandedRetailerGroups[groupKey] ?: (group.retailerId == null)
                                item(key = "header_${groupKey}") {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, bottom = 2.dp)
                                            .clickable {
                                                expandedRetailerGroups[groupKey] = !isExpanded
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = if (isExpanded) "Collapse ${group.retailerName}" else "Expand ${group.retailerName}"
                                                )
                                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                                    Text(
                                                        text = "${group.retailerName} · $groupCheckedCount/${group.items.size}",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                    Text(
                                                        text = if (isExpanded) "Tap to collapse" else "Tap to expand",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                            if (group.subtotal != null) {
                                                Text(
                                                    text = group.subtotal.toFormattedString(),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isExpanded) {
                                    items(group.items, key = { it.item.id }) { itemWithSources ->
                                        ShoppingItemCard(
                                            itemWithSources = itemWithSources,
                                            basketLine = group.lineByItemId[itemWithSources.item.id],
                                            onToggleChecked = { id, status -> onEvent(ShoppingUiEvent.ToggleItemChecked(id, status)) },
                                            onRemove = { id -> onEvent(ShoppingUiEvent.RemoveItem(id)) },
                                            onDetailClicked = { onEvent(ShoppingUiEvent.SelectItemDetail(itemWithSources)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ShoppingTab.STAPLE_RULES -> {
                    StapleRulesScreen(
                        stapleRules = uiState.stapleRules,
                        onSaveRule = { onEvent(ShoppingUiEvent.SaveStapleRule(it)) },
                        onDeleteRule = { onEvent(ShoppingUiEvent.DeleteStapleRule(it)) }
                    )
                }
            }
        }
    }
}

@Composable
fun ShoppingItemCard(
    itemWithSources: ShoppingListItemWithSources,
    basketLine: BasketLine? = null,
    onToggleChecked: (id: String, currentStatus: ShoppingItemStatus) -> Unit,
    onRemove: (id: String) -> Unit,
    onDetailClicked: () -> Unit
) {
    val item = itemWithSources.item
    val isChecked = item.status == ShoppingItemStatus.CHECKED || item.status == ShoppingItemStatus.PURCHASED_CONFIRMED

    LaunchedEffect(item.id, basketLine) {
        val assignmentState = if (basketLine != null) "ASSIGNED" else "UNASSIGNED"
        val retailerName = basketLine?.retailerId?.name ?: "UNASSIGNED"
        val prodName = basketLine?.productWithOffer?.product?.name ?: "null"
        val prodId = basketLine?.productWithOffer?.product?.retailerProductId ?: "null"
        println("[AI_MEALPLANNER] [SHOPPING_UI_ROW] buildAudit=TOMATO_UI_PATH_20261001_01 name=\"${item.displayName}\" assignmentState=$assignmentState retailer=$retailerName productName=\"$prodName\" productId=$prodId")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetailClicked),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggleChecked(item.id, item.status) }
                )
                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                    )
                    val qtyText = if (item.suggestedPurchaseQuantity != null) {
                        val approxSymbol = if (item.requirementConfidence == RequirementConfidence.APPROXIMATE) "≈" else ""
                        val formatted = formatQuantityAndUnit(item.suggestedPurchaseQuantity, item.suggestedPurchaseUnit)
                        "$approxSymbol$formatted"
                    } else "Quantity unstated"

                    Text(
                        text = "$qtyText • ${item.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (basketLine != null) {
                        val prodName = basketLine.productWithOffer.product.name
                        val costStr = basketLine.lineTotalCost.toFormattedString()
                        val packCount = basketLine.packCountToBuy
                        val packStr = if (packCount > 1) " (x$packCount)" else ""
                        val lineDetail = "$prodName$packStr • $costStr"

                        val freshness = basketLine.productWithOffer.offer.calculateFreshness(RetailFreshnessPolicy())
                        val freshnessNote = if (freshness == PriceFreshness.STALE) " (Cached price)" else ""

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$lineDetail$freshnessNote",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(onClick = { onRemove(item.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Item")
                }
            }

            if (item.quantityUncertain && item.uncertaintyReason != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = item.uncertaintyReason!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun formatQuantityAndUnit(quantity: Double?, unit: QuantityUnit?): String {
    if (quantity == null) return "Quantity unstated"
    val formattedQty = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    return when (unit) {
        QuantityUnit.UNIT -> "$formattedQty x"
        QuantityUnit.GRAM -> "$formattedQty g"
        QuantityUnit.KILOGRAM -> "$formattedQty kg"
        QuantityUnit.MILLILITRE -> "$formattedQty ml"
        QuantityUnit.LITRE -> "$formattedQty l"
        QuantityUnit.PACK -> "$formattedQty pack${if (quantity > 1.0) "s" else ""}"
        QuantityUnit.TIN -> "$formattedQty tin${if (quantity > 1.0) "s" else ""}"
        QuantityUnit.BOTTLE -> "$formattedQty bottle${if (quantity > 1.0) "s" else ""}"
        QuantityUnit.BAG -> "$formattedQty bag${if (quantity > 1.0) "s" else ""}"
        QuantityUnit.SLICE -> "$formattedQty slice${if (quantity > 1.0) "s" else ""}"
        QuantityUnit.PORTION -> "$formattedQty portion${if (quantity > 1.0) "s" else ""}"
        null -> formattedQty
    }
}
