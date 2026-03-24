import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.CueList
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.dbserver.Message
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreview
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.Base64
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/**
 * 主路径：服务端 raw(getData) -> 桌面端恢复 WaveformPreview/WaveformDetail 对象 -> 原生组件绘制。
 * fallback：仅在 raw 缺失/解码失败/构造失败时，使用轻量 Canvas 渲染保证可见性。
 */

private data class WavePoint(val h: Int, val color: Color)

@Composable
fun BeatLinkDetailWave(
    player: DashboardPlayer,
    progressMs: Long,
    detailScale: Int = 1,
    onDetailScaleChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scale = detailScale.coerceIn(0, 256)
    val onScaleChangeState = rememberUpdatedState(onDetailScaleChange)
    var currentDetail by remember(player.number) { mutableStateOf<WaveformDetail?>(null) }
    val component = remember(player.number) {
        WaveformDetailComponent(0).apply {
            setAutoScroll(true)
            addMouseWheelListener { e ->
                val current = getScale().coerceIn(1, 256)
                val fitScale = currentDetail?.let { detail ->
                    if (width > 0) ((detail.getFrameCount() + width - 1) / width).coerceIn(1, 256) else current
                } ?: current
                // 语义：滚轮向上(负值)放大=更细节(更小 scale)；向下缩小=更总览(更大 scale)
                // 边界：最小缩小到 fitScale（刚好铺满容器），不能再更小
                val next = if (e.wheelRotation < 0) {
                    max(1, current / 2)
                } else {
                    min(fitScale, current * 2)
                }
                if (next != current) {
                    setScale(next)
                    onScaleChangeState.value(next)
                    revalidate()
                    repaint()
                }
            }
        }
    }

    var nativeReady by remember(player.number, player.detailRawBase64, player.detailRawStyle, player.detailRawFormat) {
        mutableStateOf(false)
    }

    LaunchedEffect(player.number, player.detailRawBase64, player.detailRawStyle, player.detailRawFormat, scale) {
        val detail = buildDetailFromRaw(player)
        val beatGrid = buildBeatGridFromState(player)
        val cueList = buildCueListFromState(player)
        if (detail != null) {
            println("[DetailBridge] player=${player.number} detail=true cueList=${cueList != null} beatGrid=${beatGrid != null} beatCount=${beatGrid?.beatCount ?: 0}")
        }
        currentDetail = detail
        nativeReady = detail != null
        if (detail != null) {
            SwingUtilities.invokeLater {
                component.setMonitoredPlayer(0)
                if (scale > 0) {
                    component.setScale(scale)
                }
                component.setWaveform(detail, cueList, beatGrid)
                component.revalidate()
                component.repaint()
            }
        }
    }

    LaunchedEffect(progressMs, player.stateText, player.number) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        SwingUtilities.invokeLater {
            component.setPlaybackState(player.number, progressMs, playing)
            component.repaint()
        }
    }

    if (nativeReady) {
        SwingPanel(factory = { component }, update = { c ->
            if (scale > 0) {
                if (c.getScale() != scale) c.setScale(scale)
            } else {
                val detail = currentDetail
                if (detail != null && c.width > 0) {
                    val fitScale = ((detail.getFrameCount() + c.width - 1) / c.width).coerceIn(1, 256)
                    if (c.getScale() != fitScale) c.setScale(fitScale)
                }
            }
        }, modifier = modifier)
    } else {
        DetailFallbackCanvas(player = player, detailScale = max(1, scale), modifier = modifier)
    }
}

