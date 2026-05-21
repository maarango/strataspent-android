package com.strataspent.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Lightweight Canvas-based bar chart. Sized externally via [Modifier]; the
 * caller is responsible for the surrounding card/padding. Avoids pulling in
 * a charts library for the MVP.
 */
@Composable
fun BarChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val maxValue = max(1.0, data.maxOf { it.second })
        val plotHeight = size.height - 40f // leave room for axis labels
        val barCount = data.size
        val totalSpacing = 12f * (barCount + 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(2f)

        // X-axis baseline
        drawLine(
            color = axisColor,
            start = Offset(0f, plotHeight),
            end = Offset(size.width, plotHeight),
            strokeWidth = 1.5f,
        )

        val textPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        data.forEachIndexed { idx, (label, value) ->
            val height = (value / maxValue).toFloat() * (plotHeight - 8f)
            val x = 12f + idx * (barWidth + 12f)
            val y = plotHeight - height
            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
            )
            // Value above bar
            drawContext.canvas.nativeCanvas.drawText(
                "$${"%.0f".format(value)}",
                x + barWidth / 2f,
                (y - 4f).coerceAtLeast(10f),
                textPaint,
            )
            // Truncated category label below baseline
            val short = if (label.length > 8) label.take(7) + "…" else label
            drawContext.canvas.nativeCanvas.drawText(
                short,
                x + barWidth / 2f,
                plotHeight + 22f,
                textPaint,
            )
        }
    }
}

@Composable
fun LineChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primaryContainer
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier) {
        if (data.size < 2) return@Canvas
        val maxValue = max(1.0, data.maxOf { it.second })
        val plotHeight = size.height - 40f

        drawLine(
            color = axisColor,
            start = Offset(0f, plotHeight),
            end = Offset(size.width, plotHeight),
            strokeWidth = 1.5f,
        )

        val xStep = size.width / (data.size - 1).coerceAtLeast(1)
        val points = data.mapIndexed { idx, (_, value) ->
            val x = idx * xStep
            val y = plotHeight - (value / maxValue).toFloat() * (plotHeight - 8f)
            Offset(x, y)
        }

        // Soft fill underneath the line.
        val fill = Path().apply {
            moveTo(points.first().x, plotHeight)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, plotHeight)
            close()
        }
        drawPath(path = fill, color = fillColor, alpha = 0.45f)

        // Stroke.
        val stroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path = stroke, color = lineColor, style = Stroke(width = 3f))

        // First + last date labels for orientation.
        val textPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val firstDate = data.first().first.ifBlank { "" }
        val lastDate = data.last().first.ifBlank { "" }
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(firstDate, 4f, plotHeight + 22f, textPaint)
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas
            .drawText(lastDate, size.width - 4f, plotHeight + 22f, textPaint)
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
