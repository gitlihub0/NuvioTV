@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EXTERNAL_AUDIO_TAG = "ExternalAudio"

/** After this long, pick among the sources whose tracks showed up instead of waiting for the rest. */
private const val TRACK_WAIT_MS = 20_000L

/** Stop listening for tracks altogether after this long (bad URL, unsupported stream). */
private const val TRACK_GIVE_UP_MS = 45_000L

private typealias AudioCandidate = Triple<Tracks.Group, Int, Format>

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

    attachExternalAudio(
        ExternalAudio(
            url = url,
            lang = "tr",
            label = "Türkçe Dublaj (harici)",
            headers = headers.ifEmpty { null }
        )
    )
}

/** Single-audio convenience for the manual URL dialog. */
internal fun PlayerRuntimeController.attachExternalAudio(audio: ExternalAudio) =
    attachExternalAudios(listOf(audio))

/**
 * Merges every audio in [audios] into the running stream with ONE media source rebuild, so each
 * one shows up as its own row in the audio menu (named by its label). The first usable one is
 * selected automatically; the others stay available in the menu. A source whose tracks are all
 * marked as another language is dropped. Must be called on the main thread.
 */
internal fun PlayerRuntimeController.attachExternalAudios(audios: List<ExternalAudio>) {
    val player = _exoPlayer ?: return
    if (audios.isEmpty()) return

    // Signed URLs change on every search, so the provider is the identity of a dub; a manually
    // typed URL falls back to the URL without its query.
    val knownKeys = streamExternalAudios.map { audioIdentity(it) }.toHashSet()
    val fresh = audios.filter { knownKeys.add(audioIdentity(it)) }
    if (fresh.isEmpty()) {
        Toast.makeText(context, "Bulunan ses zaten ekli", Toast.LENGTH_SHORT).show()
        return
    }
    streamExternalAudios = streamExternalAudios + fresh
    fresh.forEach {
        Log.i(
            EXTERNAL_AUDIO_TAG,
            "Adding external audio source=${it.sourceId} label=${it.label} url=${it.url.take(80)} headers=${it.headers?.keys} delay=${it.audioDelayMs}"
        )
    }

    val position = player.currentPosition
    val playWhenReady = player.playWhenReady

    // Sources we are still waiting for, in menu order. The first one that is usable gets selected.
    val expected = fresh.toMutableList()
    var giveUp = false
    var finished = false
    lateinit var listener: Player.Listener

    fun finish() {
        finished = true
        player.removeListener(listener)
    }

    fun evaluate(tracks: Tracks) {
        if (finished) return
        if (expected.isEmpty()) {
            finish()
            return
        }

        val found = LinkedHashMap<ExternalAudio, List<AudioCandidate>>()
        for (audio in expected) {
            // MergingMediaSource child index: 0 is the main stream, the external audios follow.
            val child = streamExternalAudios.indexOfFirst { it.url == audio.url } + 1
            if (child <= 0) continue
            val list = audioCandidates(tracks, child)
            if (list.isNotEmpty()) found[audio] = list
        }
        if (found.isEmpty()) return
        // Wait until every source has reported its tracks, unless the wait ran out.
        if (!giveUp && found.size < expected.size) return

        found.forEach { (audio, list) ->
            list.forEach { (g, i, f) ->
                Log.i(
                    EXTERNAL_AUDIO_TAG,
                    "Candidate [${audio.label}] group=${g.mediaTrackGroup.id} idx=$i lang=${f.language} label=${f.label} id=${f.id}"
                )
            }
        }

        // A source that says it is another language (for example English on a dual-audio page)
        // is dropped instead of being offered as a dub.
        val rejected = found.filter { (audio, list) ->
            list.all { langKnown(it.third) } && list.none { langMatches(it.third, wantedLang(audio)) }
        }
        if (rejected.isNotEmpty()) {
            val seen = rejected.values.flatten().joinToString { it.third.language.orEmpty() }
            Log.w(EXTERNAL_AUDIO_TAG, "Dropping non-matching external audio (found: $seen): ${rejected.keys.map { it.label }}")
            val urls = rejected.keys.map { it.url }.toSet()
            streamExternalAudios = streamExternalAudios.filterNot { it.url in urls }
            expected.removeAll { it.url in urls }
            rebuildMediaSource(player.currentPosition, playWhenReady)
            Toast.makeText(context, "Bulunan ses Türkçe değil ($seen), eklenmedi", Toast.LENGTH_LONG).show()
            // The track list changes again after the rebuild; this listener handles the rest then.
            return
        }

        val chosen = expected.firstOrNull { found.containsKey(it) } ?: return
        val candidates = found.getValue(chosen)
        val pick = pickDubTrack(candidates, wantedLang(chosen))
        Log.i(
            EXTERNAL_AUDIO_TAG,
            "Selecting [${chosen.label}] group=${pick.first.mediaTrackGroup.id} idx=${pick.second} " +
                "lang=${pick.third.language} label=${pick.third.label} (of ${candidates.size}; sources=${found.size})"
        )
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        chosen.audioDelayMs?.let { applyDubAudioDelay(it, chosen.lang) }
        maybeAutoSyncDub(chosen)
        finish()
    }

    listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) = evaluate(tracks)
    }
    player.addListener(listener)
    scope.launch {
        // Safety: do not wait forever for a source that never shows up (bad URL, unsupported stream).
        delay(TRACK_WAIT_MS)
        if (!finished) {
            giveUp = true
            evaluate(player.currentTracks)
        }
        delay(TRACK_GIVE_UP_MS - TRACK_WAIT_MS)
        if (!finished) finish()
    }

    rebuildMediaSource(position, playWhenReady)
    val message = if (fresh.size == 1) "Harici ses ekleniyor…" else "${fresh.size} harici ses ekleniyor…"
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun PlayerRuntimeController.rebuildMediaSource(position: Long, playWhenReady: Boolean) {
    val player = _exoPlayer ?: return
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
}

