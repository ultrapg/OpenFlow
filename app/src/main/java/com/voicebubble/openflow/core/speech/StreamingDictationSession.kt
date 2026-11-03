package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.audio.StoppedRecordingTail
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.DictationChunkSettings
import com.voicebubble.openflow.settings.GemmaPromptPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * "Transcribe while still recording": watches the live microphone buffer for pauses (or valley-overlap bounds
 * on continuous non-stop speech) and hands every finished chunk to [SmartPipeline] immediately, so that after
 * the user stops only the final tail is left to process.
 *
 * Lifecycle: [start] right after recording started -> [stopPolling] + `audio.stopRecordingDetailed()` when the user
 * stops -> [finish] with the remaining tail (returns the full transcript), or [cancel] to abandon everything.
 *
 * Guarantees:
 * - Chunks are transcribed strictly in order, one at a time (single worker), with 1-sample-exact global
 *   session coordinates (`[startSampleInSession, endSampleInSession)`) and `Voz` word timestamps preserved.
 * - Adjacent chunks (including `1.20 s` bilateral valley-overlap cuts on continuous speech) are merged via
 *   [mergeTimestampedChunkSegmentsWithTelemetry] using timestamp-gated lexical anchors and revisable tails,
 *   eliminating boundary word drops, boundary duplications, and synthetic mid-sentence periods.
 * - Background live LLM overlap ([planStreamingOverlapCommit]) only commits complete, closed sentence groups
 *   across verified acoustic pauses and verifies the final joined output against the whole-session raw `Voz`
 *   transcript ([reconcileWholeDictationWithRawVoz]).
 */
