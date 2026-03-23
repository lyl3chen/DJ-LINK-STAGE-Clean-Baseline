import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

private val httpClient: HttpClient = HttpClient.newBuilder().build()
private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private object UiText {
    const val APP_TITLE = "DJ Link Stage"
    const val UI_VERSION = "UI bc70482"
    const val TAB_LIVE = "LIVE"
    const val TAB_SYNC = "同步中心"
    const val TAB_LOCAL = "本地播放器"
    const val TAB_TRIGGER = "触发"
    const val SETTINGS = "设置"

    const val MASTER = "MASTER"
    const val MASTER_BPM = "MASTER BPM"
    const val DJ_LINK = "DJ LINK"
    const val SCAN = "SCAN"
    const val LAST_UPDATE = "LAST UPDATE"
    const val FAIL = "FAIL"

    const val PLAYER = "PLAYER"
    const val ARTIST = "ARTIST"
    const val ZOOM = "ZOOM WAVE SLOT"
    const val CURRENT = "CURRENT"
    const val REMAIN = "REMAIN"
    const val BPM = "BPM"
    const val PITCH = "PITCH"
    const val EFFECTIVE = "EFFECTIVE"
    const val DEBUG = "DEBUG"
    const val HIDE = "HIDE"

    const val MINI_DECK = "MINI DECK OVERVIEW"
}

private val C_BG = Color(0xFF0A0D12)
private val C_PANEL = Color(0xFF10151C)
private val C_PANEL_2 = Color(0xFF131A23)
private val C_BORDER = Color(0xFF2A313B)
private val C_TEXT = Color(0xFFE7EDF5)
private val C_MUTED = Color(0xFF8A97A8)
private val C_PLAY = Color(0xFF36C965)
private val C_PAUSE = Color(0xFFFFB01F)
private val C_CUED = Color(0xFF3B82F6)
private val C_STOP = Color(0xFF7B8796)
private val C_OFFLINE = Color(0xFF5A6470)
private val C_ALERT = Color(0xFFFF4D4F)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = UiText.APP_TITLE) {
        MaterialTheme {
            AppRoot()
        }
    }
}

data class DashboardPlayer(
    val number: Int,
    val online: Boolean,
    val stateText: String,
    val rawStateSummary: String,
    val onAir: Boolean,
    val master: Boolean,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val artworkAvailable: Boolean,
    val sourceSlot: String?,
    val sourcePlayer: Int,
    val rekordboxId: Int,
    val hasTrack: Boolean,
    val currentTimeMs: Long,
    val durationMs: Long,
    val remainTimeMs: Long,
    val timeSource: String,
    val rawBpm: Double?,
    val pitch: Double?,
    val effectiveBpm: Double?
)

data class DashboardState(
    val players: List<DashboardPlayer> = emptyList(),
    val masterPlayer: Int? = null,
    val masterBpm: Double? = null,
    val scanEnabled: Boolean? = null,
    val updatedAtMs: Long = 0,
    val stale: Boolean = true,
    val consecutiveFailures: Int = 0,
    val error: String? = null
)

@Composable
private fun AppRoot() {
    val baseUrl = "http://127.0.0.1:8080"
    var refreshMs by remember { mutableStateOf(500) }
    var state by remember { mutableStateOf(DashboardState()) }
    var uiNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(refreshMs) {
        while (isActive) {
            state = fetchDashboardState(baseUrl, state)
            delay(refreshMs.toLong())
        }
    }

    // 本地高频时钟：用于播放态时间线性推进（视觉平滑，不增加后端负载）
    LaunchedEffect(Unit) {
        while (isActive) {
            uiNowMs = System.currentTimeMillis()
            delay(33)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C_BG)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TopToolbar()
        TopStatusBar(state, refreshMs) { refreshMs = it }
        LiveMain(state.players, state.updatedAtMs, uiNowMs, modifier = Modifier.weight(1f))
        MiniDeckOverview(state.players, state.updatedAtMs, uiNowMs)
    }
}

