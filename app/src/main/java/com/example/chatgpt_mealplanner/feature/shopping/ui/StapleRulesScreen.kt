package com.example.chatgpt_mealplanner.feature.shopping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chatgpt_mealplanner.core.model.StapleRule
import com.example.chatgpt_mealplanner.core.model.StapleRuleMode

@Composable
fun StapleRulesScreen(
    stapleRules: List<StapleRule>,
    onSaveRule: (StapleRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stapleRules.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No configured staple rules",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Staple rules automatically keep essential pantry items restocked when post-plan inventory drops below a threshold.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(stapleRules, key = { it.id }) { rule ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))

                            val ruleDetails = if (rule.mode == StapleRuleMode.QUANTITY) {
                                "Threshold: ${rule.thresholdQuantity ?: 0.0} ${rule.thresholdUnit?.name ?: ""} • Target: ${rule.targetQuantity ?: 0.0} ${rule.targetUnit?.name ?: ""}"
                            } else {
                                "Threshold: ${rule.thresholdPackCount ?: 1} packs • Target: ${rule.targetPackCount ?: 4} packs"
                            }

                            Text(
                                text = ruleDetails,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onSaveRule(rule.copy(enabled = it)) }
                        )

                        IconButton(onClick = { onDeleteRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Rule")
                        }
                    }
                }
            }
        }
    }
}
