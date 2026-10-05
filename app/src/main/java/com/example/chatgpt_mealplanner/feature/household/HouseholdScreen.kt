package com.example.chatgpt_mealplanner.feature.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.database.entity.PairedDeviceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HouseholdScreen(
    uiState: HouseholdUiState,
    onPairNewDeviceClicked: () -> Unit,
    onJoinHouseholdClicked: () -> Unit,
    onRequestRevocation: (PairedDeviceEntity) -> Unit,
    onConfirmRevocation: () -> Unit,
    onCancelRevocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.deviceToRevoke != null) {
        val dev = uiState.deviceToRevoke
        AlertDialog(
            onDismissRequest = onCancelRevocation,
            title = { Text("Revoke access for ${dev.deviceName}?") },
            text = {
                Text(
                    "This device will no longer be able to authenticate or sync with this household. Its local data will not be remotely erased."
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRevocation,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke Access")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRevocation) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                text = "Household Sync",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Household Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val household = uiState.householdIdentity
                    if (household != null) {
                        Text("Name: ${household.householdName}")
                        Text("Household ID: ${household.householdId}")
                        Text("This Device: Main / Authoritative Device (${household.mainDeviceId.take(8)}...)")
                    }

                    if (uiState.isFollowerPaired) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Paired as Follower with Host: ${uiState.hostIp}",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (uiState.statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.statusMessage,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (uiState.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Paired Follower Devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.pairedDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No follower devices paired yet.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onPairNewDeviceClicked) {
                            Text("Pair New Device")
                        }
                    }
                }
            }
        } else {
            items(uiState.pairedDevices, key = { it.deviceId }) { device ->
                PairedDeviceCard(
                    device = device,
                    onRevokeClicked = { onRequestRevocation(device) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onPairNewDeviceClicked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pair New Device (Generate QR Code)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onJoinHouseholdClicked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Join Household (Scan QR Code)")
            }
        }
    }
}

@Composable
fun PairedDeviceCard(
    device: PairedDeviceEntity,
    onRevokeClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Paired device ${device.deviceName}" }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = device.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val statusColor = if (device.status == "ACTIVE") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }

                Text(
                    text = device.status,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
            val dateStr = sdf.format(Date(device.pairedAt))
            Text(
                text = "Paired on: $dateStr",
                style = MaterialTheme.typography.bodySmall
            )

            if (device.status == "ACTIVE") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRevokeClicked,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke Access")
                }
            } else if (device.status == "REVOKED") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Access Revoked by Main",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
