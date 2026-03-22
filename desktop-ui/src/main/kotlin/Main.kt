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

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "DJ Link Stage - CDJ Dashboard") {
        MaterialTheme {
            CDJDashboardApp()
        }
    }
}

data class DashboardPlayer(
    val number: Int,
    val online: Boolean,
    val stateText: String,
    val onAir: Boolean,
    val master: Boolean,
    val title: String,
    val artist: String,
    val currentTimeMs: Long,
    val durationMs: Long,
    val remainTimeMs: Long,
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
    val error: String? = null
)

@Composable
private fun CDJDashboardApp() {
    var baseUrl by remember { mutableStateOf("http://127.0.0.1:8080") }
    var refreshMs by remember { mutableStateOf(300) }
    var state by remember { mutableStateOf(DashboardState()) }

    LaunchedEffect(baseUrl, refreshMs) {
        while (isActive) {
            state = fetchDashboardState(baseUrl, state)
            delay(refreshMs.toLong())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        TopStatusBar(
            state = state,
            baseUrl = baseUrl,
            onBaseUrlChange = { baseUrl = it },
            refreshMs = refreshMs,
            onRefreshChange = { refreshMs = it }
        )

        Spacer(Modifier.height(10.dp))

        PlayersGrid(state.players)
    }
}

@Composable
private fun TopStatusBar(
    state: DashboardState,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    refreshMs: Int,
    onRefreshChange: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CDJ Dashboard V1", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("API Base URL") },
                    singleLine = true,
                    modifier = Modifier.width(320.dp)
                )

                Text("刷新:")
                listOf(200, 300, 500).forEach { ms ->
                    FilterChip(
                        selected = refreshMs == ms,
                        onClick = { onRefreshChange(ms) },
                        label = { Text("${ms}ms") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val updateText = if (state.updatedAtMs > 0) {
                timeFmt.format(Instant.ofEpochMilli(state.updatedAtMs).atZone(ZoneId.systemDefault()))
            } else "-"

            val linkStateText = when {
                state.error != null -> "数据中断"
                state.stale -> "数据中断"
                state.players.any { it.online } -> "已连接"
                else -> "无设备"
            }

            val linkStateColor = when {
                state.error != null || state.stale -> Color(0xFFD32F2F)
                state.players.any { it.online } -> Color(0xFF2E7D32)
                else -> Color(0xFFEF6C00)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusPill("Master", state.masterPlayer?.toString() ?: "-")
                StatusPill("Master BPM", state.masterBpm?.let { "%.2f".format(it) } ?: "-")
                StatusPill("DJ Link", linkStateText, valueColor = linkStateColor)
                StatusPill("Scan", when (state.scanEnabled) {
                    true -> "ON"
                    false -> "OFF"
                    null -> "UNKNOWN"
                })
                StatusPill("Last Update", updateText)
            }

            if (state.error != null) {
                Spacer(Modifier.height(6.dp))
                Text("错误: ${state.error}", color = Color(0xFFD32F2F))
            } else if (state.stale) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ 数据超过 2 秒未刷新，可能断连", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, valueColor: Color = Color(0xFF0D47A1)) {
    Row(
        modifier = Modifier
            .border(1.dp, Color(0xFFCFD8DC), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$label:", color = Color(0xFF546E7A))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayersGrid(players: List<DashboardPlayer>) {
    val byNumber = players.associateBy { it.number }
    val fixed = (1..4).map { idx ->
        byNumber[idx] ?: DashboardPlayer(
            number = idx,
            online = false,
            stateText = "OFFLINE",
            onAir = false,
            master = false,
            title = "-",
            artist = "-",
            currentTimeMs = 0,
            durationMs = 0,
            remainTimeMs = 0,
            rawBpm = null,
            pitch = null,
            effectiveBpm = null
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(fixed) { p ->
            PlayerCard(p)
        }
    }
}

@Composable
private fun PlayerCard(p: DashboardPlayer) {
    val stateColor = when (p.stateText) {
        "PLAY" -> Color(0xFF2E7D32)
        "PAUSE" -> Color(0xFFEF6C00)
        "STOP", "CUED", "OFFLINE" -> Color(0xFF607D8B)
        else -> Color(0xFF455A64)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PLAYER ${p.number}", fontWeight = FontWeight.Bold)
                Chip(p.online, if (p.online) "ONLINE" else "OFFLINE")
                Chip(p.onAir, "ON-AIR")
                Chip(p.master, "MASTER")
                Box(modifier = Modifier.background(stateColor, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(p.stateText, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("${p.title.ifBlank { "-" }}", fontWeight = FontWeight.SemiBold)
            Text("Artist: ${p.artist.ifBlank { "-" }}", color = Color(0xFF546E7A))

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Current: ${fmtTime(p.currentTimeMs)}")
                Text("Remain: ${fmtTime(max(0L, p.remainTimeMs))}")
                Text("Raw BPM: ${p.rawBpm?.let { "%.2f".format(it) } ?: "-"}")
                Text("Pitch: ${p.pitch?.let { "%+.2f%%".format(it) } ?: "-"}")
                Text("Effective BPM: ${p.effectiveBpm?.let { "%.2f".format(it) } ?: "-"}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Chip(active: Boolean, label: String) {
    val color = if (active) Color(0xFF2E7D32) else Color(0xFFB0BEC5)
    Box(modifier = Modifier.background(color, MaterialTheme.shapes.small).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(label, color = Color.White)
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
                val currentTimeMs = p.optLong("currentTimeMs", p.optLong("beatTimeMs", 0L))
                val durationMs = p.optLong("durationMs", 0L)
                val remain = if (durationMs > 0) durationMs - currentTimeMs else 0L
                val rawBpm = p.optDoubleOrNull("bpm")
                val pitch = p.optDoubleOrNull("pitch")
                val effective = if (rawBpm != null && pitch != null) rawBpm * (1.0 + pitch / 100.0) else rawBpm

                val track = p.optObj("track")
                val title = track?.optString("title") ?: p.optString("title") ?: "-"
                val artist = track?.optString("artist") ?: p.optString("artist") ?: "-"

                val explicitState = p.optString("state") ?: p.optString("playState") ?: p.optString("status")
                val stateText = when {
                    !online -> "OFFLINE"
                    !explicitState.isNullOrBlank() -> explicitState.uppercase()
                    playing -> "PLAY"
                    currentTimeMs in 1..100 -> "CUED"
                    currentTimeMs <= 0 -> "STOP"
                    else -> "PAUSE"
                }

                players += DashboardPlayer(
                    number = number,
                    online = online,
                    stateText = stateText,
                    onAir = onAir,
                    master = master,
                    title = title,
                    artist = artist,
                    currentTimeMs = currentTimeMs,
                    durationMs = durationMs,
                    remainTimeMs = remain,
                    rawBpm = rawBpm,
                    pitch = pitch,
                    effectiveBpm = effective
                )
            }
        }

        val master = players.firstOrNull { it.master } ?: players.firstOrNull { it.online && it.stateText == "PLAY" }
        val updatedAt = System.currentTimeMillis()

        DashboardState(
            players = players,
            masterPlayer = master?.number,
            masterBpm = master?.effectiveBpm ?: master?.rawBpm,
            scanEnabled = scanJson?.optBool("scanning", false),
            updatedAtMs = updatedAt,
            stale = false,
            error = null
        )
    } catch (e: Exception) {
        old.copy(
            stale = true,
            error = e.message ?: "获取状态失败"
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

private fun JsonObject.optBool(key: String, default: Boolean): Boolean =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asBoolean }.getOrElse { default } else default

private fun JsonObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null
