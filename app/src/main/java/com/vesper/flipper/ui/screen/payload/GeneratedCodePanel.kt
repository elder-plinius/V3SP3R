package com.vesper.flipper.ui.screen.payload

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Generated output preview: monospace, line count, and copy.
 * The block scrolls on its own so long pages do not push the save action away.
 */
@Composable
fun GeneratedCodePanel(
    label: String,
    code: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val lines = if (code.isBlank()) 0 else code.lines().size
    val shape = MaterialTheme.shapes.small

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PayloadGroupLabel(label)
            Spacer(Modifier.weight(1f))
            Text(
                text = lines.toString() + " lines",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary
            )
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy the generated code",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(shape)
                .background(colors.surface1)
                .border(1.dp, colors.hairlineSoft, shape)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(VesperSpace.sm)
            )
        }
    }
}
