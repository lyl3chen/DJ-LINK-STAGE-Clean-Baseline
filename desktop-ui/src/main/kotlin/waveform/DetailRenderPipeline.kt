import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.pow

object DetailRenderPipeline {
    data class DetailDrawData(
        val fillPath: Path,
        val playheadX: Float,
        val color: Color
    )

    fun build(
        heights: List<Int>,
        colors: List<Int>,
        progress: Float,
        widthPx: Int,
        heightPx: Int,
        zoom: Float,
        sourceTag: WaveformSourceTag
    ): DetailDrawData? {
        if (heights.isEmpty() || widthPx <= 1 || heightPx <= 1) return null

        val total = heights.size
        val playSeg = progress.coerceIn(0f, 1f) * (total - 1).toFloat()

        // 正式语义：zoom 改变每屏可见 segment 范围
        val baseWindow = (total / 3.2f)
        val windowSeg = (baseWindow / zoom.coerceAtLeast(1f)).coerceIn(64f, total.toFloat())
        val segPerPx = (windowSeg / widthPx.toFloat()).coerceAtLeast(0.05f)

        fun segmentCenterForX(x: Int): Float {
            val dx = x.toFloat() - (widthPx / 2f)
            return playSeg + dx * segPerPx
        }

        fun sampleHeight(centerSeg: Float): Float {
            if (centerSeg < -1f || centerSeg > total.toFloat()) return 0f
            val bucket = kotlin.math.max(1f, segPerPx)
            val s = kotlin.math.floor(centerSeg - bucket * 0.5f).toInt().coerceAtLeast(0)
            val e = kotlin.math.ceil(centerSeg + bucket * 0.5f).toInt().coerceAtMost(total)
            if (e <= s) return 0f
            var sum = 0f
            var peak = 0
            var cnt = 0
            for (i in s until e) {
                val v = heights[i]
                sum += v
                if (v > peak) peak = v
                cnt++
            }
            val mean = if (cnt > 0) sum / cnt.toFloat() else 0f
            return peak * 0.72f + mean * 0.28f
        }

        val values = FloatArray(widthPx)
        for (x in 0 until widthPx) values[x] = sampleHeight(segmentCenterForX(x))

        val sorted = values.sorted()
        fun percentile(q: Float): Float {
            if (sorted.isEmpty()) return 1f
            val idx = ((sorted.lastIndex) * q).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

        val lo = percentile(0.10f)
        val hiRaw = percentile(0.95f)
        val hi = if (hiRaw - lo < 1e-3f) (lo + 1f) else hiRaw
        fun ampNorm(v: Float): Float {
            val n = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            return n.toDouble().pow(0.85).toFloat()
        }

        val axis = heightPx / 2f
        val maxAmp = heightPx * 0.47f
        val top = Path()
        for (x in 0 until widthPx) {
            val amp = ampNorm(values[x]) * maxAmp
            if (x == 0) top.moveTo(x.toFloat(), axis - amp)
            else top.lineTo(x.toFloat(), axis - amp)
        }

        val fill = Path().apply {
            addPath(top)
            for (x in widthPx - 1 downTo 0) {
                val amp = ampNorm(values[x]) * maxAmp
                lineTo(x.toFloat(), axis + amp)
            }
            close()
        }

        val color = if (colors.isNotEmpty()) {
            val idx = (playSeg.toInt().coerceIn(0, heights.lastIndex) * colors.size / heights.size).coerceIn(0, colors.lastIndex)
            val c = colors[idx]
            Color((((c and 0x00FFFFFF) or (0xFF shl 24)).toLong()))
        } else {
            when (sourceTag) {
                WaveformSourceTag.RAW -> Color(0xFF7EA8FF)
                WaveformSourceTag.SAMPLE_FALLBACK -> Color(0xFF6E86D8)
                WaveformSourceTag.NONE -> Color(0xFF5A6270)
            }
        }

        return DetailDrawData(
            fillPath = fill,
            playheadX = (widthPx / 2f),
            color = color
        )
    }
}
