package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.settings.DictationChunkSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure (Android-free) audio chunking helpers shared by the offline auto-chunker
 * ([splitAudioIntoSmartChunks] / [computeSmartChunkRanges]) and the live "transcribe while recording"
 * detector ([findStreamingCut]).
 */

/**
 * Classification of how a chunk boundary was chosen.
 */
enum class CutKind {
    /** Buffer holds only pre-speech dead air; discard leading silence without invoking `Voz`. */
    SILENCE_DISCARD,

    /** Cut fell inside a verified inter-clause silence pause; zero spoken samples are duplicated. */
    CONFIDENT_PAUSE,

    /**
     * Speaker talked continuously up to `maxChunk` without a full pause; cut fell in the best local
     * energy valley and retains [FORCED_CUT_OVERLAP_SAMPLES] (`1.20 s`) of bilateral speech overlap
     * for timestamp-aligned reconciliation.
     */
    VALLEY_OVERLAP
}

/** 20 ms analysis frame @ 16 kHz. */
internal const val CHUNK_FRAME_SAMPLES = 320

/** Default per-frame RMS below which audio counts as silence (before adaptive noise-floor adjustment). */
internal const val DEFAULT_SILENCE_RMS = DictationChunkSettings.DEFAULT_SILENCE_THRESHOLD

/** Minimum voiced audio (160 ms) a chunk must contain to be worth sending to the speech model. */
private const val MIN_SPEECH_FRAMES = 8

/** Minimum sustained speech (100 ms) a post-cut tail must contain to be worth sending after live chunks. */
internal const val MIN_TAIL_SPEECH_FRAMES = 5

/**
 * Loud bursts shorter than this (60 ms) that are completely surrounded by silence are clicks, taps or lip
 * smacks - not words - and must not break a pause into two runs that are each too short to qualify.
 */
private const val MIN_SPEECH_RUN_FRAMES = 3

/**
 * Maximum vocal-tract occlusion (100 ms = 5 frames) inside a stop consonant (`/p/`, `/t/`, `/k/`, `/b/`,
 * `/d/`, `/g/`) or affricate before its plosive release burst or final unstressed syllable. Short bursts
 * within this distance of a confirmed speech run are bridged into the word rather than erased as clicks.
 */
private const val MAX_STOP_CLOSURE_FRAMES = 5

/**
 * Dual-threshold hysteresis ratio for word edges: frames immediately adjacent to a confirmed speech run
 * that stay above `threshold * SPEECH_HANGOVER_RATIO` are soft consonant onsets/tails (`s`, `t`, `f`, `k`,
 * `n`, `m`) and remain attached to the word so cuts never clip trailing or leading phonemes.
 */
private const val SPEECH_HANGOVER_RATIO = 0.45f
private const val SPEECH_PRE_ONSET_FRAMES = 3
private const val SPEECH_HANGOVER_FRAMES = 5

/** Natural-sounding trailing silence kept after the last word of a live chunk (320 ms). */
internal const val LIVE_CUT_PAD_SAMPLES = 5_120

/**
 * Natural-sounding leading silence retained at the start of the remaining live buffer when cutting at a pause
 * (200 ms). Because this overlap lies strictly inside the verified silent pause `[pauseStart, pauseEnd)`,
 * zero speech samples are ever duplicated while both chunks receive clean acoustic padding.
 */
internal const val LIVE_CUT_LEAD_IN_SAMPLES = 3_200

/**
 * `1.20 s` (`19,200` samples = `15` Conformer 80 ms frames) of real bilateral acoustic overlap retained
 * on forced non-pause cuts (`usable >= maxChunk` when the speaker talked non-stop without a full pause)
 * and offline valley cuts. Both adjacent chunks decode the boundary phrase with `>= 600 ms` of spoken
 * acoustic context around the nominal crossover, and [mergeTimestampedChunkSegments] reconciles the
 * overlap using `Voz` word timestamps and lexical anchors without dropping or duplicating words.
 */
