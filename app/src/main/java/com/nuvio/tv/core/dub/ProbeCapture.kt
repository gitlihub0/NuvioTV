package com.nuvio.tv.core.dub

/**
 * What one probe window records, one value per [DubSyncAnalyzer.FRAME_MS] ms. Android-free so the
 * analyzer can be unit tested on the JVM.
 */
internal class ProbeCapture(
    /**
     * Mean-square energy per frame, indexed by [BAND_FULL], [BAND_LOW], [BAND_LOWMID], [BAND_MID]
     * and [BAND_HIGH]; all arrays have the same length. null when the audio could not be decoded
     * to PCM (for example TrueHD/DTS-HD without a decoder). The subtitle cues can still be there.
     */
    val bands: Array<FloatArray>?,
    /**
     * 1f while an EMBEDDED subtitle cue was on screen, else 0f, per frame. null when the stream has
     * no usable embedded text track. Subtitles that were downloaded from add-ons are never used.
     */
    val cueActivity: FloatArray?,
    val frames: Int
) {
    companion object {
        const val BAND_FULL = 0

        /** below ~150 Hz: bass, explosions, impacts. Almost no speech. */
        const val BAND_LOW = 1

        /** ~150-700 Hz */
        const val BAND_LOWMID = 2

        /** ~700-3400 Hz: where speech intelligibility lives */
        const val BAND_MID = 3

        /** above ~3400 Hz: cymbals, hi-hats, glass, gunfire crack (and speech sibilants) */
        const val BAND_HIGH = 4
        const val BAND_COUNT = 5
    }
}
