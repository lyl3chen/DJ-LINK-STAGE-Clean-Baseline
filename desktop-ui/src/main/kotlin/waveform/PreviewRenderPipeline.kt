import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max

object PreviewRenderPipeline {
    private const val BASE_HEIGHT = 64
    private const val MAX_CACHE = 48
    private val cache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

    fun getOrBuildBaseImage(heights: List<Int>, sourceTag: WaveformSourceTag): ImageBitmap {
        val key = buildKey(heights, sourceTag)
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val image = buildBaseImage(heights, sourceTag)
        synchronized(cache) {
            cache[key] = image
            if (cache.size > MAX_CACHE) {
                val first = cache.entries.iterator().next()
                cache.remove(first.key)
            }
        }
        return image
    }

    private fun buildBaseImage(heights: List<Int>, sourceTag: WaveformSourceTag): ImageBitmap {
        val w = max(heights.size, 2)
        val h = BASE_HEIGHT
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color(0, 0, 0, 0)
            g.fillRect(0, 0, w, h)

            val maxV = heights.maxOrNull()?.coerceAtLeast(1) ?: 1
            val axis = h / 2f
            val waveColor = when (sourceTag) {
                WaveformSourceTag.RAW -> java.awt.Color(112, 158, 255, 230)
                WaveformSourceTag.SAMPLE_FALLBACK -> java.awt.Color(96, 136, 220, 220)
                WaveformSourceTag.NONE -> java.awt.Color(110, 110, 110, 200)
            }

            // 从“底边单边竖线”改为“总览连续波形轮廓（上下闭合）”
            val upper = FloatArray(w)
            val lower = FloatArray(w)
            for (x in 0 until w) {
                val idx = (x * heights.size / w).coerceIn(0, heights.lastIndex)
                val v = heights[idx]
                val amp = ((v.toFloat() / maxV.toFloat()) * (h * 0.46f)).coerceAtLeast(1f)
                upper[x] = (axis - amp).coerceAtLeast(0f)
                lower[x] = (axis + amp).coerceAtMost((h - 1).toFloat())
            }

            val shape = Path2D.Float().apply {
                moveTo(0f, upper[0])
                for (x in 1 until w) lineTo(x.toFloat(), upper[x])
                for (x in w - 1 downTo 0) lineTo(x.toFloat(), lower[x])
                closePath()
            }

            g.color = java.awt.Color(waveColor.red, waveColor.green, waveColor.blue, 190)
            g.fill(shape)
            g.color = waveColor
            g.stroke = BasicStroke(1f)
            g.draw(shape)
        } finally {
            g.dispose()
        }
        return buffered.toComposeImageBitmap()
    }

    private fun buildKey(heights: List<Int>, sourceTag: WaveformSourceTag): String {
        var checksum = 0L
        val step = (heights.size / 32).coerceAtLeast(1)
        var i = 0
        while (i < heights.size) {
            checksum = (checksum * 1315423911L + heights[i] + i).and(0x7fffffffffffffffL)
            i += step
        }
        return "${sourceTag.name}|${heights.size}|$checksum"
    }
}
