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
    val prices by viewModel.prices.collectAsState()
    var context = androidx.compose.ui.platform.LocalContext.current
    while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
    val activity = context as? android.app.Activity
    val buy: (UsageTier) -> Unit = { tier -> activity?.let { viewModel.subscribe(it, tier) } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Plans and usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your plan: ${when (usage.tier) { UsageTier.FREE -> "Free"; UsageTier.PRO -> "Pro"; UsageTier.MAX -> "Max" }}", fontWeight = FontWeight.Bold)
                if (usage.unlimited) {
                    Text("Limits are not tracked here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (usage.example) Text("Test build: limits are off, so these numbers are only an example.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("Today", style = MaterialTheme.typography.labelLarge)
                    Text(usageText(usage) ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text("Your allowance refreshes on the date shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        PlanCard("Free", price = null, points = listOf("Limited trial usage of the AI features, refreshed every week"), highlighted = usage.tier == UsageTier.FREE)
        PlanCard("Pro", price = prices[UsageTier.PRO], points = listOf("${QuotaLimits.PRO_MULTIPLE}x the AI usage of Free, refreshed every day"), highlighted = usage.tier == UsageTier.PRO)
        PlanCard("Max", price = prices[UsageTier.MAX], points = listOf("${QuotaLimits.MAX_MULTIPLE_OF_PRO}x the AI usage of Pro, refreshed every day"), highlighted = usage.tier == UsageTier.MAX)

        if (usage.tier == UsageTier.FREE) {
            Button(onClick = { buy(UsageTier.PRO) }, modifier = Modifier.fillMaxWidth()) { Text("Subscribe to Pro") }
            OutlinedButton(onClick = { buy(UsageTier.MAX) }, modifier = Modifier.fillMaxWidth()) { Text("Subscribe to Max") }
            OutlinedButton(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth()) { Text("Restore purchase") }
        } else {
            if (usage.tier == UsageTier.PRO) {
                Button(onClick = { buy(UsageTier.MAX) }, modifier = Modifier.fillMaxWidth()) { Text("Upgrade to Max") }
            }
            Button(onClick = viewModel::manage, modifier = Modifier.fillMaxWidth()) { Text("Change or cancel subscription") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (viewModel.isDebugBuild) {
            Spacer(Modifier.height(8.dp))
            Text("Debug only", style = MaterialTheme.typography.labelLarge)
            Text(
                usage.real?.let { "The Worker says: ${it.tier}, ${it.percentLeft}% left" + (it.resetsOn?.let { d -> ", refreshes $d" } ?: ", period not started") }
                    ?: "No answer from the Worker yet (own Claude key saved, or nothing sent so far).",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.FREE) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Free plan") }
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.PRO) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Pro plan") }
            OutlinedButton(onClick = { viewModel.debugSetTier(UsageTier.MAX) }, modifier = Modifier.fillMaxWidth()) { Text("Show as Max plan") }
        }
    }
}

@Composable
private fun PlanCard(name: String, price: String?, points: List<String>, highlighted: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (price != null) "$name · $price / month" else name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
