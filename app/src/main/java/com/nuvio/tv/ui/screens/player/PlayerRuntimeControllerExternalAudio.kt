@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EXTERNAL_AUDIO_TAG = "ExternalAudio"

/**
 * Manually attaches an audio-only URL (for example a Turkish dub) to the current stream.
 * ExoPlayer only: the media source is rebuilt with a MergingMediaSource at the current position
 * and the new audio track is selected as soon as it shows up in the track list.
 */
internal fun PlayerRuntimeController.addExternalAudio(rawUrl: String, referer: String?) {
    val player = _exoPlayer
    if (player == null) {
        Toast.makeText(context, "Harici ses sadece ExoPlayer ile eklenebilir", Toast.LENGTH_LONG).show()
        return
    }
    val url = rawUrl.trim()
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        Toast.makeText(context, "Geçerli bir http(s) bağlantısı girin", Toast.LENGTH_LONG).show()
        return
    }

    val headers = buildMap {
        val ref = referer?.trim()?.takeIf { it.isNotBlank() }
        if (ref != null) {
            put("Referer", ref)
            runCatching {
                val uri = Uri.parse(ref)
                val scheme = uri.scheme
                val host = uri.host
                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) put("Origin", "$scheme://$host")
            }
        }
    }

    val audio = ExternalAudio(
        url = url,
        lang = "tr",
        label = "Türkçe Dublaj (harici)",
        headers = headers.ifEmpty { null }
    )
    streamExternalAudios = streamExternalAudios + audio
    // MergingMediaSource child index of the audio we just added (0 is the main stream).
    val newChildIndex = streamExternalAudios.size

    Log.i(EXTERNAL_AUDIO_TAG, "Adding external audio url=${url.take(80)} headers=${headers.keys} children=$newChildIndex")

    val position = player.currentPosition
    val playWhenReady = player.playWhenReady

    val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                val child = group.mediaTrackGroup.id.substringBefore(':').toIntOrNull() ?: continue
                if (child != newChildIndex || group.length == 0) continue
                Log.i(EXTERNAL_AUDIO_TAG, "External audio track found, selecting it (group=${group.mediaTrackGroup.id})")
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
                player.removeListener(this)
                return
            }
        }
    }
    player.addListener(listener)
    scope.launch {
        // Safety: stop listening if the track never appears (bad URL, unsupported stream).
        delay(20_000)
        player.removeListener(listener)
    }

    player.setMediaSource(
        mediaSourceFactory.createMediaSource(
            context = context,
            url = currentStreamUrl,
            headers = currentHeaders,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders,
            mimeTypeOverride = currentStreamMimeType,
            audioDelayUsProvider = audioDelayUs::get,
            cacheKey = currentStreamCacheKey,
            externalAudios = streamExternalAudios
        ),
        position
    )
    player.prepare()
    player.playWhenReady = playWhenReady
    Toast.makeText(context, "Harici ses ekleniyor…", Toast.LENGTH_SHORT).show()
}
