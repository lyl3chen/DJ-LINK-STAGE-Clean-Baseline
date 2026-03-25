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
import org.deepsymmetry.beatlink.data.OverlayPainter
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreview
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
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

private class StretchDetailComponent(player: Int) : WaveformDetailComponent(player) {
    private val stretchY = 1.18
    override fun paintComponent(g: Graphics) {
        val g2 = g as? Graphics2D
        if (g2 == null) {
            super.paintComponent(g)
            return
        }
        val old: AffineTransform = g2.transform
        try {
            val center = height / 2.0
            g2.translate(0.0, center * (1.0 - stretchY))
            g2.scale(1.0, stretchY)
            super.paintComponent(g2)
        } finally {
            g2.transform = old
        }
    }
}

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
    val latestPlaybackMs = remember { mutableStateOf(progressMs) }
    val latestPlaying = remember { mutableStateOf(false) }
    val latestPlayer = remember { mutableStateOf(player.number) }
    val lastPushedPlaybackMs = remember { mutableStateOf(Long.MIN_VALUE) }
    val lastPushedPlaying = remember { mutableStateOf<Boolean?>(null) }
    val component = remember(player.number) {
        StretchDetailComponent(0).apply {
            setAutoScroll(true)
            setLabelFont(null)
            addMouseWheelListener { e ->
                val current = getScale().coerceIn(1, 256)
                val fitScale = currentDetail?.let { detail ->
                    if (width > 0) ((detail.getFrameCount() + width - 1) / width).coerceIn(1, 256) else current
                } ?: current
                // 现场锁定：把“向上滚动 3 次后的 scale（fitScale/8）”作为可回退的最小边界
                val minReturnBoundary = max(1, fitScale / 8)
                // 语义：滚轮向上(负值)放大=更细节(更小 scale)；向下缩小=更总览(更大 scale)
                val next = if (e.wheelRotation < 0) {
                    max(1, current / 2)
                } else {
                    min(minReturnBoundary, current * 2)
                }
                if (next != current) {
                    setScale(next)
                    // 关键修正：缩放后立刻重放最近播放头状态，暂停时也保持以当前位置为中心
                    setPlaybackState(latestPlayer.value, latestPlaybackMs.value, latestPlaying.value)
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

    LaunchedEffect(player.number, player.detailRawBase64, player.detailRawStyle, player.detailRawFormat, player.durationMs, player.hotCueTimesMs) {
        val detail = buildDetailFromRaw(player)
        val beatGrid = buildBeatGridFromState(player)
        currentDetail = detail
        nativeReady = detail != null
        if (detail != null) {
            SwingUtilities.invokeLater {
                component.setMonitoredPlayer(0)
                if (scale > 0) {
                    component.setScale(scale)
                }
                component.setOverlayPainter(buildDetailHotCueOverlayPainter(player.hotCueTimesMs, player.durationMs))
                component.setWaveform(detail, null as CueList?, beatGrid)
                // 关键：scale 变化后 setWaveform 可能重置内部可视基准，立即重放最近播放头（暂停态也生效）
                component.setPlaybackState(latestPlayer.value, latestPlaybackMs.value, latestPlaying.value)
                component.revalidate()
                component.repaint()
            }
        }
    }

    LaunchedEffect(progressMs, player.stateText, player.number) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        latestPlaybackMs.value = progressMs
        latestPlaying.value = playing
        latestPlayer.value = player.number

        // 最小节流：仅在状态变化或时间位移超过阈值时推送，降低无效重绘导致的卡顿。
        val shouldPush = (lastPushedPlaying.value != playing) ||
            (kotlin.math.abs(progressMs - lastPushedPlaybackMs.value) >= 24L)
        if (!shouldPush) return@LaunchedEffect

        lastPushedPlaying.value = playing
        lastPushedPlaybackMs.value = progressMs
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
                    val minReturnBoundary = max(1, fitScale / 8)
                    if (c.getScale() != minReturnBoundary) c.setScale(minReturnBoundary)
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

    LaunchedEffect(player.number, player.previewRawBase64, player.previewRawStyle, player.previewRawFormat, player.durationMs, player.hotCueTimesMs) {
        val preview = buildPreviewFromRaw(player)
        nativeReady = preview != null
        if (preview != null) {
            val durationSec = (player.durationMs / 1000L).toInt().coerceAtLeast(0)
            SwingUtilities.invokeLater {
                component.setMonitoredPlayer(0)
                val overlayPainter = buildPreviewHotCueOverlayPainter(player.hotCueTimesMs, player.durationMs)
                component.setOverlayPainter(overlayPainter)
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
            update = { },
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

private fun buildDetailHotCueOverlayPainter(hotCueTimesMs: List<Int>, durationMs: Long): OverlayPainter? {
    if (durationMs <= 0 || hotCueTimesMs.isEmpty()) return null
    return OverlayPainter { c, g ->
        val comp = c as? WaveformDetailComponent ?: return@OverlayPainter
        val h = comp.height.coerceAtLeast(1)
        val palette = intArrayOf(0xE91E63, 0x03A9F4, 0x4CAF50, 0xFF9800, 0x9C27B0, 0xFFEB3B)
        val font = g.font.deriveFont(9f)
        g.font = font
        hotCueTimesMs.take(8).forEachIndexed { i, ms ->
            val x = comp.millisecondsToX(ms.toLong())
            if (x < 1 || x > comp.width - 2) return@forEachIndexed
            val rgb = palette[i % palette.size]
            val color = java.awt.Color((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff, 220)
            g.color = color
            g.drawLine(x, 3, x, h - 3)
            val label = ('A'.code + i).toChar().toString()
            val boxW = 10
            val boxH = 10
            val boxX = (x - boxW / 2).coerceIn(1, (comp.width - boxW - 1).coerceAtLeast(1))
            g.color = color
            g.fillRoundRect(boxX, 1, boxW, boxH, 3, 3)
            g.color = java.awt.Color.WHITE
            g.drawString(label, boxX + 2, 9)
        }
    }
}

private fun buildPreviewHotCueOverlayPainter(hotCueTimesMs: List<Int>, durationMs: Long): OverlayPainter? {
    if (durationMs <= 0 || hotCueTimesMs.isEmpty()) return null
    return OverlayPainter { c, g ->
        val comp = c as? WaveformPreviewComponent ?: return@OverlayPainter
        val h = comp.height.coerceAtLeast(1)
        val palette = intArrayOf(0xE91E63, 0x03A9F4, 0x4CAF50, 0xFF9800, 0x9C27B0, 0xFFEB3B)
        val top = 8
        val bottom = (h * 0.48).toInt().coerceAtLeast(top + 8) // keep inside waveform body, away from progress area
        val font = g.font.deriveFont(9f)
        g.font = font
        hotCueTimesMs.take(8).forEachIndexed { i, ms ->
            val x = comp.millisecondsToX(ms.toLong())
            val rgb = palette[i % palette.size]
            val color = java.awt.Color((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff, 220)
            g.color = color
            g.drawLine(x, top, x, bottom)
            val label = ('A'.code + i).toChar().toString()
            val boxW = 10
            val boxH = 10
            val boxX = (x - boxW / 2).coerceIn(1, (comp.width - boxW - 1).coerceAtLeast(1))
            val boxY = 2
            g.color = color
            g.fillRoundRect(boxX, boxY, boxW, boxH, 3, 3)
            g.color = java.awt.Color.WHITE
            g.drawString(label, boxX + 2, boxY + 8)
        }
    }
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
