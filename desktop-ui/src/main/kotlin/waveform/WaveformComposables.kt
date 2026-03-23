import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

@Composable
fun PreviewWaveformView(
    heights: List<Int>,
    progress: Float,
    sourceTag: WaveformSourceTag,
    modifier: Modifier = Modifier
) {
    val baseImage = PreviewRenderPipeline.getOrBuildBaseImage(heights, sourceTag)

    Canvas(modifier = modifier) {
        drawImage(
            image = baseImage,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        )

        val px = progress.coerceIn(0f, 1f) * size.width
        drawLine(
            color = Color(0xFF00E5FF),
            start = Offset(px, 0f),
            end = Offset(px, size.height),
            strokeWidth = 1.2f
        )
    }
}

@Composable
fun DetailWaveformView(
    heights: List<Int>,
    colors: List<Int>,
    progress: Float,
    zoom: Float,
    sourceTag: WaveformSourceTag,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val drawData = DetailRenderPipeline.build(
            heights = heights,
            colors = colors,
            progress = progress,
            widthPx = size.width.toInt().coerceAtLeast(2),
            heightPx = size.height.toInt().coerceAtLeast(2),
            zoom = zoom,
            sourceTag = sourceTag
        ) ?: return@Canvas

        drawPath(drawData.fillPath, color = drawData.color.copy(alpha = 0.94f))
        drawLine(
            color = Color(0xFF00E5FF),
            start = Offset(drawData.playheadX, 0f),
            end = Offset(drawData.playheadX, size.height),
            strokeWidth = 1.4f
        )
    }
}