class StreamingDictationSession(
    private val scope: CoroutineScope,
    private val audio: AudioRecordingController,
    private val pipeline: SmartPipeline,
    private val config: DictationChunkSettings,
    private val onStageProgress: (String) -> Unit
) {
    private class PendingChunk(
        val index: Int,
        val samples: FloatArray,
        val startSampleInSession: Long,
        val nominalCutSampleAfterPrev: Long?,
        val cutKindAfterPrev: CutKind?
    )

    private val queue = Channel<PendingChunk>(Channel.UNLIMITED)
    private val results = ConcurrentHashMap<Int, ChunkTranscriptionSegment>()
    private val failed = ConcurrentHashMap<Int, PendingChunk>()
    private val nextIndex = AtomicInteger(0)
    private val inFlightChunks = AtomicInteger(0)
    private val failedBufferedSamples = AtomicLong(0L)
    private val droppedFailedChunks = AtomicInteger(0)
    private val sessionExtentSamples = AtomicLong(0L)
    private val liveChunkCount = AtomicInteger(0)
    private val pauseCutCount = AtomicInteger(0)
    private val valleyOverlapCutCount = AtomicInteger(0)
    private val stopRequested = AtomicBoolean(false)
    private val preDetectInFlight = AtomicBoolean(false)
    private val preDetectAttempts = AtomicInteger(0)

    // Accessed strictly serially by workerJob while running, then by finish() after workerJob.join()
    private val preCleanedSegments = ArrayList<String>()
    private var nextUncleanedChunkIndex: Int = 0
    private var lastCleanedTail: String? = null

    @Volatile
    private var language: String? = null

    private var pollerJob: Job? = null
    private var workerJob: Job? = null
    private var preDetectJob: Job? = null

    /** Total non-duplicated session extent in samples handed to the pipeline (live chunks + tail). */
    val totalSamples: Long get() = sessionExtentSamples.get()

    /** Number of chunks that were sent to the pipeline *while recording*. */
    val liveChunksSent: Int get() = liveChunkCount.get()

    /** Number of confident silence-pause cuts performed during this session. */
    val confidentPauseCuts: Int get() = pauseCutCount.get()

    /** Number of continuous-speech valley-overlap cuts performed during this session. */
    val valleyOverlapCuts: Int get() = valleyOverlapCutCount.get()

    /** Number of chunks that still failed after the retry in [finish]. */
    @Volatile
    var failedChunkCount: Int = 0
        private set

    fun start() {
        workerJob = scope.launch(Dispatchers.Default) {
            for (chunk in queue) {
                try {
                    val segment = transcribeChunk(chunk, allowLanguageHint = true)
                    if (segment == null) {
                        val canRetainForRetry = !pipeline.isMemoryCriticallyTight() &&
                            failedBufferedSamples.get() + chunk.samples.size <= MAX_FAILED_RETRY_SAMPLES
                        if (canRetainForRetry) {
                            failed[chunk.index] = chunk
                            failedBufferedSamples.addAndGet(chunk.samples.size.toLong())
                        } else {
                            droppedFailedChunks.incrementAndGet()
                            DebugLogManager.logWarn(
                                TAG,
                                "Skipping retry audio retention for failed chunk #${chunk.index} to protect RAM headroom"
                            )
                        }
                    } else {
                        results[chunk.index] = segment
                        tryOverlapCleanFinalizedPrefix(currentIndex = chunk.index)
                        if (!stopRequested.get() && inFlightChunks.get() <= 1) {
                            pipeline.preparePostProcessorWhileRecordingAsync(initialDelayMs = 0L)
                        }
                    }
                } finally {
                    inFlightChunks.decrementAndGet()
                }
            }
        }
        pollerJob = scope.launch(Dispatchers.Default) {
            val readinessCoalesceCap = ((config.maxChunkSamples.toLong() * 85L) / 100L).toInt()
            val nearMinChunkSamples = (config.minLiveChunkSamples - POLL_APPROACH_MARGIN_SAMPLES).coerceAtLeast(0)
            while (isActive && !stopRequested.get()) {
                val buffered = audio.bufferedSampleCount()
                maybeScheduleEarlyLanguagePreDetect(buffered)

                // If Voz is still cold-loading on the very first chunk, coalesce speech inside the single
                // PCM buffer until Voz becomes ready (unless approaching maxChunkSamples).
                if (!pipeline.isVozReady && buffered < readinessCoalesceCap) {
                    delay(COLD_LOAD_POLL_INTERVAL_MS)
                    continue
                }

                // Adaptive buffer-aware sleep: avoid scanning RMS windows while buffered audio is still
                // well below minLiveChunkSamples (waking early if live Ear pre-detection needs a peek).
                if (buffered < nearMinChunkSamples) {
                    val nextWakeTarget = when {
                        config.reuseLanguage && language == null &&
                            preDetectAttempts.get() == 0 && PRE_DETECT_FIRST_TRY_SAMPLES < nearMinChunkSamples ->
                            PRE_DETECT_FIRST_TRY_SAMPLES
                        config.reuseLanguage && language == null &&
                            preDetectAttempts.get() == 1 && PRE_DETECT_SECOND_TRY_SAMPLES < nearMinChunkSamples ->
                            PRE_DETECT_SECOND_TRY_SAMPLES
                        else -> nearMinChunkSamples
                    }
                    if (buffered < nextWakeTarget) {
                        val remainingMs = ((nextWakeTarget - buffered).toLong() * 1000L) / DictationChunkSettings.SAMPLE_RATE
                        delay(remainingMs.coerceIn(ACTIVE_POLL_INTERVAL_MS, MAX_IDLE_BUFFER_SLEEP_MS))
                        continue
                    }
                }

                delay(ACTIVE_POLL_INTERVAL_MS)
                if (stopRequested.get()) break

                // Backpressure: if the inference worker already has MAX_IN_FLIGHT_LIVE_CHUNKS queued or memory
                // is critically tight, keep new speech inside AudioRecordingController's single PCM buffer.
                if (inFlightChunks.get() >= MAX_IN_FLIGHT_LIVE_CHUNKS || pipeline.isMemoryCriticallyTight()) {
                    continue
                }
                val extracted = try {
                    audio.extractStreamingChunkDetailed(config)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    DebugLogManager.logWarn(TAG, "Live chunk extraction failed; will retry on next poll", t)
                    null
                }
                if (extracted != null) {
                    liveChunkCount.incrementAndGet()
                    when (extracted.cutKindAtEnd) {
                        CutKind.CONFIDENT_PAUSE -> pauseCutCount.incrementAndGet()
                        CutKind.VALLEY_OVERLAP -> valleyOverlapCutCount.incrementAndGet()
                        CutKind.SILENCE_DISCARD -> {}
                    }
                    DebugLogManager.logInfo(
                        TAG,
                        "Live chunk #${nextIndex.get()} cut (${extracted.cutKindAtEnd}, " +
                            "${extracted.samples.size * 1000L / DictationChunkSettings.SAMPLE_RATE} ms, " +
                            "range=${extracted.startSampleInSession}..${extracted.endSampleInSession}) -> sent to pipeline while recording"
                    )
                    enqueue(
                        samples = extracted.samples,
                        startSampleInSession = extracted.startSampleInSession,
                        nominalCutSampleAfterPrev = extracted.nominalCutSampleAfterPrev,
                        cutKindAfterPrev = extracted.cutKindAfterPrev
                    )
                }
            }
        }
    }

    /**
     * Peeks at the leading live microphone buffer (~1.2s / ~2.8s) while the user is still speaking
     * their first sentence so `Ear` resolves the spoken language before Chunk #0 is even cut.
     */
    private fun maybeScheduleEarlyLanguagePreDetect(bufferedSamples: Int) {
        if (!config.reuseLanguage || language != null || stopRequested.get()) return
        if (!pipeline.isVozReady || inFlightChunks.get() > 0) return
        val attempts = preDetectAttempts.get()
        val threshold = when (attempts) {
            0 -> PRE_DETECT_FIRST_TRY_SAMPLES
            1 -> PRE_DETECT_SECOND_TRY_SAMPLES
            else -> return
        }
        if (bufferedSamples < threshold) return
        if (!preDetectInFlight.compareAndSet(false, true)) return
        preDetectAttempts.incrementAndGet()

        preDetectJob = scope.launch(Dispatchers.Default) {
            try {
                if (stopRequested.get() || language != null || inFlightChunks.get() > 0) return@launch
                val preview = audio.peekLeadingSamples(PRE_DETECT_PEEK_MAX_SAMPLES) ?: return@launch
                val detected = pipeline.tryPreDetectLanguageWhileRecording(preview)
                if (detected != null && language == null) {
                    language = detected
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            } finally {
                preDetectInFlight.set(false)
            }
        }
    }

    /**
     * Boundary-aware live LLM overlap: when `Voz` finishes chunk [currentIndex] and the audio queue is
     * momentarily empty while the user speaks the next chunk, uses [planStreamingOverlapCommit] over the
     * timestamp-aligned `ChunkTranscriptionSegment`s to commit ONLY closed, complete sentence groups across
     * verified acoustic pauses (`>= 0.45 s`), holding back any unclosed clauses, valley-overlap cuts, or
     * cross-chunk verbal repairs.
     */
    private suspend fun tryOverlapCleanFinalizedPrefix(currentIndex: Int) {
        if (stopRequested.get()) return
        if (currentIndex <= nextUncleanedChunkIndex) return
        // Only run background LLM overlap when no waiting audio chunks are backed up behind this one
        if (inFlightChunks.get() > 1) return
        if (failed.isNotEmpty() || droppedFailedChunks.get() > 0) return
        if (!pipeline.canOverlapStreamingLlmCleanup()) return

        val nonBlankEntries = ArrayList<Pair<Int, ChunkTranscriptionSegment>>(currentIndex - nextUncleanedChunkIndex + 1)
        for (idx in nextUncleanedChunkIndex..currentIndex) {
            val seg = results[idx] ?: return
            if (seg.normalizedText.isNotBlank()) {
                nonBlankEntries.add(idx to seg)
            }
        }
        if (nonBlankEntries.size < 2) {
            if (nonBlankEntries.isEmpty()) {
                nextUncleanedChunkIndex = currentIndex + 1
            }
            return
        }

        val activeSettings = pipeline.currentSettings()
        val resolvedLang = language ?: activeSettings.preferredLanguage
        val commitPlan = planStreamingOverlapCommit(
            uncleanedSegments = nonBlankEntries.map { it.second },
            language = resolvedLang,
            customVocabulary = activeSettings.customVocabulary
        ) ?: return

        val cleanedPrefix = try {
            pipeline.postProcessStreamingSegment(
                text = commitPlan.prefixTextToClean,
                languageHint = resolvedLang,
                precedingCleanedTail = lastCleanedTail,
                applyLeadingCursorContinuation = preCleanedSegments.isEmpty()
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLogManager.logWarn(TAG, "Deferred background streaming LLM overlap: ${t.message}")
            null
        } ?: return

        val newNextUncleanedIdx = nonBlankEntries[commitPlan.commitRelativeEndExclusive].first
        nextUncleanedChunkIndex = newNextUncleanedIdx
        if (cleanedPrefix.isNotBlank()) {
            preCleanedSegments.add(cleanedPrefix)
            lastCleanedTail = GemmaTextProcessor.extractContinuityTail(cleanedPrefix)
        }
        DebugLogManager.logInfo(
            TAG,
            "Live LLM overlap cleaned closed sentence group [..${newNextUncleanedIdx - 1}] " +
                "(${commitPlan.prefixTextToClean.length} -> ${cleanedPrefix.length} chars) while user continued speaking"
        )
    }

    /** Stops looking for new pauses. Must be called (and awaited) before `audio.stopRecordingDetailed()`. */
    suspend fun stopPolling() {
        stopRequested.set(true)
        preDetectJob?.cancel()
        preDetectJob = null
        pollerJob?.cancelAndJoin()
        pollerJob = null
    }

    /**
     * Backwards-compatible overload when caller only holds raw [tail] samples.
     */
    suspend fun finish(tail: FloatArray): String {
        val fallbackStart = sessionExtentSamples.get()
        return finish(
            StoppedRecordingTail(
                samples = tail,
                startSampleInSession = fallbackStart,
                endSampleInSession = fallbackStart + tail.size.toLong(),
                nominalCutSampleAfterPrev = null,
                cutKindAfterPrev = null
            )
        )
    }

    /**
     * Sends the final [tailDetail] (audio recorded after the last live cut, tagged with exact global session
     * sample coordinates), waits for the ordered worker to drain the queue, retries failed chunks once,
     * aligns all chunks via [mergeTimestampedChunkSegmentsWithTelemetry], and verifies whole-dictation
     * completeness via [reconcileWholeDictationWithRawVoz].
     */
    suspend fun finish(tailDetail: StoppedRecordingTail): String {
        stopPolling()
        val tail = tailDetail.samples
        if (tail.isNotEmpty()) {
            val shouldEnqueueTail = liveChunkCount.get() == 0 ||
                hasAnyTailSpeech(tail, config.silenceThreshold)
            if (shouldEnqueueTail) {
                enqueue(
                    samples = tail,
                    startSampleInSession = tailDetail.startSampleInSession,
                    nominalCutSampleAfterPrev = tailDetail.nominalCutSampleAfterPrev,
                    cutKindAfterPrev = tailDetail.cutKindAfterPrev
                )
            } else {
                updateSessionExtent(tailDetail.endSampleInSession)
                DebugLogManager.logInfo(
                    TAG,
                    "Skipping post-pause silent/tap tail (${tail.size * 1000L / DictationChunkSettings.SAMPLE_RATE} ms) after ${liveChunkCount.get()} live chunk(s)"
                )
            }
        }
        queue.close()
        pipeline.onRecordingStopped()
        try {
            workerJob?.join()

            var stillFailed = droppedFailedChunks.get()
            for (index in failed.keys.sorted()) {
                // Detach audio buffer immediately so GC can reclaim it right after retry
                val pending = failed.remove(index) ?: continue
                failedBufferedSamples.addAndGet(-pending.samples.size.toLong())
                val retried = transcribeChunk(pending, allowLanguageHint = false)
                if (retried != null) {
                    results[index] = retried
                } else {
                    stillFailed++
                }
            }
            failed.clear()
            failedChunkCount = stillFailed

            val sortedEntries = results.entries.sortedBy { it.key }
            val allOrderedSegments = sortedEntries
                .map { it.value }
                .filter { it.normalizedText.isNotBlank() }
            val orderedRemainingSegments = sortedEntries
                .filter { it.key >= nextUncleanedChunkIndex }
                .map { it.value }
                .filter { it.normalizedText.isNotBlank() }
            results.clear()

            val activeSettings = pipeline.currentSettings()
            val resolvedLang = language ?: activeSettings.preferredLanguage

            val fullRawMerged = mergeTimestampedChunkSegmentsWithTelemetry(
                segments = allOrderedSegments,
                language = resolvedLang,
                customVocabulary = activeSettings.customVocabulary
            )
            if (liveChunkCount.get() > 0 || allOrderedSegments.size > 1) {
                val tel = fullRawMerged.telemetry
                DebugLogManager.logInfo(
                    TAG,
                    "Live session summary • liveChunks=${liveChunkCount.get()}, pauseCuts=${pauseCutCount.get()}, " +
                        "valleyOverlapCuts=${valleyOverlapCutCount.get()}, segments=${tel.totalSegments}, " +
                        "anchorJoins=${tel.anchorAlignedJoins}, timeJoins=${tel.timeAlignedJoins}, pauseJoins=${tel.pauseBoundaryJoins}"
                )
            }

            if (preCleanedSegments.isEmpty()) {
                val postProcessed = pipeline.postProcessFinalTranscript(
                    text = fullRawMerged.text,
                    languageHint = resolvedLang,
                    onStageProgress = onStageProgress
                )
                return reconcileWholeDictationWithRawVoz(
                    rawMergedVoz = fullRawMerged.text,
                    candidateOutput = postProcessed,
                    preset = activeSettings.gemmaPromptPreset
                )
            }

            val tailStitched = mergeTimestampedChunkSegments(
                segments = orderedRemainingSegments,
                language = resolvedLang,
                customVocabulary = activeSettings.customVocabulary
            )
            if (tailStitched.isNotBlank()) {
                val cleanedTail = pipeline.postProcessFinalTranscript(
                    text = tailStitched,
                    languageHint = resolvedLang,
                    onStageProgress = onStageProgress,
                    precedingCleanedTail = lastCleanedTail,
                    applyLeadingCursorContinuation = false
                )
                if (cleanedTail.isNotBlank()) {
                    preCleanedSegments.add(cleanedTail)
                }
            }
            val joined = joinStreamingCleanedSegments(
                segments = preCleanedSegments,
                preset = activeSettings.gemmaPromptPreset
            )
            preCleanedSegments.clear()
            return reconcileWholeDictationWithRawVoz(
                rawMergedVoz = fullRawMerged.text,
                candidateOutput = joined,
                preset = activeSettings.gemmaPromptPreset
            )
        } finally {
            pipeline.completeStreamingSession()
        }
    }

    /** Abandons the session (screen turned off, recording failed, service destroyed). */
    fun cancel() {
        stopRequested.set(true)
        pipeline.onRecordingStopped()
        preDetectJob?.cancel()
        preDetectJob = null
        pollerJob?.cancel()
        pollerJob = null
        workerJob?.cancel()
        workerJob = null
        queue.close()
        results.clear()
        failed.clear()
        preCleanedSegments.clear()
        inFlightChunks.set(0)
        failedBufferedSamples.set(0L)
    }

    private fun updateSessionExtent(candidateEndSample: Long) {
        while (true) {
            val current = sessionExtentSamples.get()
            val updated = max(current, candidateEndSample)
            if (updated == current || sessionExtentSamples.compareAndSet(current, updated)) {
                break
            }
        }
    }

    private fun enqueue(
        samples: FloatArray,
        startSampleInSession: Long,
        nominalCutSampleAfterPrev: Long?,
        cutKindAfterPrev: CutKind?
    ) {
        updateSessionExtent(startSampleInSession + samples.size.toLong())
        inFlightChunks.incrementAndGet()
        val pending = PendingChunk(
            index = nextIndex.getAndIncrement(),
            samples = samples,
            startSampleInSession = startSampleInSession,
            nominalCutSampleAfterPrev = nominalCutSampleAfterPrev,
            cutKindAfterPrev = cutKindAfterPrev
        )
        if (queue.trySend(pending).isFailure) {
            inFlightChunks.decrementAndGet()
        }
    }

    /** Returns the timestamped chunk segment, or `null` if the pipeline threw (caller keeps the audio for a retry). */
    private suspend fun transcribeChunk(chunk: PendingChunk, allowLanguageHint: Boolean): ChunkTranscriptionSegment? {
        return try {
            pipeline.transcribeDetailed(
                samples = chunk.samples,
                sessionStartSample = chunk.startSampleInSession,
                nominalCutSampleBefore = chunk.nominalCutSampleAfterPrev,
                cutKindBefore = chunk.cutKindAfterPrev,
                onStageProgress = { stage ->
                    if (stopRequested.get()) {
                        onStageProgress(stage)
                    }
                },
                languageHint = if (config.reuseLanguage && allowLanguageHint) language else null,
                onLanguageResolved = { detected ->
                    if (config.reuseLanguage && language == null) language = detected
                },
                applyPostProcessing = false,
                keepModelsWarmForSession = true
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            DebugLogManager.logError(TAG, "Chunk #${chunk.index} transcription failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "StreamingDictation"
        private const val ACTIVE_POLL_INTERVAL_MS = 85L
        private const val COLD_LOAD_POLL_INTERVAL_MS = 140L
        private const val MAX_IDLE_BUFFER_SLEEP_MS = 380L
        private const val POLL_APPROACH_MARGIN_SAMPLES = 4_800 // 300ms before minLiveChunkSamples
        private const val PRE_DETECT_FIRST_TRY_SAMPLES = 19_200 // ~1.2s of audio
        private const val PRE_DETECT_SECOND_TRY_SAMPLES = 44_800 // ~2.8s of audio
        private const val PRE_DETECT_PEEK_MAX_SAMPLES = 56_000
        private const val MAX_IN_FLIGHT_LIVE_CHUNKS = 4
        private const val MAX_FAILED_RETRY_SAMPLES = 45L * DictationChunkSettings.SAMPLE_RATE
        private val LIST_LINE_REGEX = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+\\S.*$")
        private val EMAIL_SALUTATION_LINE_REGEX = Regex(
            "^(?:hi|hello|hey|dear|good\\s+(?:morning|afternoon|evening)|hallo|liebe[rs]?|guten\\s+(?:morgen|tag|abend)|sehr\\s+geehrte[rn]?)\\b[^.!?\\n]{0,48}[,!]\\s*$",
            RegexOption.IGNORE_CASE
        )
        private val EMAIL_SIGNOFF_START_REGEX = Regex(
            "^(?:best(?:\\s+regards|\\s+wishes)?|kind\\s+regards|warm\\s+regards|regards|sincerely|thanks(?:\\s+again|\\s+so\\s+much)?|thank\\s+you|cheers|many\\s+thanks|viele\\s+grüße|beste\\s+grüße|mit\\s+freundlichen\\s+grüßen|freundliche\\s+grüße|herzliche\\s+grüße|liebe\\s+grüße|danke(?:\\s+schön|\\s+sehr)?)\\b[^.!?\\n]{0,24}[,!]?(?:\\n|$)",
            RegexOption.IGNORE_CASE
        )

        /**
         * Verifies that [prefixStitched]'s right boundary is closed (`.` / `!` / `?` / `…`), was not
         * modified by cross-chunk stitching when [lookaheadChunk] arrived, and [lookaheadChunk] does not
         * open with a cross-chunk self-correction, list continuation, or conjunction.
         */
        fun canCommitStreamingPrefixForOverlap(
            prefixStitched: String,
            lookaheadChunk: String,
            combinedStitched: String
        ): Boolean {
            val trimmedPrefix = prefixStitched.trim()
            val trimmedLookahead = lookaheadChunk.trim()
            if (trimmedPrefix.isEmpty() || trimmedLookahead.isEmpty()) return false

            val strippedEnd = trimmedPrefix.trimEnd { it == '"' || it == '”' || it == '\'' || it == '’' || it == ')' }
            val lastChar = strippedEnd.lastOrNull() ?: return false
            if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != '…') {
                return false
            }
            if (!combinedStitched.trim().startsWith("$trimmedPrefix ")) {
                return false
            }
            if (GemmaTextProcessor.startsWithStreamingRepairOrContinuation(trimmedLookahead)) {
                return false
            }
            return true
        }

        fun joinStreamingCleanedSegments(
            segments: List<String>,
            preset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH
        ): String {
            val nonBlank = segments.map { it.trim() }.filter { it.isNotEmpty() }
            if (nonBlank.isEmpty()) return ""
            if (nonBlank.size == 1) return nonBlank[0]

            val sb = StringBuilder(nonBlank.sumOf { it.length + 2 })
            sb.append(nonBlank[0])
            for (idx in 1 until nonBlank.size) {
                val prev = nonBlank[idx - 1]
                val next = nonBlank[idx]
                val prevLastLine = prev.substringAfterLast('\n')
                val nextFirstLine = next.substringBefore('\n')
                val bothListItems = LIST_LINE_REGEX.matches(prevLastLine) &&
                    LIST_LINE_REGEX.matches(nextFirstLine)
                val nextStartsList = LIST_LINE_REGEX.matches(nextFirstLine)
                val prevEndsSalutation = EMAIL_SALUTATION_LINE_REGEX.matches(prevLastLine.trim())
                val nextStartsSignoff = EMAIL_SIGNOFF_START_REGEX.containsMatchIn(next)
                val sep = when {
                    preset == GemmaPromptPreset.STRUCTURED_NOTES || bothListItems || nextStartsList -> "\n"
                    prevEndsSalutation || nextStartsSignoff -> "\n\n"
                    else -> " "
                }
                sb.append(sep).append(next)
            }
            return sb.toString()
        }
    }
}
