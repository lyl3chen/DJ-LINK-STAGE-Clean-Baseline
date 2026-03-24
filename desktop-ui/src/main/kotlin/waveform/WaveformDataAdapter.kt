data class WaveformResolvedData(
    val sourceTag: WaveformSourceTag,
    val detailHeights: List<Int>,
    val previewHeights: List<Int>,
    val detailColors: List<Int>,
    val detailEnvelopeBinMs: Int,
    val detailEnvelopeMin: List<Int>,
    val detailEnvelopeMax: List<Int>,
    val detailEnvelopeColor: List<Int>,
    val previewEnvelopeBinMs: Int,
    val previewEnvelopeMin: List<Int>,
    val previewEnvelopeMax: List<Int>,
    val previewEnvelopeColor: List<Int>
)

object WaveformDataAdapter {
    private val cache = WaveformDecodeCache()

    fun resolve(p: DashboardPlayer): WaveformResolvedData {
        val identity = WaveformTrackIdentity.fromPlayer(p)
        val entry = cache.getOrCreate(identity)

        decodeRawWaveHeights(p.detailRawBase64, target = 900)?.takeIf { it.heights.isNotEmpty() }?.let {
            entry.decodedDetailRaw = it
            entry.lastUpdatedMs = System.currentTimeMillis()
        }
        decodeRawWaveHeights(p.previewRawBase64, target = 240)?.takeIf { it.heights.isNotEmpty() }?.let {
            entry.decodedPreviewRaw = it
            entry.lastUpdatedMs = System.currentTimeMillis()
        }

        val rawReady = (entry.decodedDetailRaw?.heights?.isNotEmpty() == true) &&
            (entry.decodedPreviewRaw?.heights?.isNotEmpty() == true)

        entry.sourceState = when (entry.sourceState) {
            WaveformSourceState.LATCHED_RAW -> WaveformSourceState.LATCHED_RAW
            WaveformSourceState.UNKNOWN,
            WaveformSourceState.SAMPLE_FALLBACK -> {
                when {
                    rawReady -> WaveformSourceState.LATCHED_RAW
                    p.detailSampleHeights.isNotEmpty() || p.previewSample.isNotEmpty() -> WaveformSourceState.SAMPLE_FALLBACK
                    else -> WaveformSourceState.UNKNOWN
                }
            }
        }

        val useRaw = entry.sourceState == WaveformSourceState.LATCHED_RAW
        val detailHeights = if (useRaw) {
            entry.decodedDetailRaw?.heights ?: p.detailSampleHeights
        } else {
            p.detailSampleHeights
        }
        val previewHeights = if (useRaw) {
            entry.decodedPreviewRaw?.heights ?: p.previewSample
        } else {
            p.previewSample
        }

        val sourceTag = when (entry.sourceState) {
            WaveformSourceState.LATCHED_RAW -> WaveformSourceTag.RAW
            WaveformSourceState.SAMPLE_FALLBACK -> WaveformSourceTag.SAMPLE_FALLBACK
            WaveformSourceState.UNKNOWN -> WaveformSourceTag.NONE
        }

        val detailEnvMin = if (p.detailEnvelopeMin.isNotEmpty()) p.detailEnvelopeMin else detailHeights
        val detailEnvMax = if (p.detailEnvelopeMax.isNotEmpty()) p.detailEnvelopeMax else detailHeights
        val previewEnvMin = if (p.previewEnvelopeMin.isNotEmpty()) p.previewEnvelopeMin else previewHeights
        val previewEnvMax = if (p.previewEnvelopeMax.isNotEmpty()) p.previewEnvelopeMax else previewHeights

        return WaveformResolvedData(
            sourceTag = sourceTag,
            detailHeights = detailHeights,
            previewHeights = previewHeights,
            detailColors = p.detailSampleColors,
            detailEnvelopeBinMs = p.detailEnvelopeBinMs,
            detailEnvelopeMin = detailEnvMin,
            detailEnvelopeMax = detailEnvMax,
            detailEnvelopeColor = if (p.detailEnvelopeColor.isNotEmpty()) p.detailEnvelopeColor else p.detailSampleColors,
            previewEnvelopeBinMs = p.previewEnvelopeBinMs,
            previewEnvelopeMin = previewEnvMin,
            previewEnvelopeMax = previewEnvMax,
            previewEnvelopeColor = p.previewEnvelopeColor
        )
    }

    private fun decodeRawWaveHeights(rawBase64: String?, target: Int): WaveformDecodedData? {
        if (rawBase64.isNullOrBlank()) return null
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(rawBase64)
            if (bytes.isEmpty()) return@runCatching null
            val src = bytes.map { it.toInt() and 0xFF }
            val heights = resampleMax(src, target)
            if (heights.isEmpty()) null else WaveformDecodedData(heights)
        }.getOrNull()
    }

    private fun resampleMax(src: List<Int>, target: Int): List<Int> {
        if (src.isEmpty() || target <= 0) return emptyList()
        if (src.size == target) return src
        if (src.size < target) {
            return List(target) { i -> src[(i * src.size / target).coerceIn(0, src.size - 1)] }
        }
        val out = MutableList(target) { 0 }
        val bucket = src.size.toFloat() / target.toFloat()
        for (i in 0 until target) {
            val s = (i * bucket).toInt()
            val e = (((i + 1) * bucket).toInt()).coerceAtMost(src.size)
            var maxV = 0
            for (j in s until e) if (src[j] > maxV) maxV = src[j]
            out[i] = maxV
        }
        return out
    }
}
