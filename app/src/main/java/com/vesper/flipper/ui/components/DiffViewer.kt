package com.vesper.flipper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.flipper.domain.model.FileDiff
import com.vesper.flipper.domain.service.DiffLine
import com.vesper.flipper.domain.service.DiffLineType
import com.vesper.flipper.domain.service.DiffService
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Rendering cap: diffs longer than this are cut with an explicit footer. */
private const val MAX_DIFF_LINES = 400
private const val MAX_DIFF_HEIGHT_DP = 360

/**
 * Side-by-side file diff.
 *
 * At most 400 lines render, the whole block scrolls in both directions, and
 * colors come from the design tokens so light and dark themes both read.
 */
@Composable
fun DiffViewer(
    diff: FileDiff,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val diffService = remember { DiffService() }
    val allLines = remember(diff) { diffService.generateSideBySide(diff) }
    val visibleLines = remember(allLines) { allLines.take(MAX_DIFF_LINES) }

    Column(modifier = modifier) {
        DiffStatsHeader(diff)

        Spacer(Modifier.height(VesperSpace.xs))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.surface1,
            border = BorderStroke(1.dp, colors.hairlineSoft)
        ) {
            Column {
                if (visibleLines.isEmpty()) {
                    EmptyDiff()
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val longestLeft = remember(visibleLines) {
                            visibleLines.maxOfOrNull { it.originalContent?.length ?: 0 } ?: 0
                        }
                        val longestRight = remember(visibleLines) {
                            visibleLines.maxOfOrNull { it.newContent?.length ?: 0 } ?: 0
                        }
                        // Scale with the user's font size so 200 percent text is not clipped.
                        val charWidth = with(LocalDensity.current) { 12.sp.toDp() } * 0.62f
                        val viewportSide = (maxWidth - 88.dp) * 0.5f
                        val leftWidth = maxOf(viewportSide, charWidth * longestLeft.toFloat())
                            .coerceAtMost(1000.dp)
                        val rightWidth = maxOf(viewportSide, charWidth * longestRight.toFloat())
                            .coerceAtMost(1000.dp)
                        val contentWidth = leftWidth + rightWidth + 88.dp
                        val vertical = rememberScrollState()
                        val horizontal = rememberScrollState()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = MAX_DIFF_HEIGHT_DP.dp)
                                .verticalScroll(vertical)
                                .horizontalScroll(horizontal)
                        ) {
                            Column(modifier = Modifier.width(contentWidth)) {
                                visibleLines.forEach { line ->
                                    SideBySideLine(
                                        line = line,
                                        leftWidth = leftWidth,
                                        rightWidth = rightWidth
                                    )
                                }
                            }
                        }
                    }
                }
                DiffLimitFooter(showing = visibleLines.size, total = allLines.size)
            }
        }
    }
}

/**
 * Unified diff used by approvals. The first 400 lines render; the footer states
 * how many lines were hidden. Long lines scroll horizontally instead of wrapping
 * into an unreadable paragraph.
 */
