import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.delay
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent

@Composable
fun BeatLinkDetailWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val component = remember(player.number) {
        WaveformDetailComponent(player.number).apply {
            setMonitoredPlayer(player.number)
            setAutoScroll(true)
        }
    }

    LaunchedEffect(player.number, player.rekordboxId, player.title, player.artist) {
        val finder = WaveformFinder.getInstance()
        while (true) {
            runCatching {
                val detail = finder.getLatestDetailFor(player.number)
                if (detail != null) {
                    component.setWaveform(detail, null as org.deepsymmetry.beatlink.data.TrackMetadata?, null)
                }
            }
            delay(300)
        }
    }

    LaunchedEffect(progressMs, player.stateText) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        component.setPlaybackState(player.number, progressMs, playing)
    }

    SwingPanel(
        factory = { component },
        update = { },
        modifier = modifier
    )
}

@Composable
fun BeatLinkPreviewWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val component = remember(player.number) {
        WaveformPreviewComponent(player.number).apply {
            setMonitoredPlayer(player.number)
        }
    }

    LaunchedEffect(player.number, player.rekordboxId, player.title, player.artist) {
        val finder = WaveformFinder.getInstance()
        while (true) {
            runCatching {
                val preview = finder.getLatestPreviewFor(player.number)
                if (preview != null) {
                    component.setWaveformPreview(preview, player.number, null)
                }
            }
            delay(300)
        }
    }

    LaunchedEffect(progressMs, player.stateText) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        component.setPlaybackState(player.number, progressMs, playing)
    }

    SwingPanel(
        factory = { component },
        update = { },
        modifier = modifier
    )
}
