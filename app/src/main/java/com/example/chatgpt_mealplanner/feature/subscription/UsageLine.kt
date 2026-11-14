package com.example.chatgpt_mealplanner.feature.subscription

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.quota.UsageSnapshot
import com.example.chatgpt_mealplanner.core.quota.UsageTier

/** Plain-text summary used by the screens: "AI usage left this week: 70% · refreshes Mon 20 Nov". Null when usage is not tracked. */
fun usageText(usage: UsageSnapshot): String? {
    if (usage.unlimited) return null
    val period = if (usage.tier == UsageTier.FREE) "this week" else "today"
    val refresh = usage.resetsOn?.let { " · refreshes ${com.example.chatgpt_mealplanner.core.quota.UsageQuota.formatDate(it)}" } ?: ""
    return "AI usage left $period: ${usage.percentLeft}%" + refresh + if (usage.example) " (example)" else ""
}

/** One line showing what is left today; tapping it opens the plans screen. Manual adds and edits are never limited. */
@Composable
fun UsageLine(usage: UsageSnapshot, onOpenPlans: (() -> Unit)?, modifier: Modifier = Modifier) {
    val text = usageText(usage) ?: return
    val empty = usage.percentLeft == 0 && !usage.example
    Row(modifier = modifier.fillMaxWidth().let { m -> if (onOpenPlans != null) m.clickable { onOpenPlans() } else m }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text + if (usage.tier == UsageTier.FREE && onOpenPlans != null) " · Get more" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (empty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