@Composable
fun BeatLinkPreviewWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val component = remember(player.number) { WaveformPreviewComponent(0) }

    var nativeReady by remember(player.number, player.previewRawBase64, player.previewRawStyle, player.previewRawFormat) {
        mutableStateOf(false)
    }

    LaunchedEffect(player.number, player.previewRawBase64, player.previewRawStyle, player.previewRawFormat, player.durationMs) {
        val preview = buildPreviewFromRaw(player)
        nativeReady = preview != null
        if (preview != null) {
            val durationSec = (player.durationMs / 1000L).toInt().coerceAtLeast(0)
            SwingUtilities.invokeLater {
                component.setMonitoredPlayer(0)
                component.setWaveformPreview(preview, durationSec, null)
                component.revalidate()
                component.repaint()
            }
        }
    }

    LaunchedEffect(progressMs, player.stateText, player.number) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        SwingUtilities.invokeLater {
            component.setPlaybackState(player.number, progressMs, playing)
            component.repaint()
        }
    }

    if (nativeReady) {
        SwingPanel(
            factory = { component },
            update = { c ->
                val min = c.minimumSize
                val pref = c.preferredSize
                println("[PreviewLayout] player=${player.number} swingPanel=${c.width}x${c.height}px componentHeight=${c.height} min=${min.width}x${min.height} pref=${pref.width}x${pref.height}")
            },
            modifier = modifier
        )
    } else {
        PreviewFallbackCanvas(player = player, modifier = modifier)
    }
}

private fun buildPreviewFromRaw(player: DashboardPlayer): WaveformPreview? {
    if (player.previewRawFormat != "beatlink.getData.v1") return null
    val raw = decodeBase64(player.previewRawBase64) ?: return null
    return runCatching {
        WaveformPreview(buildDataReference(player), ByteBuffer.wrap(raw), parseStyle(player.previewRawStyle, player.previewRawIsColor))
    }.getOrNull()
}

private fun buildDetailFromRaw(player: DashboardPlayer): WaveformDetail? {
    if (player.detailRawFormat != "beatlink.getData.v1") return null
    val raw = decodeBase64(player.detailRawBase64) ?: return null
    return runCatching {
        WaveformDetail(buildDataReference(player), ByteBuffer.wrap(raw), parseStyle(player.detailRawStyle, player.detailRawIsColor))
    }.getOrNull()
}

private fun parseStyle(style: String?, isColor: Boolean): WaveformFinder.WaveformStyle = when (style?.uppercase()) {
    "RGB" -> WaveformFinder.WaveformStyle.RGB
    "THREE_BAND" -> WaveformFinder.WaveformStyle.THREE_BAND
    else -> if (isColor) WaveformFinder.WaveformStyle.RGB else WaveformFinder.WaveformStyle.BLUE
}

private fun parseSlot(slot: String?): CdjStatus.TrackSourceSlot = when (slot?.uppercase()) {
    "CD_SLOT" -> CdjStatus.TrackSourceSlot.CD_SLOT
    "USB_SLOT" -> CdjStatus.TrackSourceSlot.USB_SLOT
    "SD_SLOT" -> CdjStatus.TrackSourceSlot.SD_SLOT
    "COLLECTION" -> CdjStatus.TrackSourceSlot.COLLECTION
    else -> CdjStatus.TrackSourceSlot.USB_SLOT
}

private fun buildDataReference(player: DashboardPlayer): DataReference {
    val sourcePlayer = if (player.sourcePlayer > 0) player.sourcePlayer else player.number
    val rekordboxId = if (player.rekordboxId > 0) player.rekordboxId else 1
    return DataReference(sourcePlayer, parseSlot(player.sourceSlot), rekordboxId, CdjStatus.TrackType.REKORDBOX)
}

private fun buildBeatGridFromState(player: DashboardPlayer): BeatGrid? {
    val times = player.beatTicksMs
    val inBar = player.beatTicksInBar
    val bpmX100 = player.beatTicksBpmX100
    if (times.isEmpty() || inBar.isEmpty() || bpmX100.isEmpty()) return null
    val count = minOf(times.size, inBar.size, bpmX100.size)
    if (count <= 0) return null

    val inBarArr = IntArray(count)
    val bpmArr = IntArray(count)
    val timesArr = LongArray(count)
    for (i in 0 until count) {
        inBarArr[i] = inBar[i]
        bpmArr[i] = bpmX100[i]
        timesArr[i] = times[i].toLong()
    }
    return runCatching {
        BeatGrid(buildDataReference(player), inBarArr, bpmArr, timesArr)
    }.getOrNull()
}

