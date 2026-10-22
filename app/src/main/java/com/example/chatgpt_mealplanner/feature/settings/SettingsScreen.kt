package com.example.chatgpt_mealplanner.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.ai.provider.GEMINI_MODEL_ID
import com.example.chatgpt_mealplanner.retail.contract.RetailerId
import com.example.chatgpt_mealplanner.retail.contract.RetailStoreFormat

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val createDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        viewModel.exportInventoryToUri(context, uri)
    }

    val createBackupDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        viewModel.onBackupDestinationSelected(uri)
    }

    val uiState by viewModel.uiState.collectAsState()
    var apiKeyInput by remember { mutableStateOf("") }
    var storeNameInput by remember { mutableStateOf("") }
    var storePostcodeInput by remember { mutableStateOf("") }
    var storeSearchPostcodeInput by remember { mutableStateOf("") }
    var storeDirectoryApiKeyInput by remember { mutableStateOf("") }
    var selectedStoreRetailer by remember { mutableStateOf(RetailerId.ASDA) }
    var selectedStoreFormat by remember { mutableStateOf(RetailStoreFormat.ASDA_SUPERMARKET) }
    var retailerMenuExpanded by remember { mutableStateOf(false) }
    var formatMenuExpanded by remember { mutableStateOf(false) }
    var branchRadiusMenuExpanded by remember { mutableStateOf(false) }
    var showClearChatHistoryDialog by remember { mutableStateOf(false) }
    var showStoreDataSourcesDialog by remember { mutableStateOf(false) }

    if (showStoreDataSourcesDialog) {
        AlertDialog(
            onDismissRequest = { showStoreDataSourcesDialog = false },
            title = { Text("Store search data sources") },
            text = {
                Text("Postcodes are resolved with Nominatim. Branch listings come from OpenStreetMap. OpenStreetMap data is © OpenStreetMap contributors and is available under the Open Database License.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStoreDataSourcesDialog = false
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.openstreetmap.org/copyright")
                                )
                            )
                        }
                    }
                ) { Text("View attribution and licence") }
            },
            dismissButton = {
                TextButton(onClick = { showStoreDataSourcesDialog = false }) { Text("Close") }
            }
        )
    }

    if (showClearChatHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatHistoryDialog = false },
            title = { Text("Clear chat history?") },
            text = { Text("This permanently deletes chat conversations on this device. Inventory and meal plans are not cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearChatHistoryDialog = false
                    viewModel.clearChatHistory()
                }) {
                    Text("Clear history")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.isPassphraseDialogVisible) {
        EncryptedBackupPassphraseDialog(
            onDismiss = { viewModel.dismissPassphraseDialog() },
            onConfirm = { passChars ->
                viewModel.executeEncryptedBackup(context, passChars)
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // AI SECTION
        Text("AI Configuration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Model: $GEMINI_MODEL_ID (Direct Google Gemini API)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Gemini API Key", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                if (uiState.isApiKeyConfigured) {
                    Text("Status: Configured securely.", color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.deleteApiKey() }) {
                        Text("Delete API Key")
                    }
                } else {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("AIza...") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.saveApiKey(apiKeyInput)
                            apiKeyInput = ""
                        },
                        enabled = apiKeyInput.isNotBlank()
                    ) {
                        Text("Save Key")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Your Gemini API key is stored securely on this device and used to make requests directly to Google Gemini API. Only necessary pantry context is sent when invoking AI features.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.testAiConnection() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Connection")
        }
        if (uiState.connectionTestResult != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Result: ${uiState.connectionTestResult}", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Overpass.ch API Key", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Used for nearby store branch searches. The key is stored encrypted on this device. Postcode lookup still uses OpenStreetMap search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (uiState.isStoreDirectoryApiKeyConfigured) {
                    Text("Status: Configured securely.", color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.deleteStoreDirectoryApiKey() }) {
                        Text("Delete API Key")
                    }
                } else {
                    OutlinedTextField(
                        value = storeDirectoryApiKeyInput,
                        onValueChange = { storeDirectoryApiKeyInput = it },
                        label = { Text("Overpass.ch API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.saveStoreDirectoryApiKey(storeDirectoryApiKeyInput)
                            storeDirectoryApiKeyInput = ""
                        },
                        enabled = storeDirectoryApiKeyInput.isNotBlank()
                    ) {
                        Text("Save Key")
                    }
                }
                if (uiState.storeDirectoryApiKeyMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.storeDirectoryApiKeyMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // RETAIL SECTION
        Text("Retail & Shopping", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Basket comparisons use retailers for which you have saved preferred branches. Add or remove branches below to control which retailers participate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))


        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Preferred Store Locations", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Save branches you use. Live catalogue search is available for Iceland, Aldi, Tesco, Morrisons, Sainsbury's and Lidl; Food Warehouse shares Iceland's catalogue, while ASDA live lookup is disabled pending an approved source. Catalogue prices are not proof of a branch's price or stock, and selected-branch stock is not yet integrated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("Find a branch", fontWeight = FontWeight.SemiBold)
                Text(
                    "Searches once for all supported retailers near the postcode. Each result is labelled by retailer; results can be incomplete, so verify before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = storeSearchPostcodeInput,
                    onValueChange = { storeSearchPostcodeInput = it },
                    label = { Text("Search postcode") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Search distance",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { branchRadiusMenuExpanded = true }) {
                        Text("${uiState.branchSearchRadiusMeters / 1000} km")
                    }
                    DropdownMenu(
                        expanded = branchRadiusMenuExpanded,
                        onDismissRequest = { branchRadiusMenuExpanded = false }
                    ) {
                        listOf(1_000, 3_000, 5_000, 10_000, 15_000).forEach { radiusMeters ->
                            DropdownMenuItem(
                                text = { Text("${radiusMeters / 1000} km") },
                                onClick = {
                                    viewModel.setBranchSearchRadiusMeters(radiusMeters)
                                    branchRadiusMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Choose how far away branches can be: 1, 3, 5, 10 or 15 km.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { viewModel.searchStoreDirectory(storeSearchPostcodeInput) },
                    enabled = storeSearchPostcodeInput.isNotBlank() && !uiState.isStoreSearchInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isStoreSearchInProgress) "Searching…" else "Find branches")
                }
                if (uiState.storeSearchMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(uiState.storeSearchMessage!!, style = MaterialTheme.typography.bodySmall)
                }
                uiState.discoveredStores.forEach { store ->
                    val alreadyAdded = isDirectoryStoreAlreadySaved(store, uiState.preferredStores)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${retailerLabel(store.retailerId)} · ${store.name}", fontWeight = FontWeight.SemiBold)
                            if (store.address.isNotBlank()) {
                                Text(
                                    store.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                listOfNotNull(
                                    store.postcode?.takeIf { it.isNotBlank() },
                                    storeDistanceLabel(store.distanceMeters)
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Directory listing only; local prices and stock are not verified.", style = MaterialTheme.typography.bodySmall)
                            if (alreadyAdded) {
                                Text(
                                    "Already added to preferred locations",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Button(
                                onClick = { viewModel.addDiscoveredStore(store) },
                                enabled = !alreadyAdded
                            ) { Text(if (alreadyAdded) "Already added" else "Add branch") }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                TextButton(
                    onClick = { showStoreDataSourcesDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Data source details")
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = storeNameInput,
                    onValueChange = { storeNameInput = it },
                    label = { Text("Branch name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = storePostcodeInput,
                    onValueChange = { storePostcodeInput = it },
                    label = { Text("Postcode") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { retailerMenuExpanded = true }) {
                        Text("Manual retailer: ${retailerLabel(selectedStoreRetailer)}")
                    }
                    DropdownMenu(expanded = retailerMenuExpanded, onDismissRequest = { retailerMenuExpanded = false }) {
                        RetailerId.entries.filter { it != RetailerId.FOOD_WAREHOUSE }.forEach { retailer ->
                            DropdownMenuItem(
                                text = { Text(retailerLabel(retailer)) },
                                onClick = {
                                    selectedStoreRetailer = retailer
                                    selectedStoreFormat = storeFormatsFor(retailer).first()
                                    retailerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { formatMenuExpanded = true }) {
                        Text("Format: ${formatLabel(selectedStoreFormat)}")
                    }
                    DropdownMenu(expanded = formatMenuExpanded, onDismissRequest = { formatMenuExpanded = false }) {
                        storeFormatsFor(selectedStoreRetailer).forEach { format ->
                            DropdownMenuItem(
                                text = { Text(formatLabel(format)) },
                                onClick = { selectedStoreFormat = format; formatMenuExpanded = false }
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        viewModel.addPreferredStore(selectedStoreRetailer, storeNameInput, storePostcodeInput, selectedStoreFormat)
                        storeNameInput = ""
                        storePostcodeInput = ""
                    },
                    enabled = storeNameInput.isNotBlank() && storePostcodeInput.isNotBlank()
                ) { Text("Add Store") }

                uiState.preferredStores.forEach { store ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = store.isPreferred,
                            onCheckedChange = { viewModel.setPreferredStore(store.id, it) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${retailerLabel(store.retailerId)} · ${store.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(store.postcode.takeIf { it.isNotBlank() }, store.address?.takeIf { it.isNotBlank() }, formatLabel(store.format)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { viewModel.removePreferredStore(store.id) }) { Text("Remove") }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // DATA SECTION
        Text("Data Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Household sync and device coordination are managed from the Household menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showClearChatHistoryDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Chat History")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                        val suggestedFileName = "pantry_inventory_export_$timestamp.json"
                        createDocumentLauncher.launch(suggestedFileName)
                    },
                    enabled = uiState.isMainRole,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Inventory (JSON)")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.isMainRole) {
                        "Saves current inventory items as a JSON document to a location you choose. This is an inventory export, not a full household backup."
                    } else {
                        "Inventory export is available on the Main device only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.exportMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uiState.exportMessage!!, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                        val suggestedFileName = "pantry_household_backup_$timestamp.pantryenc"
                        createBackupDocumentLauncher.launch(suggestedFileName)
                    },
                    enabled = uiState.isMainRole && !uiState.isBackupInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isBackupInProgress) "Encrypting Backup..." else "Save Encrypted Household Backup")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.isMainRole) {
                        "Saves a passphrase-protected, encrypted backup of all household items, staples, categories, and canonical sync operations to a location you choose."
                    } else {
                        "Household backup is available on the Main device only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.backupStatusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uiState.backupStatusMessage!!, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun retailerLabel(retailerId: RetailerId): String = when (retailerId) {
    RetailerId.ASDA -> "ASDA"
    RetailerId.ICELAND -> "Iceland"
    RetailerId.FOOD_WAREHOUSE -> "Iceland"
    RetailerId.TESCO -> "Tesco"
    RetailerId.MORRISONS -> "Morrisons"
    RetailerId.SAINSBURYS -> "Sainsbury's"
    RetailerId.ALDI -> "Aldi"
    RetailerId.LIDL -> "Lidl"
}

private fun storeDistanceLabel(meters: Int?): String = when {
    meters == null -> "Distance unavailable"
    meters < 1000 -> "$meters m away"
    else -> String.format(java.util.Locale.US, "%.1f km away", meters / 1000.0)
}

private fun formatLabel(format: RetailStoreFormat): String = when (format) {
    RetailStoreFormat.ASDA_EXPRESS -> "Asda Express"
    RetailStoreFormat.ASDA_SUPERMARKET -> "ASDA Supermarket"
    RetailStoreFormat.ASDA_SUPERSTORE -> "ASDA Superstore"
    RetailStoreFormat.ASDA_SUPERCENTRE -> "ASDA Supercentre"
    RetailStoreFormat.TESCO_EXPRESS -> "Tesco Express"
    RetailStoreFormat.TESCO_METRO -> "Tesco Metro"
    RetailStoreFormat.TESCO_SUPERMARKET -> "Tesco Supermarket"
    RetailStoreFormat.TESCO_SUPERSTORE -> "Tesco Superstore"
    RetailStoreFormat.TESCO_EXTRA -> "Tesco Extra"
    RetailStoreFormat.MORRISONS_DAILY -> "Morrisons Daily"
    RetailStoreFormat.MORRISONS_SUPERMARKET -> "Morrisons supermarket"
    RetailStoreFormat.SAINSBURYS_LOCAL -> "Sainsbury's Local"
    RetailStoreFormat.SAINSBURYS_SUPERMARKET -> "Sainsbury's supermarket"
    RetailStoreFormat.SAINSBURYS_SUPERSTORE -> "Sainsbury's superstore"
    RetailStoreFormat.ICELAND -> "Iceland"
    RetailStoreFormat.FOOD_WAREHOUSE -> "The Food Warehouse"
    RetailStoreFormat.UNKNOWN -> "Unknown format"
}

private fun storeFormatsFor(retailerId: RetailerId): List<RetailStoreFormat> = when (retailerId) {
    RetailerId.ASDA -> listOf(RetailStoreFormat.ASDA_EXPRESS, RetailStoreFormat.ASDA_SUPERMARKET, RetailStoreFormat.ASDA_SUPERSTORE, RetailStoreFormat.ASDA_SUPERCENTRE)
    RetailerId.ICELAND -> listOf(RetailStoreFormat.ICELAND)
    RetailerId.FOOD_WAREHOUSE -> listOf(RetailStoreFormat.FOOD_WAREHOUSE)
    RetailerId.TESCO -> listOf(RetailStoreFormat.TESCO_EXPRESS, RetailStoreFormat.TESCO_METRO, RetailStoreFormat.TESCO_SUPERMARKET, RetailStoreFormat.TESCO_SUPERSTORE, RetailStoreFormat.TESCO_EXTRA)
    RetailerId.MORRISONS -> listOf(RetailStoreFormat.MORRISONS_DAILY, RetailStoreFormat.MORRISONS_SUPERMARKET)
    RetailerId.SAINSBURYS -> listOf(RetailStoreFormat.SAINSBURYS_LOCAL, RetailStoreFormat.SAINSBURYS_SUPERMARKET, RetailStoreFormat.SAINSBURYS_SUPERSTORE)
    RetailerId.ALDI, RetailerId.LIDL -> listOf(RetailStoreFormat.UNKNOWN)
}

@Composable
fun EncryptedBackupPassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var passphraseInput by remember { mutableStateOf("") }
    var confirmPassphraseInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Backup Passphrase", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Create a strong passphrase to encrypt your household backup archive.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = {
                        passphraseInput = it
                        errorMessage = null
                    },
                    label = { Text("Backup Passphrase (min 8 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirmPassphraseInput,
                    onValueChange = {
                        confirmPassphraseInput = it
                        errorMessage = null
                    },
                    label = { Text("Confirm Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "IMPORTANT: Remember this passphrase! If lost or forgotten, your encrypted backup CANNOT be recovered or decrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (passphraseInput.length < 8) {
                        errorMessage = "Passphrase must be at least 8 characters long."
                    } else if (passphraseInput != confirmPassphraseInput) {
                        errorMessage = "Passphrases do not match."
                    } else {
                        val passChars = passphraseInput.toCharArray()
                        passphraseInput = ""
                        confirmPassphraseInput = ""
                        onConfirm(passChars)
                    }
                }
            ) {
                Text("Encrypt & Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    passphraseInput = ""
                    confirmPassphraseInput = ""
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}
