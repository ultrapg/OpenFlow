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

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.GeminiNanoVariant
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.LlmPromptTier
import com.voicebubble.openflow.settings.LlmProviderMode
import com.voicebubble.openflow.settings.LlmThinkingEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Availability state reported by Android AICore for a specific [GeminiNanoVariant].
 */
enum class GeminiNanoAvailability(val label: String) {
    UNKNOWN("Not Checked"),
    CHECKING("Checking..."),
    AVAILABLE("Available"),
    DOWNLOADABLE("Downloadable"),
    DOWNLOADING("Downloading..."),
    UNAVAILABLE("Unavailable")
}

/**
 * Live status & capability snapshot for a single [GeminiNanoVariant] (`FULL` or `FAST`).
 */
data class GeminiNanoVariantStatus(
    val variant: GeminiNanoVariant,
    val availability: GeminiNanoAvailability = GeminiNanoAvailability.UNKNOWN,
    val resolvedReleaseStage: Int? = null,
    val baseModelName: String? = null,
    val tokenLimit: Int? = null,
    val supportsSystemPrompt: Boolean = false,
    val supportsThinkingMode: Boolean = false,
    val supportsPrefixCaching: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val bytesToDownload: Long = 0L,
    val errorMessage: String? = null,
    val lastCheckedAtMs: Long = 0L
) {
    val isAvailable: Boolean
        get() = availability == GeminiNanoAvailability.AVAILABLE

    val isDownloadable: Boolean
        get() = availability == GeminiNanoAvailability.DOWNLOADABLE

    val isDownloading: Boolean
        get() = availability == GeminiNanoAvailability.DOWNLOADING

    val stageLabel: String?
        get() = when (resolvedReleaseStage) {
            ModelReleaseStage.STABLE -> "Stable"
            ModelReleaseStage.PREVIEW -> "Preview"
            else -> null
        }

    val progressFraction: Float
        get() = if (bytesToDownload > 0L) {
            (bytesDownloaded.toDouble() / bytesToDownload.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Aggregate state across both Gemini Nano variants (`FULL` and `FAST`) plus recent AICore runtime notes.
 */
data class GeminiNanoCatalogState(
    val statuses: Map<GeminiNanoVariant, GeminiNanoVariantStatus> =
        GeminiNanoVariant.entries.associateWith { GeminiNanoVariantStatus(variant = it) },
    val isRefreshing: Boolean = false,
    val lastRuntimeWarning: String? = null,
    val lastRuntimeSuccessNote: String? = null
) {
    fun statusOf(variant: GeminiNanoVariant): GeminiNanoVariantStatus =
        statuses[variant] ?: GeminiNanoVariantStatus(variant = variant)

    val anyAvailable: Boolean
        get() = statuses.values.any { it.isAvailable }

    val anyDownloadable: Boolean
        get() = statuses.values.any { it.isDownloadable }

    val anyDownloading: Boolean
        get() = statuses.values.any { it.isDownloading }
}

data class GeminiNanoProcessResult(
    val text: String,
    val succeeded: Boolean,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val baseModelName: String? = null,
    val latencyMs: Long = 0L
)

data class GeminiNanoBenchmarkResult(
    val variant: GeminiNanoVariant,
    val stageLabel: String,
    val baseModelName: String?,
    val tokenLimit: Int?,
    val supportsSystemPrompt: Boolean,
    val supportsThinkingMode: Boolean,
    val inputText: String,
    val cleanedOutput: String,
    val latencyMs: Long
)

/**
 * Process-wide coordinator for Android AICore Gemini Nano (`Full` & `Fast` via ML Kit GenAI Prompt API):
 * - Checks availability across `ModelReleaseStage.STABLE` and `ModelReleaseStage.PREVIEW`
 * - Triggers & tracks on-device AICore downloads when a variant returns `FeatureStatus.DOWNLOADABLE`
 * - Exposes base model name, token limit, and capability flags (`SYSTEM_PROMPT`, `THINKING_MODE`, `PREFIX_CACHING`)
 */
object GeminiNanoManager {
    private const val TAG = "GeminiNanoManager"
    private const val REFRESH_COOLDOWN_MS = 12_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshMutex = Mutex()
    private val downloadJobs = ConcurrentHashMap<GeminiNanoVariant, Job>()

    private val _catalogState = MutableStateFlow(GeminiNanoCatalogState())
    val catalogState: StateFlow<GeminiNanoCatalogState> = _catalogState.asStateFlow()

    internal fun toModelPreference(variant: GeminiNanoVariant): Int = when (variant) {
        GeminiNanoVariant.FULL -> ModelPreference.FULL
        GeminiNanoVariant.FAST -> ModelPreference.FAST
    }

    internal fun mapFeatureStatus(status: Int): GeminiNanoAvailability = when (status) {
        FeatureStatus.AVAILABLE -> GeminiNanoAvailability.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> GeminiNanoAvailability.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> GeminiNanoAvailability.DOWNLOADING
        else -> GeminiNanoAvailability.UNAVAILABLE
    }

    /**
     * Resolves the highest-readiness `(releaseStage, FeatureStatus)` pair across `STABLE` and
     * `PREVIEW`, preferring an already-available model (`STABLE` first, then `PREVIEW`), and
     * otherwise choosing whichever track can be downloaded right now.
     */
    internal fun chooseBestStageStatus(stableStatus: Int, previewStatus: Int): Pair<Int, Int> = when {
        stableStatus == FeatureStatus.AVAILABLE -> ModelReleaseStage.STABLE to FeatureStatus.AVAILABLE
        previewStatus == FeatureStatus.AVAILABLE -> ModelReleaseStage.PREVIEW to FeatureStatus.AVAILABLE
        stableStatus == FeatureStatus.DOWNLOADING -> ModelReleaseStage.STABLE to FeatureStatus.DOWNLOADING
        stableStatus == FeatureStatus.DOWNLOADABLE -> ModelReleaseStage.STABLE to FeatureStatus.DOWNLOADABLE
        previewStatus == FeatureStatus.DOWNLOADING -> ModelReleaseStage.PREVIEW to FeatureStatus.DOWNLOADING
        previewStatus == FeatureStatus.DOWNLOADABLE -> ModelReleaseStage.PREVIEW to FeatureStatus.DOWNLOADABLE
        else -> ModelReleaseStage.STABLE to FeatureStatus.UNAVAILABLE
    }

    internal fun shouldTriggerAutoFallback(t: Throwable): Boolean {
        if (t !is GenAiException) return true
        return when (t.errorCode) {
            GenAiException.ErrorCode.CANCELLED -> false
            else -> true
        }
    }

    internal fun describeGenAiException(t: Throwable): String {
        if (t !is GenAiException) {
            return t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        }
        return when (t.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                "AICore blocked background/overlay inference (BACKGROUND_USE_BLOCKED)."
            GenAiException.ErrorCode.NOT_AVAILABLE ->
                "Gemini Nano is not available on this device/track (NOT_AVAILABLE)."
            GenAiException.ErrorCode.NOT_SUPPORTED ->
                "Requested Gemini Nano feature or configuration is unsupported on this device."
            GenAiException.ErrorCode.BUSY ->
                "AICore is currently busy with another request (BUSY)."
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                "Daily per-app AICore battery quota exceeded."
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                "Not enough free device storage to download or run Gemini Nano."
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
                "Android system or AICore service update required."
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE ->
                "Installed Android AICore version is incompatible."
            GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                "Prompt exceeded Gemini Nano's on-device token limit."
            GenAiException.ErrorCode.REQUEST_TOO_SMALL ->
                "Input was too small for Gemini Nano."
            GenAiException.ErrorCode.CANCELLED ->
                "Gemini Nano request or download was cancelled."
            else -> t.message?.takeIf { it.isNotBlank() }
                ?: "Gemini Nano error code ${t.errorCode}"
        }
    }

    fun createClient(variant: GeminiNanoVariant, releaseStage: Int): GenerativeModel {
        val config = generationConfig {
            modelConfig = modelConfig {
                this.releaseStage = releaseStage
                this.preference = toModelPreference(variant)
            }
        }
        return Generation.getClient(config)
    }

    fun refreshStatusesAsync(force: Boolean = false) {
        scope.launch {
            refreshStatuses(force = force)
        }
    }

    suspend fun refreshStatuses(force: Boolean = false) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val nowMs = SystemClock.elapsedRealtime()
            val snapshot = _catalogState.value
            val allFresh = GeminiNanoVariant.entries.all { v ->
                val st = snapshot.statusOf(v)
                st.availability != GeminiNanoAvailability.UNKNOWN &&
                    st.lastCheckedAtMs > 0L &&
                    (nowMs - st.lastCheckedAtMs) < REFRESH_COOLDOWN_MS
            }
            if (!force && allFresh) {
                return@withLock
            }

            _catalogState.update { state ->
                val updatedMap = state.statuses.mapValues { (_, s) ->
                    if (s.isDownloading || downloadJobs[s.variant]?.isActive == true) {
                        s
                    } else if (s.availability == GeminiNanoAvailability.UNKNOWN) {
                        s.copy(availability = GeminiNanoAvailability.CHECKING, errorMessage = null)
                    } else {
                        s.copy(errorMessage = null)
                    }
                }
                state.copy(statuses = updatedMap, isRefreshing = true)
            }

            val updatedStatuses = linkedMapOf<GeminiNanoVariant, GeminiNanoVariantStatus>()
            for (variant in GeminiNanoVariant.entries) {
                val activeDownload = downloadJobs[variant]?.isActive == true
                if (activeDownload) {
                    updatedStatuses[variant] = _catalogState.value.statusOf(variant)
                    continue
                }
                updatedStatuses[variant] = probeVariantStatus(variant)
            }

            _catalogState.update { state ->
                state.copy(
                    statuses = updatedStatuses,
                    isRefreshing = false
                )
            }
        }
    }

    suspend fun probeVariantStatus(variant: GeminiNanoVariant): GeminiNanoVariantStatus = withContext(Dispatchers.IO) {
        val nowMs = SystemClock.elapsedRealtime()
        var firstError: String? = null

        suspend fun checkStage(stage: Int): Int {
            var client: GenerativeModel? = null
            return try {
                client = createClient(variant, stage)
                client.checkStatus()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (firstError == null) {
                    firstError = describeGenAiException(t)
                }
                Log.d(TAG, "checkStatus(${variant.id}, stage=$stage) failed: ${t.message}")
                FeatureStatus.UNAVAILABLE
            } finally {
                runCatching { client?.close() }
            }
        }

        val stableStatus = checkStage(ModelReleaseStage.STABLE)
        val previewStatus = if (stableStatus == FeatureStatus.AVAILABLE) {
            FeatureStatus.UNAVAILABLE
        } else {
            checkStage(ModelReleaseStage.PREVIEW)
        }
        val (winningStage, winningStatus) = chooseBestStageStatus(stableStatus, previewStatus)
        val availability = mapFeatureStatus(winningStatus)

        if (winningStatus != FeatureStatus.AVAILABLE) {
            return@withContext GeminiNanoVariantStatus(
                variant = variant,
                availability = availability,
                resolvedReleaseStage = if (winningStatus == FeatureStatus.UNAVAILABLE) null else winningStage,
                errorMessage = if (winningStatus == FeatureStatus.UNAVAILABLE) firstError else null,
                lastCheckedAtMs = nowMs
            )
        }

        var metaClient: GenerativeModel? = null
        return@withContext try {
            metaClient = createClient(variant, winningStage)
            val baseModelName = runCatching { metaClient.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() }
            val tokenLimit = runCatching { metaClient.getTokenLimit() }.getOrNull()?.takeIf { it > 0 }
            val sysPrompt = runCatching { metaClient.isSystemPromptAvailable() }.getOrDefault(false)
            val thinking = runCatching { metaClient.isThinkingModeAvailable() }.getOrDefault(false)
            val caching = runCatching { metaClient.isCachingFeatureAvailable() }.getOrDefault(false)

            GeminiNanoVariantStatus(
                variant = variant,
                availability = GeminiNanoAvailability.AVAILABLE,
                resolvedReleaseStage = winningStage,
                baseModelName = baseModelName,
                tokenLimit = tokenLimit,
                supportsSystemPrompt = sysPrompt,
                supportsThinkingMode = thinking,
                supportsPrefixCaching = caching,
                lastCheckedAtMs = nowMs
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            GeminiNanoVariantStatus(
                variant = variant,
                availability = GeminiNanoAvailability.AVAILABLE,
                resolvedReleaseStage = winningStage,
                errorMessage = describeGenAiException(t),
                lastCheckedAtMs = nowMs
            )
        } finally {
            runCatching { metaClient?.close() }
        }
    }

    /**
     * Triggers an Android AICore model download for [variant] (`FULL` or `FAST`) when available
     * as `DOWNLOADABLE` (or monitors `DOWNLOADING`), streaming byte progress into [catalogState].
     */
    fun startDownload(
        variant: GeminiNanoVariant,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        downloadJobs[variant]?.cancel()
        val job = scope.launch {
            var client: GenerativeModel? = null
            var succeeded = false
            try {
                updateVariantState(variant) {
                    it.copy(
                        availability = GeminiNanoAvailability.CHECKING,
                        bytesDownloaded = 0L,
                        bytesToDownload = 0L,
                        errorMessage = null
                    )
                }

                val currentProbe = probeVariantStatus(variant)
                if (currentProbe.isAvailable) {
                    updateVariantState(variant) { currentProbe }
                    succeeded = true
                    onCompleted?.invoke(true)
                    return@launch
                }

                val stage = currentProbe.resolvedReleaseStage ?: ModelReleaseStage.STABLE
                if (currentProbe.availability != GeminiNanoAvailability.DOWNLOADABLE &&
                    currentProbe.availability != GeminiNanoAvailability.DOWNLOADING
                ) {
                    updateVariantState(variant) {
                        currentProbe.copy(
                            errorMessage = currentProbe.errorMessage
                                ?: "${variant.title} is not downloadable on this device."
                        )
                    }
                    onCompleted?.invoke(false)
                    return@launch
                }

                updateVariantState(variant) {
                    currentProbe.copy(
                        availability = GeminiNanoAvailability.DOWNLOADING,
                        errorMessage = null
                    )
                }

                client = createClient(variant, stage)
                client.download().collect { dl ->
                    when (dl) {
                        is DownloadStatus.DownloadStarted -> {
                            updateVariantState(variant) {
                                it.copy(
                                    availability = GeminiNanoAvailability.DOWNLOADING,
                                    bytesToDownload = dl.bytesToDownload,
                                    errorMessage = null
                                )
                            }
                        }
                        is DownloadStatus.DownloadProgress -> {
                            updateVariantState(variant) {
                                it.copy(
                                    availability = GeminiNanoAvailability.DOWNLOADING,
                                    bytesDownloaded = dl.totalBytesDownloaded,
                                    errorMessage = null
                                )
                            }
                        }
                        DownloadStatus.DownloadCompleted -> {
                            succeeded = true
                        }
                        is DownloadStatus.DownloadFailed -> {
                            throw dl.e
                        }
                    }
                }

                val refreshed = probeVariantStatus(variant)
                updateVariantState(variant) {
                    if (refreshed.isAvailable) {
                        refreshed.copy(errorMessage = null)
                    } else if (succeeded) {
                        refreshed
                    } else {
                        refreshed.copy(errorMessage = "Download did not complete cleanly.")
                    }
                }
                if (refreshed.isAvailable) {
                    DebugLogManager.logInfo(
                        TAG,
                        "Gemini Nano download completed (${variant.title}, base=${refreshed.baseModelName ?: "unknown"})"
                    )
                    succeeded = true
                }
            } catch (ce: CancellationException) {
                val refreshed = runCatching { probeVariantStatus(variant) }.getOrNull()
                if (refreshed != null) {
                    updateVariantState(variant) { refreshed.copy(errorMessage = "Download cancelled.") }
                }
                throw ce
            } catch (t: Throwable) {
                val msg = describeGenAiException(t)
                Log.w(TAG, "Gemini Nano download (${variant.id}) failed: $msg", t)
                DebugLogManager.logWarn(TAG, "Gemini Nano download (${variant.id}) failed: $msg")
                val refreshed = runCatching { probeVariantStatus(variant) }.getOrNull()
                updateVariantState(variant) { prev ->
                    (refreshed ?: prev.copy(availability = GeminiNanoAvailability.DOWNLOADABLE)).copy(
                        errorMessage = msg
                    )
                }
            } finally {
                runCatching { client?.close() }
                downloadJobs.remove(variant)
                onCompleted?.invoke(succeeded)
            }
        }
        downloadJobs[variant] = job
    }

    fun cancelDownload(variant: GeminiNanoVariant) {
        downloadJobs.remove(variant)?.cancel()
        refreshStatusesAsync(force = true)
    }

    fun recordRuntimeWarning(message: String?) {
        _catalogState.update { it.copy(lastRuntimeWarning = message) }
    }

    fun recordRuntimeSuccess(note: String) {
        _catalogState.update {
            it.copy(
                lastRuntimeWarning = null,
                lastRuntimeSuccessNote = note
            )
        }
    }

    private fun updateVariantState(
        variant: GeminiNanoVariant,
        transform: (GeminiNanoVariantStatus) -> GeminiNanoVariantStatus
    ) {
        _catalogState.update { state ->
            val current = state.statusOf(variant)
            val nextMap = state.statuses.toMutableMap()
            nextMap[variant] = transform(current)
            state.copy(statuses = nextMap)
        }
    }
}

/**
 * On-device post-processing engine powered by Google ML Kit GenAI Prompt API (`Gemini Nano`
 * `FULL` and `FAST` variants via Android AICore).
 *
 * Reuses OpenFlow's deterministic fast-path, spoken normalization, sentence sieve, sentence-bounded
 * chunking, and anti-hallucination output sanitizer (`GemmaTextProcessor`) so zero reasoning or
 * chatter can ever leak into pasted text.
 */
object GeminiNanoTextProcessor {
    private const val TAG = "GeminiNanoTextProc"
    private const val DEFAULT_NANO_TOKEN_LIMIT = 1_024

    /**
     * Computes a safe character budget per AICore request pass from the reported [tokenLimit]
     * (reserving system instruction + response output budget inside the on-device context window).
     */
    internal fun computeMaxInputCharsPerPass(
        tokenLimit: Int?,
        preset: GemmaPromptPreset
    ): Int {
        val effectiveLimit = (tokenLimit ?: DEFAULT_NANO_TOKEN_LIMIT).coerceIn(512, 8_192)
        val systemOverheadTokens = when {
            preset == GemmaPromptPreset.CLEAN_AND_POLISH -> 390
            else -> 250
        }
        val availableForIo = (effectiveLimit - systemOverheadTokens).coerceAtLeast(240)
        // Roughly ~50% input / ~50% output tokens at ~3.2 chars/token average across EU languages
        val estimatedInputTokens = (availableForIo * 45) / 100
        return (estimatedInputTokens * 3).coerceIn(380, 2_200)
    }

    internal fun computeMaxOutputTokensForPass(
        inputText: String,
        tokenLimit: Int?,
        preset: GemmaPromptPreset,
        thinkingEnabled: Boolean
    ): Int {
        val effectiveLimit = (tokenLimit ?: DEFAULT_NANO_TOKEN_LIMIT).coerceIn(512, 8_192)
        val words = inputText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val multiplier = if (preset == GemmaPromptPreset.PROFESSIONAL_MESSAGE || preset == GemmaPromptPreset.CUSTOM) 3 else 2
        val requested = words * multiplier + 96 + if (thinkingEnabled) 96 else 0
        val safeUpperCap = min((effectiveLimit * 45) / 100, 1_536).coerceAtLeast(160)
        return requested.coerceIn(128, safeUpperCap)
    }

    suspend fun process(
        text: String,
        variant: GeminiNanoVariant,
        preset: GemmaPromptPreset,
        customPrompt: String = "",
        languageCode: String? = null,
        customVocabulary: List<String> = emptyList(),
        fieldContext: FlowFieldContext = FlowFieldContext.NONE,
        disableThinking: Boolean = false,
        chunkingEnabled: Boolean = true,
        engineResolved: Boolean = false,
        precedingCleanedTail: String? = null
    ): GeminiNanoProcessResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext GeminiNanoProcessResult(text = text, succeeded = true)
        }

        if (GemmaTextProcessor.canBypassLlmFastPath(trimmed, preset, fieldContext, engineResolved)) {
            val bypassed = GemmaTextProcessor.applyDeterministicFastPathCleanup(
                text = trimmed,
                preset = preset,
                languageCode = languageCode,
                customVocabulary = customVocabulary,
                fieldContext = fieldContext
            )
            return@withContext GeminiNanoProcessResult(text = bypassed, succeeded = true, latencyMs = 1L)
        }

        val startMs = SystemClock.elapsedRealtime()
        val cachedStatus = GeminiNanoManager.catalogState.value.statusOf(variant)
        val resolvedStatus = if (cachedStatus.isAvailable && cachedStatus.resolvedReleaseStage != null) {
            cachedStatus
        } else {
            GeminiNanoManager.probeVariantStatus(variant)
        }

        if (!resolvedStatus.isAvailable) {
            val msg = resolvedStatus.errorMessage
                ?: "${variant.title} is ${resolvedStatus.availability.label.lowercase(Locale.ROOT)} on this device."
            GeminiNanoManager.recordRuntimeWarning(msg)
            return@withContext GeminiNanoProcessResult(
                text = trimmed,
                succeeded = false,
                errorCode = GenAiException.ErrorCode.NOT_AVAILABLE,
                errorMessage = msg
            )
        }

        val releaseStage = resolvedStatus.resolvedReleaseStage ?: ModelReleaseStage.STABLE
        val client = GeminiNanoManager.createClient(variant, releaseStage)
        try {
            val useThinking = resolvedStatus.supportsThinkingMode && !disableThinking
            val sieveSpans = GemmaTextProcessor.partitionSentenceSieve(trimmed, preset, fieldContext)
            if (sieveSpans != null) {
                val outSegments = ArrayList<String>(sieveSpans.size)
                var rollingTail = precedingCleanedTail?.trim()?.takeIf { it.isNotEmpty() }
                for ((spanIdx, span) in sieveSpans.withIndex()) {
                    if (!span.needsLlm) {
                        val deterministic = GemmaTextProcessor.applyCustomVocabularyExactCasing(
                            GemmaTextProcessor.preNormalizeSpokenConstructs(span.text, preset),
                            customVocabulary
                        )
                        outSegments.add(deterministic)
                        rollingTail = GemmaTextProcessor.extractContinuityTail(deterministic)
                    } else {
                        val spanFieldCtx = if (spanIdx == 0 && rollingTail == null) fieldContext else FlowFieldContext.NONE
                        val cleaned = runChunkedNanoPasses(
                            client = client,
                            status = resolvedStatus,
                            rawInput = span.text,
                            preset = preset,
                            customPrompt = customPrompt,
                            languageCode = languageCode,
                            customVocabulary = customVocabulary,
                            fieldContext = spanFieldCtx,
                            enableThinking = useThinking,
                            chunkingEnabled = chunkingEnabled,
                            precedingSegmentTail = rollingTail
                        )
                        outSegments.add(cleaned)
                        rollingTail = GemmaTextProcessor.extractContinuityTail(cleaned)
                    }
                }
                var joined = GemmaTextProcessor.joinFormattedSegments(outSegments, preset)
                if (preset != GemmaPromptPreset.CUSTOM && fieldContext.isMidSentenceContinuation && precedingCleanedTail == null) {
                    joined = GemmaTextProcessor.applyCursorContinuationCasing(
                        text = joined,
                        languageCode = languageCode,
                        customVocabulary = customVocabulary
                    )
                }
                val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                GeminiNanoManager.recordRuntimeSuccess("${variant.shortLabel} (${resolvedStatus.baseModelName ?: "AICore"}) · ${latency} ms")
                return@withContext GeminiNanoProcessResult(
                    text = joined,
                    succeeded = true,
                    baseModelName = resolvedStatus.baseModelName,
                    latencyMs = latency
                )
            }

            val formatted = runChunkedNanoPasses(
                client = client,
                status = resolvedStatus,
                rawInput = trimmed,
                preset = preset,
                customPrompt = customPrompt,
                languageCode = languageCode,
                customVocabulary = customVocabulary,
                fieldContext = fieldContext,
                enableThinking = useThinking,
                chunkingEnabled = chunkingEnabled,
                precedingSegmentTail = precedingCleanedTail
            )
            var finalOutput = formatted
            if (preset != GemmaPromptPreset.CUSTOM && fieldContext.isMidSentenceContinuation && precedingCleanedTail == null) {
                finalOutput = GemmaTextProcessor.applyCursorContinuationCasing(
                    text = finalOutput,
                    languageCode = languageCode,
                    customVocabulary = customVocabulary
                )
            }
            val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            GeminiNanoManager.recordRuntimeSuccess("${variant.shortLabel} (${resolvedStatus.baseModelName ?: "AICore"}) · ${latency} ms")
            return@withContext GeminiNanoProcessResult(
                text = finalOutput,
                succeeded = true,
                baseModelName = resolvedStatus.baseModelName,
                latencyMs = latency
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val code = (t as? GenAiException)?.errorCode
            val msg = GeminiNanoManager.describeGenAiException(t)
            Log.w(TAG, "${variant.title} inference failed: $msg", t)
            GeminiNanoManager.recordRuntimeWarning(msg)
            return@withContext GeminiNanoProcessResult(
                text = trimmed,
                succeeded = false,
                errorCode = code,
                errorMessage = msg,
                baseModelName = resolvedStatus.baseModelName,
                latencyMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun runChunkedNanoPasses(
        client: GenerativeModel,
        status: GeminiNanoVariantStatus,
        rawInput: String,
        preset: GemmaPromptPreset,
        customPrompt: String,
        languageCode: String?,
        customVocabulary: List<String>,
        fieldContext: FlowFieldContext,
        enableThinking: Boolean,
        chunkingEnabled: Boolean,
        precedingSegmentTail: String?
    ): String {
        val maxChars = if (chunkingEnabled) {
            computeMaxInputCharsPerPass(status.tokenLimit, preset)
        } else {
            maxOf(rawInput.length + 1, 2_200)
        }
        val segments = GemmaTextProcessor.splitLongTranscript(rawInput, maxChars)
        if (segments.isEmpty()) return rawInput

        val formattedSegments = ArrayList<String>(segments.size)
        var rollingTail = precedingSegmentTail?.trim()?.takeIf { it.isNotEmpty() }

        for ((idx, segment) in segments.withIndex()) {
            val effectiveCtx = if (idx == 0) {
                fieldContext
            } else {
                fieldContext.copy(
                    isMidSentenceContinuation = false,
                    continuingNumberedListFrom = null,
                    isContinuingBulletList = false
                )
            }
            val cleaned = runSingleNanoPass(
                client = client,
                status = status,
                segment = segment,
                preset = preset,
                customPrompt = customPrompt,
                languageCode = languageCode,
                customVocabulary = customVocabulary,
                fieldContext = effectiveCtx,
                enableThinking = enableThinking,
                precedingSegmentTail = rollingTail
            )
            formattedSegments.add(cleaned)
            rollingTail = GemmaTextProcessor.extractContinuityTail(cleaned)
        }
        return GemmaTextProcessor.joinFormattedSegments(formattedSegments, preset)
    }

    private suspend fun runSingleNanoPass(
        client: GenerativeModel,
        status: GeminiNanoVariantStatus,
        segment: String,
        preset: GemmaPromptPreset,
        customPrompt: String,
        languageCode: String?,
        customVocabulary: List<String>,
        fieldContext: FlowFieldContext,
        enableThinking: Boolean,
        precedingSegmentTail: String?
    ): String {
        val preNormalized = GemmaTextProcessor.preNormalizeSpokenConstructs(segment, preset)
        val systemInstructionText = GemmaTextProcessor.buildSystemInstruction(
            preset = preset,
            customPrompt = customPrompt,
            languageCode = languageCode,
            customVocabulary = customVocabulary,
            fieldContext = fieldContext,
            precedingSegmentTail = precedingSegmentTail,
            disableThinking = !enableThinking,
            llmChoice = LlmModelChoice.GEMMA_4_E2B,
            llmProvider = LlmProviderMode.LOCAL,
            promptTierOverride = LlmPromptTier.GEMINI_NANO
        )
        val userPromptText = GemmaTextProcessor.buildUserPrompt(preNormalized, languageCode)
        val maxOut = computeMaxOutputTokensForPass(preNormalized, status.tokenLimit, preset, enableThinking)

        val request = if (status.supportsSystemPrompt) {
            generateContentRequest(
                SystemInstruction(systemInstructionText),
                TextPart(userPromptText)
            ) {
                temperature = 0.1f
                topK = 20
                candidateCount = 1
                maxOutputTokens = maxOut
                this.enableThinking = enableThinking
            }
        } else {
            generateContentRequest(
                TextPart("$systemInstructionText\n\n$userPromptText")
            ) {
                temperature = 0.1f
                topK = 20
                candidateCount = 1
                maxOutputTokens = maxOut
                this.enableThinking = enableThinking
            }
        }

        val response = client.generateContent(request)
        val rawText = response.candidates.firstOrNull()?.text.orEmpty()
        var cleaned = GemmaTextProcessor.sanitizeGemmaOutput(
            rawOutput = rawText,
            fallbackInput = preNormalized,
            preset = preset,
            customPrompt = customPrompt,
            precedingSegmentTail = precedingSegmentTail
        )
        cleaned = GemmaTextProcessor.expandInlineSequentialLists(cleaned)
        if (customVocabulary.isNotEmpty()) {
            cleaned = GemmaTextProcessor.applyCustomVocabularyExactCasing(cleaned, customVocabulary)
        }
        return cleaned
    }

    /**
     * Runs a foreground quick test/benchmark directly against Android AICore Gemini Nano (`FULL` or `FAST`).
     */
    suspend fun runQuickBenchmark(
        inputText: String,
        variant: GeminiNanoVariant,
        preset: GemmaPromptPreset,
        customPrompt: String = "",
        languageCode: String? = null,
        customVocabulary: List<String> = emptyList(),
        disableThinking: Boolean = false
    ): GeminiNanoBenchmarkResult = withContext(Dispatchers.IO) {
        val status = GeminiNanoManager.probeVariantStatus(variant)
        if (!status.isAvailable) {
            throw IllegalStateException(
                status.errorMessage ?: "${variant.title} is ${status.availability.label} on this device."
            )
        }
        val stage = status.resolvedReleaseStage ?: ModelReleaseStage.STABLE
        val client = GeminiNanoManager.createClient(variant, stage)
        val startMs = SystemClock.elapsedRealtime()
        try {
            val useThinking = status.supportsThinkingMode && !disableThinking
            val cleaned = runSingleNanoPass(
                client = client,
                status = status,
                segment = inputText.trim(),
                preset = preset,
                customPrompt = customPrompt,
                languageCode = languageCode,
                customVocabulary = customVocabulary,
                fieldContext = FlowFieldContext.NONE,
                enableThinking = useThinking,
                precedingSegmentTail = null
            )
            val latencyMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            GeminiNanoManager.recordRuntimeSuccess("${variant.shortLabel} (${status.baseModelName ?: "AICore"}) · ${latencyMs} ms")
            GeminiNanoBenchmarkResult(
                variant = variant,
                stageLabel = status.stageLabel ?: "Stable",
                baseModelName = status.baseModelName,
                tokenLimit = status.tokenLimit,
                supportsSystemPrompt = status.supportsSystemPrompt,
                supportsThinkingMode = status.supportsThinkingMode,
                inputText = inputText.trim(),
                cleanedOutput = cleaned,
                latencyMs = latencyMs
            )
        } finally {
            runCatching { client.close() }
        }
    }
}
