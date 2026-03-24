import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.pow

@Composable
fun DetailWaveformDirect(
    envelopeMin: List<Int>,
    envelopeMax: List<Int>,
    envelopeColor: List<Int>,
    envelopeBinMs: Int,
    progress: Float,
    zoom: Float,
    bpm: Float?,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (envelopeMin.isEmpty() || envelopeMax.isEmpty() || durationMs <= 0L) return@Canvas

        val totalBins = minOf(envelopeMin.size, envelopeMax.size)
        val z = zoom.coerceIn(1f, 10f)

        // Rekordbox-like detail窗口语义：最小约12小节(48拍)，最大约3.5拍
        val bpmSafe = (bpm ?: 128f).coerceIn(30f, 300f)
        val t = ((z - 1f) / 9f).coerceIn(0f, 1f)
        val windowBeats = 48f * (3.5f / 48f).pow(t)
        val windowMs = windowBeats * (60000f / bpmSafe)

        val currentMs = (progress.coerceIn(0f, 1f) * durationMs.toFloat())
        val startMs = (currentMs - windowMs / 2f).coerceIn(0f, (durationMs - 1).toFloat())
        val endMs = (startMs + windowMs).coerceAtMost(durationMs.toFloat())

        val binMs = envelopeBinMs.coerceAtLeast(1)
        val startBin = (startMs / binMs).toInt().coerceIn(0, totalBins - 1)
        val endBin = ((endMs / binMs).toInt() + 1).coerceIn(startBin + 1, totalBins)

        val axis = size.height / 2f
        val maxAmp = size.height * 0.47f
        val w = size.width.toInt().coerceAtLeast(2)

        val top = FloatArray(w)
        val bottom = FloatArray(w)

        for (x in 0 until w) {
            val s = (startBin + (x * (endBin - startBin) / w)).coerceIn(startBin, endBin - 1)
            val e = (startBin + ((x + 1) * (endBin - startBin) / w)).coerceIn(s + 1, endBin)
            var mn = 31
            var mx = 0
            for (i in s until e) {
                val lo = envelopeMin[i].coerceIn(0, 31)
                val hi = envelopeMax[i].coerceIn(0, 31)
                if (lo < mn) mn = lo
                if (hi > mx) mx = hi
            }
            top[x] = axis - (mx / 31f) * maxAmp
            bottom[x] = axis + (mn / 31f) * maxAmp
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

        val centerBin = ((startBin + endBin) / 2).coerceIn(0, totalBins - 1)
        val rgb = if (envelopeColor.isNotEmpty()) envelopeColor[centerBin.coerceIn(0, envelopeColor.lastIndex)] else 0x6E86FF
        val c = Color((((rgb and 0x00FFFFFF) or (0xFF shl 24)).toLong()))
        drawPath(fillPath, c)

        val localProgress = if (endMs > startMs) ((currentMs - startMs) / (endMs - startMs)).coerceIn(0f, 1f) else 0f
        val px = localProgress * size.width
        drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.4f)
    }
}

@Composable
fun PreviewWaveformDirect(
    envelopeMin: List<Int>,
    envelopeMax: List<Int>,
    envelopeBinMs: Int,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (envelopeMin.isEmpty() || envelopeMax.isEmpty()) return@Canvas

        val total = minOf(envelopeMin.size, envelopeMax.size)
        val axis = size.height / 2f
        val maxAmp = size.height * 0.20f
        val w = size.width.toInt().coerceAtLeast(2)

        val top = FloatArray(w)
        val bottom = FloatArray(w)
        for (x in 0 until w) {
            val s = (x * total / w).coerceIn(0, total - 1)
            val e = ((x + 1) * total / w).coerceIn(s + 1, total)
            var mn = 127
            var mx = 0
            for (i in s until e) {
                val lo = envelopeMin[i].coerceIn(0, 127)
                val hi = envelopeMax[i].coerceIn(0, 127)
                if (lo < mn) mn = lo
                if (hi > mx) mx = hi
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
