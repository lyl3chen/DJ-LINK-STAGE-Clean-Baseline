enum class WaveformSourceState {
    UNKNOWN,
    SAMPLE_FALLBACK,
    LATCHED_RAW
}

enum class WaveformSourceTag {
    RAW,
    SAMPLE_FALLBACK,
    NONE
}

data class WaveformTrackIdentity(
    val deckNumber: Int,
    val trackToken: String
) {
    companion object {
        fun fromPlayer(p: DashboardPlayer): WaveformTrackIdentity {
            val token = when {
                p.rekordboxId > 0 -> "rbid:${p.rekordboxId}"
                !p.title.isBlank() || !p.artist.isBlank() || p.durationMs > 0L ->
                    "meta:${p.title}|${p.artist}|${p.durationMs}|${p.sourceSlot ?: "-"}|${p.sourcePlayer}"
                else -> "deck:${p.number}:unknown"
            }
            return WaveformTrackIdentity(p.number, token)
        }
    }
}