@Composable
fun UnifiedDiffViewer(
    unifiedDiff: String,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val allLines = remember(unifiedDiff) {
        if (unifiedDiff.isBlank()) emptyList() else unifiedDiff.lines()
    }
    val visibleLines = remember(allLines) { allLines.take(MAX_DIFF_LINES) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.hairlineSoft)
    ) {
        Column {
            if (visibleLines.isEmpty()) {
                EmptyDiff()
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val longest = remember(visibleLines) {
                        visibleLines.maxOfOrNull { it.length } ?: 0
                    }
                    val charWidth = with(LocalDensity.current) { 12.sp.toDp() } * 0.62f
                    val contentWidth = maxOf(maxWidth, charWidth * longest.toFloat())
                    val vertical = rememberScrollState()
                    val horizontal = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = MAX_DIFF_HEIGHT_DP.dp)
                            .verticalScroll(vertical)
                            .horizontalScroll(horizontal)
                    ) {
                        Column(modifier = Modifier.width(contentWidth)) {
                            visibleLines.forEach { line ->
                                val (background, textColor) = unifiedLineColors(line)
                                Text(
                                    text = line,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(background)
                                        .padding(horizontal = VesperSpace.xs, vertical = 2.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = textColor,
                                    softWrap = false,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }
            }
            DiffLimitFooter(showing = visibleLines.size, total = allLines.size)
        }
    }
}

@Composable
private fun SideBySideLine(
    line: DiffLine,
    leftWidth: androidx.compose.ui.unit.Dp,
    rightWidth: androidx.compose.ui.unit.Dp
) {
    val colors = Vesper.colors
    val background = sideBySideBackground(line.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background),
        verticalAlignment = Alignment.Top
    ) {
        LineNumber(
            number = line.originalLineNumber,
            color = when (line.type) {
                DiffLineType.REMOVED, DiffLineType.CHANGED -> colors.riskHigh
                else -> colors.textTertiary
            }
        )
        Text(
            text = line.originalContent.orEmpty(),
            modifier = Modifier
                .width(leftWidth)
                .padding(horizontal = VesperSpace.xxs, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = when (line.type) {
                DiffLineType.REMOVED, DiffLineType.CHANGED -> colors.riskHigh
                else -> colors.textSecondary
            },
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(IntrinsicSize.Min)
                .background(colors.hairline)
        )
        LineNumber(
            number = line.newLineNumber,
            color = when (line.type) {
                DiffLineType.ADDED, DiffLineType.CHANGED -> colors.success
                else -> colors.textTertiary
            }
        )
        Text(
            text = line.newContent.orEmpty(),
            modifier = Modifier
                .width(rightWidth)
                .padding(horizontal = VesperSpace.xxs, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = when (line.type) {
                DiffLineType.ADDED, DiffLineType.CHANGED -> colors.success
                else -> colors.textSecondary
            },
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun LineNumber(number: Int?, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = number?.toString().orEmpty(),
        modifier = Modifier
            .width(32.dp)
            .padding(end = VesperSpace.xxs),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1
    )
}

@Composable
private fun DiffStatsHeader(diff: FileDiff) {
    val colors = Vesper.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Changes",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.sm)) {
            StatBadge(text = "+" + diff.linesAdded, color = colors.success)
            StatBadge(text = "-" + diff.linesRemoved, color = colors.riskHigh)
        }
    }
}

@Composable
private fun StatBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = VesperSpace.xs, vertical = VesperSpace.xxs),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun DiffLimitFooter(showing: Int, total: Int) {
    if (showing >= total) return
    val colors = Vesper.colors
    Text(
        text = "Showing " + showing + " of " + total + " lines. The rest is hidden.",
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2)
            .padding(horizontal = VesperSpace.sm, vertical = VesperSpace.xs)
            .clearAndSetSemantics {
                contentDescription = "Showing " + showing + " of " + total + " diff lines"
            },
        style = MaterialTheme.typography.labelSmall,
        color = colors.textTertiary
    )
}

@Composable
private fun EmptyDiff() {
    Text(
        text = "No changes to display",
        modifier = Modifier.padding(VesperSpace.md),
        style = MaterialTheme.typography.bodySmall,
        color = Vesper.colors.textTertiary
    )
}

@Composable
private fun unifiedLineColors(line: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val colors = Vesper.colors
    return when {
        line.startsWith("+++") || line.startsWith("---") ->
            colors.surface2 to colors.textSecondary
        line.startsWith("@@") ->
            colors.info.copy(alpha = 0.12f) to colors.info
        line.startsWith("+") ->
            colors.success.copy(alpha = 0.12f) to colors.success
        line.startsWith("-") ->
            colors.riskHigh.copy(alpha = 0.12f) to colors.riskHigh
        else ->
            androidx.compose.ui.graphics.Color.Transparent to colors.textSecondary
    }
}

@Composable
private fun sideBySideBackground(type: DiffLineType): androidx.compose.ui.graphics.Color {
    val colors = Vesper.colors
    return when (type) {
        DiffLineType.ADDED -> colors.success.copy(alpha = 0.10f)
        DiffLineType.REMOVED -> colors.riskHigh.copy(alpha = 0.10f)
        DiffLineType.CHANGED -> colors.gold.copy(alpha = 0.10f)
        DiffLineType.UNCHANGED -> androidx.compose.ui.graphics.Color.Transparent
    }
}
