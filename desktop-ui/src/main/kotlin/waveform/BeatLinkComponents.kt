import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.delay
import org.deepsymmetry.beatlink.BeatFinder
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.BeatGridFinder
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TrackMetadata
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent
import javax.swing.SwingUtilities

private object BeatLinkStartup {
    @Volatile
    private var attempted = false

    @Synchronized
    fun ensureRunning() {
        if (attempted && WaveformFinder.getInstance().isRunning) return
        attempted = true

        println("[BeatLinkStartup] begin")
        try {
            val deviceFinder = DeviceFinder.getInstance()
            if (!deviceFinder.isRunning) {
                deviceFinder.start()
                println("[BeatLinkStartup] DeviceFinder started=true")
            } else {
                println("[BeatLinkStartup] DeviceFinder already running")
            }

            val virtualCdj = VirtualCdj.getInstance()
            if (!virtualCdj.isRunning) {
                virtualCdj.setDeviceNumber(4.toByte())
                virtualCdj.start()
                println("[BeatLinkStartup] VirtualCdj started=true device=4")
            } else {
                println("[BeatLinkStartup] VirtualCdj already running")
            }

            val beatFinder = BeatFinder.getInstance()
            if (!beatFinder.isRunning) {
                beatFinder.start()
                println("[BeatLinkStartup] BeatFinder started=true")
            } else {
                println("[BeatLinkStartup] BeatFinder already running")
            }

            val metadataFinder = MetadataFinder.getInstance()
            if (!metadataFinder.isRunning) {
                metadataFinder.start()
                println("[BeatLinkStartup] MetadataFinder started=true")
            } else {
                println("[BeatLinkStartup] MetadataFinder already running")
            }

            val beatGridFinder = BeatGridFinder.getInstance()
            if (!beatGridFinder.isRunning) {
                beatGridFinder.start()
                println("[BeatLinkStartup] BeatGridFinder started=true")
            } else {
                println("[BeatLinkStartup] BeatGridFinder already running")
            }

            val waveformFinder = WaveformFinder.getInstance()
            if (!waveformFinder.isRunning) {
                waveformFinder.setFindDetails(true)
                waveformFinder.start()
                println("[BeatLinkStartup] WaveformFinder started=true")
            } else {
                println("[BeatLinkStartup] WaveformFinder already running")
            }

            println("[BeatLinkStartup] done waveformRunning=${waveformFinder.isRunning}")
        } catch (e: Exception) {
            println("[BeatLinkStartup] failed: ${e::class.java.name}: ${e.message}")
            e.printStackTrace()
        }
    }
}

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

        BeatLinkStartup.ensureRunning()

        while (true) {
            runCatching {
                println("[WaveDetail] player=${player.number} finderRunning=${finder.isRunning}")
                if (!finder.isRunning) {
                    BeatLinkStartup.ensureRunning()
                }
                if (!finder.isRunning) {
                    delay(300)
                    return@runCatching
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

        BeatLinkStartup.ensureRunning()

        while (true) {
            runCatching {
                println("[WavePreview] player=${player.number} finderRunning=${finder.isRunning}")
                if (!finder.isRunning) {
                    BeatLinkStartup.ensureRunning()
                }
                if (!finder.isRunning) {
                    delay(300)
                    return@runCatching
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
