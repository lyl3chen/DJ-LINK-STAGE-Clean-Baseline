import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "DJ Link Stage Desktop") {
        MaterialTheme {
            AppShell()
        }
    }
}

@Composable
private fun AppShell() {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("DJ Link Stage - Native Desktop UI (V1 Skeleton)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel("曲库区", Modifier.weight(1f))
            Panel("播放器区", Modifier.weight(1f))
            Panel("分析区", Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Panel("状态/日志区", Modifier.fillMaxWidth().height(120.dp))
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Text(title, color = Color(0xFF2E7D32))
            Text("待接入真实功能", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
        }
    }
}
