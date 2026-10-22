package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.settings.DictationChunkSettings
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure (Android-free) audio chunking helpers shared by the offline auto-chunker
 * ([splitAudioIntoSmartChunks]) and the live "transcribe while recording" detector ([findStreamingCut]).
 */

/** 20 ms analysis frame @ 16 kHz. */
internal const val CHUNK_FRAME_SAMPLES = 320

/** Default per-frame RMS below which audio counts as silence (before adaptive noise-floor adjustment). */
internal const val DEFAULT_SILENCE_RMS = DictationChunkSettings.DEFAULT_SILENCE_THRESHOLD

/** Minimum voiced audio (160 ms) a chunk must contain to be worth sending to the speech model. */
private const val MIN_SPEECH_FRAMES = 8

/** Natural-sounding silence kept after the last word of a live chunk (160 ms). */
private const val LIVE_CUT_PAD_SAMPLES = 2_560

/** When a live buffer holds no speech at all, only this much trailing audio (1 s) is kept as lead-in context. */
private const val LIVE_SILENCE_KEEP_SAMPLES = 16_000

/** RMS of every complete [frameLen]-sample frame inside `[start, endExclusive)`. */
internal fun computeFrameRms(
    samples: FloatArray,
    start: Int,
    endExclusive: Int,
    frameLen: Int = CHUNK_FRAME_SAMPLES
): FloatArray {
    val safeStart = start.coerceAtLeast(0)
    val safeEnd = min(endExclusive, samples.size)
    val frames = ((safeEnd - safeStart) / frameLen).coerceAtLeast(0)
    val out = FloatArray(frames)
    for (f in 0 until frames) {
        val base = safeStart + f * frameLen
        var sum = 0.0
        for (i in 0 until frameLen) {
            val s = samples[base + i]
            sum += s * s
        }
        out[f] = sqrt(sum / frameLen).toFloat()
    }
    return out
}

/**
 * Silence threshold that adapts to the recording's noise floor: never below the user's [userThreshold],
 * but raised to ~3x the 10th-percentile frame level so constant background noise (fans, traffic) is not
 * mistaken for speech. Capped so loud rooms cannot push it above typical quiet speech.
 */
internal fun adaptiveSilenceThreshold(frameRms: FloatArray, userThreshold: Float): Float {
    if (frameRms.isEmpty()) return userThreshold
    val sorted = frameRms.copyOf()
    sorted.sort()
    val noiseFloor = sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.size - 1)]
    val adaptive = noiseFloor * 3f
    return max(userThreshold, adaptive).coerceAtMost(max(userThreshold, 0.03f))
}

/**
 * Picks the best place to cut inside `[rangeStart, rangeEndExclusive)`:
 *
 * 1. Finds every run of silent frames (adaptive threshold). Runs at least [minPauseFrames] long are pauses.
 * 2. Among pauses, accepts those within 75% of the longest one and prefers the **latest** (bigger chunks),
 *    cutting at the **centre** of the pause so no word is clipped on either side.
 * 3. If the range contains no real pause, falls back to the quietest ~80 ms window.
 *
 * The returned index always lies inside the range.
 */
