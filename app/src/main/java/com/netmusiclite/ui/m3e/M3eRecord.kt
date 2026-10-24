package com.netmusiclite.ui.m3e

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*

/** Selection follows the displayed period; an inactive period stays visually secondary. */
@Composable
internal fun M3eRecordPeriodSwitch(type: Int, height: Dp = 32.dp, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    M3eTransformingItem(Modifier.fillMaxWidth()) {
        ButtonGroup(contentPadding = PaddingValues(), spacing = 16.dp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            listOf(1 to "本周", 0 to "全部时间").forEach { (period, label) ->
                val active = type == period
                Button(
                    onClick = { onSelect(period) },
                    modifier = Modifier.weight(1f).height(height).testTag("record_period_$period")
                        .semantics { selected = active; role = Role.Tab },
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) colors.primary else colors.surfaceContainer,
                        contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = .6f),
                    ),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
    }
}
