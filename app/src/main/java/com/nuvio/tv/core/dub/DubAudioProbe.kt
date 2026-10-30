@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.dub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val PROBE_TAG = "DubAudioProbe"

/**
 * Plays a stretch of one media source silently and fast and records, every
 * [DubSyncAnalyzer.FRAME_MS] ms, how loud it is in a few frequency bands. For the original it also
 * records when an EMBEDDED subtitle cue is on screen. Nothing is stored except a few floats per
 * frame.
 *
 * It uses its own headless ExoPlayer (video disabled, volume 0, 8x speed) with a
 * [TeeAudioProcessor] in front of the audio sink, so it sees exactly the PCM the real player
 * would play (custom processors run before the speed change, so frames are in media time).
 * Seeking is done by the player, which keeps HLS/MKV/MP4 handling identical to normal playback.
 * The small buffer limits how much is downloaded beyond the wanted stretch: for a 4K remux every
 * extra second costs megabytes.
 *
 * Only subtitles that sit inside the media (the source passed in must not carry side-loaded
 * subtitle configurations) are ever looked at.
 */
internal object DubAudioProbe {

    private const val SPEED = 8f

    /** Wait this long for the track list before starting without subtitles / track choice. */
    private const val TRACKS_WAIT_MS = 6_000L

    /** How often the player position is checked against the end of the window. */
    private const val POLL_MS = 100L

    /** Cues are collected until the player position is this close to the end of the window. */
    private const val END_SLACK_MS = 150L