internal const val FORCED_CUT_OVERLAP_SAMPLES = 19_200

/** Trailing window (`3.5 s` = `56,000` samples) searched for an inter-word energy valley during non-stop speech. */
internal const val VALLEY_TRAILING_WINDOW_SAMPLES = 56_000

/** Initial `300 ms` (`15` frames) pre-speech ambient calibration window at the start of a buffer. */
private const val EARLY_NOISE_CALIBRATION_FRAMES = 15

/** When a live buffer holds no speech at all, only this much trailing audio (1 s) is kept as lead-in context. */
private const val LIVE_SILENCE_KEEP_SAMPLES = 16_000

/** The pause requirement never shrinks below this fraction of the configured pause length. */
private const val ADAPTIVE_PAUSE_FLOOR_RATIO = 0.55f

/** Adaptive pause reaches its floor once the pending audio is this fraction of the maximum chunk length. */
private const val ADAPTIVE_PAUSE_FULL_AT_RATIO = 0.75f

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
 * Estimates the recording's ambient noise floor by combining the global 5th-percentile frame RMS with
 * an early `300 ms` pre-speech calibration window. When a speaker talks non-stop (>95% voiced frames),
 * the 5th percentile alone can jump up to the voiced speech level; checking the initial `300 ms` room
 * lead-in recovers the true background noise floor without inflating the threshold on clean recordings.
 */
internal fun calibrateAmbientNoiseFloor(frameRms: FloatArray): Float {
    if (frameRms.isEmpty()) return 0f
    val sorted = frameRms.copyOf()
    sorted.sort()
    val p05 = sorted[(sorted.size * 0.05f).toInt().coerceIn(0, sorted.size - 1)]
    val speechLevel = sorted[(sorted.size * 0.80f).toInt().coerceIn(0, sorted.size - 1)]

    if (frameRms.size >= 25) {
        val earlyCount = min(EARLY_NOISE_CALIBRATION_FRAMES, frameRms.size)
        val early = frameRms.copyOfRange(0, earlyCount)
        early.sort()
        val earlyP30 = early[(early.size * 0.30f).toInt().coerceIn(0, early.size - 1)]
        if (earlyP30 <= speechLevel * 0.30f) {
            return if (p05 > speechLevel * 0.35f) {
                earlyP30
            } else {
                max(p05, min(sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.size - 1)], earlyP30))
            }
        }
    }
    return p05
}

/**
 * Silence threshold that adapts to the recording's noise floor: never below the user's [userThreshold],
 * but raised to ~3x the calibrated ambient noise floor so constant background noise (fans, traffic, AC) is
 * not mistaken for speech. Bounded by both `0.03f` and `25%` of the 80th-percentile speech level so quiet
 * speakers or trailing phrase declination are never misclassified as silence.
 */
internal fun adaptiveSilenceThreshold(frameRms: FloatArray, userThreshold: Float): Float {
    if (frameRms.isEmpty()) return userThreshold
    val sorted = frameRms.copyOf()
    sorted.sort()
    val speechLevel = sorted[(sorted.size * 0.80f).toInt().coerceIn(0, sorted.size - 1)]
    val noiseFloor = calibrateAmbientNoiseFloor(frameRms)
    val adaptive = noiseFloor * 3f
    val ceiling = max(userThreshold, min(0.03f, speechLevel * 0.25f))
    return max(userThreshold, adaptive).coerceAtMost(ceiling)
}

/**
 * Estimates whether the speaker is talking at a rapid syllable cadence (`>= 5.5` envelope peaks/sec)
 * over the recent `4.0 s` window preceding [upToFrame]. Fast talkers naturally take brief `300–420 ms`
 * mid-clause catch-breaths that are NOT sentence boundaries; returning a small `+3` frame (`60 ms`)
 * margin prevents chopping rapid-fire clauses prematurely while leaving normal speech and steady tones untouched.
 */