@Composable
private fun TopToolbar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(C_PANEL)
            .border(1.dp, C_BORDER)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TopTab(UiText.TAB_LIVE, selected = true)
        TopTab(UiText.TAB_SYNC, selected = false)
        TopTab(UiText.TAB_LOCAL, selected = false)
        TopTab(UiText.TAB_TRIGGER, selected = false)
        Spacer(Modifier.weight(1f))
        Text(UiText.UI_VERSION, color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = {}, modifier = Modifier.height(26.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text(UiText.SETTINGS, color = C_TEXT, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TopTab(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .background(if (selected) C_PANEL_2 else Color.Transparent)
            .border(1.dp, if (selected) Color(0xFF3A4656) else C_BORDER)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) C_TEXT else C_MUTED, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopStatusBar(state: DashboardState, refreshMs: Int, onRefreshChange: (Int) -> Unit) {
    val updateText = if (state.updatedAtMs > 0) {
        timeFmt.format(Instant.ofEpochMilli(state.updatedAtMs).atZone(ZoneId.systemDefault()))
    } else "-"

    val linkStateText = when {
        state.error != null -> "INTERRUPTED"
        state.stale -> "INTERRUPTED"
        state.players.any { it.online } -> "CONNECTED"
        else -> "NO DEVICE"
    }

    val linkStateColor = when {
        state.error != null || state.stale -> C_ALERT
        state.players.any { it.online } -> C_PLAY
        else -> C_PAUSE
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_PANEL)
            .border(1.dp, C_BORDER)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TopMetric(UiText.MASTER, state.masterPlayer?.toString() ?: "-", Modifier.weight(1f), highlight = true)
            TopMetric(UiText.MASTER_BPM, state.masterBpm?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f), highlight = true)
            TopMetric(UiText.DJ_LINK, linkStateText, Modifier.weight(1f), valueColor = linkStateColor, highlight = true)
            TopMetric(UiText.SCAN, if (state.scanEnabled == true) "ON" else if (state.scanEnabled == false) "OFF" else "UNKNOWN", Modifier.weight(1f))
            TopMetric(UiText.LAST_UPDATE, updateText, Modifier.weight(1f))
            TopMetric(UiText.FAIL, state.consecutiveFailures.toString(), Modifier.weight(1f), valueColor = if (state.consecutiveFailures > 0) C_ALERT else C_PLAY)
            RefreshSelector(refreshMs, onRefreshChange)
        }
        if (state.error != null || state.stale) {
            Text("⚠ ${state.error ?: "DATA INTERRUPTED"}", color = C_ALERT, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RefreshSelector(refreshMs: Int, onRefreshChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(200, 300, 500).forEach { ms ->
            FilterChip(
                selected = refreshMs == ms,
                onClick = { onRefreshChange(ms) },
                label = { Text("${ms}ms") }
            )
        }
    }
}

@Composable
private fun TopMetric(label: String, value: String, modifier: Modifier, valueColor: Color = C_TEXT, highlight: Boolean = false) {
    Column(
        modifier = modifier
            .height(44.dp)
            .background(if (highlight) C_PANEL_2 else Color.Transparent)
            .border(1.dp, if (highlight) Color(0xFF3A4656) else C_BORDER)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(label, color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveMain(players: List<DashboardPlayer>, sourceUpdatedAtMs: Long, uiNowMs: Long, modifier: Modifier = Modifier) {
    fun placeholder(idx: Int) = DashboardPlayer(
        number = idx,
        online = false,
        stateText = "OFFLINE",
        rawStateSummary = "active=false",
        onAir = false,
        master = false,
        title = "-",
        artist = "-",
        artworkUrl = null,
        artworkAvailable = false,
        sourceSlot = null,
        sourcePlayer = 0,
        rekordboxId = 0,
        hasTrack = false,
        currentTimeMs = 0,
        durationMs = 0,
        remainTimeMs = 0,
        timeSource = "-",
        rawBpm = null,
        pitch = null,
        effectiveBpm = null
    )

    val onlinePlayers = players.filter { it.online }.sortedBy { it.number }.take(4)
    val display = if (onlinePlayers.isNotEmpty()) {
        onlinePlayers
    } else {
        listOf(placeholder(1), placeholder(2))
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(display) { p ->
            LiveChannelRow(p, sourceUpdatedAtMs, uiNowMs)
        }
    }
}

@Composable
private fun LiveChannelRow(p: DashboardPlayer, sourceUpdatedAtMs: Long, uiNowMs: Long) {
    val stateColor = when (p.stateText.uppercase()) {
        "PLAYING", "PLAY" -> C_PLAY
        "PAUSED" -> C_PAUSE
        "CUED" -> C_CUED
        "STOPPED", "STOP" -> C_STOP
        "OFFLINE" -> C_OFFLINE
        else -> C_STOP
    }

    val isPlaying = p.stateText.equals("PLAYING", true) || p.stateText.equals("PLAY", true)
    val elapsed = if (sourceUpdatedAtMs > 0) max(0L, uiNowMs - sourceUpdatedAtMs) else 0L
    val displayedCurrentMs = if (isPlaying) p.currentTimeMs + elapsed else p.currentTimeMs
    val displayedRemainMs = if (p.durationMs > 0) max(0L, p.durationMs - displayedCurrentMs) else p.remainTimeMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_PANEL)
            .border(1.dp, C_BORDER)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(98.dp)
                    .background(if (p.onAir) Color(0xFFD23838) else Color(0xFF1A2531))
                    .border(1.dp, C_BORDER),
                contentAlignment = Alignment.Center
            ) {
                Text(p.number.toString(), color = C_TEXT, fontWeight = FontWeight.Bold)
            }

            // LEFT: 只显示歌曲-歌手名 + 波形 + 元数据
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .height(98.dp)
                    .background(Color(0xFF0F141B))
                    .border(1.dp, C_BORDER)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${p.title.ifBlank { "-" }} - ${p.artist.ifBlank { "-" }}",
                    color = C_TEXT,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color(0xFF0C1117))
                        .border(1.dp, Color(0xFF25303C))
                ) {
                    WaveformEmptyState("WAVEFORM PLACEHOLDER", Modifier.align(Alignment.Center))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("DUR ${if (p.durationMs > 0) (p.durationMs / 1000).toString() + "s" else "-"}", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
                    Text("SRC ${(p.sourceSlot ?: "-")}/${if (p.sourcePlayer > 0) p.sourcePlayer else "-"}", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
                    Text("RBID ${if (p.rekordboxId > 0) p.rekordboxId else "-"}", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Artwork: 改回原位并放大（可见性优先）
            ArtworkSquare(
                artworkUrl = p.artworkUrl,
                sizeDp = 98
            )

            // CENTER: 大号电子段码时间 + BPM/Pitch 次级
            Column(
                modifier = Modifier
                    .weight(0.30f)
                    .height(98.dp)
                    .background(Color(0xFF0F141B))
                    .border(1.dp, C_BORDER)
                    .padding(6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                DigitalTimeReadout(
                    time = fmtTimeDigitalCs(displayedCurrentMs),
                    color = if (p.stateText.uppercase() == "PLAYING") C_PLAY else C_CUED
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SmallMetric(UiText.REMAIN, fmtTimeDigitalCs(displayedRemainMs), Modifier.weight(1f), emphasize = true)
                    SmallMetric(UiText.BPM, p.rawBpm?.let { "%.1f".format(it) } ?: "-", Modifier.weight(1f))
                    SmallMetric(UiText.PITCH, p.pitch?.let { "%+.2f%%".format(it) } ?: "-", Modifier.weight(1f))
                }
            }

            // RIGHT: 状态按钮列（按需求移除 ONLINE/BPM）
            Column(
                modifier = Modifier
                    .weight(0.15f)
                    .height(98.dp)
                    .background(Color(0xFF0F141B))
                    .border(1.dp, C_BORDER)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RightStatusTag("MASTER", p.master, color = Color(0xFFFFD54A))
                RightStatusTag("ON-AIR", p.onAir, color = Color(0xFFD23838))
                RightStatusTag(p.stateText.uppercase(), true, color = stateColor)
            }
        }

        // 调试按钮移除（按当前需求）
    }
}

@Composable
private fun StateTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TinyFlag(text: String, on: Boolean) {
    Box(
        modifier = Modifier
            .background(if (on) Color(0xFF263447) else Color(0xFF151D27))
            .border(1.dp, if (on) Color(0xFF4C9BFF) else C_BORDER)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, color = if (on) C_TEXT else C_MUTED, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RightStatusTag(text: String, active: Boolean, color: Color = C_PLAY) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) Color(0xFF1A2531) else Color(0xFF121820))
            .border(1.dp, if (active) color else C_BORDER)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) C_TEXT else C_MUTED, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier, emphasize: Boolean = false) {
    Column(
        modifier = modifier
            .height(34.dp)
            .background(if (emphasize) Color(0xFF18222E) else Color(0xFF121922))
            .border(1.dp, C_BORDER)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(label, color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        Text(value, color = if (emphasize) C_TEXT else Color(0xFFD0D8E2), fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun DigitalTimeReadout(time: String, color: Color) {
    val parts = time.split(".")
    val main = parts.getOrElse(0) { "00:00:00" }
    val frac = parts.getOrElse(1) { "00" }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color(0xFF0A0F15))
            .border(1.dp, Color(0xFF334050)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                main,
                color = color,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displaySmall,
                letterSpacing = 0.4.sp,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                ".${frac}",
                color = Color(0xFFBFD3EA),
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 0.2.sp,
                modifier = Modifier.alignByBaseline().padding(start = 1.dp)
            )
        }
    }
}

@Composable
private fun ArtworkSquare(artworkUrl: String?, sizeDp: Int) {
    var bitmap by remember(artworkUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(artworkUrl) {
        bitmap = null
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(artworkUrl).openStream().use { it.readBytes() }
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .background(Color(0xFF0B1016))
            .border(1.dp, Color(0xFF3A4656)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("ART", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MiniDeckOverview(players: List<DashboardPlayer>, sourceUpdatedAtMs: Long, uiNowMs: Long) {
    fun placeholder(idx: Int) = DashboardPlayer(
        number = idx,
        online = false,
        stateText = "OFFLINE",
        rawStateSummary = "active=false",
        onAir = false,
        master = false,
        title = "-",
        artist = "-",
        artworkUrl = null,
        artworkAvailable = false,
        sourceSlot = null,
        sourcePlayer = 0,
        rekordboxId = 0,
        hasTrack = false,
        currentTimeMs = 0,
        durationMs = 0,
        remainTimeMs = 0,
        timeSource = "-",
        rawBpm = null,
        pitch = null,
        effectiveBpm = null
    )

    val onlinePlayers = players.filter { it.online }.sortedBy { it.number }.take(4)
    val display = if (onlinePlayers.isNotEmpty()) onlinePlayers else listOf(placeholder(1), placeholder(2))

    val rows = display.chunked(2)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (rows.size > 1) 156.dp else 78.dp)
            .background(Color(0xFF0F1319))
            .border(1.dp, C_BORDER)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { rowPlayers ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowPlayers.forEach { p ->
                    MiniDeckItem(p.number, p, sourceUpdatedAtMs, uiNowMs, Modifier.weight(1f))
                }
                if (rowPlayers.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniDeckItem(index: Int, p: DashboardPlayer?, sourceUpdatedAtMs: Long, uiNowMs: Long, modifier: Modifier = Modifier) {
    val st = p?.stateText?.uppercase() ?: "OFFLINE"
    val stColor = when (st) {
        "PLAYING", "PLAY" -> C_PLAY
        "PAUSED" -> C_PAUSE
        "CUED" -> C_CUED
        "OFFLINE" -> C_OFFLINE
        else -> C_STOP
    }
    val bpmTxt = p?.rawBpm?.let { "%.1f".format(it) } ?: "-"
    val pitchTxt = p?.pitch?.let { "%+.2f".format(it) } ?: "-"
    val isPlaying = p?.stateText?.equals("PLAYING", true) == true || p?.stateText?.equals("PLAY", true) == true
    val elapsed = if (sourceUpdatedAtMs > 0) max(0L, uiNowMs - sourceUpdatedAtMs) else 0L
    val displayMs = if (p != null && isPlaying) p.currentTimeMs + elapsed else (p?.currentTimeMs ?: 0L)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF10141B))
            .border(1.dp, Color(0xFF343B47))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.background(Color(0xFF1A202A)).border(1.dp, C_BORDER).padding(horizontal = 4.dp, vertical = 1.dp)) {
                Text("PLAYER $index", color = C_TEXT, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.background(stColor).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(st, color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(fmtTimeDigitalCs(displayMs), color = C_TEXT, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).background(Color(0xFF171D27)).border(1.dp, C_BORDER).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(p?.title ?: "-", color = C_MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.background(Color(0xFF171D27)).border(1.dp, C_BORDER).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("BPM $bpmTxt", color = C_TEXT, style = MaterialTheme.typography.labelSmall)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF0B1016))
                .border(1.dp, Color(0xFF2A3340))
        ) {
            WaveformEmptyState("PREVIEW PLACEHOLDER", Modifier.align(Alignment.Center))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text("TEMPO $pitchTxt", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
            Text("SRC ${p?.sourceSlot ?: "-"}/${if ((p?.sourcePlayer ?: 0) > 0) p?.sourcePlayer else "-"}", color = C_MUTED, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("MASTER ${if (p?.master == true) "ON" else "OFF"}", color = if (p?.master == true) C_PLAY else C_MUTED, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun fetchDashboardState(baseUrl: String, old: DashboardState): DashboardState {
    return try {
        val playersJson = httpGetJson("$baseUrl/api/players/state")
        val scanJson = runCatching { httpGetJson("$baseUrl/api/scan") }.getOrNull()

        val playersArray = playersJson.getAsJsonArray("players")
        val players = mutableListOf<DashboardPlayer>()

        if (playersArray != null) {
            for (el in playersArray) {
                val p = el.asJsonObject
                val number = p.optInt("number", 0)
                if (number <= 0) continue

                val online = p.optBool("active", false)
                val playing = p.optBool("playing", false)
                val onAir = p.optBool("onAir", false)
                val master = p.optBool("master", false)
                val beat = p.optInt("beat", -1)

                val currentTime = p.optLongOrNull("currentTimeMs")
                val beatTime = p.optLongOrNull("beatTimeMs")
                val currentTimeMs = when {
                    currentTime != null && currentTime >= 0 -> currentTime
                    beatTime != null && beatTime >= 0 -> beatTime
                    else -> 0L
                }
                val timeSource = when {
                    currentTime != null && currentTime >= 0 -> "currentTimeMs"
                    beatTime != null && beatTime >= 0 -> "beatTimeMs"
                    else -> "none"
                }

                val durationMs = p.optLong("durationMs", 0L)
                val remain = if (durationMs > 0) durationMs - currentTimeMs else 0L
                val rawBpm = p.optDoubleOrNull("bpm")
                val pitch = p.optDoubleOrNull("pitch")
                val effective = if (rawBpm != null && pitch != null) rawBpm * (1.0 + pitch / 100.0) else rawBpm

                val track = p.optObj("track")
                val title = track?.optString("title") ?: p.optString("title") ?: "-"
                val artist = track?.optString("artist") ?: p.optString("artist") ?: "-"
                val sourceSlot = track?.optString("sourceSlot")
                val sourcePlayer = track?.optInt("sourcePlayer", 0) ?: 0
                val rekordboxId = track?.optInt("rekordboxId", 0) ?: 0
                val trackId = track?.optString("trackId") ?: p.optString("trackId") ?: ""
                val hasTrack = trackId.isNotBlank() || title.isNotBlank() && title != "-"

                val explicitState = (p.optString("state") ?: p.optString("playState") ?: p.optString("status"))?.uppercase()
                val dbg = p.optObj("debugState")
                val dbgIsCued = dbg?.optBoolOrNull("isCued") ?: p.optBoolOrNull("isCued")
                val dbgIsPaused = dbg?.optBoolOrNull("isPaused") ?: p.optBoolOrNull("isPaused")
                val dbgIsTrackLoaded = dbg?.optBoolOrNull("isTrackLoaded") ?: p.optBoolOrNull("isTrackLoaded")

                val stateText = when {
                    !online -> "OFFLINE"
                    playing || dbg?.optBoolOrNull("isPlaying") == true -> "PLAYING"
                    dbgIsCued == true -> "CUED"
                    dbgIsPaused == true && dbgIsCued != true -> "PAUSED"
                    dbgIsTrackLoaded == false -> "STOPPED"
                    explicitState == "PLAYING" || explicitState == "PLAY" -> "PLAYING"
                    explicitState == "PAUSED" || explicitState == "PAUSE" -> "PAUSED"
                    explicitState == "STOPPED" || explicitState == "STOP" -> "STOPPED"
                    explicitState == "CUED" || explicitState == "CUE" -> "CUED"
                    (dbgIsTrackLoaded ?: hasTrack) && currentTimeMs <= 120 && beat <= 0 -> "CUED"
                    (dbgIsTrackLoaded ?: hasTrack) && (currentTimeMs > 120 || beat > 0) -> "PAUSED"
                    else -> "STOPPED"
                }

                val ps1 = friendly(dbg?.optString("playState1") ?: p.optString("playState1"))
                val ps2 = friendly(dbg?.optString("playState2") ?: p.optString("playState2"))
                val ps3 = friendly(dbg?.optString("playState3") ?: p.optString("playState3"))
                val rawSummary = "state=${friendly(explicitState)},ps=[$ps1/$ps2/$ps3],isCued=${friendlyBool(dbgIsCued)},isPaused=${friendlyBool(dbgIsPaused)},isTrackLoaded=${friendlyBool(dbgIsTrackLoaded)},playing=${friendlyBool(playing)},beat=${friendlyNum(beat)},time=${friendlyNum(currentTimeMs)}"

                players += DashboardPlayer(
                    number = number,
                    online = online,
                    stateText = stateText,
                    rawStateSummary = rawSummary,
                    onAir = onAir,
                    master = master,
                    title = title,
                    artist = artist,
                    artworkUrl = track?.optString("artworkUrl")?.let { if (it.startsWith("/")) "$baseUrl$it" else it },
                    artworkAvailable = track?.optBool("artworkAvailable", false) ?: false,
                    sourceSlot = sourceSlot,
                    sourcePlayer = sourcePlayer,
                    rekordboxId = rekordboxId,
                    hasTrack = hasTrack,
                    currentTimeMs = currentTimeMs,
                    durationMs = durationMs,
                    remainTimeMs = remain,
                    timeSource = timeSource,
                    rawBpm = rawBpm,
                    pitch = pitch,
                    effectiveBpm = effective
                )
            }
        }

        val master = players.firstOrNull { it.master } ?: players.firstOrNull { it.online && (it.stateText == "PLAYING" || it.stateText == "PLAY") }
        val updatedAt = System.currentTimeMillis()

        DashboardState(
            players = players,
            masterPlayer = master?.number,
            masterBpm = master?.effectiveBpm ?: master?.rawBpm,
            scanEnabled = scanJson?.optBool("scanning", false),
            updatedAtMs = updatedAt,
            stale = false,
            consecutiveFailures = 0,
            error = null
        )
    } catch (e: Exception) {
        old.copy(
            stale = true,
            consecutiveFailures = old.consecutiveFailures + 1,
            error = e.message ?: "fetch failed"
        )
    }
}

private fun httpGetJson(url: String): JsonObject {
    val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build()
    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) {
        error("HTTP ${resp.statusCode()} @ $url")
    }
    return JsonParser.parseString(resp.body()).asJsonObject
}

private fun fmtTime(ms: Long): String {
    val sec = max(0L, ms / 1000)
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

private fun fmtTimeDigitalCs(ms: Long): String {
    val safeMs = max(0L, ms)
    val totalSec = safeMs / 1000
    val cs = (safeMs % 1000) / 10
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return "%02d:%02d:%02d.%02d".format(hours, minutes, seconds, cs)
}

@Composable
private fun WaveformEmptyState(text: String, modifier: Modifier = Modifier) {
    Text(text, color = C_MUTED, style = MaterialTheme.typography.labelSmall, modifier = modifier)
}

private fun JsonObject.optObj(key: String): JsonObject? =
    if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

private fun JsonObject.optArray(key: String): com.google.gson.JsonArray? =
    if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

private fun JsonObject.optString(key: String): String? =
    if (has(key) && !get(key).isJsonNull) get(key).asString else null

private fun JsonObject.optInt(key: String, default: Int): Int =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asInt }.getOrElse { default } else default

private fun JsonObject.optLong(key: String, default: Long): Long =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrElse { default } else default

private fun JsonObject.optLongOrNull(key: String): Long? =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

private fun JsonObject.optBool(key: String, default: Boolean): Boolean =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asBoolean }.getOrElse { default } else default

private fun JsonObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

private fun JsonObject.optBoolOrNull(key: String): Boolean? {
    if (!has(key) || get(key).isJsonNull) return null
    val raw = get(key)
    return runCatching { raw.asBoolean }.getOrElse {
        val s = runCatching { raw.asString }.getOrNull()?.trim()?.lowercase()
        when (s) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

private fun friendly(v: String?): String =
    if (v.isNullOrBlank() || v.equals("null", true) || v.equals("n/a", true)) "-" else v

private fun friendlyBool(v: Boolean?): String = v?.toString() ?: "-"

private fun friendlyNum(v: Any?): String = when (v) {
    null -> "-"
    is Number -> v.toString()
    else -> v.toString().takeIf { it.isNotBlank() } ?: "-"
}
