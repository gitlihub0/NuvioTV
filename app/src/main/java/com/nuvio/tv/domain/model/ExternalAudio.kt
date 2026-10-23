package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * A separate audio-only track (for example a Turkish dub) that is played together with the
 * main video stream. The player merges it with the video as an additional audio track.
 */
@Immutable
data class ExternalAudio(
    val url: String,
    val lang: String = "tr",
    val label: String? = null,
    val headers: Map<String, String>? = null
)
