import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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

private val httpClient: HttpClient = HttpClient.newBuilder().build()
private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private object UiText {
    const val APP_TITLE = "DJ Link Stage - CDJ Monitor"
    const val DASHBOARD_TITLE = "SHOW MONITOR"
    const val REFRESH = "Refresh"
    const val MASTER = "Master"
    const val MASTER_BPM = "Master BPM"
    const val DJ_LINK = "DJ Link"
    const val SCAN = "Scan"
    const val LAST_UPDATE = "Last Update"
    const val FAIL_COUNT = "Fail"
    const val INTERRUPTED = "DATA INTERRUPTED"
    const val NO_DEVICE = "NO DEVICE"
    const val CONNECTED = "CONNECTED"
    const val DEBUG = "Debug"
    const val HIDE_DEBUG = "Hide"
    const val PLAYER = "PLAYER"
    const val ARTIST = "Artist"
    const val CURRENT = "Current"
    const val REMAIN = "Remain"
    const val BPM = "BPM"
    const val PITCH = "Pitch"
    const val EFFECTIVE = "Eff"
    const val RESERVED_ZONE = "MINI DECK / WAVE OVERVIEW (RESERVED)"
}

private val C_BG = Color(0xFF0B0E12)
private val C_PANEL = Color(0xFF11161D)
private val C_BORDER = Color(0xFF2A313B)
private val C_TEXT = Color(0xFFE9EEF5)
private val C_MUTED = Color(0xFF8D99A8)
private val C_PLAY = Color(0xFF34C759)
private val C_PAUSE = Color(0xFFFFB020)
private val C_CUED = Color(0xFF3B82F6)
private val C_STOP = Color(0xFF7D8794)
private val C_OFFLINE = Color(0xFF5B6470)
private val C_ALERT = Color(0xFFFF4D4F)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = UiText.APP_TITLE) {
        MaterialTheme {
            CDJDashboardApp()
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
private fun CDJDashboardApp() {
    val baseUrl = "http://127.0.0.1:8080"
    var refreshMs by remember { mutableStateOf(300) }
    var state by remember { mutableStateOf(DashboardState()) }

    LaunchedEffect(refreshMs) {
        while (isActive) {
            state = fetchDashboardState(baseUrl, state)
            delay(refreshMs.toLong())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C_BG)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TopStatusBar(state, refreshMs) { refreshMs = it }
        PlayersRows(state.players, modifier = Modifier.weight(1f))
        BottomReservedZone()
    }
}

@Composable
private fun TopStatusBar(state: DashboardState, refreshMs: Int, onRefreshChange: (Int) -> Unit) {
    val updateText = if (state.updatedAtMs > 0) {
        timeFmt.format(Instant.ofEpochMilli(state.updatedAtMs).atZone(ZoneId.systemDefault()))
    } else "-"

    val linkStateText = when {
        state.error != null -> UiText.INTERRUPTED
        state.stale -> UiText.INTERRUPTED
        state.players.any { it.online } -> UiText.CONNECTED
        else -> UiText.NO_DEVICE
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(UiText.DASHBOARD_TITLE, color = C_TEXT, fontWeight = FontWeight.Bold)
            Text("${UiText.REFRESH}:", color = C_MUTED)
            listOf(200, 300, 500).forEach { ms ->
                FilterChip(
                    selected = refreshMs == ms,
                    onClick = { onRefreshChange(ms) },
                    label = { Text("${ms}ms") }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TopMetric(UiText.MASTER, state.masterPlayer?.toString() ?: "-", modifier = Modifier.weight(1f), highlight = true)
            TopMetric(UiText.MASTER_BPM, state.masterBpm?.let { "%.2f".format(it) } ?: "-", modifier = Modifier.weight(1f), highlight = true)
            TopMetric(UiText.DJ_LINK, linkStateText, modifier = Modifier.weight(1f), valueColor = linkStateColor, highlight = true)
            TopMetric(UiText.SCAN, when (state.scanEnabled) {
                true -> "ON"
                false -> "OFF"
                null -> "UNKNOWN"
            }, modifier = Modifier.weight(1f))
            TopMetric(UiText.LAST_UPDATE, updateText, modifier = Modifier.weight(1f))
            TopMetric(UiText.FAIL_COUNT, state.consecutiveFailures.toString(), modifier = Modifier.weight(1f), valueColor = if (state.consecutiveFailures > 0) C_ALERT else C_PLAY)
        }

        if (state.error != null || state.stale) {
            Text("⚠ ${state.error ?: UiText.INTERRUPTED}", color = C_ALERT, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TopMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = C_TEXT,
    highlight: Boolean = false
) {
    Column(
        modifier = modifier
            .background(if (highlight) Color(0xFF151C24) else Color.Transparent)
            .border(1.dp, if (highlight) Color(0xFF3A4656) else C_BORDER)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label.uppercase(), color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayersRows(players: List<DashboardPlayer>, modifier: Modifier = Modifier) {
    val byNumber = players.associateBy { it.number }
    val fixed = (1..4).map { idx ->
        byNumber[idx] ?: DashboardPlayer(
            number = idx,
            online = false,
            stateText = "OFFLINE",
            rawStateSummary = "active=false",
            onAir = false,
            master = false,
            title = "-",
            artist = "-",
            currentTimeMs = 0,
            durationMs = 0,
            remainTimeMs = 0,
            timeSource = "-",
            rawBpm = null,
            pitch = null,
            effectiveBpm = null
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        items(fixed) { p -> PlayerStrip(p) }
    }
}

@Composable
private fun PlayerStrip(p: DashboardPlayer) {
    var showDebug by remember(p.number) { mutableStateOf(false) }
    val stateColor = when (p.stateText.uppercase()) {
        "PLAYING", "PLAY" -> C_PLAY
        "PAUSED" -> C_PAUSE
        "CUED" -> C_CUED
        "STOPPED", "STOP" -> C_STOP
        "OFFLINE" -> C_OFFLINE
        else -> C_STOP
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_PANEL)
            .border(1.dp, C_BORDER)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // single strip line like show monitors
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(72.dp)) {
                Text("${UiText.PLAYER} ${p.number}", color = C_TEXT, fontWeight = FontWeight.Bold)
            }

            StateTag(p.stateText.uppercase(), stateColor)
            TinyFlag("ON", p.online)
            TinyFlag("AIR", p.onAir)
            TinyFlag("MST", p.master)

            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(p.title.ifBlank { "-" }, color = C_TEXT, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text("${UiText.ARTIST}: ${p.artist.ifBlank { "-" }}", color = C_MUTED, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }

            MetricReadout(UiText.CURRENT, fmtTime(p.currentTimeMs), emphasis = true)
            MetricReadout(UiText.REMAIN, fmtTime(max(0L, p.remainTimeMs)), emphasis = true)
            MetricReadout(UiText.BPM, p.rawBpm?.let { "%.2f".format(it) } ?: "-")
            MetricReadout(UiText.PITCH, p.pitch?.let { "%+.2f%%".format(it) } ?: "-")
            MetricReadout(UiText.EFFECTIVE, p.effectiveBpm?.let { "%.2f".format(it) } ?: "-", emphasis = true)

            TextButton(onClick = { showDebug = !showDebug }) {
                Text(if (showDebug) UiText.HIDE_DEBUG else UiText.DEBUG, color = C_MUTED)
            }
        }

        if (showDebug) {
            Text(
                "timeSource=${p.timeSource} | ${p.rawStateSummary}",
                color = C_MUTED,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun StateTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .widthIn(min = 76.dp)
    ) {
        Text(text, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TinyFlag(text: String, on: Boolean) {
    Box(
        modifier = Modifier
            .background(if (on) Color(0xFF2A3542) else Color(0xFF1A212B))
            .border(1.dp, if (on) C_PLAY else C_BORDER)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (on) C_TEXT else C_MUTED, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MetricReadout(label: String, value: String, emphasis: Boolean = false) {
    Column(
        modifier = Modifier
            .widthIn(min = 68.dp)
            .background(if (emphasis) Color(0xFF18222E) else Color(0xFF131922))
            .border(1.dp, C_BORDER)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(label.uppercase(), color = C_MUTED, style = MaterialTheme.typography.labelSmall)
        Text(value, color = if (emphasis) C_TEXT else Color(0xFFD0D8E2), fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun BottomReservedZone() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF0F1319))
            .border(1.dp, C_BORDER)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(UiText.RESERVED_ZONE, color = C_MUTED, style = MaterialTheme.typography.labelMedium)
        Text("(layout only, no waveform in this phase)", color = Color(0xFF677485), style = MaterialTheme.typography.labelSmall)
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

private fun JsonObject.optObj(key: String): JsonObject? =
    if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

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
