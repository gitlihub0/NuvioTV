@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import com.nuvio.tv.core.dub.DubAudioProbe
import com.nuvio.tv.core.dub.DubSyncAnalyzer
import com.nuvio.tv.core.dub.ProbeCapture
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val DUB_SYNC_TAG = "DubSync"

/**
 * true: a dub without a fixed `audioDelayMs` in the recipe and without a remembered result is
 * measured automatically right after it is attached. Off by default because the measurement reads
 * several short stretches of the ORIGINAL stream (video included, for interleaved files: for a 4K
 * remux that is tens of MB per stretch). The audio menu entry "Dublajı senkronla" always works,
 * and a result that was measured once is remembered and applied automatically next time.
 */
private const val DUB_AUTO_SYNC_AFTER_ATTACH = false

/**
 * Length of one stretch. Short on purpose: the cost is the download of the original, and many
 * short stretches spread over the film are worth more than a few long ones.
 */
private const val SYNC_WINDOW_MS = 15_000L

/** Never more stretches than this, however long it takes (each one is a separate chance). */
private const val SYNC_MAX_WINDOWS = 8

/**
 * Where the stretches are, as fractions of the duration, in the order they are used: any prefix of
 * this list is already spread over the film. Intro and end credits are avoided.
 */
private val SYNC_FRACTIONS = doubleArrayOf(0.30, 0.60, 0.45, 0.75, 0.20, 0.52, 0.68, 0.38)

private const val SYNC_MIN_START_MS = 60_000L
private const val SYNC_END_MARGIN_MS = 60_000L
private const val SYNC_MIN_DURATION_MS = 8 * 60_000L

/** The aim: finish within this. Not a hard stop: it only decides whether an extra refinement window is worth it. */
private const val SYNC_TARGET_MS = 60_000L

/** A window that takes longer than this is given up on (slow source, stalled download). */
private const val SYNC_WINDOW_TIMEOUT_MS = 30_000L

/** A shift smaller than this is below what people notice; leave the delay alone. */
private const val SYNC_IGNORE_BELOW_MS = 40

/** Subtitle-only results are accurate to roughly +-150 ms; smaller corrections would be noise. */
private const val SYNC_IGNORE_BELOW_MS_COARSE = 100

/** Audio menu entry: measures the dub that is currently selected. */
internal fun PlayerRuntimeController.syncDubAudio() {
    val player = _exoPlayer
    if (player == null) {
        Toast.makeText(context, "Senkron sadece ExoPlayer ile çalışır", Toast.LENGTH_LONG).show()
        return
    }
    // The selected audio group belongs to MergingMediaSource child N (0 = the video's own audio).
    val selectedGroup = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
    val child = selectedGroup?.mediaTrackGroup?.id?.substringBefore(':')?.toIntOrNull() ?: 0
    val audio = streamExternalAudios.getOrNull(child - 1)
    if (child <= 0 || audio == null) {
        Toast.makeText(context, "Önce harici dublaj sesini seçin", Toast.LENGTH_LONG).show()
        return
    }
    startDubSync(audio, silent = false)
}

/**
 * Hook for [attachExternalAudios]. A dub with a fixed delay in the recipe is left alone. A result
 * that was measured before for this exact video file and this dub is applied right away.
 * Otherwise nothing happens unless [DUB_AUTO_SYNC_AFTER_ATTACH] is on.
 */
internal fun PlayerRuntimeController.maybeAutoSyncDub(audio: ExternalAudio) {
    if (audio.audioDelayMs != null) return
    val remembered = DubSyncMemory.get(context, syncMemoryKey(audio))
    if (remembered != null) {
        Log.i(DUB_SYNC_TAG, "remembered delay for this video and dub: ${remembered}ms")
        if (remembered != 0) applyMeasuredDubDelay(remembered, audio.lang)
        return
    }
    if (DUB_AUTO_SYNC_AFTER_ATTACH) startDubSync(audio, silent = true)
}

/** Same video file (name and size, because releases differ) + same dub source = same shift. */
private fun PlayerRuntimeController.syncMemoryKey(audio: ExternalAudio): String =
    listOf(
        currentVideoId ?: contentId.orEmpty(),
        currentFilename.orEmpty(),
        (currentVideoSize ?: 0L).toString(),
        audio.sourceId ?: audio.url.substringBefore('#').substringBefore('?')
    ).joinToString("|")