internal fun estimateRapidCadencePauseBonusFrames(
    frameRms: FloatArray,
    silent: BooleanArray,
    upToFrame: Int
): Int {
    val end = min(upToFrame, min(frameRms.size, silent.size))
    val windowFrames = 200 // 4.0 s @ 20 ms/frame
    val start = max(0, end - windowFrames)
    if (end - start < 75) return 0 // Need at least 1.5 s of history

    var voicedFrames = 0
    var voicedSum = 0.0
    for (f in start until end) {
        if (!silent[f]) {
            voicedFrames++
            voicedSum += frameRms[f].toDouble()
        }
    }
    if (voicedFrames < 50) return 0
    val voicedMean = (voicedSum / voicedFrames.toDouble()).toFloat()
    val crestThreshold = voicedMean * 1.08f
    val troughThreshold = voicedMean * 0.78f

    var peaks = 0
    var armedByTrough = true
    var lastPeakFrame = -10
    for (f in start until end) {
        if (silent[f]) {
            armedByTrough = true
            continue
        }
        val r = frameRms[f]
        if (r <= troughThreshold) {
            armedByTrough = true
        } else if (armedByTrough && r >= crestThreshold && f - lastPeakFrame >= 4) {
            peaks++
            lastPeakFrame = f
            armedByTrough = false
        }
    }

    val voicedSeconds = (voicedFrames * CHUNK_FRAME_SAMPLES).toFloat() / DictationChunkSettings.SAMPLE_RATE.toFloat()
    val peaksPerSec = peaks / max(0.5f, voicedSeconds)
    return if (peaksPerSec >= 5.5f) 3 else 0
}

/**
 * Per-frame silence classification (`true` = silent) with:
 * 1. **Stop-closure & plosive-release bridging:** Words ending or containing stop consonants (`/p/`, `/t/`,
 *    `/k/`, `/b/`, `/d/`, `/g/`) have a 2–5 frame (`40–100 ms`) silent vocal-tract occlusion followed by a
 *    1–2 frame (`20–40 ms`) plosive release burst or short unstressed syllable. Any short burst within
 *    [MAX_STOP_CLOSURE_FRAMES] of a confirmed speech run (`>= [MIN_SPEECH_RUN_FRAMES]`) is bridged across
 *    the stop closure so the final consonant or syllable is never clipped or mistaken for a pause start.
 * 2. **Isolated click suppression:** A run of loud frames shorter than [MIN_SPEECH_RUN_FRAMES] that is
 *    separated from all confirmed speech runs by more than [MAX_STOP_CLOSURE_FRAMES] of silence on both
 *    sides is a lone tap or lip smack and is suppressed to silence so it does not split a pause in two.
 * 3. **Dual-threshold speech hangover:** Frames immediately before ([SPEECH_PRE_ONSET_FRAMES]) or after
 *    ([SPEECH_HANGOVER_FRAMES]) a speech run that remain above `threshold * SPEECH_HANGOVER_RATIO` are soft
 *    consonant onsets/tails (`s`, `t`, `f`, `k`, `n`, `m`, `l`) and are kept as speech (`silent = false`).
 */
