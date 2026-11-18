package com.example.chatgpt_mealplanner.feature.spend

import androidx.compose.material3.LoadingIndicator
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.feature.inventory.ui.EnumDropdownSelector
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendCategory
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendItemDraft
import com.example.chatgpt_mealplanner.core.receipt.ReceiptSpendParser
import com.example.chatgpt_mealplanner.core.receipt.ReceiptMoneyInput
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSpendScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiptSpendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedPhotoUri = tempPhotoUri
        if (success && capturedPhotoUri != null) {
            cameraErrorMessage = null
            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_RESULT] success=true uri=$capturedPhotoUri")
            // OCR only needs the photo while it runs, so don't leave it in the cache afterwards.
            viewModel.processReceiptImage(capturedPhotoUri) {
                runCatching { context.contentResolver.delete(capturedPhotoUri, null, null) }
            }
        } else {
            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_CANCELLED] success=$success tempPhotoUri=$capturedPhotoUri")
            capturedPhotoUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            if (!success) {
                cameraErrorMessage = "No photo was captured. Please try again."
            }
        }
    }

    val launchCamera: () -> Unit = {
        try {
            android.util.Log.i("AI_MEALPLANNER", "[RECEIPT_CAMERA_LAUNCH]")
            // TakePicture writes into the supplied URI; create the destination first
            // so camera apps that require an existing output file can open it.
            // Clear any photo left behind if the app was killed before OCR finished.
            context.cacheDir.listFiles { file -> file.name.startsWith("receipt_") && file.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            val photoFile = File.createTempFile("receipt_", ".jpg", context.cacheDir)
            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempPhotoUri = photoUri
            cameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            android.util.Log.e("AI_MEALPLANNER", "[RECEIPT_CAMERA_ERROR]", e)
            cameraErrorMessage = when (e) {
                is android.content.ActivityNotFoundException ->
                    "No camera app is available on this device."
                else ->
                    "Couldn't open the camera: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            cameraErrorMessage = "Camera permission is needed to take a photo. You can choose an image from your gallery instead."
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
                var showDatePicker by remember { mutableStateOf(false) }
                var showTotalEditor by remember { mutableStateOf(false) }
                var showAddItem by remember { mutableStateOf(false) }
                var itemBeingEdited by remember { mutableStateOf<ReceiptSpendItemDraft?>(null) }

                if (showDatePicker) {
                    ReceiptDatePickerDialog(
                        initialIsoDate = draft.purchasedAt,
                        onConfirm = {
                            viewModel.updatePurchaseDate(it)
                            showDatePicker = false
                        },
                        onDismiss = { showDatePicker = false }
                    )
                }
                if (showTotalEditor) {
                    ReceiptTotalDialog(
                        currencyCode = draft.currencyCode,
                        initialPence = draft.totalMinorPence,
                        onConfirm = {
                            viewModel.updateSummaryPaidTotal(it)
                            showTotalEditor = false
                        },
                        onDismiss = { showTotalEditor = false }
                    )
                }
                if (showAddItem) {
                    ReceiptItemDialog(
                        title = "Add missed item",
                        currencyCode = draft.currencyCode,
                        initialDescription = "",
                        initialPence = null,
                        onConfirm = { description, pence ->
                            viewModel.addItemDraft(description, pence)
                            showAddItem = false
                        },
                        onDismiss = { showAddItem = false }
                    )
                }
                itemBeingEdited?.let { item ->
                    ReceiptItemDialog(
                        title = "Edit item",
                        currencyCode = draft.currencyCode,
                        initialDescription = item.normalizedDescription,
                        initialPence = item.lineAmountMinorPence,
                        onConfirm = { description, pence ->
                            viewModel.updateItemDetails(item.id, description, pence)
                            itemBeingEdited = null
                        },
                        onDismiss = { itemBeingEdited = null }
                    )
                }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Date: ${draft.purchasedAt ?: "Not detected"}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showDatePicker = true }) {
                                Text("Change date")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Net Paid Total", style = MaterialTheme.typography.labelMedium)
                                val netPaidDisplay = if (draft.totalMinorPence != null && draft.totalMinorPence > 0L) com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(draft.totalMinorPence, draft.currencyCode) else "Not detected"
                                Text(
                                    netPaidDisplay,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (draft.totalMinorPence != null && draft.totalMinorPence > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { showTotalEditor = true }) {
                                    Text("Edit total")
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Recognised Items: ${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(draft.calculatedItemsSumMinorPence, draft.currencyCode)}", style = MaterialTheme.typography.bodySmall)
                                Text("Receipt Savings: -${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(draft.discountMinorPence, draft.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                uiState.scanNote?.let { note ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        itemCountText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAddItem = true }) {
                        Text("Add item")
                    }
                }
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
                                    Text(item.normalizedDescription, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(item.lineAmountMinorPence, draft.currencyCode))
                                        IconButton(onClick = { itemBeingEdited = item }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Line")
                                        }
                                        IconButton(onClick = { viewModel.deleteItemDraft(item.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Line", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                var linkMenuExpanded by remember(item.id) { mutableStateOf(false) }
                                val linkedCandidate = uiState.linkCandidates.firstOrNull {
                                    (it.type == ReceiptLinkType.INVENTORY && it.id == item.linkedInventoryItemId) ||
                                        (it.type == ReceiptLinkType.SHOPPING_LIST && it.id == item.linkedShoppingListItemId)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        linkedCandidate?.let {
                                            "${if (item.autoLinked) "Linked automatically" else "Linked"}: ${it.displayName} (${if (it.type == ReceiptLinkType.INVENTORY) "inventory" else "shopping list"})"
                                        } ?: if (item.linkLocked || item.category == "UNCATEGORISED") "Not linked" else "Not linked yet \u2013 items not in your pantry are added when you save",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { linkMenuExpanded = true }) {
                                        Text(if (linkedCandidate == null) "Link item" else "Change link")
                                    }
                                    DropdownMenu(
                                        expanded = linkMenuExpanded,
                                        onDismissRequest = { linkMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Leave unmatched") },
                                            onClick = {
                                                viewModel.updateItemDraft(item.copy(linkedInventoryItemId = null, linkedShoppingListItemId = null, autoLinked = false, linkLocked = true))
                                                linkMenuExpanded = false
                                            }
                                        )
                                        // Likely matches first, then the shopping list, then the rest of the pantry.
                                        val ordered = uiState.linkCandidates.sortedWith(
                                            compareBy<ReceiptLinkCandidate>(
                                                { !com.example.chatgpt_mealplanner.core.util.IngredientNameMatcher.matches(item.normalizedDescription, it.displayName) },
                                                { it.type != ReceiptLinkType.SHOPPING_LIST },
                                                { it.displayName.lowercase() }
                                            )
                                        )
                                        ordered.forEach { candidate ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text("${if (candidate.type == ReceiptLinkType.INVENTORY) "Inventory" else "Shopping list"}: ${candidate.displayName}")
                                                },
                                                onClick = {
                                                    viewModel.updateItemDraft(
                                                        if (candidate.type == ReceiptLinkType.INVENTORY) {
                                                            item.copy(linkedInventoryItemId = candidate.id, linkedShoppingListItemId = null, autoLinked = false, linkLocked = true)
                                                        } else {
                                                            item.copy(linkedInventoryItemId = null, linkedShoppingListItemId = candidate.id, autoLinked = false, linkLocked = true)
                                                        }
                                                    )
                                                    linkMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                ReceiptItemCategoryDropdown(
                                    category = item.category,
                                    onCategorySelected = { viewModel.updateItemDraft(item.copy(category = it)) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                val saveLabel = if (draft.canSave && draft.totalMinorPence != null) "Save Receipt (${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(draft.totalMinorPence, draft.currencyCode)})" else "Fix Receipt Before Saving"
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
                            if (uiState.dashboardState.otherCurrencyReceipts > 0) {
                                Text(
                                    "${uiState.dashboardState.otherCurrencyReceipts} receipt(s) in other currencies are not included in these totals.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Net Paid (This Month)", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(uiState.dashboardState.thisMonthNetPaidPence, uiState.dashboardState.currencyCode),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Last Month: ${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(uiState.dashboardState.lastMonthNetPaidPence, uiState.dashboardState.currencyCode)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Gross Items: ${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(uiState.dashboardState.thisMonthGrossItemsPence, uiState.dashboardState.currencyCode)}", style = MaterialTheme.typography.bodySmall)
                                    Text("Savings: -${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(uiState.dashboardState.thisMonthSavingsPence, uiState.dashboardState.currencyCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                                cameraErrorMessage = null
                                // The manifest declares CAMERA for the in-app scanners, so Android rejects
                                // ACTION_IMAGE_CAPTURE with a SecurityException until it has been granted.
                                val hasCameraPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCameraPermission) {
                                    launchCamera()
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "For the best scan, flatten the receipt, use good light, and keep the phone square to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (cameraErrorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            cameraErrorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (uiState.isScanning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
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
                                Text("${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(cat.totalPence, uiState.dashboardState.currencyCode)}" + " (${"%.1f".format(pct)}%)")
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
                                Text("${com.example.chatgpt_mealplanner.core.util.MoneyFormat.format(ret.totalPence, uiState.dashboardState.currencyCode)}" + " (${ret.receiptCount} ${if (ret.receiptCount == 1) "receipt" else "receipts"})")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isoDateToUtcMillis(isoDate: String): Long =
    LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptDatePickerDialog(
    initialIsoDate: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val lastSelectableMillis = remember { isoDateToUtcMillis(LocalDate.now().toString()) }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialIsoDate?.let { isoDateToUtcMillis(it) } ?: lastSelectableMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastSelectableMillis
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun ReceiptTotalDialog(
    currencyCode: String,
    initialPence: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var amountText by remember { mutableStateOf(initialPence?.takeIf { it > 0L }?.let { ReceiptMoneyInput.format(it, currencyCode) }.orEmpty()) }
    val pence = ReceiptMoneyInput.parsePositivePence(amountText, currencyCode)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Amount paid") },
        text = { ReceiptPriceField(amountText, "Total paid (${com.example.chatgpt_mealplanner.core.util.MoneyFormat.symbol(currencyCode)})", currencyCode) { amountText = it } },
        confirmButton = {
            TextButton(enabled = pence != null, onClick = { pence?.let(onConfirm) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReceiptItemDialog(
    title: String,
    currencyCode: String,
    initialDescription: String,
    initialPence: Long?,
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var description by remember { mutableStateOf(initialDescription) }
    var amountText by remember { mutableStateOf(initialPence?.let { ReceiptMoneyInput.format(it, currencyCode) }.orEmpty()) }
    val pence = ReceiptMoneyInput.parsePositivePence(amountText, currencyCode)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Item") },
                    singleLine = true
                )
                ReceiptPriceField(amountText, "Price (${com.example.chatgpt_mealplanner.core.util.MoneyFormat.symbol(currencyCode)})", currencyCode) { amountText = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pence != null && description.isNotBlank(),
                onClick = { pence?.let { onConfirm(description, it) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReceiptPriceField(value: String, label: String, currencyCode: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && ReceiptMoneyInput.parsePositivePence(value, currencyCode) == null
    )
}

@Composable
internal fun ReceiptItemCategoryDropdown(category: String, onCategorySelected: (String) -> Unit) {
    EnumDropdownSelector(
        label = "Category",
        options = ReceiptSpendCategory.entries.toTypedArray(),
        selectedOption = ReceiptSpendCategory.fromName(category),
        onOptionSelected = { onCategorySelected(it.name) },
        modifier = Modifier.fillMaxWidth(),
        optionLabel = { it.displayName }
    )
}
