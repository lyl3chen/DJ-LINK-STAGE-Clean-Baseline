import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.RenderingHints
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
            val baseY = h - 1
            val waveColor = when (sourceTag) {
                WaveformSourceTag.RAW -> java.awt.Color(112, 158, 255, 230)
                WaveformSourceTag.SAMPLE_FALLBACK -> java.awt.Color(96, 136, 220, 220)
                WaveformSourceTag.NONE -> java.awt.Color(110, 110, 110, 200)
            }

            g.color = waveColor
            g.stroke = BasicStroke(1f)
            for (x in 0 until w) {
                val idx = if (heights.isEmpty()) 0 else (x * heights.size / w).coerceIn(0, heights.lastIndex)
                val v = if (heights.isEmpty()) 0 else heights[idx]
                val amp = ((v.toFloat() / maxV.toFloat()) * (h * 0.92f)).toInt().coerceAtLeast(1)
                g.drawLine(x, baseY, x, (baseY - amp).coerceAtLeast(0))
            }
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
