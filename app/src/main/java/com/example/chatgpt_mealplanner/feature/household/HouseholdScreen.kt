package com.example.chatgpt_mealplanner.feature.household

import androidx.compose.material3.LoadingIndicator
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
import com.example.chatgpt_mealplanner.core.database.entity.SyncConflictEntity
import com.example.chatgpt_mealplanner.core.database.entity.SyncPendingApprovalEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import com.example.chatgpt_mealplanner.core.sync.enrollment.QrCodeGenerator

@Composable
fun HouseholdScreen(
    uiState: HouseholdUiState,
    onPairNewDeviceClicked: () -> Unit,
    onJoinHouseholdClicked: () -> Unit,
    onRequestRevocation: (PairedDeviceEntity) -> Unit,
    onConfirmRevocation: () -> Unit,
    onCancelRevocation: () -> Unit,
    onSyncNowClicked: () -> Unit = {},
    onReauthorizeDevice: (String) -> Unit = {},
    onDismissReauthorizationDialog: () -> Unit = {},
    onResolveConflictKeepMain: (String) -> Unit = {},
    onResolveConflictApplyFollower: (String) -> Unit = {},
    onApprovePendingDiscard: (String) -> Unit = {},
    onRejectPendingDiscard: (String) -> Unit = {},
    onLeaveHousehold: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val confirmLeave = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (confirmLeave.value) {
        AlertDialog(
            onDismissRequest = { confirmLeave.value = false },
            title = { Text("Use this phone on its own?") },
            text = {
                Text("It stops following the main phone and keeps the pantry, plans and recipes it has now. Changes it never managed to upload stay only on this phone. You can join a household again at any time.")
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave.value = false; onLeaveHousehold() }) { Text("Use on its own") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave.value = false }) { Text("Cancel") } }
        )
    }
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

    if (uiState.activeInvitationPayloadJson != null) {
        val jsonStr = uiState.activeInvitationPayloadJson
        val qrBitmap = remember(jsonStr) {
            runCatching {
                QrCodeGenerator.generateQrBitmap(jsonStr, 512)
            }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = onDismissReauthorizationDialog,
            title = { Text("Re-authorization QR Code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Scan this QR code on the revoked follower device to re-authorize access.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Re-authorization QR Code",
                            modifier = Modifier.size(240.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissReauthorizationDialog) {
                    Text("Done")
                }
            }
        )
    }

    if (uiState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
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
                    if (uiState.appRole == com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER) {
                        Text("Role: Follower Device", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Household ID: ${uiState.pairedHouseholdId ?: "Paired Household"}")
                        if (!uiState.pairedMainDeviceId.isNullOrBlank()) {
                            Text("Main Device ID: (${uiState.pairedMainDeviceId.take(8)}...)")
                        }
                        Text("Host IP: ${uiState.hostIp ?: "Unknown"}")
                    } else {
                        val household = uiState.householdIdentity
                        if (household != null) {
                            Text("Name: ${household.householdName}")
                            Text("Household ID: ${household.householdId}")
                            Text("This Device: Main / Authoritative Device (${household.mainDeviceId.take(8)}...)")
                        }
                    }
                }
            }

            if (uiState.appRole == com.example.chatgpt_mealplanner.core.sync.AppRole.FOLLOWER) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sync Section" }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sync",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val isErrorState = uiState.isAuthError || uiState.errorMessage != null || uiState.followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.SYNC_ERROR
                        Text(
                            text = "Status: ${uiState.truthfulStatusText}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isErrorState) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        if (uiState.lastSuccessfulSyncFormatted != null) {
                            Text(
                                text = "Last successful sync: ${uiState.lastSuccessfulSyncFormatted}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (uiState.followerSyncStatus?.state == com.example.chatgpt_mealplanner.core.sync.follower.repository.FollowerSyncState.ACCESS_REVOKED) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The main phone has removed this phone from the household, so it no longer syncs. " +
                                    "To reconnect, ask the main phone for a re-authorise code and scan it under Join Household. " +
                                    "Or use this phone on its own.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { confirmLeave.value = true }) { Text("Use this phone on its own") }
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onSyncNowClicked,
                                enabled = !uiState.isSyncing
                            ) {
                                Text(if (uiState.isSyncing) "Syncing…" else "Sync now")
                            }
                        }
                    }
                }
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
                    onRevokeClicked = { onRequestRevocation(device) },
                    onReauthorizeClicked = { onReauthorizeDevice(device.deviceId) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (uiState.openApprovals.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Pending Discard Approvals",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(uiState.openApprovals, key = { it.approvalId }) { approval ->
                PendingApprovalCard(
                    approval = approval,
                    onApprove = { onApprovePendingDiscard(approval.approvalId) },
                    onReject = { onRejectPendingDiscard(approval.approvalId) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (uiState.openConflicts.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sync Conflicts",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(uiState.openConflicts, key = { it.conflictId }) { conflict ->
                SyncConflictCard(
                    conflict = conflict,
                    onKeepMain = { onResolveConflictKeepMain(conflict.conflictId) },
                    onApplyFollower = { onResolveConflictApplyFollower(conflict.conflictId) }
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
    onRevokeClicked: () -> Unit,
    onReauthorizeClicked: () -> Unit = {}
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
                val lastContact = device.lastSyncTimestamp
                val quiet = lastContact == 0L || System.currentTimeMillis() - lastContact > 24L * 60 * 60 * 1000
                Text(
                    text = if (lastContact == 0L) "Hasn't connected yet"
                    else "Last contact: ${android.text.format.DateUtils.getRelativeTimeSpanString(lastContact, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quiet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (quiet && lastContact != 0L) {
                    Text(
                        text = "Hasn't synced for over a day. Open Pantry Pal on that phone, on the same Wi-Fi as this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRevokeClicked,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke Access")
                }
            } else if (device.status == "REVOKED") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Access Revoked by Main",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onReauthorizeClicked
                ) {
                    Text("Re-authorize Device")
                }
            }
        }
    }
}

@Composable
fun PendingApprovalCard(
    approval: SyncPendingApprovalEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Destructive Removal Request",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Follower Device: ${approval.deviceId.take(8)}...")
            Text("Item ID: ${approval.targetItemId}")
            Text("Requested Action: Discard Item")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Approve Discard")
                }
                OutlinedButton(onClick = onReject) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
fun SyncConflictCard(
    conflict: SyncConflictEntity,
    onKeepMain: () -> Unit,
    onApplyFollower: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sync Revision Mismatch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Follower Device: ${conflict.deviceId.take(8)}...")
            Text("Item ID: ${conflict.targetItemId}")
            Text("Revision Mismatch: Base ${conflict.baseRevision} vs Main ${conflict.currentRevision}")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepMain) {
                    Text("Keep Main Version")
                }
                OutlinedButton(onClick = onApplyFollower) {
                    Text("Apply Follower Change")
                }
            }
        }
    }
}
