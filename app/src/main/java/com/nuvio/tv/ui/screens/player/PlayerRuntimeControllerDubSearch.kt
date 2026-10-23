@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import com.nuvio.tv.core.dub.DubOutcome
import com.nuvio.tv.core.dub.DubRequest
import com.nuvio.tv.core.dub.DubSourceManager
import kotlinx.coroutines.launch

private const val DUB_SEARCH_TAG = "DubSearch"

/**
 * Looks up a Turkish dub through the remote recipe list and merges it into the stream.
 *
 * [manual] true: started from the audio menu. It refreshes the recipe file first and reports the
 * result with a toast. [manual] false: started once automatically when playback is ready; it is
 * silent unless a dub is found.
 */
internal fun PlayerRuntimeController.searchDubAudio(manual: Boolean) {
    if (_exoPlayer == null) {
        if (manual) Toast.makeText(context, "Harici ses sadece ExoPlayer ile eklenebilir", Toast.LENGTH_LONG).show()
        return
    }
    // A plugin already delivered its own audio track for this stream.
    if (!manual && streamExternalAudios.isNotEmpty()) return
    if (dubSearchJob?.isActive == true) {
        if (manual) Toast.makeText(context, "Dublaj zaten aranıyor…", Toast.LENGTH_SHORT).show()
        return
    }

    val request = buildDubRequest()
    if (request.imdbId == null && request.title.isNullOrBlank()) {
        if (manual) Toast.makeText(context, "Bu içerik için arama bilgisi yok", Toast.LENGTH_LONG).show()
        return
    }
    if (manual) Toast.makeText(context, "Türkçe dublaj aranıyor…", Toast.LENGTH_SHORT).show()

    dubSearchJob = scope.launch {
        val outcome = try {
            DubSourceManager.find(context, request, manual)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(DUB_SEARCH_TAG, "Search crashed: ${e.message}")
            null
        }
        when (outcome) {
            is DubOutcome.Found -> {
                Log.i(DUB_SEARCH_TAG, "Dub found via ${outcome.providerId}")
                attachExternalAudio(outcome.audio)
            }
            DubOutcome.NotFound ->
                if (manual) Toast.makeText(context, "Türkçe dublaj bulunamadı", Toast.LENGTH_LONG).show()
            DubOutcome.AutoDisabled -> Unit
            is DubOutcome.ConfigError ->
                if (manual) Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            null ->
                if (manual) Toast.makeText(context, "Dublaj araması başarısız", Toast.LENGTH_LONG).show()
        }
        Unit
    }
}

private fun PlayerRuntimeController.buildDubRequest(): DubRequest {
    val imdbId = listOf(contentId, videoId)
        .firstOrNull { it?.startsWith("tt") == true }
        ?.substringBefore(':')
    val durationMs = _exoPlayer?.duration ?: -1L
    return DubRequest(
        imdbId = imdbId,
        title = contentName?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() },
        year = year?.take(4)?.toIntOrNull(),
        isMovie = contentType.equals("movie", ignoreCase = true),
        season = currentSeason,
        episode = currentEpisode,
        durationMin = if (durationMs > 0L) (durationMs / 60_000L).toInt() else null
    )
}
