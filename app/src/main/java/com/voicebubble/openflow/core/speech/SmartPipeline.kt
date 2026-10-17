/*
 * Copyright 2026 OpenFlow Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.voicebubble.openflow.core.speech

import ai.desertant.clear.ChannelMode
import ai.desertant.clear.Clear
import ai.desertant.ear.Ear
import ai.desertant.uhm.FillerSpan
import ai.desertant.uhm.FrameClassification
import ai.desertant.uhm.Uhm
import ai.desertant.uhm.mergeFillerFrames
import ai.desertant.voz.Transcription
import ai.desertant.voz.Voz
import ai.desertant.voz.Word
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Orchestrates the 4-stage Desert Ant Labs on-device audio AI pipeline:
 * 1. `Clear` (`clear-studio` profile) — Speech enhancement & noise suppression
 * 2. `Ear` || `Uhm` — Parallel language identification & 20 ms acoustic filler frame detection
 * 3. `Voz` — Multilingual speech-to-text with word-level timestamps (80 ms resolution)
 * 4. Temporal Filler Subtraction & Text Normalization
 *
 * Concurrency & Memory Guarantees:
 * - All lifecycle and inference mutations (`initialize`, `transcribe`, `reloadSettings`,
 *   `evictOptionalModels`, `shutdown`) are serialized by a single coroutine [Mutex].
 * - `settings` is held as a mutable `@Volatile` property updated immediately inside `reloadSettings`.
 * - Zero unsafe non-null assertions (`!!`) on nullable model references.
 * - Keeps `Voz` warm while active and evicts auxiliary models (`Clear`, `Ear`, `Uhm`) after
 *   [IDLE_TIMEOUT_MS] (180 seconds) of inactivity or on `TRIM_MEMORY_RUNNING_LOW`.
 */
