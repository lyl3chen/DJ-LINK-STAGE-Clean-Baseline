import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * 服务端独占 beat-link 后，桌面端不再直接启动/调用 DeviceFinder/WaveformFinder。
 * 这里只消费 djlink-service /api/players/state 返回的波形采样数据。
 */

@Composable
fun BeatLinkDetailWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val heights = player.detailSampleHeights
    val colors = player.detailSampleColors

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05080C))
    ) {
        if (heights.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val n = heights.size
        val step = w / max(1, n)
        val mid = h / 2f

        for (i in 0 until n) {
            val v = heights[i].coerceIn(0, 31)
            val amp = (v / 31f) * (h * 0.48f)
            val x = i * step

            val argb = colors.getOrNull(i)
            val c = if (argb != null) {
                Color(argb)
            } else {
                Color(0xFF4FC3F7)
            }

            drawLine(
                color = c,
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
    val preview = player.previewSample

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05080C))
    ) {
        if (preview.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val n = preview.size
        val step = w / max(1, n)
        val bottom = h

        for (i in 0 until n) {
            val v = preview[i].coerceIn(0, 31)
            val amp = (v / 31f) * (h * 0.95f)
            val x = i * step
            drawLine(
                color = Color(0xFF81D4FA),
                start = Offset(x, bottom),
                end = Offset(x, bottom - amp),
                strokeWidth = max(1f, step * 0.9f)
            )
        }
    }
}
