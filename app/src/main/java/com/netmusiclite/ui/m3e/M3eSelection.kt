package com.netmusiclite.ui.m3e

import com.netmusiclite.ui.components.pressStretch

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

data class M3eSelectionAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** All callbacks come from the original selection controller; the count stays accessible. */
@Composable
fun M3eSelectionActions(count: Int, actions: List<M3eSelectionAction>, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(top = 24.dp).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("已选择 $count 首", style = MaterialTheme.typography.labelSmall)
        if (actions.size <= 3) {
            ButtonGroup(modifier = Modifier.width((actions.size * 35).dp), contentPadding = PaddingValues(0.dp), spacing = 3.dp) {
                actions.forEach { action ->
                    val source = remember { MutableInteractionSource() }
                    FilledTonalIconButton(
                        onClick = action.onClick, interactionSource = source,
                        modifier = Modifier.weight(1f).minWidth(1.dp).animateWidth(source).height(32.dp),
                    ) { Icon(action.icon, action.label, Modifier.size(17.dp)) }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                actions.forEach { action ->
                    FilledTonalIconButton(action.onClick, Modifier.size(32.dp).pressStretch()) {
                        Icon(action.icon, action.label, Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}
