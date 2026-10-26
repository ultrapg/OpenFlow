package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.DictationChunkSettings
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

    /** Stops looking for new pauses. Must be called (and awaited) before `audio.stopRecording()`. */
    suspend fun stopPolling() {
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

        val orderedChunks = results.entries
            .sortedBy { it.key }
            .map { it.value }
            .filter { it.isNotBlank() }
        results.clear()

        val activeSettings = pipeline.currentSettings()
        val resolvedLang = language ?: activeSettings.preferredLanguage
        val stitched = stitchChunkTranscripts(
            chunks = orderedChunks,
            language = resolvedLang,
            customVocabulary = activeSettings.customVocabulary
        )
        return pipeline.postProcessFinalTranscript(
            text = stitched,
            languageHint = resolvedLang,
            onStageProgress = onStageProgress
        )
    }

    /** Abandons the session (screen turned off, recording failed, service destroyed). */
    fun cancel() {
        pollerJob?.cancel()
        pollerJob = null
        workerJob?.cancel()
        workerJob = null
        queue.close()
        results.clear()
        failed.clear()
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

    private companion object {
        const val TAG = "StreamingDictation"
        const val POLL_INTERVAL_MS = 150L
        const val MAX_IN_FLIGHT_LIVE_CHUNKS = 4
        const val MAX_FAILED_RETRY_SAMPLES = 45L * DictationChunkSettings.SAMPLE_RATE
    }
}
