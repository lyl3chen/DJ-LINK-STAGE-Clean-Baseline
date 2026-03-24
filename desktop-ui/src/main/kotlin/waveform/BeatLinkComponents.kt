import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.delay
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TrackMetadata
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent
import javax.swing.SwingUtilities

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
        val metadataFinder = MetadataFinder.getInstance()
        var lastNullLogged = false

        while (true) {
            runCatching {
                if (!finder.isRunning) {
                    runCatching { finder.start() }
                }
                if (!finder.isFindingDetails) {
                    finder.setFindDetails(true)
                }

                var detail = finder.getLatestDetailFor(player.number)
                if (detail == null) {
                    val trackRef = metadataFinder.getLatestMetadataFor(player.number)?.trackReference
                    if (trackRef != null) {
                        detail = finder.requestWaveformDetailFrom(trackRef)
                    }
                }

                if (detail != null) {
                    lastNullLogged = false
                    SwingUtilities.invokeLater {
                        component.setMonitoredPlayer(player.number)
                        component.setWaveform(detail, null as TrackMetadata?, null)
                        component.revalidate()
                        component.repaint()
                    }
                } else if (!lastNullLogged) {
                    println("[WaveDetail] player=${player.number} detail=null finderRunning=${finder.isRunning} findingDetails=${finder.isFindingDetails}")
                    lastNullLogged = true
                }
            }.onFailure {
                println("[WaveDetail] player=${player.number} error=${it.message}")
            }
            delay(300)
        }
    }

    LaunchedEffect(progressMs, player.stateText) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        SwingUtilities.invokeLater {
            component.setPlaybackState(player.number, progressMs, playing)
            component.repaint()
        }
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
        val metadataFinder = MetadataFinder.getInstance()
        var lastNullLogged = false

        while (true) {
            runCatching {
                if (!finder.isRunning) {
                    runCatching { finder.start() }
                }

                var preview = finder.getLatestPreviewFor(player.number)
                if (preview == null) {
                    val trackRef = metadataFinder.getLatestMetadataFor(player.number)?.trackReference
                    if (trackRef != null) {
                        preview = finder.requestWaveformPreviewFrom(trackRef)
                    }
                }

                if (preview != null) {
                    lastNullLogged = false
                    val durationSec = (player.durationMs / 1000L).toInt().coerceAtLeast(0)
                    SwingUtilities.invokeLater {
                        component.setMonitoredPlayer(player.number)
                        component.setWaveformPreview(preview, durationSec, null)
                        component.revalidate()
                        component.repaint()
                    }
                } else if (!lastNullLogged) {
                    println("[WavePreview] player=${player.number} preview=null finderRunning=${finder.isRunning}")
                    lastNullLogged = true
                }
            }.onFailure {
                println("[WavePreview] player=${player.number} error=${it.message}")
            }
            delay(300)
        }
    }

    LaunchedEffect(progressMs, player.stateText) {
        val playing = player.stateText.equals("PLAYING", true) || player.stateText.equals("PLAY", true)
        SwingUtilities.invokeLater {
            component.setPlaybackState(player.number, progressMs, playing)
            component.repaint()
        }
    }

    SwingPanel(
        factory = { component },
        update = { },
        modifier = modifier
    )
}
