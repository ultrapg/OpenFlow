package com.voicebubble.openflow.settings

import android.content.Context

/**
 * User-configurable audio chunking & live ("transcribe while recording") behaviour.
 *
 * Stored in the same `openflow_settings` preferences file as the floating bubble settings.
 * Always read through [sanitized] so out-of-range / corrupt values can never reach the audio pipeline.
 */
data class DictationChunkSettings(
    /**
     * Master switch. When on, long audio is split at natural pauses (offline auto-chunking and, if enabled,
     * live transcription). When off, audio is processed in one piece (see [HARD_MAX_CHUNK_SECONDS]).
     */
    val chunkingEnabled: Boolean = DEFAULT_CHUNKING_ENABLED,
    /** Transcribe finished chunks while the user is still recording (pause-triggered). Needs [chunkingEnabled]. */
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
    val reuseLanguage: Boolean = DEFAULT_REUSE_LANGUAGE,
    /** Shorten the required pause the longer a chunk has been growing, so natural gaps are used before a forced cut. */
    val adaptivePause: Boolean = DEFAULT_ADAPTIVE_PAUSE
) {
    val pauseSamples: Int get() = (pauseMs * SAMPLES_PER_MS).toInt()
    val minLiveChunkSamples: Int get() = (minLiveChunkSeconds * SAMPLE_RATE).toInt()
    val maxChunkSamples: Int get() = (maxChunkSeconds * SAMPLE_RATE).toInt()

    /** `true` only if chunking is on *and* live transcription is requested. */
    val liveActive: Boolean get() = chunkingEnabled && liveTranscriptionEnabled

    /**
     * Longest chunk the offline chunker may produce. With chunking off this is only the safety ceiling
     * [HARD_MAX_CHUNK_SECONDS] that protects the native models from multi-minute single inputs.
     */
    val effectiveMaxChunkSamples: Int
        get() = if (chunkingEnabled) maxChunkSamples else (HARD_MAX_CHUNK_SECONDS * SAMPLE_RATE).toInt()

    /** Offline auto-chunking never cuts before ~55% of the max length (20 s max -> 11 s min). */
    val offlineMinChunkSamples: Int get() = (maxChunkSamples * OFFLINE_MIN_RATIO).toInt()
    val effectiveOfflineMinChunkSamples: Int get() = (effectiveMaxChunkSamples * OFFLINE_MIN_RATIO).toInt()

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

        const val KEY_CHUNKING_ENABLED = "chunk_enabled"
        const val KEY_LIVE_ENABLED = "chunk_live_enabled"
        const val KEY_PAUSE_MS = "chunk_pause_ms"
        const val KEY_MIN_LIVE_CHUNK_SECONDS = "chunk_min_live_seconds"
        const val KEY_MAX_CHUNK_SECONDS = "chunk_max_seconds"
        const val KEY_SILENCE_THRESHOLD = "chunk_silence_threshold"
        const val KEY_REUSE_LANGUAGE = "chunk_reuse_language"
        const val KEY_ADAPTIVE_PAUSE = "chunk_adaptive_pause"
        const val KEY_DEFAULTS_VERSION = "chunk_defaults_version"

        /** Bumped whenever the shipped defaults change; see [migrateDefaults]. */
        const val DEFAULTS_VERSION = 2

        const val DEFAULT_CHUNKING_ENABLED = true
        const val DEFAULT_LIVE_ENABLED = true

        /**
         * 500 ms: natural sentence/clause boundaries are typically 400-800 ms while gaps between words are
         * almost always under 300 ms, so 500 ms cuts at real phrase ends without chopping hesitant speech.
         */
        const val DEFAULT_PAUSE_MS = 500

        /** 3 s: enough acoustic context for accurate recognition, but starts working early. */
        const val DEFAULT_MIN_LIVE_CHUNK_SECONDS = 3f
        const val DEFAULT_MAX_CHUNK_SECONDS = 20f
        const val DEFAULT_SILENCE_THRESHOLD = 0.008f
        const val DEFAULT_REUSE_LANGUAGE = true
        const val DEFAULT_ADAPTIVE_PAUSE = true

        /** Defaults shipped in v2.6.0 (before tuning); users still on exactly these values are migrated. */
        private const val V1_PAUSE_MS = 600
        private const val V1_MIN_LIVE_CHUNK_SECONDS = 4f

        const val MIN_PAUSE_MS = 250
        const val MAX_PAUSE_MS = 2_000
        const val MIN_LIVE_CHUNK_SECONDS_LOWER = 2f
        const val MIN_LIVE_CHUNK_SECONDS_UPPER = 15f
        const val MIN_MAX_CHUNK_SECONDS = 8f
        const val MAX_MAX_CHUNK_SECONDS = 30f
        const val MIN_SILENCE_THRESHOLD = 0.002f
        const val MAX_SILENCE_THRESHOLD = 0.03f

        /** Safety ceiling for a single input when chunking is switched off. */
        const val HARD_MAX_CHUNK_SECONDS = 60f

        /**
         * One-time migration: users who never touched the pause / minimum-length sliders (their stored values
         * are exactly the old v2.6.0 defaults) get the new tuned defaults; real customisations are kept.
         */
        private fun migrateDefaults(prefs: android.content.SharedPreferences) {
            try {
                if (prefs.getInt(KEY_DEFAULTS_VERSION, 1) >= DEFAULTS_VERSION) return
                val editor = prefs.edit()
                if (prefs.contains(KEY_PAUSE_MS) && prefs.getInt(KEY_PAUSE_MS, -1) == V1_PAUSE_MS) {
                    editor.remove(KEY_PAUSE_MS)
                }
                if (prefs.contains(KEY_MIN_LIVE_CHUNK_SECONDS) &&
                    prefs.getFloat(KEY_MIN_LIVE_CHUNK_SECONDS, -1f) == V1_MIN_LIVE_CHUNK_SECONDS
                ) {
                    editor.remove(KEY_MIN_LIVE_CHUNK_SECONDS)
                }
                editor.putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION).apply()
            } catch (_: Throwable) {
            }
        }

        fun load(context: Context): DictationChunkSettings {
            return try {
                val prefs = context.applicationContext.getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
                migrateDefaults(prefs)
                DictationChunkSettings(
                    chunkingEnabled = prefs.getBoolean(KEY_CHUNKING_ENABLED, DEFAULT_CHUNKING_ENABLED),
                    liveTranscriptionEnabled = prefs.getBoolean(KEY_LIVE_ENABLED, DEFAULT_LIVE_ENABLED),
                    pauseMs = prefs.getInt(KEY_PAUSE_MS, DEFAULT_PAUSE_MS),
                    minLiveChunkSeconds = prefs.getFloat(KEY_MIN_LIVE_CHUNK_SECONDS, DEFAULT_MIN_LIVE_CHUNK_SECONDS),
                    maxChunkSeconds = prefs.getFloat(KEY_MAX_CHUNK_SECONDS, DEFAULT_MAX_CHUNK_SECONDS),
                    silenceThreshold = prefs.getFloat(KEY_SILENCE_THRESHOLD, DEFAULT_SILENCE_THRESHOLD),
                    reuseLanguage = prefs.getBoolean(KEY_REUSE_LANGUAGE, DEFAULT_REUSE_LANGUAGE),
                    adaptivePause = prefs.getBoolean(KEY_ADAPTIVE_PAUSE, DEFAULT_ADAPTIVE_PAUSE)
                ).sanitized()
            } catch (_: Throwable) {
                DictationChunkSettings()
            }
        }

        fun save(context: Context, settings: DictationChunkSettings) {
            val s = settings.sanitized()
            try {
                context.applicationContext.getSharedPreferences("openflow_settings", Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_CHUNKING_ENABLED, s.chunkingEnabled)
                    .putBoolean(KEY_LIVE_ENABLED, s.liveTranscriptionEnabled)
                    .putInt(KEY_PAUSE_MS, s.pauseMs)
                    .putFloat(KEY_MIN_LIVE_CHUNK_SECONDS, s.minLiveChunkSeconds)
                    .putFloat(KEY_MAX_CHUNK_SECONDS, s.maxChunkSeconds)
                    .putFloat(KEY_SILENCE_THRESHOLD, s.silenceThreshold)
                    .putBoolean(KEY_REUSE_LANGUAGE, s.reuseLanguage)
                    .putBoolean(KEY_ADAPTIVE_PAUSE, s.adaptivePause)
                    .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
                    .apply()
            } catch (_: Throwable) {
            }
        }
    }
}
