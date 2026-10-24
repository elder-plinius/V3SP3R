package com.vesper.flipper.ui.screen.signals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.WaveformData
import com.vesper.flipper.domain.model.WaveformSample
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Static waveform trace.
 *
 * The sample list is decimated to at most [MAX_POINTS] points once per
 * capture, and the [Path] is remembered for the measured canvas size, so
 * scrolling the detail pane never rebuilds the geometry. There is no
 * animation while the trace is off-screen because there is none at all.
 */
@Composable
fun WaveformPreview(waveform: WaveformData?, modifier: Modifier = Modifier) {
    val colors = Vesper.colors
    if (waveform == null || waveform.samples.size < 2) {
        WaveformUnavailable(modifier)
        return
    }

    val points = remember(waveform) { downsample(waveform.samples) }
    val insetPx = with(LocalDensity.current) { WAVEFORM_INSET.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val paths = remember(points, canvasSize, insetPx) {
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            null
        } else {
            buildWaveformPaths(
                points = points,
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                inset = insetPx
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface1)
            .onSizeChanged { canvasSize = it }
    ) {
        val top = insetPx
        val bottom = size.height - insetPx
        if (bottom > top) {
            repeat(GRID_ROWS + 1) { row ->
                val y = top + (bottom - top) * row / GRID_ROWS
                drawLine(
                    color = colors.hairlineSoft,
                    start = Offset(insetPx, y),
                    end = Offset(size.width - insetPx, y),
                    strokeWidth = 1f
                )
            }
        }

        val fill = paths?.second
        if (fill != null) {
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.gold.copy(alpha = 0.14f),
                        colors.gold.copy(alpha = 0.01f)
                    )
                )
            )
        }

        val trace = paths?.first
        if (trace != null) {
            drawPath(
                path = trace,
                color = colors.gold,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/** Static placeholder while the samples are being prepared. */
@Composable
fun WaveformLoading(modifier: Modifier = Modifier) {
    val colors = Vesper.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface1),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Reading waveform",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
    }
}

@Composable
private fun WaveformUnavailable(modifier: Modifier = Modifier) {
    val colors = Vesper.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surface1)
            .padding(horizontal = VesperSpace.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No waveform is stored for this capture.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
    }
}

private fun buildWaveformPaths(
    points: List<Offset>,
    width: Float,
    height: Float,
    inset: Float
): Pair<Path, Path> {
    val drawWidth = (width - inset * 2f).coerceAtLeast(1f)
    val drawHeight = (height - inset * 2f).coerceAtLeast(1f)
    val trace = Path()
    points.forEachIndexed { index, point ->
        val x = inset + point.x * drawWidth
        val y = inset + point.y * drawHeight
        if (index == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
    }
    val fill = Path().apply {
        addPath(trace)
        lineTo(inset + drawWidth, inset + drawHeight)
        lineTo(inset, inset + drawHeight)
        close()
    }
    return trace to fill
}

private fun downsample(
    samples: List<WaveformSample>,
    maxPoints: Int = MAX_POINTS
): List<Offset> {
    if (samples.isEmpty()) return emptyList()
    val duration = samples.last().time.takeIf { it > 0f } ?: 1f
    val stride = ((samples.size + maxPoints - 2) / (maxPoints - 1)).coerceAtLeast(1)
    val points = ArrayList<Offset>(minOf(samples.size, maxPoints))

    var index = 0
    while (index < samples.size) {
        val sample = samples[index]
        points += Offset(
            x = (sample.time / duration).coerceIn(0f, 1f),
            y = (1f - sample.value).coerceIn(0f, 1f)
        )
        index += stride
    }

    val last = samples.last()
    val lastPoint = Offset(x = 1f, y = (1f - last.value).coerceIn(0f, 1f))
    if (points.last() != lastPoint) points += lastPoint
    return points
}

private const val MAX_POINTS = 600
private const val GRID_ROWS = 4
private val WAVEFORM_HEIGHT = 140.dp
private val WAVEFORM_INSET = 10.dp
