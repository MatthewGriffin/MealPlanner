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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.ai.provider.GEMINI_MODEL_ID

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
    var userShopNameInput by remember { mutableStateOf("") }
    var showClearChatHistoryDialog by remember { mutableStateOf(false) }

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

                if (uiState.credentialStorageError != null) {
                    Text(
                        uiState.credentialStorageError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Enter your Gemini API key again to replace the unreadable saved key. This replaces only the Gemini API key stored on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                            viewModel.replaceApiKey(apiKeyInput)
                            apiKeyInput = ""
                        },
                        enabled = apiKeyInput.isNotBlank()
                    ) {
                        Text("Replace API Key")
                    }
                } else if (uiState.isApiKeyConfigured) {
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
            enabled = !uiState.isConnectionTestInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isConnectionTestInProgress) "Testing Connection..." else "Test Connection")
        }
        if (uiState.connectionTestResult != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Result: ${uiState.connectionTestResult}", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // RETAIL SECTION
        Text("Retail & Shopping", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Purchase history entered when scanning products helps compare prices between your shops and determine where items are best to buy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))


        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("My Shops & Purchase Prices", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Add any shop you buy from, including independent and convenience stores. These shop names help organise your purchase history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = userShopNameInput,
                    onValueChange = { userShopNameInput = it },
                    label = { Text("Shop name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.addUserStore(userShopNameInput)
                        userShopNameInput = ""
                    },
                    enabled = userShopNameInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add Shop") }

                if (uiState.userStores.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No shops added yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    uiState.userStores.forEach { store ->
                        var editedStoreName by remember(store.id, store.name) { mutableStateOf(store.name) }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = editedStoreName,
                                onValueChange = { editedStoreName = it },
                                label = { Text("Shop name") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                TextButton(
                                    onClick = { viewModel.renameUserStore(store.id, editedStoreName) },
                                    enabled = editedStoreName.isNotBlank() && editedStoreName.trim() != store.name
                                ) { Text("Rename") }
                                TextButton(
                                    onClick = { viewModel.archiveUserStore(store.id) }
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
                if (uiState.syncStatusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.syncStatusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Purchase History", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Purchase history entered when scanning products helps compare prices between your shops and determine where items are best to buy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.recentPriceHistory.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Recent recorded prices", fontWeight = FontWeight.SemiBold)
                    uiState.recentPriceHistory.take(10).forEach { entry ->
                        val observation = entry.observation
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(observation.productName, fontWeight = FontWeight.Medium)
                                Text(
                                    "${entry.storeName} · ${SimpleDateFormat("dd MMM yyyy", Locale.UK).format(Date(observation.purchasedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val packDescription = listOfNotNull(
                                    observation.packCount?.let { "${it} pack" },
                                    observation.packQuantity?.let { qty -> "${qty} ${observation.packUnit.orEmpty()}".trim() }
                                ).joinToString(" · ")
                                if (packDescription.isNotBlank()) {
                                    Text(packDescription, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                "${observation.currencyCode} ${"%.2f".format(observation.priceMinorUnits / 100.0)}",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