internal fun buildSilenceMask(frameRms: FloatArray, threshold: Float): BooleanArray {
    val n = frameRms.size
    if (n == 0) return BooleanArray(0)
    val silent = BooleanArray(n) { frameRms[it] < threshold }

    // Identify confirmed core speech runs (>= MIN_SPEECH_RUN_FRAMES = 60 ms)
    val hasCoreSpeech = BooleanArray(n)
    var i = 0
    while (i < n) {
        if (!silent[i]) {
            var j = i
            while (j < n && !silent[j]) j++
            if (j - i >= MIN_SPEECH_RUN_FRAMES) {
                for (k in i until j) hasCoreSpeech[k] = true
            }
            i = j
        } else {
            i++
        }
    }

    // Bridge short bursts (< MIN_SPEECH_RUN_FRAMES) that lie within MAX_STOP_CLOSURE_FRAMES (100 ms)
    // of a confirmed speech run (plosive release bursts like -t/-k/-p or short unstressed syllables),
    // and suppress only truly isolated clicks surrounded by > 100 ms of silence on both sides.
    i = 0
    while (i < n) {
        if (!silent[i]) {
            var j = i
            while (j < n && !silent[j]) j++
            if (j - i < MIN_SPEECH_RUN_FRAMES) {
                var prevCore = -1
                for (k in (i - 1) downTo max(0, i - MAX_STOP_CLOSURE_FRAMES)) {
                    if (hasCoreSpeech[k]) {
                        prevCore = k
                        break
                    }
                }
                var nextCore = -1
                for (k in j until min(n, j + MAX_STOP_CLOSURE_FRAMES)) {
                    if (hasCoreSpeech[k]) {
                        nextCore = k
                        break
                    }
                }
                if (prevCore >= 0 || nextCore >= 0) {
                    if (prevCore >= 0) {
                        for (k in (prevCore + 1) until j) {
                            silent[k] = false
                            hasCoreSpeech[k] = true
                        }
                    }
                    if (nextCore >= 0) {
                        for (k in i until nextCore) {
                            silent[k] = false
                            hasCoreSpeech[k] = true
                        }
                    }
                } else if (i > 0 && j < n) {
                    // Isolated click far from any speech -> suppress to silence
                    for (k in i until j) silent[k] = true
                }
            }
            i = j
        } else {
            i++
        }
    }

    // Dual-threshold hysteresis: protect soft consonant onsets and trailing fricative/nasal tails
    val hangoverFloor = threshold * SPEECH_HANGOVER_RATIO
    val speechRuns = ArrayList<IntRange>()
    i = 0
    while (i < n) {
        if (!silent[i]) {
            var j = i
            while (j < n && !silent[j]) j++
            if (j - i >= MIN_SPEECH_RUN_FRAMES) {
                speechRuns.add(i until j)
            }
            i = j
        } else {
            i++
        }
    }
    for (run in speechRuns) {
        for (step in 1..SPEECH_PRE_ONSET_FRAMES) {
            val idx = run.first - step
            if (idx >= 0 && frameRms[idx] >= hangoverFloor) {
                silent[idx] = false
            } else {
                break
            }
        }
        for (step in 0 until SPEECH_HANGOVER_FRAMES) {
            val idx = run.last + 1 + step
            if (idx < n && frameRms[idx] >= hangoverFloor) {
                silent[idx] = false
            } else {
                break
            }
        }
    }

    return silent
}

/**
 * Returns `true` when [samples] contains at least [minSpeechFrames] (default 100 ms) of sustained
 * non-click voice activity. Used to skip post-pause silent or button-tap-only tail buffers so the speech
 * model is never invoked on dead air or a stop-button tap.
 */
internal fun hasUsableSpeech(
    samples: FloatArray,
    silenceThreshold: Float = DEFAULT_SILENCE_RMS,
    minSpeechFrames: Int = MIN_TAIL_SPEECH_FRAMES
): Boolean {
    if (samples.size < minSpeechFrames * CHUNK_FRAME_SAMPLES) return false
    val rms = computeFrameRms(samples, 0, samples.size, CHUNK_FRAME_SAMPLES)
    if (rms.size < minSpeechFrames) return false
    val effectiveThreshold = adaptiveSilenceThreshold(rms, max(silenceThreshold * 0.6f, 0.004f))
    val silent = buildSilenceMask(rms, effectiveThreshold)
    var speechFrames = 0
    for (isSilent in silent) {
        if (!isSilent) {
            speechFrames++
            if (speechFrames >= minSpeechFrames) return true
        }
    }
    return false
}

