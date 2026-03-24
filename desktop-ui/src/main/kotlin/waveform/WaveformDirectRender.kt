import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
        val w = size.width.toInt().coerceAtLeast(2)

        // 每像素列构建 min/max envelope（连续上下包络）
        val top = FloatArray(w)
        val bottom = FloatArray(w)

        for (x in 0 until w) {
            val segStart = start + (x * count / w)
            val segEnd = (start + ((x + 1) * count / w)).coerceAtMost(end).coerceAtLeast(segStart + 1)
            var mn = 31
            var mx = 0
            for (i in segStart until segEnd) {
                val v = heights[i].coerceIn(0, 31)
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val ampTop = (mx / 31f) * maxAmp
            val ampBottom = (mn / 31f) * maxAmp
            top[x] = axis - ampTop
            bottom[x] = axis + ampBottom
        }

        val upPath = Path().apply {
            moveTo(0f, top[0])
            for (x in 1 until w) lineTo(x.toFloat(), top[x])
        }
        val fillPath = Path().apply {
            addPath(upPath)
            for (x in (w - 1) downTo 0) lineTo(x.toFloat(), bottom[x])
            close()
        }

        // 颜色仍按当前窗口中心列直取（本轮只做形态）
        val colorIdx = (start + count / 2).coerceIn(0, colors.lastIndex.coerceAtLeast(0))
        val rgb = if (colors.isNotEmpty()) colors[colorIdx] else 0x6E86FF
        val c = Color((((rgb and 0x00FFFFFF) or (0xFF shl 24)).toLong()))
        drawPath(fillPath, c)

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
        val w = size.width.toInt().coerceAtLeast(2)

        // preview 使用长程 envelope（每像素 min/max）
        val top = FloatArray(w)
        val bottom = FloatArray(w)
        for (x in 0 until w) {
            val s = (x * total / w).coerceIn(0, total - 1)
            val e = ((x + 1) * total / w).coerceIn(s + 1, total)
            var mn = 127
            var mx = 0
            for (i in s until e) {
                val v = heights[i].coerceIn(0, 127)
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            top[x] = axis - (mx / 127f) * maxAmp
            bottom[x] = axis + (mn / 127f) * maxAmp
        }

        val upPath = Path().apply {
            moveTo(0f, top[0])
            for (x in 1 until w) lineTo(x.toFloat(), top[x])
        }
        val fillPath = Path().apply {
            addPath(upPath)
            for (x in (w - 1) downTo 0) lineTo(x.toFloat(), bottom[x])
            close()
        }
        drawPath(fillPath, Color(0xFF7EA8FF))

        val px = progress.coerceIn(0f, 1f) * size.width
        drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.2f)
    }
}
