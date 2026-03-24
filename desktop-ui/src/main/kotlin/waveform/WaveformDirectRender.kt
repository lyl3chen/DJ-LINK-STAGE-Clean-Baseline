import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

private object WaveformRenderProbe {
    private val counter = ConcurrentHashMap<String, AtomicInteger>()
    private val lastLog = ConcurrentHashMap<String, Long>()
    fun hit(tag: String) {
        val c = counter.computeIfAbsent(tag) { AtomicInteger(0) }.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastLog[tag] ?: 0L
        if (now - last >= 2000) {
            println("[WAVE-RENDER] $tag count=$c")
            lastLog[tag] = now
        }
    }
}

private object WaveformBitmapCache {
    private const val MAX_ITEMS = 48
    private val map = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

    fun getOrBuild(key: String, builder: () -> ImageBitmap): ImageBitmap {
        synchronized(map) { map[key]?.let { return it } }
        val built = builder()
        synchronized(map) {
            map[key] = built
            while (map.size > MAX_ITEMS) {
                val first = map.entries.iterator().next()
                map.remove(first.key)
            }
        }
        return built
    }
}

private fun sparseFingerprint(values: List<Int>, probes: Int = 32): Long {
    if (values.isEmpty()) return 0L
    val n = values.size
    var h = 1125899906842597L
    val p = probes.coerceAtLeast(1)
    for (i in 0 until p) {
        val idx = (i * (n - 1) / max(1, p - 1)).coerceIn(0, n - 1)
        h = h * 1315423911L + values[idx].toLong() + idx
    }
    return h and 0x7fffffffffffffffL
}

@Composable
fun DetailWaveformDirect(
    heights: List<Int>,
    colors: List<Int>,
    baseCurrentMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    sourceUpdatedAtMs: Long,
    zoom: Float,
    modifier: Modifier = Modifier,
    trackToken: String = "-"
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isPlaying) {
        while (true) {
            if (isPlaying) nowMs = System.currentTimeMillis()
            delay(33)
        }
    }

    // 只在窗口锚点变更时重建主体（播放头高频更新只走覆盖层）
    val total = heights.size.coerceAtLeast(1)
    val z = zoom.coerceIn(1f, 10f)
    val windowSize = (total / z).toInt().coerceIn(16, total)
    val liveCurrentMs = if (isPlaying && sourceUpdatedAtMs > 0L) {
        baseCurrentMs + (nowMs - sourceUpdatedAtMs).coerceAtLeast(0L)
    } else baseCurrentMs
    val progress = if (durationMs > 0L) (liveCurrentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val center = (progress * (total - 1)).toInt()

    var windowStart by remember(trackToken, total, windowSize) { mutableIntStateOf((center - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))) }
    val localProgress = if (windowSize > 1) ((center - windowStart).toFloat() / (windowSize - 1).toFloat()) else 0f
    if (localProgress < 0.35f || localProgress > 0.65f) {
        windowStart = (center - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
    }

    val hsFp = remember(heights) { sparseFingerprint(heights) }
    val csFp = remember(colors) { sparseFingerprint(colors) }

    val baseKey = "d|$trackToken|$hsFp|$csFp|$widthPx|$heightPx|$windowStart|$windowSize"
    WaveformRenderProbe.hit("DetailWaveformBody")
    val baseImage = remember(baseKey) {
        WaveformBitmapCache.getOrBuild(baseKey) {
            buildDetailImage(
                heights = heights,
                colors = colors,
                width = widthPx.coerceAtLeast(1),
                height = heightPx.coerceAtLeast(1),
                start = windowStart,
                windowSize = windowSize
            )
        }
    }

    Box(modifier = modifier.onSizeChanged { s -> widthPx = s.width; heightPx = s.height }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawImage(baseImage, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            WaveformRenderProbe.hit("DetailPlayheadLayer")
            val px = (((center - windowStart).toFloat() / (windowSize - 1).toFloat()).coerceIn(0f, 1f)) * size.width
            drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.4f)
        }
    }
}