/**
 * Sensitive speech check for the final post-cut `tail` when the user stops recording:
 * returns `true` if [samples] holds even a single short spoken syllable (`>= 4` frames / `80 ms` of
 * voiced energy above a sensitive floor), ensuring a fast 1-syllable final word at the end of a dictation
 * (`"no"`, `"it"`, `"so"`, `"ja"`, `"ok"`) is never dropped while still skipping pure dead air or a
 * brief `40 ms` finger-tap click straddling frame boundaries.
 */
internal fun hasAnyTailSpeech(
    samples: FloatArray,
    silenceThreshold: Float = DEFAULT_SILENCE_RMS
): Boolean {
    val minFrames = 4
    if (samples.size < minFrames * CHUNK_FRAME_SAMPLES) return false
    if (hasUsableSpeech(samples, silenceThreshold, minSpeechFrames = minFrames)) return true
    val rms = computeFrameRms(samples, 0, samples.size, CHUNK_FRAME_SAMPLES)
    if (rms.size < minFrames) return false
    val floor = min(max(silenceThreshold * 0.28f, 0.0020f), 0.0042f)
    var consecutive = 0
    for (r in rms) {
        if (r >= floor) {
            consecutive++
            if (consecutive >= minFrames) return true
        } else {
            consecutive = 0
        }
    }
    return false
}

/**
 * Result of [findBestSplitDecision]: chosen sample index inside `[rangeStart, rangeEndExclusive)` and
 * whether that split fell inside a real silence run ([CutKind.CONFIDENT_PAUSE]) or an inter-word energy
 * valley ([CutKind.VALLEY_OVERLAP]).
 */
internal data class SplitPointDecision(
    val sampleIndex: Int,
    val cutKind: CutKind
)

/**
 * Picks the best place to cut inside `[rangeStart, rangeEndExclusive)` and classifies the cut:
 *
 * 1. Finds every run of silent frames (adaptive threshold, click suppression, consonant hangover). Runs at
 *    least [minPauseFrames] long qualify as genuine pauses ([CutKind.CONFIDENT_PAUSE]).
 * 2. Among pauses, accepts those within 75% of the longest one and prefers the **latest** (bigger chunks),
 *    cutting at the **centre** of the pause so no word is clipped on either side.
 * 3. If the range contains no real pause, returns [CutKind.VALLEY_OVERLAP] at the lowest-energy inter-word
 *    valley (optionally focusing on the trailing [preferTrailingValleySamples] so continuous speech uses its
 *    full chunk budget). Scores a 6-frame (`120 ms`) core + 6-frame (`120 ms`) bilateral halo + a transient
 *    wall-steepness penalty so brief `40–60 ms` intra-word stop-consonant closures (`/p/`, `/t/`, `/k/`)
 *    flanked by loud vowels are rejected in favor of smooth inter-word prosodic valleys.
 */
