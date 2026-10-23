package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.ExternalAudio

/**
 * One-shot external audio tracks (e.g. dubbed audio) for the stream that is about to play.
 * Keyed by playback URL so a leftover list cannot attach to a different stream.
 * Mirrors [StreamSidecarSubtitles].
 */
internal object StreamSidecarAudio {
    @Volatile
    private var playbackUrl: String? = null

    @Volatile
    private var audios: List<ExternalAudio> = emptyList()

    fun set(url: String?, audios: List<ExternalAudio>) {
        val playbackUrl = url?.takeIf { it.isNotBlank() }
        this.playbackUrl = playbackUrl
        this.audios = if (playbackUrl == null) emptyList() else audios
    }

    fun forUrl(url: String): List<ExternalAudio> {
        val storedUrl = playbackUrl ?: return emptyList()
        return if (storedUrl == url) audios else emptyList()
    }
}