private fun buildDetailImage(
    heights: List<Int>,
    colors: List<Int>,
    width: Int,
    height: Int,
    start: Int,
    windowSize: Int
): ImageBitmap {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val axis = h / 2f
        val maxAmp = h * 0.47f
        val count = windowSize.coerceAtLeast(2)
        val end = (start + count).coerceAtMost(heights.size)
        val realCount = (end - start).coerceAtLeast(2)

        val xs = FloatArray(realCount)
        val yt = FloatArray(realCount)
        val yb = FloatArray(realCount)

        for (i in 0 until realCount) {
            val idx = start + i
            val x = (i.toFloat() / (realCount - 1).toFloat()) * (w - 1)
            val hh = heights[idx].coerceIn(0, 31)
            val amp = (hh / 31f) * maxAmp
            xs[i] = x
            yt[i] = axis - amp
            yb[i] = axis + amp
        }

        for (i in 0 until realCount - 1) {
            val idx = (start + i).coerceIn(0, colors.lastIndex.coerceAtLeast(0))
            val rgb = if (colors.isNotEmpty()) colors[idx] else 0x6E86FF
            val c = java.awt.Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
            g.color = c
            val px = intArrayOf(xs[i].toInt(), xs[i + 1].toInt(), xs[i + 1].toInt(), xs[i].toInt())
            val py = intArrayOf(yt[i].toInt(), yt[i + 1].toInt(), yb[i + 1].toInt(), yb[i].toInt())
            g.fillPolygon(px, py, 4)
        }
    } finally {
        g.dispose()
    }
    return image.toComposeImageBitmap()
}

@Composable
fun PreviewWaveformDirect(
    heights: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier,
    trackToken: String = "-"
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }

    val hsFp = remember(heights) { sparseFingerprint(heights) }
    val baseKey = "p|$trackToken|$hsFp|$widthPx|$heightPx"
    WaveformRenderProbe.hit("PreviewWaveformBody")
    val baseImage = remember(baseKey) {
        WaveformBitmapCache.getOrBuild(baseKey) {
            buildPreviewImage(heights, widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        }
    }

    Box(modifier = modifier.onSizeChanged { s -> widthPx = s.width; heightPx = s.height }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawImage(baseImage, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            WaveformRenderProbe.hit("PreviewPlayheadLayer")
            val px = progress.coerceIn(0f, 1f) * size.width
            drawLine(Color(0xFF00E5FF), Offset(px, 0f), Offset(px, size.height), 1.2f)
        }
    }
}

private fun buildPreviewImage(heights: List<Int>, width: Int, height: Int): ImageBitmap {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val axis = h / 2f
        val maxAmp = h * 0.47f
        val n = heights.size.coerceAtLeast(2)
        val color = java.awt.Color(126, 168, 255, 255)
        g.color = color

        val upX = IntArray(n)
        val upY = IntArray(n)
        val dnX = IntArray(n)
        val dnY = IntArray(n)
        for (i in 0 until n) {
            val x = (i.toFloat() / (n - 1).toFloat()) * (w - 1)
            val hh = heights.getOrElse(i) { 0 }.coerceIn(0, 127)
            val amp = (hh / 127f) * maxAmp
            upX[i] = x.toInt(); upY[i] = (axis - amp).toInt()
            dnX[i] = x.toInt(); dnY[i] = (axis + amp).toInt()
        }

        val polyX = IntArray(n * 2)
        val polyY = IntArray(n * 2)
        for (i in 0 until n) {
            polyX[i] = upX[i]; polyY[i] = upY[i]
            polyX[n + i] = dnX[n - 1 - i]; polyY[n + i] = dnY[n - 1 - i]
        }
        g.fillPolygon(polyX, polyY, n * 2)
    } finally {
        g.dispose()
    }
    return image.toComposeImageBitmap()
}
