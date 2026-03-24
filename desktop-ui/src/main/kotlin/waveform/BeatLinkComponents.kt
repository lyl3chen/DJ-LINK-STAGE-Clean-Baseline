import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.Base64
import kotlin.math.max

/**
 * 服务端独占 beat-link；桌面端只消费服务端输出的 beat-link 原生 getData() 数据。
 * sampleHeights/previewSample 仅作为兼容兜底，不是主路径。
 */

private data class WavePoint(val h: Int, val color: Color)

@Composable
fun BeatLinkDetailWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val style = player.detailRawStyle ?: if (player.detailRawIsColor) "RGB" else "BLUE"
    val points = decodeDetailPoints(player.detailRawBase64, style)
        ?: decodeDetailFallback(player.detailSampleHeights, player.detailSampleColors)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05080C))
    ) {
        if (points.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val n = points.size
        val step = w / max(1, n)
        val mid = h / 2f

        for (i in 0 until n) {
            val p = points[i]
            val amp = (p.h.coerceIn(0, 31) / 31f) * (h * 0.48f)
            val x = i * step
            drawLine(
                color = p.color,
                start = Offset(x, mid - amp),
                end = Offset(x, mid + amp),
                strokeWidth = max(1f, step * 0.9f)
            )
        }
    }
}

@Composable
fun BeatLinkPreviewWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val style = player.previewRawStyle ?: if (player.previewRawIsColor) "RGB" else "BLUE"
    val points = decodePreviewPoints(player.previewRawBase64, style)
        ?: player.previewSample.map { WavePoint(it.coerceIn(0, 31), Color(0xFF81D4FA)) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05080C))
    ) {
        if (points.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val n = points.size
        val step = w / max(1, n)
        val bottom = h

        for (i in 0 until n) {
            val p = points[i]
            val amp = (p.h.coerceIn(0, 31) / 31f) * (h * 0.95f)
            val x = i * step
            drawLine(
                color = p.color,
                start = Offset(x, bottom),
                end = Offset(x, bottom - amp),
                strokeWidth = max(1f, step * 0.9f)
            )
        }
    }
}

private fun decodePreviewPoints(base64: String?, style: String): List<WavePoint>? {
    val raw = decodeBase64(base64) ?: return null
    val out = ArrayList<WavePoint>()
    when (style.uppercase()) {
        "RGB" -> {
            val n = raw.size / 6
            for (i in 0 until n) {
                val b = i * 6
                val r = raw[b + 3].toUByte().toInt()
                val g = raw[b + 4].toUByte().toInt()
                val bl = raw[b + 5].toUByte().toInt()
                val h = max(r, max(g, bl)).coerceIn(0, 31)
                val rr = (r * 255 / max(1, h)).coerceIn(0, 255)
                val gg = (g * 255 / max(1, h)).coerceIn(0, 255)
                val bb = (bl * 255 / max(1, h)).coerceIn(0, 255)
                out += WavePoint(h, Color(rr, gg, bb))
            }
        }
        "THREE_BAND" -> {
            val n = raw.size / 3
            for (i in 0 until n) {
                val b = i * 3
                val low = raw[b + 2].toUByte().toInt() * 0.49f
                val mid = raw[b].toUByte().toInt() * 0.32f
                val high = raw[b + 1].toUByte().toInt() * 0.25f
                out += WavePoint((low + mid + high).toInt().coerceIn(0, 31), Color(0xFFF2AA3C))
            }
        }
        else -> {
            val n = raw.size / 2
            for (i in 0 until n) {
                val b = i * 2
                val h = (raw[b].toInt() and 0x1f)
                val intense = (raw[b + 1].toInt() and 0x07) >= 5
                out += WavePoint(h, if (intense) Color(0xFF74F6F4) else Color(0xFF2B59FF))
            }
        }
    }
    return out
}

private fun decodeDetailPoints(base64: String?, style: String): List<WavePoint>? {
    val raw = decodeBase64(base64) ?: return null
    val out = ArrayList<WavePoint>()
    when (style.uppercase()) {
        "RGB" -> {
            val n = raw.size / 2
            for (i in 0 until n) {
                val b = i * 2
                val bits = (raw[b].toUByte().toInt() shl 8) or raw[b + 1].toUByte().toInt()
                val h = ((bits shr 2) and 0x1f)
                val r = ((bits shr 13) and 0x07) * 255 / 7
                val g = ((bits shr 10) and 0x07) * 255 / 7
                val bl = ((bits shr 7) and 0x07) * 255 / 7
                out += WavePoint(h, Color(r, bl, g))
            }
        }
        "THREE_BAND" -> {
            val n = raw.size / 3
            for (i in 0 until n) {
                val b = i * 3
                val low = raw[b + 2].toUByte().toInt() * 0.4f
                val mid = raw[b].toUByte().toInt() * 0.3f
                val high = raw[b + 1].toUByte().toInt() * 0.06f
                out += WavePoint((low + mid + high).toInt().coerceIn(0, 31), Color(0xFF90CAF9))
            }
        }
        else -> {
            for (v in raw) {
                val h = v.toInt() and 0x1f
                val intensity = (v.toInt() and 0xe0) ushr 5
                val c = when (intensity.coerceIn(0, 7)) {
                    0 -> Color(0xFF006890)
                    1 -> Color(0xFF0088B0)
                    2 -> Color(0xFF00A8E8)
                    3 -> Color(0xFF00B8D8)
                    4 -> Color(0xFF78B8D8)
                    5, 6 -> Color(0xFF88C0E8)
                    else -> Color(0xFFC8E0E8)
                }
                out += WavePoint(h, c)
            }
        }
    }
    return out
}

private fun decodeDetailFallback(heights: List<Int>, colors: List<Int>): List<WavePoint> =
    heights.mapIndexed { i, h -> WavePoint(h.coerceIn(0, 31), colors.getOrNull(i)?.let { Color(it) } ?: Color(0xFF4FC3F7)) }

private fun decodeBase64(v: String?): ByteArray? = try {
    if (v.isNullOrBlank()) null else Base64.getDecoder().decode(v)
} catch (_: Exception) {
    null
}