internal fun findBestSplitPoint(
    samples: FloatArray,
    rangeStart: Int,
    rangeEndExclusive: Int,
    silenceThreshold: Float = DEFAULT_SILENCE_RMS,
    minPauseFrames: Int = 3,
    frameLen: Int = CHUNK_FRAME_SAMPLES
): Int {
    val rms = computeFrameRms(samples, rangeStart, rangeEndExclusive, frameLen)
    if (rms.isEmpty()) return ((rangeStart.toLong() + rangeEndExclusive) / 2).toInt()
    val n = rms.size
    val thr = adaptiveSilenceThreshold(rms, silenceThreshold)

    var longest = 0
    var i = 0
    while (i < n) {
        if (rms[i] < thr) {
            var j = i
            while (j < n && rms[j] < thr) j++
            longest = max(longest, j - i)
            i = j
        } else {
            i++
        }
    }

    if (longest >= minPauseFrames) {
        val accept = max(minPauseFrames, ceil(longest * 0.75).toInt())
        var bestStart = -1
        var bestLen = 0
        i = 0
        while (i < n) {
            if (rms[i] < thr) {
                var j = i
                while (j < n && rms[j] < thr) j++
                val len = j - i
                if (len >= accept) {
                    bestStart = i
                    bestLen = len
                }
                i = j
            } else {
                i++
            }
        }
        if (bestStart >= 0) {
            return rangeStart + (bestStart + bestLen / 2) * frameLen + frameLen / 2
        }
    }

    // Fallback: quietest 4-frame (80 ms) window
    val win = min(4, n)
    var energy = 0.0
    for (k in 0 until win) energy += rms[k].toDouble() * rms[k]
    var bestIdx = 0
    var bestEnergy = energy
    for (start in 1..(n - win)) {
        energy += rms[start + win - 1].toDouble() * rms[start + win - 1] -
            rms[start - 1].toDouble() * rms[start - 1]
        if (energy < bestEnergy) {
            bestEnergy = energy
            bestIdx = start
        }
    }
    return rangeStart + (bestIdx + win / 2) * frameLen
}

/**
 * Decision of the live pause detector.
 *
 * @property index exclusive end of the finished chunk inside the live buffer (`[0, index)`).
 * @property hasSpeech `false` when `[0, index)` holds no usable speech and should just be discarded
 * (long silence before the user started talking).
 */
internal data class StreamingCut(val index: Int, val hasSpeech: Boolean)

/**
 * Live pause detector used while the user is still recording.
 *
 * Looks at the not-yet-transcribed audio `samples[0, size)` and returns where a *finished* chunk ends, or
 * `null` if nothing should be cut yet:
 *
 * - **Pause cut:** the earliest silence run of at least `config.pauseMs` that starts after
 *   `config.minLiveChunkSeconds` of audio. The cut sits inside the pause (up to 160 ms after the last word).
 * - **Forced cut:** once the buffer reaches `config.maxChunkSeconds` without a pause, cut at the best
 *   split point (longest pause / quietest window) in `[minLive, size)`.
 * - **Silence discard:** a buffer with no speech at all is dropped (keeping 1 s of lead-in) so waiting before
 *   speaking never builds a giant silent chunk.
 */
internal fun findStreamingCut(
    samples: FloatArray,
    size: Int,
    config: DictationChunkSettings
): StreamingCut? {
    val minChunk = config.minLiveChunkSamples
    val maxChunk = config.maxChunkSamples
    val usable = min(size, samples.size)
    if (usable < minChunk) return null

    val frame = CHUNK_FRAME_SAMPLES
    val rms = computeFrameRms(samples, 0, usable, frame)
    if (rms.isEmpty()) return null
    val n = rms.size
    val thr = adaptiveSilenceThreshold(rms, config.silenceThreshold)

    var totalSpeech = 0
    for (v in rms) if (v >= thr) totalSpeech++
    if (totalSpeech < MIN_SPEECH_FRAMES) {
        val index = usable - LIVE_SILENCE_KEEP_SAMPLES
        return if (index >= frame) StreamingCut(index, hasSpeech = false) else null
    }

    val pauseFrames = max(2, config.pauseSamples / frame)
    var i = 0
    while (i < n) {
        if (rms[i] >= thr) {
            i++
            continue
        }
        var j = i
        while (j < n && rms[j] < thr) j++
        val runLen = j - i
        if (i > 0 && runLen >= pauseFrames) {
            val cut = i * frame + min(runLen * frame / 2, LIVE_CUT_PAD_SAMPLES)
            if (cut >= minChunk && cut <= usable) {
                var speechBefore = 0
                for (f in 0 until min(n, cut / frame)) if (rms[f] >= thr) speechBefore++
                return StreamingCut(cut, hasSpeech = speechBefore >= MIN_SPEECH_FRAMES)
            }
        }
        i = j
    }

    if (usable >= maxChunk) {
        val minPauseFrames = max(2, pauseFrames / 2)
        val idx = findBestSplitPoint(samples, minChunk, usable, config.silenceThreshold, minPauseFrames, frame)
        return StreamingCut(idx.coerceIn(minChunk, usable), hasSpeech = true)
    }
    return null
}