private fun buildCueListFromState(player: DashboardPlayer): CueList? {
    // Prefer dbserver cue messages when available.
    val extMessage = decodeMessage(player.cueExtendedMessageBase64)
    if (extMessage != null && extMessage.knownType == Message.KnownType.CUE_LIST_EXT) {
        return runCatching { CueList(extMessage) }.getOrNull()
    }
    val cueMessage = decodeMessage(player.cueMessageBase64)
    if (cueMessage != null && cueMessage.knownType == Message.KnownType.CUE_LIST) {
        return runCatching { CueList(cueMessage) }.getOrNull()
    }

    // Fallback to raw cue tags when available.
    val rawTagBuffers = player.cueRawTagsBase64.mapNotNull { b64 ->
        decodeBase64(b64)?.let { ByteBuffer.wrap(it) }
    }
    val rawExtTagBuffers = player.cueRawExtendedTagsBase64.mapNotNull { b64 ->
        decodeBase64(b64)?.let { ByteBuffer.wrap(it) }
    }
    if (rawTagBuffers.isEmpty() && rawExtTagBuffers.isEmpty()) return null
    return runCatching {
        CueList(rawTagBuffers, rawExtTagBuffers)
    }.getOrNull()
}

private fun decodeMessage(v: String?): Message? {
    val bytes = decodeBase64(v) ?: return null
    return runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { dis ->
            Message.read(dis)
        }
    }.getOrNull()
}

@Composable
private fun DetailFallbackCanvas(player: DashboardPlayer, detailScale: Int, modifier: Modifier = Modifier) {
    val style = player.detailRawStyle ?: if (player.detailRawIsColor) "RGB" else "BLUE"
    val points = decodeDetailPoints(player.detailRawBase64, style)
        ?: decodeDetailFallback(player.detailSampleHeights, player.detailSampleColors)
    val visiblePoints = if (detailScale > 1) aggregatePoints(points, detailScale) else points

    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF05080C))) {
        if (visiblePoints.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val n = visiblePoints.size
        val step = w / max(1, n)
        val mid = h / 2f
        for (i in 0 until n) {
            val p = visiblePoints[i]
            val amp = (p.h.coerceIn(0, 31) / 31f) * (h * 0.48f)
            val x = i * step
            drawLine(p.color, Offset(x, mid - amp), Offset(x, mid + amp), max(1f, step * 0.9f))
        }
    }
}

@Composable
private fun PreviewFallbackCanvas(player: DashboardPlayer, modifier: Modifier = Modifier) {
    val style = player.previewRawStyle ?: if (player.previewRawIsColor) "RGB" else "BLUE"
    val points = decodePreviewPoints(player.previewRawBase64, style)
        ?: player.previewSample.map { WavePoint(it.coerceIn(0, 31), Color(0xFF81D4FA)) }

    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF05080C))) {
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
            drawLine(p.color, Offset(x, bottom), Offset(x, bottom - amp), max(1f, step * 0.9f))
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

private fun aggregatePoints(points: List<WavePoint>, scale: Int): List<WavePoint> {
    if (scale <= 1 || points.isEmpty()) return points
    val out = ArrayList<WavePoint>((points.size / scale) + 1)
    var i = 0
    while (i < points.size) {
        val end = minOf(points.size, i + scale)
        var hSum = 0
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        var c = 0
        for (j in i until end) {
            val p = points[j]
            hSum += p.h
            rSum += p.color.red
            gSum += p.color.green
            bSum += p.color.blue
            c++
        }
        out += WavePoint((hSum / max(1, c)).coerceIn(0, 31), Color(rSum / max(1, c), gSum / max(1, c), bSum / max(1, c), 1f))
        i = end
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