internal fun findBestSplitDecision(
    samples: FloatArray,
    rangeStart: Int,
    rangeEndExclusive: Int,
    silenceThreshold: Float = DEFAULT_SILENCE_RMS,
    minPauseFrames: Int = 3,
    frameLen: Int = CHUNK_FRAME_SAMPLES,
    preferTrailingValleySamples: Int = 0
): SplitPointDecision {
    val rms = computeFrameRms(samples, rangeStart, rangeEndExclusive, frameLen)
    if (rms.isEmpty()) {
        return SplitPointDecision(
            sampleIndex = ((rangeStart.toLong() + rangeEndExclusive) / 2).toInt(),
            cutKind = CutKind.VALLEY_OVERLAP
        )
    }
    val n = rms.size
    val silent = buildSilenceMask(rms, adaptiveSilenceThreshold(rms, silenceThreshold))

    var longest = 0
    var i = 0
    while (i < n) {
        if (silent[i]) {
            var j = i
            while (j < n && silent[j]) j++
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
            if (silent[i]) {
                var j = i
                while (j < n && silent[j]) j++
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
            val idx = rangeStart + (bestStart + bestLen / 2) * frameLen + frameLen / 2
            return SplitPointDecision(
                sampleIndex = idx.coerceIn(rangeStart, max(rangeStart, rangeEndExclusive - 1)),
                cutKind = CutKind.CONFIDENT_PAUSE
            )
        }
    }

    // Fallback: smooth inter-word energy valley (6-frame / 120 ms core + 3-frame bilateral halo + transient penalty)
    val win = min(6, n)
    val valleyStartFrame = if (preferTrailingValleySamples > 0) {
        val trailingFrames = max(win + 2, preferTrailingValleySamples / frameLen)
        max(0, n - trailingFrames)
    } else {
        0
    }
    val maxStart = (n - win).coerceAtLeast(0)
    val effectiveValleyStart = min(valleyStartFrame, maxStart)

    var bestIdx = effectiveValleyStart
    var bestScore = Double.MAX_VALUE
    for (start in effectiveValleyStart..maxStart) {
        var coreEnergy = 0.0
        var coreMean = 0.0
        for (k in start until (start + win)) {
            val r = rms[k].toDouble()
            coreEnergy += r * r
            coreMean += r
        }
        coreMean /= win.toDouble()

        var haloEnergy = 0.0
        var haloCount = 0
        var wallPeak = 0.0
        for (k in max(0, start - 3) until start) {
            val r = rms[k].toDouble()
            haloEnergy += r * r
            if (r > wallPeak) wallPeak = r
            haloCount++
        }
        for (k in (start + win) until min(n, start + win + 3)) {
            val r = rms[k].toDouble()
            haloEnergy += r * r
            if (r > wallPeak) wallPeak = r
            haloCount++
        }
        val normalizedHalo = if (haloCount > 0) (haloEnergy / haloCount) * win * 0.40 else 0.0
        // Stop-closure transient penalty: a sharp cliff from a loud vowel into a brief occlusion and back up
        // to a plosive burst has a high (wallPeak - coreMean)^2 compared to a gradual inter-word prosodic dip.
        val transientCliff = max(0.0, wallPeak - coreMean)
        val transientPenalty = transientCliff * transientCliff * 0.65
        val score = coreEnergy + normalizedHalo + transientPenalty
        if (score <= bestScore * 1.005) {
            if (score < bestScore) {
                bestScore = score
            }
            bestIdx = start
        }
    }
    val idx = rangeStart + (bestIdx + win / 2) * frameLen
    return SplitPointDecision(
        sampleIndex = idx.coerceIn(rangeStart, max(rangeStart, rangeEndExclusive - 1)),
        cutKind = CutKind.VALLEY_OVERLAP
    )
}

/**
 * Picks the best sample index to cut inside `[rangeStart, rangeEndExclusive)`.
 * Delegates to [findBestSplitDecision] for backwards compatibility.
 */
internal fun findBestSplitPoint(
    samples: FloatArray,
    rangeStart: Int,
    rangeEndExclusive: Int,
    silenceThreshold: Float = DEFAULT_SILENCE_RMS,
    minPauseFrames: Int = 3,
    frameLen: Int = CHUNK_FRAME_SAMPLES
): Int = findBestSplitDecision(
    samples = samples,
    rangeStart = rangeStart,
    rangeEndExclusive = rangeEndExclusive,
    silenceThreshold = silenceThreshold,
    minPauseFrames = minPauseFrames,
    frameLen = frameLen
).sampleIndex

/**
 * Pause length (in frames) that qualifies as a cut point when the pending audio already spans
 * [pendingSamples] samples.
 *
 * With [DictationChunkSettings.adaptivePause] the requirement shrinks linearly from the configured pause
 * (right at the minimum chunk length) down to [ADAPTIVE_PAUSE_FLOOR_RATIO] of it (at 75% of the maximum
 * chunk length): the longer someone has been talking without a clear pause, the more any natural gap is
 * preferred over eventually being forced to cut mid-phrase at the hard maximum.
 */
internal fun requiredPauseFrames(pendingSamples: Int, config: DictationChunkSettings): Int {
    val base = max(2, config.pauseSamples / CHUNK_FRAME_SAMPLES)
    if (!config.adaptivePause) return base
    val floor = max(2, (base * ADAPTIVE_PAUSE_FLOOR_RATIO).roundToInt())
    val start = config.minLiveChunkSamples
    val end = max(start + 1, (config.maxChunkSamples * ADAPTIVE_PAUSE_FULL_AT_RATIO).toInt())
    val t = ((pendingSamples - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    return (base + (floor - base) * t).roundToInt().coerceIn(floor, base)
}

/**
 * Decision of the live pause detector.
 *
 * @property index exclusive end of the finished chunk inside the live buffer (`[0, index)`).
 * @property hasSpeech `false` when `[0, index)` holds no usable speech and should just be discarded
 * (long silence before the user started talking).
 * @property resumeIndex index inside the live buffer where the next chunk should start (`<= index`).
 * - On [CutKind.CONFIDENT_PAUSE], `[resumeIndex, index)` lies strictly inside the verified silent pause so
 *   both chunks receive natural silence padding without duplicating a single spoken sample.
 * - On [CutKind.VALLEY_OVERLAP], `resumeIndex` rewinds by [FORCED_CUT_OVERLAP_SAMPLES] (`1.20 s`) so both
 *   chunks decode the boundary phrase with bilateral Conformer context for timestamp-aligned merging.
 * @property cutKind classification of this cut for telemetry and boundary-aware post-processing.
 * @property nominalSplitSample ideal splice point inside `[resumeIndex, index]` (`(resumeIndex + index) / 2`)
 * used as the timestamp crossover point when merging adjacent chunks.
 */
internal data class StreamingCut(
    val index: Int,
    val hasSpeech: Boolean,
    val resumeIndex: Int = index,
    val cutKind: CutKind = if (hasSpeech) CutKind.CONFIDENT_PAUSE else CutKind.SILENCE_DISCARD,
    val nominalSplitSample: Int = (resumeIndex + index) / 2
)

/**
 * Live pause detector used while the user is still recording.
 *
 * Looks at the not-yet-transcribed audio `samples[0, size)` and returns where a *finished* chunk ends, or
 * `null` if nothing should be cut yet:
 *
 * - **Confident pause cut ([CutKind.CONFIDENT_PAUSE]):** finds qualifying silence runs (see [requiredPauseFrames]
 *   plus speaking-rate protection via [estimateRapidCadencePauseBonusFrames]) that end at or after
 *   `config.minLiveChunkSeconds`. Prefers the latest comparable pause so complete thoughts stay together.
 * - **Valley-overlap cut ([CutKind.VALLEY_OVERLAP]):** once the buffer reaches `config.maxChunkSeconds`
 *   without a full pause, cuts at the best local pause or inter-word valley in the trailing window and
 *   retains [FORCED_CUT_OVERLAP_SAMPLES] (`1.20 s`) of real speech overlap for timestamp-aligned stitching.
 * - **Silence discard ([CutKind.SILENCE_DISCARD]):** a buffer with no speech at all is dropped (keeping 1 s
 *   of lead-in) so waiting before speaking never builds a giant silent chunk.
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
    val silent = buildSilenceMask(rms, adaptiveSilenceThreshold(rms, config.silenceThreshold))

    var totalSpeech = 0
    for (s in silent) if (!s) totalSpeech++
    if (totalSpeech < MIN_SPEECH_FRAMES) {
        val index = usable - LIVE_SILENCE_KEEP_SAMPLES
        return if (index >= frame) {
            StreamingCut(
                index = index,
                hasSpeech = false,
                resumeIndex = index,
                cutKind = CutKind.SILENCE_DISCARD,
                nominalSplitSample = index
            )
        } else {
            null
        }
    }

    data class CandidatePause(val startFrame: Int, val endFrame: Int, val cutSample: Int, val hasSpeechBefore: Boolean) {
        val runLen: Int get() = endFrame - startFrame
    }

    val candidates = ArrayList<CandidatePause>()
    val searchFrames = min(n, maxChunk / frame)
    val rapidGuardCutoffSamples = (maxChunk * ADAPTIVE_PAUSE_FULL_AT_RATIO).toInt()
    var i = 0
    while (i < searchFrames) {
        if (!silent[i]) {
            i++
            continue
        }
        var j = i
        while (j < n && silent[j]) j++
        val runLen = j - i
        val pendingAtPause = i * frame
        val cadenceBonus = if (pendingAtPause < rapidGuardCutoffSamples) {
            estimateRapidCadencePauseBonusFrames(rms, silent, i)
        } else {
            0
        }
        val neededFrames = requiredPauseFrames(pendingAtPause, config) + cadenceBonus
        if (i > 0 && runLen >= neededFrames) {
            val cut = i * frame + min((runLen * frame * 2) / 3, LIVE_CUT_PAD_SAMPLES)
            if (cut >= minChunk && cut <= usable) {
                var speechBefore = 0
                for (f in 0 until min(n, cut / frame)) if (!silent[f]) speechBefore++
                candidates.add(
                    CandidatePause(
                        startFrame = i,
                        endFrame = j,
                        cutSample = cut,
                        hasSpeechBefore = speechBefore >= MIN_SPEECH_FRAMES
                    )
                )
            }
        }
        i = j
    }

    if (candidates.isNotEmpty()) {
        val longestLen = candidates.maxOf { it.runLen }
        val baseRequired = max(2, config.pauseSamples / frame)
        val acceptLen = min(baseRequired, ceil(longestLen * 0.75).toInt())
        val chosen = candidates.lastOrNull { it.runLen >= acceptLen && it.hasSpeechBefore }
            ?: candidates.last()
        val pauseStartSamples = chosen.startFrame * frame
        val pauseEndSamples = min(usable, chosen.endFrame * frame)
        val cut = chosen.cutSample
        val resumeIndex = if (chosen.hasSpeechBefore) {
            max(pauseStartSamples + frame, min(cut, pauseEndSamples - LIVE_CUT_LEAD_IN_SAMPLES))
                .coerceIn(1, cut)
        } else {
            cut
        }
        val kind = if (chosen.hasSpeechBefore) CutKind.CONFIDENT_PAUSE else CutKind.SILENCE_DISCARD
        return StreamingCut(
            index = cut,
            hasSpeech = chosen.hasSpeechBefore,
            resumeIndex = resumeIndex,
            cutKind = kind,
            nominalSplitSample = (pauseStartSamples + pauseEndSamples) / 2
        )
    }

    if (usable >= maxChunk) {
        val searchEnd = min(usable, maxChunk).coerceAtLeast(minChunk + frame)
        val safeSearchEnd = min(usable, searchEnd)
        val minPauseFrames = max(3, requiredPauseFrames(safeSearchEnd, config) / 2)
        val decision = findBestSplitDecision(
            samples = samples,
            rangeStart = minChunk,
            rangeEndExclusive = safeSearchEnd,
            silenceThreshold = config.silenceThreshold,
            minPauseFrames = minPauseFrames,
            frameLen = frame,
            preferTrailingValleySamples = VALLEY_TRAILING_WINDOW_SAMPLES
        )
        val clamped = decision.sampleIndex.coerceIn(minChunk, safeSearchEnd)
        val resumeAt = (clamped - FORCED_CUT_OVERLAP_SAMPLES).coerceIn(1, clamped)
        return StreamingCut(
            index = clamped,
            hasSpeech = true,
            resumeIndex = resumeAt,
            cutKind = CutKind.VALLEY_OVERLAP,
            nominalSplitSample = (resumeAt + clamped) / 2
        )
    }
    return null
}
