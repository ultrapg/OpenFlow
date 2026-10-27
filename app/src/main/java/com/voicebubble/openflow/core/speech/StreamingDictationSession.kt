package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.core.audio.AudioRecordingController
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

/**
 * "Transcribe while still recording": watches the live microphone buffer for pauses and hands every finished
 * chunk to [SmartPipeline] immediately, so that after the user stops only the final tail is left to process.
 *
 * Lifecycle: [start] right after recording started -> [stopPolling] + `audio.stopRecording()` when the user
 * stops -> [finish] with the remaining tail (returns the full transcript), or [cancel] to abandon everything.
 *
 * Guarantees:
 * - Chunks are transcribed strictly in order, one at a time (single worker), so results are always joined in
 *   spoken order and the native models never run concurrently.
 * - Finished chunks are *removed* from the recording buffer ([AudioRecordingController.extractStreamingChunk]),
 *   so audio is never held twice; only the queue of not-yet-transcribed chunks adds transient memory.
 * - A chunk that fails to transcribe is kept and retried once after the tail, so a transient native error
 *   does not silently lose a section of the dictation.
 */
class StreamingDictationSession(
    private val scope: CoroutineScope,
    private val audio: AudioRecordingController,
    private val pipeline: SmartPipeline,
    private val config: DictationChunkSettings,
    private val onStageProgress: (String) -> Unit
) {
    private class PendingChunk(val index: Int, val samples: FloatArray)

    private val queue = Channel<PendingChunk>(Channel.UNLIMITED)
    private val results = ConcurrentHashMap<Int, String>()
    private val failed = ConcurrentHashMap<Int, FloatArray>()
    private val nextIndex = AtomicInteger(0)
    private val inFlightChunks = AtomicInteger(0)
    private val failedBufferedSamples = AtomicLong(0L)
    private val droppedFailedChunks = AtomicInteger(0)
    private val recordedSamples = AtomicLong(0L)
    private val liveChunkCount = AtomicInteger(0)
    private val stopRequested = AtomicBoolean(false)

    // Accessed strictly serially by workerJob while running, then by finish() after workerJob.join()
    private val preCleanedSegments = ArrayList<String>()
    private var nextUncleanedChunkIndex: Int = 0
    private var lastCleanedTail: String? = null

    @Volatile
    private var language: String? = null

    private var pollerJob: Job? = null
    private var workerJob: Job? = null

    /** Total number of speech samples handed to the pipeline so far (live chunks + tail). */
    val totalSamples: Long get() = recordedSamples.get()

    /** Number of chunks that were sent to the pipeline *while recording*. */
    val liveChunksSent: Int get() = liveChunkCount.get()

    /** Number of chunks that still failed after the retry in [finish]. */
    @Volatile
    var failedChunkCount: Int = 0
        private set

    fun start() {
        workerJob = scope.launch(Dispatchers.Default) {
            for (chunk in queue) {
                try {
                    val text = transcribeChunk(chunk, allowLanguageHint = true)
                    if (text == null) {
                        val canRetainForRetry = !pipeline.isMemoryCriticallyTight() &&
                            failedBufferedSamples.get() + chunk.samples.size <= MAX_FAILED_RETRY_SAMPLES
                        if (canRetainForRetry) {
                            failed[chunk.index] = chunk.samples
                            failedBufferedSamples.addAndGet(chunk.samples.size.toLong())
                        } else {
                            droppedFailedChunks.incrementAndGet()
                            DebugLogManager.logWarn(
                                TAG,
                                "Skipping retry audio retention for failed chunk #${chunk.index} to protect RAM headroom"
                            )
                        }
                    } else {
                        results[chunk.index] = text
                        tryOverlapCleanFinalizedPrefix(currentIndex = chunk.index, lookaheadText = text)
                    }
                } finally {
                    inFlightChunks.decrementAndGet()
                }
            }
        }
        pollerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // Backpressure: if the inference worker already has MAX_IN_FLIGHT_LIVE_CHUNKS queued or memory
                // is critically tight, keep new speech inside AudioRecordingController's single PCM buffer.
                if (inFlightChunks.get() >= MAX_IN_FLIGHT_LIVE_CHUNKS || pipeline.isMemoryCriticallyTight()) {
                    continue
                }
                val chunk = try {
                    audio.extractStreamingChunk(config)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    DebugLogManager.logWarn(TAG, "Live chunk extraction failed; will retry on next poll", t)
                    null
                }
                if (chunk != null) {
                    liveChunkCount.incrementAndGet()
                    DebugLogManager.logInfo(
                        TAG,
                        "Live chunk #${nextIndex.get()} cut after pause (${chunk.size * 1000L / DictationChunkSettings.SAMPLE_RATE} ms) " +
                            "-> sent to pipeline while recording"
                    )
                    enqueue(chunk)
                }
            }
        }
    }

    /**
     * Pillar 3: When `Voz` finishes chunk [currentIndex] and the audio queue is momentarily empty while the
     * user speaks the next chunk, cleans finalized earlier chunks `[nextUncleanedChunkIndex .. currentIndex - 1]`
     * in the background once [lookaheadText] confirms their right boundary is closed and backtrack-free.
     */
    private suspend fun tryOverlapCleanFinalizedPrefix(currentIndex: Int, lookaheadText: String) {
        if (stopRequested.get()) return
        if (currentIndex <= nextUncleanedChunkIndex) return
        // Only run background LLM overlap when no waiting audio chunks are backed up behind this one
        if (inFlightChunks.get() > 1) return
        if (failed.isNotEmpty() || droppedFailedChunks.get() > 0) return
        if (!pipeline.canOverlapStreamingLlmCleanup()) return

        val lookaheadTrimmed = lookaheadText.trim()
        if (lookaheadTrimmed.isEmpty()) return

        val prefixRaw = ArrayList<String>(currentIndex - nextUncleanedChunkIndex)
        for (idx in nextUncleanedChunkIndex until currentIndex) {
            val part = results[idx] ?: return
            if (part.isNotBlank()) {
                prefixRaw.add(part)
            }
        }
        if (prefixRaw.isEmpty()) {
            nextUncleanedChunkIndex = currentIndex
            return
        }

        val activeSettings = pipeline.currentSettings()
        val resolvedLang = language ?: activeSettings.preferredLanguage
        val prefixStitched = stitchChunkTranscripts(
            chunks = prefixRaw,
            language = resolvedLang,
            customVocabulary = activeSettings.customVocabulary
        )
        val combinedStitched = stitchChunkTranscripts(
            chunks = prefixRaw + lookaheadTrimmed,
            language = resolvedLang,
            customVocabulary = activeSettings.customVocabulary
        )
        if (!canCommitStreamingPrefixForOverlap(prefixStitched, lookaheadTrimmed, combinedStitched)) {
            return
        }

        val cleanedPrefix = try {
            pipeline.postProcessStreamingSegment(
                text = prefixStitched,
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

        for (idx in nextUncleanedChunkIndex until currentIndex) {
            results.remove(idx)
        }
        nextUncleanedChunkIndex = currentIndex
        if (cleanedPrefix.isNotBlank()) {
            preCleanedSegments.add(cleanedPrefix)
            lastCleanedTail = GemmaTextProcessor.extractContinuityTail(cleanedPrefix)
        }
        DebugLogManager.logInfo(
            TAG,
            "Live LLM overlap cleaned chunks [..${currentIndex - 1}] (${prefixStitched.length} -> ${cleanedPrefix.length} chars) while user continued speaking"
        )
    }

    /** Stops looking for new pauses. Must be called (and awaited) before `audio.stopRecording()`. */
    suspend fun stopPolling() {
        stopRequested.set(true)
        pollerJob?.cancelAndJoin()
        pollerJob = null
    }

    /**
     * Sends the final [tail] (audio recorded after the last live cut), waits for the ordered worker to drain
     * the queue, retries failed chunks once and returns the seamlessly stitched transcript.
     */
    suspend fun finish(tail: FloatArray): String {
        stopPolling()
        if (tail.isNotEmpty()) {
            val shouldEnqueueTail = liveChunkCount.get() == 0 ||
                hasAnyTailSpeech(tail, config.silenceThreshold)
            if (shouldEnqueueTail) {
                enqueue(tail)
            } else {
                DebugLogManager.logInfo(
                    TAG,
                    "Skipping post-pause silent/tap tail (${tail.size * 1000L / DictationChunkSettings.SAMPLE_RATE} ms) after ${liveChunkCount.get()} live chunk(s)"
                )
            }
        }
        queue.close()
        workerJob?.join()

        var stillFailed = droppedFailedChunks.get()
        for (index in failed.keys.sorted()) {
            // Detach audio buffer immediately so GC can reclaim it right after retry
            val samples = failed.remove(index) ?: continue
            failedBufferedSamples.addAndGet(-samples.size.toLong())
            val text = transcribeChunk(PendingChunk(index, samples), allowLanguageHint = false)
            if (text != null) {
                results[index] = text
            } else {
                stillFailed++
            }
        }
        failed.clear()
        failedChunkCount = stillFailed

        val orderedRemainingChunks = results.entries
            .filter { it.key >= nextUncleanedChunkIndex }
            .sortedBy { it.key }
            .map { it.value }
            .filter { it.isNotBlank() }
        results.clear()

        val activeSettings = pipeline.currentSettings()
        val resolvedLang = language ?: activeSettings.preferredLanguage

        if (preCleanedSegments.isEmpty()) {
            val stitched = stitchChunkTranscripts(
                chunks = orderedRemainingChunks,
                language = resolvedLang,
                customVocabulary = activeSettings.customVocabulary
            )
            return pipeline.postProcessFinalTranscript(
                text = stitched,
                languageHint = resolvedLang,
                onStageProgress = onStageProgress
            )
        }

        val tailStitched = stitchChunkTranscripts(
            chunks = orderedRemainingChunks,
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
        return joined
    }

    /** Abandons the session (screen turned off, recording failed, service destroyed). */
    fun cancel() {
        stopRequested.set(true)
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

    private fun enqueue(samples: FloatArray) {
        recordedSamples.addAndGet(samples.size.toLong())
        inFlightChunks.incrementAndGet()
        if (queue.trySend(PendingChunk(nextIndex.getAndIncrement(), samples)).isFailure) {
            inFlightChunks.decrementAndGet()
        }
    }

    /** Returns the chunk transcript, or `null` if the pipeline threw (caller keeps the audio for a retry). */
    private suspend fun transcribeChunk(chunk: PendingChunk, allowLanguageHint: Boolean): String? {
        return try {
            pipeline.transcribe(
                samples = chunk.samples,
                onStageProgress = onStageProgress,
                languageHint = if (config.reuseLanguage && allowLanguageHint) language else null,
                onLanguageResolved = { detected ->
                    if (config.reuseLanguage && language == null) language = detected
                },
                applyPostProcessing = false
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
        private const val POLL_INTERVAL_MS = 150L
        private const val MAX_IN_FLIGHT_LIVE_CHUNKS = 4
        private const val MAX_FAILED_RETRY_SAMPLES = 45L * DictationChunkSettings.SAMPLE_RATE
        private val LIST_LINE_REGEX = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+\\S.*$")

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
                val sep = when {
                    preset == GemmaPromptPreset.STRUCTURED_NOTES || bothListItems -> "\n"
                    prev.contains('\n') || next.contains('\n') -> "\n\n"
                    else -> " "
                }
                sb.append(sep).append(next)
            }
            return sb.toString()
        }
    }
}