    /**
     * Band energies (and, with [withCues], embedded-subtitle activity) for [lengthMs] starting at
     * [startMs]. null when nothing usable came out (bad URL, timeout, error, no decodable audio
     * and no subtitle track). A result may have no bands (audio not decodable, e.g. TrueHD without
     * a decoder) or no cues (no embedded subtitle track); the dub side always needs bands.
     */
    suspend fun capture(
        context: Context,
        source: MediaSource,
        startMs: Long,
        lengthMs: Long,
        withCues: Boolean = false,
        timeoutMs: Long = 30_000L
    ): ProbeCapture? {
        val targetFrames = (lengthMs / DubSyncAnalyzer.FRAME_MS).toInt()
        val endMs = startMs + lengthMs
        val done = CompletableDeferred<Boolean>()
        val main = Handler(Looper.getMainLooper())
        var player: ExoPlayer? = null
        val cues = if (withCues) CueCollector(startMs, targetFrames) else null
        lateinit var sink: BandSink
        val startedAt = System.nanoTime()

        return try {
            withContext(Dispatchers.Main) {
                sink = BandSink(targetFrames) {
                    // Called on the playback thread. Window done for audio-only probes.
                    main.post { if (cues?.trackChosen != true && !done.isCompleted) done.complete(true) }
                }
                val tee = TeeAudioProcessor(sink)
                val renderers = object : DefaultRenderersFactory(context) {
                    init {
                        // Uses a bundled FFmpeg/other extension decoder when the app has one.
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    }

                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean
                    ): AudioSink? = DefaultAudioSink.Builder(context)
                        // PCM only: pass-through (AC3/EAC3 to HDMI) would bypass the processor.
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setAudioProcessors(arrayOf<AudioProcessor>(tee))
                        .build()
                }
                // Small look-ahead: everything buffered beyond the window is wasted download.
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 5_000, 500, 1_000)
                    .build()
                val p = ExoPlayer.Builder(context, renderers)
                    .setLoadControl(loadControl)
                    .build()
                player = p

                val poll = object : Runnable {
                    override fun run() {
                        if (done.isCompleted) return
                        val pos = p.currentPosition
                        val cuesOn = cues?.trackChosen == true
                        if (cuesOn) {
                            // Cues are only complete once playback has really reached the end.
                            if (pos >= endMs - END_SLACK_MS) done.complete(true)
                        } else if (sink.isFull || pos >= endMs) {
                            done.complete(true)
                        }
                        if (!done.isCompleted) main.postDelayed(this, POLL_MS)
                    }
                }

                var started = false
                // Starts playback of the window; for the original after the tracks are known, so
                // the subtitle track (and a sensible audio track) can be chosen first.
                fun begin(textGroup: Tracks.Group?, audioGroup: Tracks.Group?) {
                    if (started) return
                    started = true
                    val params = p.trackSelectionParameters.buildUpon()
                    if (textGroup != null) {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, 0))
                        cues?.trackChosen = true
                    }
                    if (audioGroup != null) {
                        params.setOverrideForType(TrackSelectionOverride(audioGroup.mediaTrackGroup, 0))
                    }
                    p.trackSelectionParameters = params.build()
                    // The seek flushes the audio sink, so nothing recorded during pre-roll stays.
                    cues?.reset()
                    p.seekTo(startMs)
                    p.playWhenReady = true
                    main.postDelayed(poll, POLL_MS)
                }

                p.volume = 0f
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                p.playbackParameters = PlaybackParameters(SPEED)
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(PROBE_TAG, "probe error: ${error.errorCodeName}")
                        done.complete(false)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // Window runs past the end of the media: use what was captured.
                        if (state == Player.STATE_ENDED) done.complete(true)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (cues == null || started || tracks.isEmpty) return
                        begin(pickEmbeddedTextGroup(tracks), pickOriginalAudioGroup(tracks))
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        // presentationTimeUs is when this set of cues took effect (not when the
                        // callback ran), so the 8x speed does not blur the timing.
                        if (started) cues?.onCues(cueGroup.cues.isNotEmpty(), cueGroup.presentationTimeUs / 1000)
                    }
                })
                p.setMediaSource(source, startMs)
                p.prepare()
                if (cues == null) {
                    started = true
                    p.playWhenReady = true
                    main.postDelayed(poll, POLL_MS)
                } else {
                    p.playWhenReady = false
                    // Some sources report their tracks late or never: do not wait forever.
                    main.postDelayed({ begin(null, null) }, TRACKS_WAIT_MS)
                }
            }

            val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
            val bands = sink.snapshot()?.takeIf { it[0].size >= DubSyncAnalyzer.MIN_USABLE_FRAMES }
            // Cues are only trustworthy when the window was read to its end.
            val cueArray = if (ok && cues?.trackChosen == true) cues.snapshot() else null
            Log.i(
                PROBE_TAG,
                "start=${startMs}ms took=${(System.nanoTime() - startedAt) / 1_000_000}ms ok=$ok " +
                    "audioFrames=${bands?.get(0)?.size} audioUnsupported=${sink.unsupported} " +
                    "subtitleTrack=${cues?.trackChosen}"
            )
            if (bands == null && cueArray == null) null
            else ProbeCapture(bands, cueArray, bands?.get(0)?.size ?: targetFrames)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PROBE_TAG, "probe failed: ${e.message}")
            null
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                main.removeCallbacksAndMessages(null)
                player?.release()
            }
        }
    }

    /**
     * An embedded subtitle track that mostly follows the dialogue: supported, not "forced" (those
     * only cover foreign-language lines), preferably English, preferably without sound
     * descriptions, preferably the default one. Works for text (SRT, tx3g, ASS...) and bitmap
     * (PGS, DVB) tracks alike: only the timing of the cues matters.
     */
    private fun pickEmbeddedTextGroup(tracks: Tracks): Tracks.Group? {
        var best: Tracks.Group? = null
        var bestScore = Int.MIN_VALUE
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT || group.length == 0 || !group.isTrackSupported(0)) continue
            val format = group.getTrackFormat(0)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) continue
            var score = 0
            if (format.language?.lowercase()?.startsWith("en") == true) score += 4
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND == 0) score += 2
            if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) score += 1
            if (score > bestScore) {
                bestScore = score
                best = group
            }
        }
        return best
    }

    /**
     * The audio track of the ORIGINAL that is best for measuring: one this device can decode, not
     * a commentary (that is a different mix), the default one, and a cheap codec (AC3, E-AC3,
     * AAC...) over DTS-HD/TrueHD when both exist. null keeps the player's own choice.
     */
    private fun pickOriginalAudioGroup(tracks: Tracks): Tracks.Group? {
        var best: Tracks.Group? = null
        var bestScore = Int.MIN_VALUE
        var supported = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0 || !group.isTrackSupported(0)) continue
            supported++
            val format = group.getTrackFormat(0)
            val label = format.label?.lowercase().orEmpty()
            if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 ||
                label.contains("commentary") || label.contains("yorum")
            ) continue
            var score = 0
            if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) score += 4
            when (format.sampleMimeType) {
                MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AAC,
                MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC -> score += 3
            }
            if (format.channelCount in 1..6) score += 1
            if (score > bestScore) {
                bestScore = score
                best = group
            }
        }
        // One candidate: the default selection already picks it, nothing to override.
        return if (supported > 1) best else null
    }

    /** Turns cue on/off events into a per-frame "subtitle visible" array. */
    private class CueCollector(private val startMs: Long, private val targetFrames: Int) {
        @Volatile
        var trackChosen = false
        private val lock = Any()
        private var activity = FloatArray(targetFrames)
        private var openSinceMs = -1L

        fun reset() {
            synchronized(lock) {
                activity = FloatArray(targetFrames)
                openSinceMs = -1L
            }
        }

        fun onCues(visible: Boolean, timeMs: Long) {
            synchronized(lock) {
                val t = max(timeMs, startMs)
                if (visible && openSinceMs < 0) {
                    openSinceMs = t
                } else if (!visible && openSinceMs >= 0) {
                    fill(openSinceMs, t)
                    openSinceMs = -1L
                }
            }
        }

        fun snapshot(): FloatArray {
            synchronized(lock) {
                // A cue still on screen when the window ends covers the rest of it.
                if (openSinceMs >= 0) fill(openSinceMs, startMs + targetFrames.toLong() * DubSyncAnalyzer.FRAME_MS)
                return activity.copyOf()
            }
        }

        private fun fill(fromMs: Long, toMs: Long) {
            val a = ((fromMs - startMs) / DubSyncAnalyzer.FRAME_MS).toInt().coerceIn(0, targetFrames)
            val b = ((toMs - startMs) / DubSyncAnalyzer.FRAME_MS).toInt().coerceIn(0, targetFrames)
            for (i in a until b) activity[i] = 1f
        }
    }

    /** Second-order section (RBJ cookbook, Q = 1/sqrt(2)), transposed direct form II. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val Q = 0.70710678

            fun lowPass(fs: Double, f: Double): Biquad = make(fs, f, low = true)
            fun highPass(fs: Double, f: Double): Biquad = make(fs, f, low = false)

            private fun make(fs: Double, f: Double, low: Boolean): Biquad {
                val w0 = 2.0 * PI * f / fs
                val c = cos(w0)
                val alpha = sin(w0) / (2.0 * Q)
                val a0 = 1.0 + alpha
                val b0: Double
                val b1: Double
                if (low) {
                    b0 = (1.0 - c) / 2.0; b1 = 1.0 - c
                } else {
                    b0 = (1.0 + c) / 2.0; b1 = -(1.0 + c)
                }
                return Biquad(b0 / a0, b1 / a0, b0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }
        }
    }

    /** A chain of second-order sections; two per edge make 4th order (24 dB/octave). */
    private class BandFilter(private vararg val stages: Biquad) {
        fun process(x: Double): Double {
            var y = x
            for (s in stages) y = s.process(y)
            return y
        }
    }

    /** Collects per-band mean-square energy per frame from whatever PCM the audio sink receives. */
    private class BandSink(
        private val targetFrames: Int,
        private val onFull: () -> Unit
    ) : TeeAudioProcessor.AudioBufferSink {

        private val lock = Any()
        private val energy = Array(ProbeCapture.BAND_COUNT) { FloatArray(targetFrames) }
        private var count = 0
        private var channels = 0
        private var encoding = C.ENCODING_INVALID
        private var samplesPerFrame = 0
        private val acc = DoubleArray(ProbeCapture.BAND_COUNT)
        private var inFrame = 0
        private var filters: Array<BandFilter> = emptyArray()

        @Volatile
        var isFull = false
            private set

        @Volatile
        var unsupported = false
            private set

        /** Called on every (re)configuration and seek: whatever follows starts a fresh stretch. */
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            synchronized(lock) {
                this.channels = channelCount
                this.encoding = encoding
                this.samplesPerFrame = sampleRateHz * DubSyncAnalyzer.FRAME_MS / 1000
                // The top band edge (3.4 kHz) needs a sample rate that leaves room above it.
                this.unsupported = (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) ||
                    sampleRateHz < 16_000
                java.util.Arrays.fill(acc, 0.0)
                this.inFrame = 0
                this.count = 0
                this.isFull = false
                if (!unsupported) {
                    val fs = sampleRateHz.toDouble()
                    fun lp(f: Double) = Biquad.lowPass(fs, f)
                    fun hp(f: Double) = Biquad.highPass(fs, f)
                    // Each edge twice: 4th order.
                    this.filters = arrayOf(
                        BandFilter(lp(LOW_EDGE), lp(LOW_EDGE)),
                        BandFilter(hp(LOW_EDGE), hp(LOW_EDGE), lp(MID_EDGE), lp(MID_EDGE)),
                        BandFilter(hp(MID_EDGE), hp(MID_EDGE), lp(HIGH_EDGE), lp(HIGH_EDGE)),
                        BandFilter(hp(HIGH_EDGE), hp(HIGH_EDGE))
                    )
                }
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            var justFilled = false
            synchronized(lock) {
                if (isFull || unsupported || channels <= 0 || samplesPerFrame <= 0 || filters.isEmpty()) return
                val b = buffer.order(ByteOrder.nativeOrder())
                val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                val frameBytes = bytesPerSample * channels
                while (b.remaining() >= frameBytes) {
                    var mono = 0.0
                    for (c in 0 until channels) {
                        mono += if (encoding == C.ENCODING_PCM_FLOAT) b.getFloat().toDouble()
                        else b.getShort() / 32768.0
                    }
                    mono /= channels
                    acc[ProbeCapture.BAND_FULL] += mono * mono
                    for (i in filters.indices) {
                        val y = filters[i].process(mono)
                        acc[i + 1] += y * y
                    }
                    if (++inFrame >= samplesPerFrame) {
                        for (k in acc.indices) {
                            energy[k][count] = (acc[k] / inFrame).toFloat()
                            acc[k] = 0.0
                        }
                        count++
                        inFrame = 0
                        if (count >= targetFrames) {
                            isFull = true
                            justFilled = true
                            break
                        }
                    }
                }
            }
            if (justFilled) onFull()
        }

        fun snapshot(): Array<FloatArray>? = synchronized(lock) {
            if (count == 0) null else Array(energy.size) { energy[it].copyOf(count) }
        }

        private companion object {
            const val LOW_EDGE = 150.0
            const val MID_EDGE = 700.0
            const val HIGH_EDGE = 3400.0
        }
    }
}
