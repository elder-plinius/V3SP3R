package com.vesper.flipper.ui.screen.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Path crumbs. Every crumb except the current one is a 48dp touch target,
 * so a deep folder is one tap away instead of one back press at a time.
 */
@Composable
fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val segments = remember(currentPath) { breadcrumbSegments(currentPath) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = VesperSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
    ) {
        segments.forEachIndexed { index, segment ->
            val isCurrent = index == segments.lastIndex
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isCurrent) {
                            Modifier
                        } else {
                            Modifier.clickable(role = Role.Button) { onNavigate(segment.second) }
                        }
                    )
                    .padding(horizontal = VesperSpace.xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = segment.first,
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    color = if (isCurrent) colors.textPrimary else colors.rose,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isCurrent) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textTertiary
                )
            }
        }
    }
}

private fun breadcrumbSegments(path: String): List<Pair<String, String>> {
    val parts = path.trim('/').split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return listOf("Root" to "/")
    val segments = mutableListOf<Pair<String, String>>()
    var accumulated = ""
    parts.forEachIndexed { index, part ->
        accumulated += "/" + part
        val label = when {
            index == 0 && part == "ext" -> "SD card"
            index == 0 && part == "int" -> "Internal"
            else -> part
        }
        segments += label to accumulated
    }
    return segments
}
