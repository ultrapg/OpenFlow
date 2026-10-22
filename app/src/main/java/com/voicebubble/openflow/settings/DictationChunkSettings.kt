package com.voicebubble.openflow.settings

import android.content.Context

/**
 * User-configurable audio chunking & live ("transcribe while recording") behaviour.
 *
 * Stored in the same `openflow_settings` preferences file as the floating bubble settings.
 * Always read through [sanitized] so out-of-range / corrupt values can never reach the audio pipeline.
 */
data class DictationChunkSettings(
    /** Transcribe finished chunks while the user is still recording (pause-triggered). */
    val liveTranscriptionEnabled: Boolean = DEFAULT_LIVE_ENABLED,
    /** Length of silence (ms) that counts as a pause worth cutting a live chunk at. */
    val pauseMs: Int = DEFAULT_PAUSE_MS,
    /** A live chunk is never cut before it contains at least this much audio. */
    val minLiveChunkSeconds: Float = DEFAULT_MIN_LIVE_CHUNK_SECONDS,
    /** Hard upper bound for any single chunk (live and offline auto-chunking). */
    val maxChunkSeconds: Float = DEFAULT_MAX_CHUNK_SECONDS,
    /** Frame RMS below this level (after adaptive noise-floor adjustment) counts as silence. */
    val silenceThreshold: Float = DEFAULT_SILENCE_THRESHOLD,
    /** Run language identification only on the first live chunk and reuse it for the rest. */
    val reuseLanguage: Boolean = DEFAULT_REUSE_LANGUAGE
) {
    val pauseSamples: Int get() = (pauseMs * SAMPLES_PER_MS).toInt()
    val minLiveChunkSamples: Int get() = (minLiveChunkSeconds * SAMPLE_RATE).toInt()
    val maxChunkSamples: Int get() = (maxChunkSeconds * SAMPLE_RATE).toInt()

    /** Offline auto-chunking never cuts before ~55% of the max length (20 s max -> 11 s min). */
    val offlineMinChunkSamples: Int get() = (maxChunkSamples * OFFLINE_MIN_RATIO).toInt()

    fun sanitized(): DictationChunkSettings {
        val max = if (maxChunkSeconds.isFinite()) maxChunkSeconds.coerceIn(MIN_MAX_CHUNK_SECONDS, MAX_MAX_CHUNK_SECONDS) else DEFAULT_MAX_CHUNK_SECONDS
        val minLiveRaw = if (minLiveChunkSeconds.isFinite()) minLiveChunkSeconds else DEFAULT_MIN_LIVE_CHUNK_SECONDS
        // A live chunk must be able to end well before the hard max, otherwise pause detection could never trigger
        val minLive = minLiveRaw.coerceIn(MIN_LIVE_CHUNK_SECONDS_LOWER, MIN_LIVE_CHUNK_SECONDS_UPPER).coerceAtMost(max * 0.75f)
        val threshold = if (silenceThreshold.isFinite()) silenceThreshold else DEFAULT_SILENCE_THRESHOLD
        return copy(
            pauseMs = pauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS),
            minLiveChunkSeconds = minLive,
            maxChunkSeconds = max,
            silenceThreshold = threshold.coerceIn(MIN_SILENCE_THRESHOLD, MAX_SILENCE_THRESHOLD)
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000.0
        private const val OFFLINE_MIN_RATIO = 0.55f

        const val KEY_LIVE_ENABLED = "chunk_live_enabled"
        const val KEY_PAUSE_MS = "chunk_pause_ms"
        const val KEY_MIN_LIVE_CHUNK_SECONDS = "chunk_min_live_seconds"
        const val KEY_MAX_CHUNK_SECONDS = "chunk_max_seconds"
        const val KEY_SILENCE_THRESHOLD = "chunk_silence_threshold"
        const val KEY_REUSE_LANGUAGE = "chunk_reuse_language"

        const val DEFAULT_LIVE_ENABLED = true
        const val DEFAULT_PAUSE_MS = 600
        const val DEFAULT_MIN_LIVE_CHUNK_SECONDS = 4f
        const val DEFAULT_MAX_CHUNK_SECONDS = 20f
        const val DEFAULT_SILENCE_THRESHOLD = 0.008f
        const val DEFAULT_REUSE_LANGUAGE = true

        const val MIN_PAUSE_MS = 250
        const val MAX_PAUSE_MS = 2_000
        const val MIN_LIVE_CHUNK_SECONDS_LOWER = 2f
        const val MIN_LIVE_CHUNK_SECONDS_UPPER = 15f
        const val MIN_MAX_CHUNK_SECONDS = 8f
        const val MAX_MAX_CHUNK_SECONDS = 30f
        const val MIN_SILENCE_THRESHOLD = 0.002f
        const val MAX_SILENCE_THRESHOLD = 0.03f

        fun load(context: Context): DictationChunkSettings {
            return try {
                val prefs = context.applicationContext.getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
                DictationChunkSettings(
                    liveTranscriptionEnabled = prefs.getBoolean(KEY_LIVE_ENABLED, DEFAULT_LIVE_ENABLED),
                    pauseMs = prefs.getInt(KEY_PAUSE_MS, DEFAULT_PAUSE_MS),
                    minLiveChunkSeconds = prefs.getFloat(KEY_MIN_LIVE_CHUNK_SECONDS, DEFAULT_MIN_LIVE_CHUNK_SECONDS),
                    maxChunkSeconds = prefs.getFloat(KEY_MAX_CHUNK_SECONDS, DEFAULT_MAX_CHUNK_SECONDS),
                    silenceThreshold = prefs.getFloat(KEY_SILENCE_THRESHOLD, DEFAULT_SILENCE_THRESHOLD),
                    reuseLanguage = prefs.getBoolean(KEY_REUSE_LANGUAGE, DEFAULT_REUSE_LANGUAGE)
                ).sanitized()
            } catch (_: Throwable) {
                DictationChunkSettings()
            }
        }

        fun save(context: Context, settings: DictationChunkSettings) {
            val s = settings.sanitized()
            try {
                context.applicationContext.getSharedPreferences("openflow_settings", Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_LIVE_ENABLED, s.liveTranscriptionEnabled)
                    .putInt(KEY_PAUSE_MS, s.pauseMs)
                    .putFloat(KEY_MIN_LIVE_CHUNK_SECONDS, s.minLiveChunkSeconds)
                    .putFloat(KEY_MAX_CHUNK_SECONDS, s.maxChunkSeconds)
                    .putFloat(KEY_SILENCE_THRESHOLD, s.silenceThreshold)
                    .putBoolean(KEY_REUSE_LANGUAGE, s.reuseLanguage)
                    .apply()
            } catch (_: Throwable) {
            }
        }
    }
}
