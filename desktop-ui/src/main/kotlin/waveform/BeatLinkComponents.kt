import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformDetailComponent
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreview
import org.deepsymmetry.beatlink.data.WaveformPreviewComponent
import java.nio.ByteBuffer
import java.util.Base64

private fun mapSlot(slot: String?): CdjStatus.TrackSourceSlot = when ((slot ?: "").uppercase()) {
    "CD" -> CdjStatus.TrackSourceSlot.CD_SLOT
    "SD" -> CdjStatus.TrackSourceSlot.SD_SLOT
    "USB" -> CdjStatus.TrackSourceSlot.USB_SLOT
    "COLLECTION" -> CdjStatus.TrackSourceSlot.COLLECTION
    "NO_TRACK" -> CdjStatus.TrackSourceSlot.NO_TRACK
    else -> CdjStatus.TrackSourceSlot.UNKNOWN
}

private fun dataRef(player: DashboardPlayer): DataReference {
    val slot = mapSlot(player.sourceSlot)
    val rbid = if (player.rekordboxId > 0) player.rekordboxId else 1
    val type = if (player.hasTrack) CdjStatus.TrackType.REKORDBOX else CdjStatus.TrackType.NO_TRACK
    return DataReference(player.number, slot, rbid, type)
}

@Composable
fun BeatLinkDetailWave(
    player: DashboardPlayer,
    progressMs: Long,
    modifier: Modifier = Modifier
) {
    val component = remember(player.number) { WaveformDetailComponent(player.number) }

    LaunchedEffect(player.detailRawBase64, player.detailRawIsColor, player.rekordboxId, player.sourceSlot, player.number) {
        val raw = player.detailRawBase64
        if (!raw.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.getDecoder().decode(raw)
                val detail = WaveformDetail(dataRef(player), ByteBuffer.wrap(bytes), WaveformFinder.WaveformStyle.RGB)
                component.setWaveform(detail, null as org.deepsymmetry.beatlink.data.TrackMetadata?, null)
            }
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
    val component = remember(player.number) { WaveformPreviewComponent(player.number) }

    LaunchedEffect(player.previewRawBase64, player.previewRawIsColor, player.rekordboxId, player.sourceSlot, player.number) {
        val raw = player.previewRawBase64
        if (!raw.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.getDecoder().decode(raw)
                val preview = WaveformPreview(dataRef(player), ByteBuffer.wrap(bytes), WaveformFinder.WaveformStyle.RGB)
                component.setWaveformPreview(preview, player.number, null)
            }
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
