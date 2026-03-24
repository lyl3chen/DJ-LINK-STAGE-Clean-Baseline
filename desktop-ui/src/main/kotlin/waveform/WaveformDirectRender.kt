import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.pow

@Composable
fun DetailWaveformDirect(
    heights: List<Int>,
    colors: List<Int>,
    progress: Float,
    zoom: Float,
    bpm: Float?,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (heights.isEmpty()) return@Canvas

        val total = heights.size
        val z = zoom.coerceIn(1f, 10f)

        // Rekordbox-like detail窗口语义：最小约12小节(48拍)，最大约3.5拍
        val bpmSafe = (bpm ?: 128f).coerceIn(30f, 300f)
        val t = ((z - 1f) / 9f).coerceIn(0f, 1f)
        val maxBeats = 48f
        val minBeats = 3.5f
        val windowBeats = maxBeats * (minBeats / maxBeats).pow(t)
        val windowMs = windowBeats * (60000f / bpmSafe)
        val windowRatio = if (durationMs > 0L) (windowMs / durationMs.toFloat()) else (1f / z)
        val windowSize = (total * windowRatio).toInt().coerceIn(16, total)

        val center = (progress.coerceIn(0f, 1f) * (total - 1)).toInt()
        val start = (center - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
        val end = (start + windowSize).coerceAtMost(total)
        val count = (end - start).coerceAtLeast(2)

        val axis = size.height / 2f
        val maxAmp = size.height * 0.47f

        // 逐像素列直绘：避免菱形块拼接感
        for (x in 0 until size.width.toInt().coerceAtLeast(1)) {
            val t = if (size.width > 1f) x.toFloat() / (size.width - 1f) else 0f
            val i = (t * (count - 1)).toInt().coerceIn(0, count - 1)
            val idx = start + i
            val h = heights[idx].coerceIn(0, 31)
            val amp = (h / 31f) * maxAmp
            val rgb = if (colors.isNotEmpty()) colors[idx.coerceIn(0, colors.lastIndex)] else 0x6E86FF
            val c = Color((((rgb and 0x00FFFFFF) or (0xFF shl 24)).toLong()))
            drawLine(c, Offset(x.toFloat(), axis - amp), Offset(x.toFloat(), axis + amp), 1f)
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
        val maxAmp = size.height * 0.20f // 总览保持细带

        for (x in 0 until size.width.toInt().coerceAtLeast(1)) {
            val t = if (size.width > 1f) x.toFloat() / (size.width - 1f) else 0f
            val i = (t * (total - 1)).toInt().coerceIn(0, total - 1)
            val h = heights[i].coerceIn(0, 127)
            val amp = (h / 127f) * maxAmp
            drawLine(Color(0xFF7EA8FF), Offset(x.toFloat(), axis - amp), Offset(x.toFloat(), axis + amp), 1f)
        }

        val px = progress.coerceIn(0f, 1f) * size.width
        drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.2f)
    }
}