private fun windowStarts(durationMs: Long): List<Long> {
    val last = durationMs - SYNC_END_MARGIN_MS - SYNC_WINDOW_MS
    return SYNC_FRACTIONS.take(SYNC_MAX_WINDOWS).map {
        (durationMs * it).toLong().coerceIn(SYNC_MIN_START_MS, maxOf(SYNC_MIN_START_MS, last))
    }
}

private class SyncOutcome(
    val result: DubSyncAnalyzer.Result,
    val windowsUsed: Int,
    val elapsedMs: Long
)

private fun PlayerRuntimeController.startDubSync(audio: ExternalAudio, silent: Boolean) {
    val player = _exoPlayer ?: return
    if (dubSyncJob?.isActive == true) {
        if (!silent) Toast.makeText(context, "Senkron ölçümü zaten çalışıyor…", Toast.LENGTH_SHORT).show()
        return
    }
    val durationMs = player.duration
    if (durationMs == C.TIME_UNSET || durationMs < SYNC_MIN_DURATION_MS) {
        if (!silent) Toast.makeText(context, "Otomatik senkron için içerik çok kısa ya da süre bilinmiyor", Toast.LENGTH_LONG).show()
        return
    }
    // Everything the probes need is captured now: the user may change stream while this runs.
    val streamUrl = currentStreamUrl
    val headers = currentHeaders
    val filename = currentFilename
    val responseHeaders = currentStreamResponseHeaders
    val mimeType = currentStreamMimeType
    val memoryKey = syncMemoryKey(audio)
    if (!silent) Toast.makeText(context, "Dublaj senkronu ölçülüyor… (yaklaşık 1 dk)", Toast.LENGTH_LONG).show()

    dubSyncJob = scope.launch {
        val starts = windowStarts(durationMs)
        val trace: (String) -> Unit = { Log.i(DUB_SYNC_TAG, it) }
        val startedAt = SystemClock.elapsedRealtime()

        val outcome = coroutineScope {
            // The dub is a small audio file: its next window is read while the heavy original one is running.
            val dubJobs = HashMap<Int, Deferred<ProbeCapture?>>()
            fun dubWindow(i: Int): Deferred<ProbeCapture?> = dubJobs.getOrPut(i) {
                async {
                    val source = mediaSourceFactory.createExternalAudioSource(context, audio)
                    if (source == null) null
                    else DubAudioProbe.capture(context, source, starts[i], SYNC_WINDOW_MS, timeoutMs = SYNC_WINDOW_TIMEOUT_MS)
                }
            }

            val windows = ArrayList<Pair<ProbeCapture, ProbeCapture>>()
            var failed = 0
            var best: DubSyncAnalyzer.Result.Ok? = null
            var last: DubSyncAnalyzer.Result = DubSyncAnalyzer.Result.Unreliable("yeterli ses penceresi yok")

            for (i in starts.indices) {
                // New MediaSource per probe; no cache key so nothing lands in the media cache. No
                // subtitleConfigurations are passed on purpose: only subtitles inside the media count.
                val original = async {
                    DubAudioProbe.capture(
                        context = context,
                        source = mediaSourceFactory.createMediaSource(
                            context = context,
                            url = streamUrl,
                            headers = headers,
                            filename = filename,
                            responseHeaders = responseHeaders,
                            mimeTypeOverride = mimeType,
                            cacheKey = null,
                            externalAudios = emptyList(),
                            useDiskCache = false
                        ),
                        startMs = starts[i],
                        lengthMs = SYNC_WINDOW_MS,
                        withCues = true,
                        timeoutMs = SYNC_WINDOW_TIMEOUT_MS
                    )
                }
                if (i + 1 < starts.size) dubWindow(i + 1)
                val o = original.await()
                val d = dubWindow(i).await()
                dubJobs.remove(i)

                if (o != null && d != null) {
                    windows.add(o to d)
                } else {
                    failed++
                }
                Log.i(
                    DUB_SYNC_TAG,
                    "window ${i + 1}/${starts.size} at ${starts[i]}ms: ${if (o != null && d != null) "ok" else "failed"} " +
                        "origAudio=${o?.bands != null} embeddedSubtitle=${o?.cueActivity != null} dub=${d != null} " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
                )

                // Nothing readable at all: no point in trying further positions.
                if (windows.isEmpty() && failed >= 2) break

                if (windows.size >= DubSyncAnalyzer.MIN_WINDOWS) {
                    last = withContext(Dispatchers.Default) { DubSyncAnalyzer.analyze(windows, trace) }
                    val ok = last as? DubSyncAnalyzer.Result.Ok
                    if (ok != null) {
                        best = ok
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        val perWindow = elapsed / (i + 1)
                        // A coarse subtitle-only result is worth one more window while there is time and
                        // the original's audio is readable (it can refine it to tens of ms).
                        val canRefine = !ok.precise && DubSyncAnalyzer.hasOriginalAudio(windows) &&
                            elapsed + perWindow <= SYNC_TARGET_MS
                        if (!canRefine) break
                    }
                }
            }
            // The prefetched dub window that is not needed any more.
            coroutineContext[Job]?.children?.forEach { it.cancel() }

            SyncOutcome(
                result = best ?: last,
                windowsUsed = windows.size,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
            )
        }
        Log.i(DUB_SYNC_TAG, "done: windows=${outcome.windowsUsed} elapsed=${outcome.elapsedMs}ms result=${outcome.result}")

        // The user may have switched stream or dub while we were measuring.
        if (currentStreamUrl != streamUrl || streamExternalAudios.none { it.url == audio.url }) return@launch

        when (val r = outcome.result) {
            is DubSyncAnalyzer.Result.Ok -> {
                val ignoreBelow = if (r.precise) SYNC_IGNORE_BELOW_MS else SYNC_IGNORE_BELOW_MS_COARSE
                if (abs(r.lagMs) < ignoreBelow) {
                    DubSyncMemory.put(context, memoryKey, 0)
                    Toast.makeText(context, "Dublaj zaten senkronda (${r.lagMs} ms)", Toast.LENGTH_LONG).show()
                } else {
                    // Dub plays later than the original -> the audio has to come earlier: negative delay.
                    applyMeasuredDubDelay(-r.lagMs, audio.lang)
                    DubSyncMemory.put(context, memoryKey, -r.lagMs)
                    val dir = if (r.lagMs > 0) "geç" else "erken"
                    val note = if (r.precise) "" else " (yaklaşık; gerekirse elle ince ayar yapın)"
                    Toast.makeText(
                        context,
                        "Dublaj ${abs(r.lagMs)} ms $dir kalıyordu, düzeltildi$note [${r.method.label}, ${outcome.elapsedMs / 1000} sn]",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            is DubSyncAnalyzer.Result.Unreliable -> {
                val reason = if (outcome.windowsUsed == 0) "kaynak okunamadı" else r.reason
                Log.i(DUB_SYNC_TAG, "unreliable: $reason")
                // Not remembered: a failed measurement says nothing about the shift.
                if (!silent) Toast.makeText(context, "Otomatik senkron güvenilir değil, elle ayarlayın ($reason)", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Sets (not adds to) the stream delay, so measuring twice never stacks. */
private fun PlayerRuntimeController.applyMeasuredDubDelay(delayMs: Int, lang: String) {
    audioDelayParts = audioDelayParts.withStream(delayMs, lang)
    syncStreamDelayWithSelectedTracks(forceApply = true)
    Log.i(DUB_SYNC_TAG, "Measured dub delay applied: ${delayMs}ms (total ${audioDelayParts.totalMs}ms)")
}

/**
 * Remembers the delay that was measured (or confirmed as 0) per video file + dub, on this device.
 * Newest 300 entries are kept.
 */
internal object DubSyncMemory {
    private const val PREFS = "dub_sync_memory"
    private const val MAX_ENTRIES = 300

    fun get(context: Context, key: String): Int? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)?.substringBefore(';')?.toIntOrNull()
    }.getOrNull()

    fun put(context: Context, key: String, delayMs: Int) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putString(key, "$delayMs;${System.currentTimeMillis()}")
            val all = prefs.all
            if (all.size >= MAX_ENTRIES) {
                all.entries
                    .sortedBy { (it.value as? String)?.substringAfter(';')?.toLongOrNull() ?: 0L }
                    .take(all.size - MAX_ENTRIES + 1)
                    .forEach { edit.remove(it.key) }
            }
            edit.apply()
        }
    }
}