/** Applies the provider's fixed offset through the existing stream-delay path (scoped to [lang]). */
private fun PlayerRuntimeController.applyDubAudioDelay(delayMs: Int, lang: String) {
    val parts = audioDelayParts
    if (parts.streamMs != 0 && !PlayerSubtitleUtils.matchesLanguageCode(parts.streamLanguage, lang)) {
        Log.w(EXTERNAL_AUDIO_TAG, "Stream already has a delay for another language, dub delay skipped")
        return
    }
    audioDelayParts = parts.withStream(parts.streamMs + delayMs, lang)
    syncStreamDelayWithSelectedTracks(forceApply = true)
    Log.i(EXTERNAL_AUDIO_TAG, "Dub audio delay applied: ${delayMs}ms (total ${audioDelayParts.totalMs}ms)")
}

private fun audioIdentity(audio: ExternalAudio): String =
    audio.sourceId?.let { "src:$it" } ?: audio.url.substringBefore('#').substringBefore('?')

private fun wantedLang(audio: ExternalAudio): String = audio.lang.lowercase().ifBlank { "tr" }

/** Every audio rendition that belongs to the external source at MergingMediaSource [childIndex]. */
private fun audioCandidates(tracks: Tracks, childIndex: Int): List<AudioCandidate> {
    val out = mutableListOf<AudioCandidate>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        val child = group.mediaTrackGroup.id.substringBefore(':').toIntOrNull() ?: continue
        if (child != childIndex) continue
        for (i in 0 until group.length) out.add(Triple(group, i, group.getTrackFormat(i)))
    }
    return out
}

/**
 * Chooses which rendition of a multi-language external playlist is the dub we asked for.
 * Order: language tag or label matches [wantLang]; else a track that is not marked as another
 * known language (so an English track is never picked as "Turkish" by accident); else the first.
 */
private fun pickDubTrack(candidates: List<AudioCandidate>, wantLang: String): AudioCandidate {
    val want = wantLang.take(2)
    val wantNames = if (want == "tr") listOf("türk", "turk", "turkish", "tr", "tur") else listOf(wantLang)
    fun lang(f: Format) = f.language?.lowercase().orEmpty()
    fun label(f: Format) = f.label?.lowercase().orEmpty()

    candidates.firstOrNull { (_, _, f) ->
        val l = lang(f)
        l.startsWith(want) || (want == "tr" && l.startsWith("tur"))
    }?.let { return it }

    candidates.firstOrNull { (_, _, f) ->
        val words = label(f).split(Regex("[^a-zçğıöşü]+")).filter { it.isNotEmpty() }
        wantNames.any { n -> words.contains(n) || label(f).contains(n) && n.length > 3 }
    }?.let { return it }

    val other = setOf("en", "eng", "english", "ingilizce", "de", "fr", "es", "ru", "ar")
    candidates.firstOrNull { (_, _, f) ->
        val l = lang(f).take(3)
        val lb = label(f)
        (l.isEmpty() || l == "und") && other.none { lb.contains(it) && (it.length > 3 || lb.split(Regex("[^a-z]+")).contains(it)) }
    }?.let { return it }

    return candidates.first()
}

private fun langKnown(f: Format): Boolean {
    val l = f.language?.lowercase().orEmpty()
    return l.isNotEmpty() && l != "und" && l != "zxx"
}

private fun langMatches(f: Format, wantLang: String): Boolean {
    val l = f.language?.lowercase().orEmpty()
    val want = wantLang.lowercase().take(2)
    return l.startsWith(want) || (want == "tr" && l.startsWith("tur"))
}
