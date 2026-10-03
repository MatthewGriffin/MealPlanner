package com.example.chatgpt_mealplanner.feature.spend

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendCategory
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendParser
import java.io.File

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSpendScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiptSpendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_RESULT] success=true uri=$tempPhotoUri")
            viewModel.processReceiptImage(tempPhotoUri!!)
        } else {
            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_CANCELLED] success=$success tempPhotoUri=$tempPhotoUri")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processReceiptImage(it) }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (uiState.isSaveSuccess) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Receipt Spend Saved Successfully!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.resetSaveSuccess() }) {
                            Text("Done")
                        }
                    }
                }
            } else if (uiState.draft != null) {
                val draft = uiState.draft!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Review Receipt Spend (${draft.retailerId})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Parser Version: ${ReceiptSpendParser.RECEIPT_PARSER_VERSION}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Date: ${draft.purchasedAt ?: "Not detected"}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Net Paid Total", style = MaterialTheme.typography.labelMedium)
                                val netPaidDisplay = if (draft.totalMinorPence != null && draft.totalMinorPence > 0L) "£%.2f".format(draft.totalMinorPence / 100.0) else "Not detected"
                                Text(
                                    netPaidDisplay,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (draft.totalMinorPence != null && draft.totalMinorPence > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Recognised Items: £${"%.2f".format(draft.calculatedItemsSumMinorPence / 100.0)}", style = MaterialTheme.typography.bodySmall)
                                Text("Receipt Savings: -£${"%.2f".format(draft.discountMinorPence / 100.0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                if (draft.reviewWarning != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                draft.reviewWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                val itemCountText = if (draft.expectedItemCount != null) "Recognised Items (${draft.itemCount} / ${draft.expectedItemCount})" else "Recognised Items (${draft.itemCount})"
                Text(itemCountText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(draft.items) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.normalizedDescription, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("£${"%.2f".format(item.lineAmountMinorPence / 100.0)}")
                                        IconButton(onClick = { viewModel.deleteItemDraft(item.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Line", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ReceiptSpendCategory.entries.forEach { cat ->
                                        FilterChip(
                                            selected = item.category.equals(cat.name, ignoreCase = true) || item.category.equals(cat.displayName, ignoreCase = true),
                                            onClick = { viewModel.updateItemDraft(item.copy(category = cat.name)) },
                                            label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                val saveLabel = if (draft.canSave && draft.totalMinorPence != null) "Save Receipt (£${"%.2f".format(draft.totalMinorPence / 100.0)})" else "Fix Receipt Before Saving"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.cancelScan() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Discard Scan")
                    }
                    Button(
                        onClick = { viewModel.saveReceiptSpend() },
                        enabled = draft.canSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(saveLabel)
                    }
                }
            } else {
                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Scan Error", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(uiState.errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.cancelScan() }) {
                                    Text("Cancel / Dismiss")
                                }
                            }
                        }
                    }
                }
                // SPEND DASHBOARD
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Monthly Grocery Spend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Net Paid (This Month)", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "£${"%.2f".format(uiState.dashboardState.thisMonthNetPaidPence / 100.0)}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Last Month: £${"%.2f".format(uiState.dashboardState.lastMonthNetPaidPence / 100.0)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Gross Items: £${"%.2f".format(uiState.dashboardState.thisMonthGrossItemsPence / 100.0)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Savings: -£${"%.2f".format(uiState.dashboardState.thisMonthSavingsPence / 100.0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val pctText = if (uiState.dashboardState.percentageChange >= 0) "+${"%.1f".format(uiState.dashboardState.percentageChange)}%" else "${"%.1f".format(uiState.dashboardState.percentageChange)}%"
                            Text("Spend Change: $pctText vs last month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                try {
                                    android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_LAUNCH]")
                                    val photoFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
                                    val photoUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                                    tempPhotoUri = photoUri
                                    cameraLauncher.launch(photoUri)
                                } catch (e: Exception) {
                                    android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_ERROR] exception=${e.javaClass.simpleName} message=\"${e.message}\"")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Take Photo")
                        }

                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Image")
                        }
                    }

                    if (uiState.isScanning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Reading receipt line items & prices...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = { viewModel.cancelScan() }) {
                                Text("Cancel")
                            }
                        }
                    }

                    if (uiState.errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Where Your Shopping Money Went (Gross Allocations)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.dashboardState.categoryBreakdown.forEach { cat ->
                        val pct = if (uiState.dashboardState.thisMonthGrossItemsPence > 0) (cat.totalPence.toDouble() / uiState.dashboardState.thisMonthGrossItemsPence) * 100.0 else 0.0
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(cat.category, fontWeight = FontWeight.Bold)
                                Text("£${"%.2f".format(cat.totalPence / 100.0)} (${"%.1f".format(pct)}%)")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Spend by Retailer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.dashboardState.retailerBreakdown.forEach { ret ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ret.retailerId, fontWeight = FontWeight.Bold)
                                Text("£${"%.2f".format(ret.totalPence / 100.0)} (${ret.receiptCount} receipts)")
                            }
                        }
                    }
                }
            }
        }
    }
}
