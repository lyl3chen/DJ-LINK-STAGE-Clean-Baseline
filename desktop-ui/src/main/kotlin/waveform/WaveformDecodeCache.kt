data class WaveformDecodedData(
    val heights: List<Int>
)

data class WaveformDeckCacheEntry(
    var identity: WaveformTrackIdentity,
    var sourceState: WaveformSourceState = WaveformSourceState.UNKNOWN,
    var decodedDetailRaw: WaveformDecodedData? = null,
    var decodedPreviewRaw: WaveformDecodedData? = null,
    var lastUpdatedMs: Long = System.currentTimeMillis()
)

class WaveformDecodeCache {
    private val byDeck = mutableMapOf<Int, WaveformDeckCacheEntry>()

    fun getOrCreate(identity: WaveformTrackIdentity): WaveformDeckCacheEntry {
        val existing = byDeck[identity.deckNumber]
        if (existing == null || existing.identity.trackToken != identity.trackToken) {
            val created = WaveformDeckCacheEntry(identity = identity)
            byDeck[identity.deckNumber] = created
            return created
        }
        existing.lastUpdatedMs = System.currentTimeMillis()
        return existing
    }
}
