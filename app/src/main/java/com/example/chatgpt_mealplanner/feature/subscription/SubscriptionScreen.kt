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
import com.example.chatgpt_mealplanner.core.quota.QuotaLimits
import com.example.chatgpt_mealplanner.core.quota.UsageTier

@Composable
fun SubscriptionScreen(modifier: Modifier = Modifier, viewModel: SubscriptionViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsState()
    val message by viewModel.message.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Plans and usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your plan: ${if (usage.tier == UsageTier.PAID) "Pro" else "Free"}", fontWeight = FontWeight.Bold)
                if (usage.unlimited) {
                    Text("Limits are not tracked here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (usage.example) Text("Test build: limits are off, so these numbers are only an example.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("Today", style = MaterialTheme.typography.labelLarge)
                    Text(usageText(usage) ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text("Limits reset every day at midnight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        PlanCard("Free", listOf("Limited trial usage of the AI features"), highlighted = usage.tier == UsageTier.FREE)
        PlanCard("Pro", listOf("${QuotaLimits.PAID_MULTIPLE}x the daily AI usage"), highlighted = usage.tier == UsageTier.PAID)

        if (usage.tier == UsageTier.FREE) {
            Button(onClick = viewModel::subscribe, modifier = Modifier.fillMaxWidth()) { Text("Subscribe to Pro") }
            OutlinedButton(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth()) { Text("Restore purchase") }
        } else {
            Button(onClick = viewModel::manage, modifier = Modifier.fillMaxWidth()) { Text("Change or cancel subscription") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (viewModel.isDebugBuild) {
            Spacer(Modifier.height(8.dp))
            Text("Debug only", style = MaterialTheme.typography.labelLarge)
            Text(
                "Real cost: spent today $" + String.format(java.util.Locale.UK, "%.4f", usage.spentMicros / 1_000_000.0) +
                    ", last reply $" + String.format(java.util.Locale.UK, "%.4f", usage.lastMicros / 1_000_000.0),
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.FREE) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Free plan") }
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.PAID) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Pro plan") }
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
