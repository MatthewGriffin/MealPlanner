package com.example.chatgpt_mealplanner.feature.household

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HouseholdScreen(
    uiState: HouseholdUiState,
    onPairNewDeviceClicked: () -> Unit,
    onJoinHouseholdClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                    Text("Main Device ID: ${household.mainDeviceId}")
                } else {
                    Text("Loading household identity...")
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

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onPairNewDeviceClicked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pair New Device (Generate QR Code)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onJoinHouseholdClicked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Household (Scan QR Code)")
        }
    }
}
