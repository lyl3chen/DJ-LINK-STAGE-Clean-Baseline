import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