class SmartPipeline(
    context: Context,
    initialSettings: ModelSettings = ModelSettings.load(context)
) {

    companion object {
        private const val TAG = "SmartPipeline"
        const val SAMPLE_RATE_HZ: Double = 16_000.0
        const val IDLE_TIMEOUT_MS: Long = 180_000L
        const val FILLER_MERGE_MAX_GAP_MS: Long = 40L
        const val FILLER_BOUNDARY_TOLERANCE_MS: Long = 80L
        const val FILLER_OVERLAP_THRESHOLD: Double = 0.50

        @Volatile
        private var sharedInstance: SmartPipeline? = null

        fun getInstance(context: Context): SmartPipeline {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: SmartPipeline(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val pipelineMutex = Mutex()
    private val idleScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var idleEvictionJob: Job? = null

    @Volatile
    var settings: ModelSettings = initialSettings
        private set

    private var voz: Voz? = null
    private var ear: Ear? = null
    private var clear: Clear? = null
    private var uhm: Uhm? = null

    @Volatile
    private var isTranscribing: Boolean = false

    /**
     * Initializes `Voz` and any currently enabled & downloaded auxiliary models inside [pipelineMutex].
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        pipelineMutex.withLock {
            val currentSettings = settings
            val vozLoaded = ensureVozLocked()
            ensureAuxiliaryModelsLocked(currentSettings)
            resetIdleEvictionTimerLocked()
            vozLoaded
        }
    }

    /**
     * Updates [settings] immediately and synchronizes loaded auxiliary models under [pipelineMutex].
     */
    suspend fun reloadSettings(newSettings: ModelSettings) = withContext(Dispatchers.IO) {
        settings = newSettings
        pipelineMutex.withLock {
            val snapshot = settings
            if (!snapshot.clearEnabled) {
                closeClearLocked()
            }
            if (!snapshot.earEnabled) {
                closeEarLocked()
            }
            if (!snapshot.uhmEnabled) {
                closeUhmLocked()
            }
            ensureVozLocked()
            ensureAuxiliaryModelsLocked(snapshot)
            resetIdleEvictionTimerLocked()
        }
    }

    /**
     * Executes the 4-stage Desert Ant audio pipeline on 16 kHz mono PCM [samples] in-memory.
     */
    suspend fun transcribe(
        samples: FloatArray,
        onStageProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""

        pipelineMutex.withLock {
            idleEvictionJob?.cancel()
            idleEvictionJob = null
            isTranscribing = true
            try {
                val activeSettings = settings

                // Ensure models are warm and ready (handles lazy reload after 180s idle eviction)
                if (!ensureVozLocked()) {
                    throw IllegalStateException("Voz speech recognition model is not downloaded.")
                }
                ensureAuxiliaryModelsLocked(activeSettings)

                // =========================================================================
                // Stage 1: Audio Enhancement (`Clear` with `clear-studio` profile)
                // =========================================================================
                val enhancedSamples = runClearStageLocked(samples, activeSettings, onStageProgress)

                // =========================================================================
                // Stage 2 & Stage 3: Parallel `Ear` || `Uhm`, with `Voz` starting
                // immediately after `Ear` finishes (without waiting for `Uhm`).
                // =========================================================================
                coroutineScope {
                    val uhmInstance = if (activeSettings.uhmEnabled) uhm else null
                    val uhmDeferred = async(Dispatchers.Default) {
                        if (uhmInstance != null && safeIsUhmDownloaded(uhmInstance)) {
                            try {
                                uhmInstance.detect(enhancedSamples, SAMPLE_RATE_HZ)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Uhm filler inference failed; bypassing filler removal: ${t.message}")
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    }

                    // Run `Ear` language identification (or fall back immediately if disabled/unavailable)
                    val resolvedLanguage = resolveLanguageLocked(enhancedSamples, activeSettings, onStageProgress)

                    // Start `Voz` immediately once `Ear` resolves the language, while `uhmDeferred` runs concurrently
                    onStageProgress?.invoke("Voz ($resolvedLanguage)")
                    val transcription = runVozWithRetryLocked(
                        samples = enhancedSamples,
                        language = resolvedLanguage,
                        customVocabulary = activeSettings.customVocabulary
                    )

                    // =========================================================================
                    // Stage 4: Temporal Filler Subtraction & Text Normalization
                    // =========================================================================
                    val uhmFrames = uhmDeferred.await()
                    val finalTranscript = reconcileTranscript(
                        transcription = transcription,
                        uhmFrames = uhmFrames,
                        uhmEnabled = activeSettings.uhmEnabled,
                        customVocabulary = activeSettings.customVocabulary
                    )

                    finalTranscript
                }
            } finally {
                isTranscribing = false
                resetIdleEvictionTimerLocked()
            }
        }
    }

    /**
     * Evicts optional models (`Clear`, `Ear`, `Uhm`) from native RAM while keeping `Voz` resident.
     * Safe to invoke from the 180-second inactivity timer or `ComponentCallbacks2.onTrimMemory`.
     */
    suspend fun evictOptionalModels() = withContext(Dispatchers.IO) {
        pipelineMutex.withLock {
            if (!isTranscribing) {
                Log.i(TAG, "Evicting optional models (Clear, Ear, Uhm) to free native RAM; keeping Voz resident.")
                closeClearLocked()
                closeEarLocked()
                closeUhmLocked()
            }
        }
    }

    /**
     * Handles Android OS memory pressure callbacks (`TRIM_MEMORY_RUNNING_LOW` or higher).
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !isTranscribing) {
            idleScope.launch {
                evictOptionalModels()
            }
        }
    }

    /**
     * Releases all native LiteRT sessions (`Clear`, `Ear`, `Uhm`, and `Voz`).
     */
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        pipelineMutex.withLock {
            idleEvictionJob?.cancel()
            idleEvictionJob = null
            closeClearLocked()
            closeEarLocked()
            closeUhmLocked()
            closeVozLocked()
        }
    }

    // =========================================================================
    // Internal Stage Helpers (Must be called inside `pipelineMutex.withLock`)
    // =========================================================================

    private suspend fun runClearStageLocked(
        rawSamples: FloatArray,
        activeSettings: ModelSettings,
        onStageProgress: ((String) -> Unit)?
    ): FloatArray {
        if (!activeSettings.clearEnabled) return rawSamples
        val clearInstance = clear ?: return rawSamples
        if (!safeIsClearDownloaded(clearInstance)) {
            Log.w(TAG, "Clear is enabled in settings, but clear-studio model files are not downloaded; bypassing Clear.")
            return rawSamples
        }

        return try {
            onStageProgress?.invoke("Clear")
            val options = ai.desertant.clear.Options(
                strength = 1.0,
                sampleRate = SAMPLE_RATE_HZ,
                channelMode = ChannelMode.MONO
            )
            val result = clearInstance.enhance(
                samples = rawSamples,
                sampleRate = SAMPLE_RATE_HZ,
                options = options
            )
            val enhanced = result.samples
            if (enhanced.isNotEmpty()) enhanced else rawSamples
        } catch (t: Throwable) {
            Log.w(TAG, "Clear enhancement failed, falling back to raw PCM audio: ${t.message}")
            rawSamples
        }
    }

    private suspend fun resolveLanguageLocked(
        samples: FloatArray,
        activeSettings: ModelSettings,
        onStageProgress: ((String) -> Unit)?
    ): String {
        val fallbackLanguage = if (activeSettings.preferredLanguage in Voz.supportedLanguages) {
            activeSettings.preferredLanguage
        } else {
            ModelSettings.defaultPreferredLanguage()
        }

        if (!activeSettings.earEnabled) {
            return fallbackLanguage
        }

        val earInstance = ear ?: return fallbackLanguage
        if (!safeIsEarDownloaded(earInstance)) {
            Log.w(TAG, "Ear is enabled in settings, but model files are not downloaded; falling back to $fallbackLanguage.")
            return fallbackLanguage
        }

        return try {
            onStageProgress?.invoke("Ear")
            val detection = earInstance.identify(
                samples = samples,
                sampleRate = SAMPLE_RATE_HZ,
                options = ai.desertant.ear.Options(windows = 3)
            )
            val detectedLang = detection.language?.lowercase(Locale.ROOT)
            if (detection.isReliable && detectedLang != null && detectedLang in Voz.supportedLanguages) {
                detectedLang
            } else {
                fallbackLanguage
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Ear language detection failed, falling back to $fallbackLanguage: ${t.message}")
            fallbackLanguage
        }
    }

    private suspend fun runVozWithRetryLocked(
        samples: FloatArray,
        language: String,
        customVocabulary: List<String>
    ): Transcription {
        val firstVoz = voz ?: run {
            ensureVozLocked()
            voz
        } ?: throw IllegalStateException("Voz speech engine could not be initialized.")

        return try {
            firstVoz.transcribeWithContext(
                samples = samples,
                sampleRate = SAMPLE_RATE_HZ,
                language = language,
                customVocabulary = customVocabulary
            )
        } catch (firstError: Throwable) {
            Log.w(TAG, "Voz inference failed on first attempt (${firstError.message}); re-initializing and retrying once.")
            closeVozLocked()
            if (!ensureVozLocked()) {
                throw IllegalStateException("Transcription failed after re-initialization: ${firstError.message}", firstError)
            }
            val retryVoz = voz ?: throw IllegalStateException("Voz unavailable on retry.", firstError)
            retryVoz.transcribeWithContext(
                samples = samples,
                sampleRate = SAMPLE_RATE_HZ,
                language = language,
                customVocabulary = customVocabulary
            )
        }
    }

    private fun ensureVozLocked(): Boolean {
        val existing = voz
        if (existing != null) {
            if (safeIsVozDownloaded(existing)) return true
            closeVozLocked()
        }
        return try {
            val candidate = Voz(appContext)
            if (candidate.isDownloaded()) {
                voz = candidate
                true
            } else {
                candidate.close()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Voz: ${t.message}")
            false
        }
    }

    private fun ensureAuxiliaryModelsLocked(activeSettings: ModelSettings) {
        if (activeSettings.clearEnabled && clear == null) {
            try {
                val candidate = Clear(appContext)
                if (candidate.isDownloaded()) {
                    clear = candidate
                } else {
                    candidate.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not initialize Clear: ${t.message}")
            }
        }
        if (activeSettings.earEnabled && ear == null) {
            try {
                val candidate = Ear(appContext)
                if (candidate.isDownloaded()) {
                    ear = candidate
                } else {
                    candidate.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not initialize Ear: ${t.message}")
            }
        }
        if (activeSettings.uhmEnabled && uhm == null) {
            try {
                val candidate = Uhm(appContext)
                if (candidate.isDownloaded()) {
                    uhm = candidate
                } else {
                    candidate.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not initialize Uhm: ${t.message}")
            }
        }
    }

    private fun resetIdleEvictionTimerLocked() {
        idleEvictionJob?.cancel()
        idleEvictionJob = idleScope.launch {
            delay(IDLE_TIMEOUT_MS)
            evictOptionalModels()
        }
    }

    private fun closeVozLocked() {
        val instance = voz
        voz = null
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeEarLocked() {
        val instance = ear
        ear = null
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeClearLocked() {
        val instance = clear
        clear = null
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeUhmLocked() {
        val instance = uhm
        uhm = null
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeIsVozDownloaded(instance: Voz): Boolean = try {
        instance.isDownloaded()
    } catch (_: Throwable) {
        false
    }

    private fun safeIsEarDownloaded(instance: Ear): Boolean = try {
        instance.isDownloaded()
    } catch (_: Throwable) {
        false
    }

    private fun safeIsClearDownloaded(instance: Clear): Boolean = try {
        instance.isDownloaded()
    } catch (_: Throwable) {
        false
    }

    private fun safeIsUhmDownloaded(instance: Uhm): Boolean = try {
        instance.isDownloaded()
    } catch (_: Throwable) {
        false
    }
}

/**
 * Extension on [Voz] that passes [samples] and [sampleRate] to the native LiteRT transducer
 * and applies language-aware custom vocabulary biasing when [customVocabulary] is non-empty.
 */
suspend fun Voz.transcribeWithContext(
    samples: FloatArray,
    sampleRate: Double = ai.desertant.voz.SAMPLE_RATE,
    language: String,
    customVocabulary: List<String> = emptyList()
): Transcription {
    val rawTranscription = transcribe(samples = samples, sampleRate = sampleRate)
    if (customVocabulary.isEmpty() || rawTranscription.words.isEmpty()) {
        return rawTranscription
    }
    val biasedWords = rawTranscription.words.map { word ->
        word.copy(text = biasWordWithCustomVocabulary(word.text, customVocabulary))
    }
    val biasedText = applyCustomVocabulary(rawTranscription.text, customVocabulary)
    return rawTranscription.copy(
        text = biasedText,
        words = biasedWords
    )
}

/**
 * Stage 4: Reconciles `Voz` word-level timestamps with `Uhm` 20 ms filler frames using the
 * deterministic overlap formula from Specification Section 4.2.
 */
fun reconcileTranscript(
    transcription: Transcription,
    uhmFrames: List<FrameClassification>,
    uhmEnabled: Boolean,
    customVocabulary: List<String> = emptyList()
): String {
    val words = transcription.words
    if (words.isEmpty()) {
        return normalizeTranscriptText(
            rawText = applyCustomVocabulary(transcription.text, customVocabulary),
            capitalizeLeadingWord = false
        )
    }

    if (!uhmEnabled || uhmFrames.isEmpty()) {
        val rawJoined = if (transcription.text.isNotBlank()) {
            transcription.text
        } else {
            words.joinToString(" ") { it.text }
        }
        return normalizeTranscriptText(
            rawText = applyCustomVocabulary(rawJoined, customVocabulary),
            capitalizeLeadingWord = false
        )
    }

    // 1. Merge contiguous/near-contiguous non-zero filler frames (classes 1..5) separated by <= 40 ms
    val fillerSpans = mergeFillerFrames(
        frames = uhmFrames,
        maxGapMs = SmartPipeline.FILLER_MERGE_MAX_GAP_MS
    )

    if (fillerSpans.isEmpty()) {
        val rawJoined = if (transcription.text.isNotBlank()) {
            transcription.text
        } else {
            words.joinToString(" ") { it.text }
        }
        return normalizeTranscriptText(
            rawText = applyCustomVocabulary(rawJoined, customVocabulary),
            capitalizeLeadingWord = false
        )
    }

    // 2. Filter word tokens using >= 50% overlap ratio with +/- 80 ms boundary tolerance
    val reconciliation = filterWordsByFillerOverlap(
        words = words,
        fillerSpans = fillerSpans,
        boundaryToleranceMs = SmartPipeline.FILLER_BOUNDARY_TOLERANCE_MS,
        overlapThreshold = SmartPipeline.FILLER_OVERLAP_THRESHOLD
    )

    if (reconciliation.keptWords.isEmpty()) {
        return ""
    }

    val joined = reconciliation.keptWords.joinToString(" ") { it.text }
    val normalized = normalizeTranscriptText(
        rawText = joined,
        capitalizeLeadingWord = reconciliation.removedLeadingCapitalizedWord
    )
    return applyCustomVocabulary(normalized, customVocabulary)
}

data class WordReconciliationResult(
    val keptWords: List<Word>,
    val removedWords: List<Word>,
    val removedLeadingCapitalizedWord: Boolean
)

/**
 * Pure implementation of the Specification Section 4.2 overlap formula:
 *
 * - $D_{\text{word}} = \max(1, E_{\text{word}} - S_{\text{word}})$
 * - $D_{\text{overlap}} = \max(0, \min(E_{\text{word}}, E_{\text{filler}} + \tau) - \max(S_{\text{word}}, S_{\text{filler}} - \tau))$
 * - $\text{OverlapRatio} = D_{\text{overlap}} / D_{\text{word}} \ge 0.50$
 */
fun filterWordsByFillerOverlap(
    words: List<Word>,
    fillerSpans: List<FillerSpan>,
    boundaryToleranceMs: Long = SmartPipeline.FILLER_BOUNDARY_TOLERANCE_MS,
    overlapThreshold: Double = SmartPipeline.FILLER_OVERLAP_THRESHOLD
): WordReconciliationResult {
    if (words.isEmpty() || fillerSpans.isEmpty()) {
        return WordReconciliationResult(
            keptWords = words,
            removedWords = emptyList(),
            removedLeadingCapitalizedWord = false
        )
    }

    val kept = ArrayList<Word>(words.size)
    val removed = ArrayList<Word>()
    var removedLeadingCapitalized = false

    for ((index, word) in words.withIndex()) {
        val sWordMs = (word.start * 1000.0).roundToLong()
        val eWordMs = max(sWordMs + 1L, (word.end * 1000.0).roundToLong())
        val dWordMs = max(1L, eWordMs - sWordMs)

        var isFillerWord = false
        for (span in fillerSpans) {
            val expandedFillerStart = span.startMs - boundaryToleranceMs
            val expandedFillerEnd = span.endMs + boundaryToleranceMs
            val overlapMs = max(0L, min(eWordMs, expandedFillerEnd) - max(sWordMs, expandedFillerStart))
            val overlapRatio = overlapMs.toDouble() / dWordMs.toDouble()
            if (overlapRatio >= overlapThreshold) {
                isFillerWord = true
                break
            }
        }

        if (isFillerWord) {
            removed.add(word)
            if (kept.isEmpty() && index == 0) {
                val firstLetter = word.text.firstOrNull { it.isLetter() }
                if (firstLetter != null && firstLetter.isUpperCase()) {
                    removedLeadingCapitalized = true
                }
            }
        } else {
            kept.add(word)
        }
    }

    return WordReconciliationResult(
        keptWords = kept,
        removedWords = removed,
        removedLeadingCapitalizedWord = removedLeadingCapitalized
    )
}

/**
 * Cleans up orphaned punctuation, duplicate spaces, and re-capitalizes the new leading word
 * when a sentence-initial filler word was removed.
 */
fun normalizeTranscriptText(
    rawText: String,
    capitalizeLeadingWord: Boolean
): String {
    if (rawText.isBlank()) return ""

    var cleaned = rawText
        .trim()
        // Strip leading orphaned punctuation left behind after a leading filler removal (e.g. ", ich...")
        .replace(Regex("^[.,;:\\-–—]+\\s*"), "")
        // Collapse spaces before punctuation
        .replace(Regex("\\s+([.,!?:;%€])"), "$1")
        // Collapse duplicate punctuation (e.g. ", ," -> ",")
        .replace(Regex("([,;:])\\s*[,;:]+"), "$1")
        // Collapse multiple spaces
        .replace(Regex("\\s+"), " ")
        .trim()

    if (cleaned.isEmpty()) return ""

    if (capitalizeLeadingWord) {
        val firstLetterIdx = cleaned.indexOfFirst { it.isLetter() }
        if (firstLetterIdx >= 0 && cleaned[firstLetterIdx].isLowerCase()) {
            val ch = cleaned[firstLetterIdx].titlecase(Locale.getDefault())
            cleaned = cleaned.substring(0, firstLetterIdx) + ch + cleaned.substring(firstLetterIdx + 1)
        }
    }

    return cleaned
}

/**
 * Applies user-configured custom vocabulary terms (canonical casing and close phonetic/edit-distance
 * token biasing) to the transcript text.
 */
fun applyCustomVocabulary(text: String, customVocabulary: List<String>): String {
    if (text.isBlank() || customVocabulary.isEmpty()) return text
    var updated = text
    for (vocabTerm in customVocabulary) {
        val trimmed = vocabTerm.trim()
        if (trimmed.isEmpty()) continue
        // Replace case-insensitive exact matches with the user's canonical casing
        val escaped = Regex.escape(trimmed)
        updated = updated.replace(Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE), trimmed)
    }
    return updated
}

private fun biasWordWithCustomVocabulary(wordText: String, customVocabulary: List<String>): String {
    if (wordText.isBlank() || customVocabulary.isEmpty()) return wordText
    val leadingPunct = wordText.takeWhile { !it.isLetterOrDigit() }
    val trailingPunct = wordText.takeLastWhile { !it.isLetterOrDigit() }
    val core = wordText.substring(leadingPunct.length, wordText.length - trailingPunct.length)
    if (core.isEmpty()) return wordText

    for (term in customVocabulary) {
        val cleanTerm = term.trim()
        if (cleanTerm.isEmpty() || cleanTerm.contains(' ')) continue
        if (core.equals(cleanTerm, ignoreCase = true)) {
            return leadingPunct + cleanTerm + trailingPunct
        }
        // Single-character edit distance biasing for words >= 5 characters
        if (core.length >= 5 && cleanTerm.length >= 5 && levenshteinDistance(core.lowercase(Locale.ROOT), cleanTerm.lowercase(Locale.ROOT)) == 1) {
            return leadingPunct + cleanTerm + trailingPunct
        }
    }
    return wordText
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in a.indices) {
        curr[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            curr[j + 1] = min(min(curr[j] + 1, prev[j + 1] + 1), prev[j] + cost)
        }
        System.arraycopy(curr, 0, prev, 0, prev.size)
    }
    return prev[b.length]
}
