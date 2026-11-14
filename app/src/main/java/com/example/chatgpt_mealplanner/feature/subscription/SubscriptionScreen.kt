package com.example.chatgpt_mealplanner.feature.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.quota.QuotaKind
import com.example.chatgpt_mealplanner.core.quota.QuotaLimits
import com.example.chatgpt_mealplanner.core.quota.UsageTier

@Composable
fun SubscriptionScreen(modifier: Modifier = Modifier, viewModel: SubscriptionViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsState()
    val message by viewModel.message.collectAsState()
    val free = QuotaLimits.FREE
    val paid = QuotaLimits.PAID

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Plans and usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your plan: ${if (usage.tier == UsageTier.PAID) "Plus" else "Free"}", fontWeight = FontWeight.Bold)
                if (usage.unlimited) {
                    Text("Test build: limits are switched off.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Today", style = MaterialTheme.typography.labelLarge)
                    Text(usageText(usage, QuotaKind.CHAT_MESSAGE) ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text(usageText(usage, QuotaKind.ITEM_ENTRY) ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text("Limits reset every day at midnight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        PlanCard("Free", listOf("${free.chatMessages} assistant messages a day", "${free.itemEntries} typed or bulk items a day"), highlighted = usage.tier == UsageTier.FREE)
        PlanCard("Plus", listOf("${paid.chatMessages} assistant messages a day", "${paid.itemEntries} typed or bulk items a day"), highlighted = usage.tier == UsageTier.PAID)

        Text(
            "Only things that use the AI count: assistant messages (including photos and recipe imports, and each meal of a plan) and typed or bulk item lists. " +
                "Adding and editing things by hand, scanning barcodes and everything else in the app is never limited.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (usage.tier == UsageTier.FREE) {
            Button(onClick = viewModel::subscribe, modifier = Modifier.fillMaxWidth()) { Text("Subscribe to Plus") }
        }
        OutlinedButton(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth()) { Text("Restore purchase") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (viewModel.isDebugBuild) {
            Spacer(Modifier.height(8.dp))
            Text("Debug only", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.FREE) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Free plan") }
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.PAID) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Plus plan") }
        }
    }
}

@Composable
private fun PlanCard(name: String, points: List<String>, highlighted: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
