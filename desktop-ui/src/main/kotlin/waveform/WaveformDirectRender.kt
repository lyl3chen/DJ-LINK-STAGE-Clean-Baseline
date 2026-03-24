import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.max

@Composable
fun DetailWaveformDirect(
    heights: List<Int>,
    colors: List<Int>,
    progress: Float,
    zoom: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (heights.isEmpty()) return@Canvas

        val total = heights.size
        val z = zoom.coerceIn(1f, 10f)
        val windowSize = (total / z).toInt().coerceIn(16, total)
        val center = (progress.coerceIn(0f, 1f) * (total - 1)).toInt()
        val start = (center - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
        val end = (start + windowSize).coerceAtMost(total)
        val count = (end - start).coerceAtLeast(2)

        val axis = size.height / 2f
        val maxAmp = size.height * 0.47f

        val xs = FloatArray(count)
        val yTop = FloatArray(count)
        val yBottom = FloatArray(count)

        for (i in 0 until count) {
            val idx = start + i
            val x = (i.toFloat() / (count - 1).toFloat()) * size.width
            val h = heights[idx].coerceIn(0, 31)
            val amp = (h / 31f) * maxAmp
            xs[i] = x
            yTop[i] = axis - amp
            yBottom[i] = axis + amp
        }

        // 每个原始sample构成一段连续带（非统计重采样、非柱状聚合）
        for (i in 0 until count - 1) {
            val idx = start + i
            val rgb = if (colors.isNotEmpty()) {
                colors[idx.coerceIn(0, colors.lastIndex)]
            } else {
                0x6E86FF
            }
            val c = Color((((rgb and 0x00FFFFFF) or (0xFF shl 24)).toLong()))

            val seg = Path().apply {
                moveTo(xs[i], yTop[i])
                lineTo(xs[i + 1], yTop[i + 1])
                lineTo(xs[i + 1], yBottom[i + 1])
                lineTo(xs[i], yBottom[i])
                close()
            }
            drawPath(seg, color = c)
        }

        val localProgress = if (windowSize > 1) {
            ((center - start).toFloat() / (windowSize - 1).toFloat()).coerceIn(0f, 1f)
        } else 0f
        val px = localProgress * size.width
        drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.4f)
    }
}

@Composable
fun PreviewWaveformDirect(
    heights: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (heights.isEmpty()) return@Canvas

        val total = heights.size
        val axis = size.height / 2f
        val maxAmp = size.height * 0.47f

        val upper = Path()
        val lower = Path()

        for (i in 0 until total) {
            val x = if (total > 1) (i.toFloat() / (total - 1).toFloat()) * size.width else 0f
            val h = heights[i].coerceIn(0, 127)
            val amp = (h / 127f) * maxAmp
            val yt = axis - amp
            val yb = axis + amp
            if (i == 0) {
                upper.moveTo(x, yt)
                lower.moveTo(x, yb)
            } else {
                upper.lineTo(x, yt)
                lower.lineTo(x, yb)
            }
        }

        val shape = Path().apply {
            addPath(upper)
            for (i in total - 1 downTo 0) {
                val x = if (total > 1) (i.toFloat() / (total - 1).toFloat()) * size.width else 0f
                val h = heights[i].coerceIn(0, 127)
                val amp = (h / 127f) * maxAmp
                lineTo(x, axis + amp)
            }
            close()
        }

        drawPath(shape, color = Color(0xFF7EA8FF))

        val px = progress.coerceIn(0f, 1f) * size.width
        drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.2f)
    }
}
