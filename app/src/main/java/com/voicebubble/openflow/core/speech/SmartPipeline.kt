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

import ai.desertant.ear.Ear
import ai.desertant.voz.Transcription
import ai.desertant.voz.Voz
import ai.desertant.voz.Word
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DictationChunkSettings
import com.voicebubble.openflow.settings.HardwareAccelerator
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Per-model runtime load status exposed to the UI.
 */
enum class ModelLoadState(val label: String) {
    DISABLED("Disabled"),
    NOT_DOWNLOADED("Not Downloaded"),
    STANDBY("Standby (Dynamic)"),
    LOADING("Loading..."),
    LOADED("Loaded in RAM"),
    ACTIVE("Running Inference"),
    ERROR("Load Error")
}

/**
 * High-level lifecycle phase of [SmartPipeline].
 */
enum class PipelinePhase(val label: String) {
    UNINITIALIZED("Standby (0 MB in RAM)"),
    LOADING("Loading Models..."),
    WARMING_UP("Warming Up Accelerator..."),
    READY("Models Loaded & Ready"),
    TRANSCRIBING("Transcribing..."),
    STANDBY_EVICTED("Optional Models on Standby"),
    ERROR("Attention Needed")
}

/**
 * Snapshot of JVM Heap, Native Heap, and OS System RAM headroom.
 * Prevents OOM crashes that occur when JVM Dalvik/ART heap is nearly full even though the
 * device has many gigabytes of free physical system RAM.
 */
data class MemoryPressureReport(
    val systemAvailMb: Long,
    val systemTotalMb: Long,
    val systemLowMemoryFlag: Boolean,
    val jvmUsedMb: Long,
    val jvmMaxMb: Long,
    val jvmFreeHeadroomMb: Long,
    val nativeAllocatedMb: Long
) {
    val jvmUsedRatio: Double
        get() = if (jvmMaxMb > 0L) jvmUsedMb.toDouble() / jvmMaxMb.toDouble() else 0.0

    val isJvmHeapTight: Boolean
        get() = jvmFreeHeadroomMb < 64L || jvmUsedRatio >= 0.80

    val isJvmHeapCritical: Boolean
        get() = jvmFreeHeadroomMb < 36L || jvmUsedRatio >= 0.88

    val isSystemRamTight: Boolean
        get() = systemLowMemoryFlag || systemAvailMb < 750L

    val isSystemRamCritical: Boolean
        get() = systemLowMemoryFlag || systemAvailMb < 450L

    /**
     * Informational telemetry flag. Note: On 64-bit Qualcomm Snapdragon (Adreno OpenCL / QNN HTP),
     * `Debug.getNativeHeapAllocatedSize()` includes ~4.6 GB of virtual GPU/DSP address space mappings
     * even when actual RSS is ~1.8 GB and >2 GB of physical RAM is free, so this must not trigger
     * mid-pipeline native session teardowns.
     */
    val isNativeHeapHigh: Boolean
        get() = nativeAllocatedMb > 1_350L

    val isAnyMemoryTight: Boolean
        get() = isJvmHeapTight || isSystemRamTight

    val isCriticalPressure: Boolean
        get() = isJvmHeapCritical || isSystemRamCritical

    /**
     * Selects Gemma 4 E2B's KV-cache context window & speculative decoding tier from physical RAM headroom.
     */
    val gemmaMemoryMode: GemmaMemoryMode
        get() = when {
            isAnyMemoryTight || systemAvailMb < 1_200L -> GemmaMemoryMode.LOW_MEMORY
            systemAvailMb < 2_200L -> GemmaMemoryMode.MODERATE_HEADROOM
            else -> GemmaMemoryMode.HIGH_HEADROOM
        }

    /**
     * Whether enough free system & JVM RAM exists to hold a pre-prefilled standby system-prompt `Conversation`
     * in Gemma 4 E2B's KV cache (~150–250 MB) while idle or recording.
     */
    val canPrewarmGemmaPromptCache: Boolean
        get() = !isAnyMemoryTight && systemAvailMb >= 1_350L && jvmFreeHeadroomMb >= 96L

    /**
     * Scales system-prompt KV-cache pre-warming threshold to the active [llmChoice]'s KV footprint.
     */
    fun canPrewarmLlmPromptCache(llmChoice: LlmModelChoice): Boolean {
        if (llmChoice == LlmModelChoice.GEMMA_4_E2B) return canPrewarmGemmaPromptCache
        return !isAnyMemoryTight && systemAvailMb >= 900L && jvmFreeHeadroomMb >= 72L
    }

    /**
     * Determines whether `Voz` (~1.3 GB) and the selected [llmChoice] can safely stay co-resident in RAM
     * without risking an Android LMKD out-of-memory kill.
     *
     * Accounts for whether each model's memory is *already* reflected in [systemAvailMb] or still needs
     * to be allocated on top of current usage.
     */
    fun canKeepVozAndLlmCoResident(
        llmChoice: LlmModelChoice,
        vozAlreadyLoaded: Boolean,
        llmAlreadyLoaded: Boolean
    ): Boolean {
        if (isAnyMemoryTight) return false
        if (llmChoice == LlmModelChoice.GEMMA_4_E2B) {
            return when {
                vozAlreadyLoaded && llmAlreadyLoaded ->
                    systemAvailMb >= 1_050L
                vozAlreadyLoaded && !llmAlreadyLoaded ->
                    systemTotalMb >= 7_200L && systemAvailMb >= 2_100L
                !vozAlreadyLoaded && llmAlreadyLoaded ->
                    systemTotalMb >= 7_200L && systemAvailMb >= 2_150L
                else ->
                    systemTotalMb >= 7_500L && systemAvailMb >= 2_500L
            }
        }
        val llmNeedMb = llmChoice.estimatedStandaloneRamMb.toLong()
        val minTotalRamMb = when {
            llmNeedMb <= 700L -> 3_600L
            llmNeedMb <= 1_150L -> 5_200L
            else -> 5_600L
        }
        return when {
            vozAlreadyLoaded && llmAlreadyLoaded ->
                systemAvailMb >= 800L
            vozAlreadyLoaded && !llmAlreadyLoaded ->
                systemTotalMb >= minTotalRamMb && systemAvailMb >= llmNeedMb + 500L
            !vozAlreadyLoaded && llmAlreadyLoaded ->
                systemTotalMb >= minTotalRamMb && systemAvailMb >= 1_850L
            else ->
                systemTotalMb >= minTotalRamMb && systemAvailMb >= llmNeedMb + 1_650L
        }
    }

    /**
     * Backwards-compatible helper for Gemma 4 E2B co-residency checks.
     */
    fun canKeepVozAndGemmaCoResident(
        vozAlreadyLoaded: Boolean,
        gemmaAlreadyLoaded: Boolean
    ): Boolean = canKeepVozAndLlmCoResident(
        llmChoice = LlmModelChoice.GEMMA_4_E2B,
        vozAlreadyLoaded = vozAlreadyLoaded,
        llmAlreadyLoaded = gemmaAlreadyLoaded
    )
}

/**
 * Observable snapshot of the speech pipeline's loaded models, active hardware accelerator,
 * detected NPU profile, and most recent inference performance.
 */
data class PipelineStatus(
    val phase: PipelinePhase = PipelinePhase.UNINITIALIZED,
    val configuredAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO,
    val activeAcceleratorLabel: String = "Auto (NPU / GPU / CPU)",
    val activeGemmaBackendLabel: String? = null,
    val detectedNpuSummary: String = NpuHardwareManager.detectHardwareProfile().summaryLabel,
    val dynamicLoadingMode: String = "Adaptive Stage Management",
    val modelStates: Map<DesertAntModel, ModelLoadState> = DesertAntModel.entries.associateWith { model ->
        when (model) {
            DesertAntModel.VOZ, DesertAntModel.EAR -> ModelLoadState.STANDBY
            else -> ModelLoadState.DISABLED
        }
    },
    val statusMessage: String = "Standby — 0 MB in RAM (loads when bubble is tapped)",
    val lastInferenceMs: Long? = null,
    val lastAudioDurationMs: Long? = null,
    val lastWarmupMs: Long? = null,
    val loadedCount: Int = 0,
    val enabledCount: Int = 1
) {
    val isReady: Boolean
        get() = phase == PipelinePhase.READY ||
            phase == PipelinePhase.STANDBY_EVICTED ||
            modelStates[DesertAntModel.VOZ] == ModelLoadState.LOADED
}

/**
 * Orchestrates the Desert Ant Labs on-device audio AI pipeline:
 * 1. `Ear` — Spoken language identification (optional)
 * 2. `Voz` — Multilingual speech-to-text across 25 European languages
 * 3. Cross-chunk stitching & custom vocabulary normalization
 * 4. Optional single-model AI post-processing (`Qwen 3` / `Qwen 2.5` / `Gemma 4 E2B` via `LiteRT-LM`) or `Punct`
 */
class SmartPipeline(
    context: Context,
    initialSettings: ModelSettings = ModelSettings.load(context)
) {

    companion object {
        private const val TAG = "SmartPipeline"
        const val SAMPLE_RATE_HZ: Double = 16_000.0
        const val IDLE_TIMEOUT_MS: Long = 20 * 60_000L // 20 minutes warm retention for core audio
        const val LLM_IDLE_TIMEOUT_MS: Long = 5 * 60_000L // 5 minutes burst retention for on-demand LLM
        const val SCREEN_OFF_DEEP_SLEEP_UNLOAD_MS: Long = 45_000L // 45s fast unload when display is locked/off
        const val DEFERRED_POST_PROC_OVERLAP_MS: Long = 2_000L // Defer multi-GB LLM load past quick <2s replies
        private const val SILENCE_THRESHOLD: Float = 0.0035f
        private const val SILENCE_PAD_SAMPLES: Int = 2_560 // 160 ms @ 16 kHz

        // Chunking bounds to guarantee bounded Conformer memory on arbitrarily long dictations
        const val DEFAULT_MAX_CHUNK_SAMPLES: Int = 16 * 16_000 // 16.0s @ 16 kHz
        const val DEFAULT_MIN_CHUNK_SAMPLES: Int = 9 * 16_000  // 9.0s @ 16 kHz
        const val LOW_MEM_MAX_CHUNK_SAMPLES: Int = 12 * 16_000 // 12.0s @ 16 kHz
        const val LOW_MEM_MIN_CHUNK_SAMPLES: Int = 7 * 16_000  // 7.0s @ 16 kHz
        private const val MAX_EAR_WINDOW_SAMPLES: Int = 88_000 // 5.5s @ 16 kHz

        enum class TrimMemoryAction {
            /** Drop only cached LLM prompt KV state; keep loaded weights warm (e.g. UI_HIDDEN when switching to chat app). */
            DROP_KV_CACHE_ONLY,
            /** Unload optional auxiliary models (`Ear`, `Punct`, `LiteRT-LM`) while keeping `Voz` ready. */
            EVICT_OPTIONAL_MODELS,
            /** Severe memory pressure: unload optional models, plus unload `Voz` if physical RAM is critical or LRU is COMPLETE. */
            SEVERE_EVICT
        }

        /**
         * Correctly separates Android's two distinct `ComponentCallbacks2.TRIM_MEMORY_*` integer bands:
         * - Running band (`5` = MODERATE, `10` = LOW, `15` = CRITICAL)
         * - UI / LRU background band (`20` = UI_HIDDEN, `40` = BACKGROUND, `60` = MODERATE, `80` = COMPLETE)
         */
        @Suppress("DEPRECATION")
        fun classifyTrimMemoryAction(level: Int): TrimMemoryAction {
            return when {
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> TrimMemoryAction.SEVERE_EVICT
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> TrimMemoryAction.EVICT_OPTIONAL_MODELS
                else -> TrimMemoryAction.DROP_KV_CACHE_ONLY
            }
        }

        /**
         * Computes the effective idle auto-unload delay in milliseconds (`null` = never auto-unload, `0L` = immediate).
         * When the screen is OFF, caps retention at [SCREEN_OFF_DEEP_SLEEP_UNLOAD_MS] (`45s`) so native NPU/GPU/CPU
         * sessions release quickly for deep sleep once the phone is locked, while surviving brief lock/unlock taps.
         */
        fun computeEffectiveIdleTimeoutMs(unloadMinutes: Int, isScreenInteractive: Boolean): Long? {
            return when {
                unloadMinutes == ModelSettings.AUTO_UNLOAD_NEVER -> null
                unloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY -> 0L
                else -> {
                    val configuredMs = unloadMinutes.coerceAtLeast(1).toLong() * 60_000L
                    if (isScreenInteractive) configuredMs else min(configuredMs, SCREEN_OFF_DEEP_SLEEP_UNLOAD_MS)
                }
            }
        }

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
    private var llmIdleEvictionJob: Job? = null
    private var promptPrewarmJob: Job? = null
    private var recordingWarmupJob: Job? = null
    private var postProcOverlapJob: Job? = null

    @Volatile
    private var isRecordingActive: Boolean = false

    @Volatile
    private var recordingStartedAtMs: Long = 0L

    @Volatile
    private var isScreenInteractiveState: Boolean = true

    /**
     * Incremented *before* waiting on [pipelineMutex] in `transcribe()` and post-processing calls so
     * background recording-overlap loaders yield [pipelineMutex] in `0 ms` the moment real audio arrives.
     */
    private val pendingInferenceRequests = AtomicInteger(0)

    @Volatile
    var settings: ModelSettings = initialSettings
        private set

    private var appliedAccelerator: HardwareAccelerator? = null
    private var appliedLowMemoryMode: Boolean? = null
    private var appliedSettingsSnapshot: ModelSettings? = null
    private var lastForcedGcAtMs: Long = 0L
    private var activeAcceleratorDescription: String =
        NpuHardwareManager.configureNativeRuntime(appContext, initialSettings.hardwareAccelerator, false)

    @Volatile
    private var voz: Voz? = null
    @Volatile
    private var ear: Ear? = null
    @Volatile
    private var punctRestorer: PunctuationRestorer? = null
    @Volatile
    private var gemmaProcessor: GemmaTextProcessor? = null
    @Volatile
    private var loadedLlmChoice: LlmModelChoice? = null

    private var vozWarmedUp: Boolean = false
    private var earWarmedUp: Boolean = false
    private var punctWarmedUp: Boolean = false
    private var gemmaWarmedUp: Boolean = false

    @Volatile
    private var isTranscribing: Boolean = false

    /** `true` as soon as the native `Voz` speech session is loaded in RAM (lock-free check for live streaming). */
    val isVozReady: Boolean
        get() = voz != null

    /** `true` if inference is running OR a real audio chunk / stop-tap is queued at [pipelineMutex]. */
    val hasPendingOrActiveInference: Boolean
        get() = isTranscribing || pendingInferenceRequests.get() > 0

    /**
     * `true` whenever audio transcription (`Ear` / `Voz`) or post-processing (`Punct` / on-device LLM)
     * is actively executing or queued to execute immediately.
     */
    val isActivelyProcessing: Boolean
        get() = isTranscribing || pendingInferenceRequests.get() > 0

    private val _pipelineStatus = MutableStateFlow(
        PipelineStatus(
            configuredAccelerator = initialSettings.hardwareAccelerator,
            activeAcceleratorLabel = activeAcceleratorDescription,
            detectedNpuSummary = NpuHardwareManager.detectHardwareProfile().summaryLabel,
            enabledCount = initialSettings.enabledModels().size
        )
    )
    val pipelineStatus: StateFlow<PipelineStatus> = _pipelineStatus.asStateFlow()

    /**
     * Refreshes on-disk vs loaded model states without acquiring native locks if idle.
     */
    fun refreshStatusSnapshot() {
        val snap = settings
        updateStatusSnapshot(
            activeSettings = snap,
            phaseOverride = null,
            messageOverride = null
        )
    }

    /**
     * Initializes `Voz` and currently enabled auxiliary models inside [pipelineMutex].
     * Deduplicates redundant calls if all enabled models are already loaded and warmed up.
     */
    suspend fun initialize(warmUp: Boolean = true, forceReload: Boolean = false): Boolean = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            val currentSettings = settings
            val accelUnchanged = appliedAccelerator == currentSettings.hardwareAccelerator
            val alreadyReady = !forceReload &&
                accelUnchanged &&
                voz != null &&
                (!warmUp || vozWarmedUp) &&
                areEnabledModelsReadyOrStandbyLocked(currentSettings)

            if (alreadyReady) {
                val count = countLoadedModelsLocked(currentSettings)
                val msg = "$count/${currentSettings.enabledModels().size} models ready in RAM ($activeAcceleratorDescription)"
                updateStatusSnapshot(
                    activeSettings = currentSettings,
                    phaseOverride = PipelinePhase.READY,
                    messageOverride = msg
                )
                resetIdleEvictionTimerLocked()
                return@withLock true
            }

            val startMs = SystemClock.elapsedRealtime()
            val memReport = evaluateMemoryPressure()
            applyHardwareAcceleratorSettingsLocked(currentSettings.hardwareAccelerator, memReport.isAnyMemoryTight)

            DebugLogManager.logInfo(
                TAG,
                "initialize(warmUp=$warmUp) start • enabled=${currentSettings.enabledModels().map { it.id }} • " +
                    "accel=$activeAcceleratorDescription • npu=${NpuHardwareManager.detectHardwareProfile().summaryLabel}",
                includeMemory = true
            )

            updateStatusSnapshot(
                activeSettings = currentSettings,
                phaseOverride = PipelinePhase.LOADING,
                messageOverride = "Loading enabled models ($activeAcceleratorDescription)..."
            )

            val vozLoaded = ensureVozLocked(currentSettings, warmUp = warmUp)
            ensureAuxiliaryModelsLocked(
                activeSettings = currentSettings,
                warmUp = warmUp && !memReport.isAnyMemoryTight,
                preloadLlmIfCoResident = forceReload
            )
            appliedSettingsSnapshot = currentSettings
            resetIdleEvictionTimerLocked()

            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val finalPhase = if (vozLoaded) {
                PipelinePhase.READY
            } else if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)) {
                PipelinePhase.UNINITIALIZED
            } else {
                PipelinePhase.ERROR
            }
            val finalMsg = when (finalPhase) {
                PipelinePhase.READY -> {
                    val count = countLoadedModelsLocked(currentSettings)
                    "$count/${currentSettings.enabledModels().size} models ready ($activeAcceleratorDescription)"
                }
                PipelinePhase.UNINITIALIZED -> "Models not downloaded yet"
                else -> "Failed to load Voz speech model"
            }

            _pipelineStatus.value = _pipelineStatus.value.copy(
                lastWarmupMs = if (vozLoaded) elapsedMs else _pipelineStatus.value.lastWarmupMs
            )
            updateStatusSnapshot(
                activeSettings = currentSettings,
                phaseOverride = finalPhase,
                messageOverride = finalMsg
            )
            DebugLogManager.logInfo(
                TAG,
                "initialize(warmUp=$warmUp) finished in ${elapsedMs}ms • vozLoaded=$vozLoaded • status=$finalMsg",
                includeMemory = true
            )
            vozLoaded
        }
    }

    /**
     * Launches [initialize] on the singleton's background scope so model loading and LiteRT
     * warm-up complete even if the calling Activity or Composable leaves the screen.
     */
    fun initializeAsync(warmUp: Boolean = true, forceReload: Boolean = false) {
        idleScope.launch {
            initialize(warmUp = warmUp, forceReload = forceReload)
        }
    }

    /**
     * Returns the current [ModelSettings] snapshot without locking.
     */
    fun currentSettings(): ModelSettings = settings

    /**
     * Lightweight lock-free check used by live streaming chunkers to apply backpressure under memory distress.
     */
    fun isMemoryCriticallyTight(): Boolean = evaluateMemoryPressure().isCriticalPressure

    /**
     * Triggered asynchronously when the user taps the microphone bubble to start recording.
     *
     * Battery & RAM Saver Architecture:
     * - Idle RAM stays at `0 MB` when all models have been auto-unloaded.
     * - Tapping the bubble overlaps loading `Voz`, `Ear`/`Punct`, and (if co-resident RAM allows)
     *   the active `LiteRT-LM` model + system-prompt KV cache while the user speaks.
     * - If the user finishes a very short utterance while loading is still underway (`!isRecordingActive`),
     *   this job yields [pipelineMutex] immediately after `Voz` is loaded so transcription starts with zero
     *   synthetic warmup delay.
     */
    fun onRecordingStartedAsync() {
        val nowMs = SystemClock.elapsedRealtime()
        isRecordingActive = true
        recordingStartedAtMs = nowMs
        idleEvictionJob?.cancel()
        idleEvictionJob = null
        llmIdleEvictionJob?.cancel()
        llmIdleEvictionJob = null
        promptPrewarmJob?.cancel()
        promptPrewarmJob = null
        postProcOverlapJob?.cancel()
        postProcOverlapJob = null
        recordingWarmupJob?.cancel()
        recordingWarmupJob = idleScope.launch {
            var shouldPreparePostProcessor = false
            // Phase 1: Load speech recognition models (`Ear` + `Voz`) immediately at t = 0 ms while the user speaks,
            // then release pipelineMutex right away so live streaming chunks or a quick Stop-tap never wait.
            pipelineMutex.withLock {
                if (!isRecordingActive || hasPendingOrActiveInference) return@withLock
                val activeSnap = settings
                if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)) {
                    updateStatusSnapshot(
                        activeSettings = activeSnap,
                        phaseOverride = PipelinePhase.UNINITIALIZED,
                        messageOverride = "Models not downloaded yet"
                    )
                    return@withLock
                }

                val mem = evaluateMemoryPressure()
                applyHardwareAcceleratorSettingsLocked(activeSnap.hardwareAccelerator, mem.isAnyMemoryTight)

                // 1a. Preload lightweight Ear (10 MB, ~40 ms) first if enabled so early live language ID can run immediately
                if (activeSnap.earEnabled &&
                    ear == null &&
                    !mem.isCriticalPressure &&
                    DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR)
                ) {
                    try {
                        val earDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.EAR).absolutePath
                        ear = Ear(appContext, earDir)
                        earWarmedUp = false
                    } catch (t: Throwable) {
                        DebugLogManager.logWarn(TAG, "Deferred Ear overlap load: ${t.message}")
                        closeEarLocked()
                    }
                }

                // 1b. Load Voz immediately while the user speaks
                if (voz == null) {
                    updateStatusSnapshot(
                        activeSettings = activeSnap,
                        phaseOverride = PipelinePhase.LOADING,
                        messageOverride = "Loading Voz speech engine ($activeAcceleratorDescription)..."
                    )
                    val loaded = ensureVozLocked(activeSnap, warmUp = false)
                    if (!loaded) {
                        updateStatusSnapshot(
                            activeSettings = activeSnap,
                            phaseOverride = PipelinePhase.ERROR,
                            messageOverride = "Failed to load Voz speech model"
                        )
                        return@withLock
                    }
                }

                // Yield lock in 0 ms if user already tapped Stop or a live audio chunk is queued
                if (!isRecordingActive || hasPendingOrActiveInference) {
                    updateStatusSnapshot(
                        activeSettings = activeSnap,
                        phaseOverride = PipelinePhase.READY,
                        messageOverride = "${countLoadedModelsLocked(activeSnap)}/${activeSnap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                    )
                    return@withLock
                }

                // 2. User is still mid-sentence with no chunk queued: compile Voz accelerator kernels ahead of Chunk #0
                if (!vozWarmedUp && !mem.isAnyMemoryTight) {
                    ensureVozLocked(activeSnap, warmUp = true)
                }
                appliedSettingsSnapshot = activeSnap

                val readyCount = countLoadedModelsLocked(activeSnap)
                updateStatusSnapshot(
                    activeSettings = activeSnap,
                    phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                    messageOverride = "$readyCount/${activeSnap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                )

                val needsLlm = activeSnap.gemmaEnabled && isActiveLlmReadyOnDisk(activeSnap)
                val needsPunct = !needsLlm &&
                    activeSnap.punctEnabled &&
                    DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
                shouldPreparePostProcessor = isRecordingActive &&
                    !hasPendingOrActiveInference &&
                    (needsLlm || needsPunct)
            }

            if (shouldPreparePostProcessor && isRecordingActive && !hasPendingOrActiveInference) {
                val elapsedRecordingMs = (SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L)
                val remainingDeferMs = (DEFERRED_POST_PROC_OVERLAP_MS - elapsedRecordingMs).coerceAtLeast(0L)
                preparePostProcessorWhileRecordingAsync(initialDelayMs = remainingDeferMs)
            }
        }
    }

    /**
     * Opportunistically loads the active post-processor (`LiteRT-LM` LLM + system-prompt KV cache, or `Punct`)
     * in decoupled non-blocking lock steps *while the user continues speaking* (either after [DEFERRED_POST_PROC_OVERLAP_MS]
     * so `< 2s` quick replies never load a multi-GB LLM unnecessarily, or in the idle gap right after a live chunk finishes).
     * Yields immediately whenever [hasPendingOrActiveInference] becomes `true`.
     */
    fun preparePostProcessorWhileRecordingAsync(initialDelayMs: Long = 0L) {
        if (!isRecordingActive || hasPendingOrActiveInference) return
        if (postProcOverlapJob?.isActive == true) return
        postProcOverlapJob = idleScope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            if (!isRecordingActive || hasPendingOrActiveInference) return@launch

            // Step A: Load co-resident LLM or Punct only if pipelineMutex is completely uncontended
            if (pipelineMutex.tryLock()) {
                try {
                    if (isRecordingActive && !hasPendingOrActiveInference) {
                        val activeSnap = settings
                        val activeLlmReady = activeSnap.gemmaEnabled && isActiveLlmReadyOnDisk(activeSnap)
                        if (activeLlmReady) {
                            val selectedLlm = activeSnap.selectedLlmModel
                            if (loadedLlmChoice != null && loadedLlmChoice != selectedLlm) {
                                closeGemmaLocked()
                            }
                            if (punctRestorer != null) {
                                closePunctLocked()
                            }
                            val memBeforeLlm = evaluateMemoryPressure()
                            if (gemmaProcessor == null &&
                                memBeforeLlm.canKeepVozAndLlmCoResident(
                                    llmChoice = selectedLlm,
                                    vozAlreadyLoaded = voz != null,
                                    llmAlreadyLoaded = false
                                )
                            ) {
                                val modelDir = DesertAntModelStorage.modelRevisionDir(appContext, selectedLlm.desertAntModel)
                                gemmaProcessor = GemmaTextProcessor(
                                    context = appContext,
                                    modelDir = modelDir,
                                    accelerator = activeSnap.hardwareAccelerator,
                                    memoryMode = memBeforeLlm.gemmaMemoryMode,
                                    llmChoice = selectedLlm
                                )
                                loadedLlmChoice = selectedLlm
                                gemmaWarmedUp = false
                                DebugLogManager.logInfo(
                                    TAG,
                                    "Loaded ${selectedLlm.title} during recording overlap (${gemmaProcessor?.activeBackendSummary})",
                                    includeMemory = true
                                )
                                updateStatusSnapshot(
                                    activeSettings = activeSnap,
                                    phaseOverride = PipelinePhase.READY,
                                    messageOverride = "${countLoadedModelsLocked(activeSnap)}/${activeSnap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                                )
                            }
                        } else if (activeSnap.punctEnabled &&
                            punctRestorer == null &&
                            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
                        ) {
                            val memNow = evaluateMemoryPressure()
                            if (!memNow.isCriticalPressure) {
                                val punctDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.PUNCT)
                                punctRestorer = PunctuationRestorer(
                                    context = appContext,
                                    modelDir = punctDir,
                                    accelerator = activeSnap.hardwareAccelerator,
                                    lowMemoryPressure = memNow.isAnyMemoryTight
                                )
                                punctWarmedUp = false
                                updateStatusSnapshot(
                                    activeSettings = activeSnap,
                                    phaseOverride = PipelinePhase.READY,
                                    messageOverride = "${countLoadedModelsLocked(activeSnap)}/${activeSnap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                                )
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    DebugLogManager.logWarn(TAG, "Deferred post-processor overlap load during recording: ${t.message}")
                    if (settings.gemmaEnabled) closeGemmaLocked() else closePunctLocked()
                } finally {
                    pipelineMutex.unlock()
                }
            }

            if (!isRecordingActive || hasPendingOrActiveInference) return@launch

            // Step B: Pre-fill LLM system-prompt KV cache in a separate lock scope so any newly cut chunk preempts it
            if (pipelineMutex.tryLock()) {
                try {
                    if (isRecordingActive && !hasPendingOrActiveInference) {
                        val activeSnap = settings
                        val gemma = gemmaProcessor
                        if (activeSnap.gemmaEnabled && gemma != null && loadedLlmChoice == activeSnap.selectedLlmModel) {
                            val memAfterLoad = evaluateMemoryPressure()
                            if (memAfterLoad.canPrewarmLlmPromptCache(activeSnap.selectedLlmModel)) {
                                gemma.prewarmSystemPrompt(
                                    preset = activeSnap.gemmaPromptPreset,
                                    customPrompt = activeSnap.gemmaCustomPrompt,
                                    customVocabulary = activeSnap.customVocabulary,
                                    disableThinking = activeSnap.gemmaDisableThinking,
                                    adaptiveThinking = activeSnap.gemmaAdaptiveThinking
                                )
                            } else {
                                gemma.evictCachedSystemPrompt()
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    DebugLogManager.logWarn(TAG, "Deferred LLM prompt prewarm during recording: ${t.message}")
                } finally {
                    pipelineMutex.unlock()
                }
            }
        }
    }

    /**
     * Runs early, non-blocking spoken-language identification on a preview slice of the live microphone buffer
     * *while the user is still speaking their first sentence* (before Chunk #0 is even cut).
     * Returns a non-null language code only when `Ear` is disabled or produces a high-confidence detection,
     * allowing Chunk #0 to start `Voz` transcription in `0 ms` without waiting on `Ear`.
     */
    suspend fun tryPreDetectLanguageWhileRecording(previewSamples: FloatArray): String? = withContext(Dispatchers.Default) {
        if (!isRecordingActive || hasPendingOrActiveInference || previewSamples.isEmpty()) return@withContext null
        val activeSnap = settings
        val fallbackLanguage = if (activeSnap.preferredLanguage in Voz.supportedLanguages) {
            activeSnap.preferredLanguage
        } else {
            ModelSettings.defaultPreferredLanguage()
        }
        if (!activeSnap.earEnabled || !DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR)) {
            return@withContext fallbackLanguage
        }
        val trimmed = trimLeadingAndTrailingSilence(previewSamples, threshold = 0.0020f, padSamples = 3_200)
        if (trimmed.size < 20_800) return@withContext null // Need >= 1.3s of voiced context for confident early ID

        if (!pipelineMutex.tryLock()) return@withContext null
        try {
            if (!isRecordingActive || hasPendingOrActiveInference) return@withContext null
            val mem = evaluateMemoryPressure()
            if (mem.isCriticalPressure) return@withContext fallbackLanguage
            val earInstance = ear ?: run {
                val earDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.EAR).absolutePath
                try {
                    Ear(appContext, earDir).also { ear = it }
                } catch (_: Throwable) {
                    return@withContext null
                }
            }
            val slice = if (trimmed.size > MAX_EAR_WINDOW_SAMPLES) {
                trimmed.copyOfRange(0, MAX_EAR_WINDOW_SAMPLES)
            } else {
                trimmed
            }
            val conditioned = conditionAudioWhenClearDisabled(slice)
            val detection = earInstance.identify(
                samples = conditioned,
                sampleRate = SAMPLE_RATE_HZ,
                options = ai.desertant.ear.Options(windows = 2)
            )
            earWarmedUp = true
            val detectedLang = detection.language?.lowercase(Locale.ROOT)
            if (detection.isReliable && detectedLang != null && detectedLang in Voz.supportedLanguages) {
                DebugLogManager.logInfo(TAG, "Early live Ear pre-detected language '$detectedLang' while user was still speaking")
                detectedLang
            } else {
                null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            pipelineMutex.unlock()
        }
    }

    /**
     * Marks microphone capture as stopped so any in-progress [onRecordingStartedAsync] warmup yields
     * [pipelineMutex] immediately to final transcription, and idle auto-unload timers can arm.
     */
    fun onRecordingStopped() {
        isRecordingActive = false
        postProcOverlapJob?.cancel()
        postProcOverlapJob = null
    }

    /**
     * Optimizes background RAM & battery efficiency when the device display turns OFF or ON:
     * - Screen OFF: immediately evicts standby LLM KV-cache memory and schedules a fast 45-second deep-sleep
     *   model unload (unless `modelAutoUnloadMinutes == Never`) so NPU/GPU/CPU drivers release cleanly.
     * - Screen ON: restores the user's configured idle auto-unload window if models are still warm in RAM.
     */
    fun onScreenInteractiveChanged(interactive: Boolean) {
        isScreenInteractiveState = interactive
        if (!interactive) {
            postProcOverlapJob?.cancel()
            postProcOverlapJob = null
            promptPrewarmJob?.cancel()
            promptPrewarmJob = null
            if (!hasPendingOrActiveInference) {
                gemmaProcessor?.evictCachedSystemPrompt()
            }
        }
        if (!isRecordingActive && !hasPendingOrActiveInference) {
            idleScope.launch {
                if (pipelineMutex.tryLock()) {
                    try {
                        if (!isRecordingActive && !isTranscribing && hasAnyLoadedModelLocked()) {
                            resetIdleEvictionTimerLocked()
                        }
                    } finally {
                        pipelineMutex.unlock()
                    }
                }
            }
        }
    }

    /**
     * Finalizes a live-streaming dictation session: clears [isRecordingActive] and either unloads
     * all models immediately (when `modelAutoUnloadMinutes == 0`) or starts the configured idle timer.
     */
    suspend fun completeStreamingSession() = withContext(Dispatchers.Default) {
        isRecordingActive = false
        pipelineMutex.withLock {
            if (!isTranscribing) {
                val snap = settings
                if (snap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY) {
                    evictAllModelsLocked(snap, "immediate auto-unload after dictation")
                } else {
                    resetIdleEvictionTimerLocked()
                }
            }
        }
    }

    private fun schedulePostDictationPromptPrewarm() {
        val snap = settings
        if (!snap.gemmaEnabled || snap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY) return
        promptPrewarmJob?.cancel()
        promptPrewarmJob = idleScope.launch {
            delay(400L)
            if (isTranscribing || !pipelineMutex.tryLock()) return@launch
            try {
                if (isTranscribing) return@launch
                val activeSnap = settings
                if (activeSnap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY) return@launch
                val gemma = gemmaProcessor ?: return@launch
                if (!activeSnap.gemmaEnabled || loadedLlmChoice != activeSnap.selectedLlmModel) return@launch
                val selectedLlm = activeSnap.selectedLlmModel
                val mem = evaluateMemoryPressure()
                val minPostDictationAvailMb = if (selectedLlm == LlmModelChoice.GEMMA_4_E2B) 1_600L else 1_100L
                if (mem.canPrewarmLlmPromptCache(selectedLlm) && mem.systemAvailMb >= minPostDictationAvailMb) {
                    gemma.prewarmSystemPrompt(
                        preset = activeSnap.gemmaPromptPreset,
                        customPrompt = activeSnap.gemmaCustomPrompt,
                        customVocabulary = activeSnap.customVocabulary,
                        disableThinking = activeSnap.gemmaDisableThinking,
                        adaptiveThinking = activeSnap.gemmaAdaptiveThinking
                    )
                } else if (!mem.canPrewarmLlmPromptCache(selectedLlm)) {
                    gemma.evictCachedSystemPrompt()
                }
            } finally {
                pipelineMutex.unlock()
            }
        }
    }

    /**
     * Launches [reloadSettings] on the singleton's background scope.
     */
    fun reloadSettingsAsync(newSettings: ModelSettings) {
        settings = newSettings
        idleScope.launch {
            reloadSettings(newSettings)
        }
    }

    /**
     * Updates [settings] immediately and synchronizes loaded models & hardware accelerator under [pipelineMutex].
     *
     * Lazy RAM Saver: If all models are currently unloaded (`!hasAnyLoadedModelLocked()`), changing settings
     * keeps RAM at `0 MB` until the floating bubble is tapped (or the user explicitly taps Load in Settings).
     */
    suspend fun reloadSettings(newSettings: ModelSettings) = withContext(Dispatchers.Default) {
        settings = newSettings
        pipelineMutex.withLock {
            val snapshot = settings
            val memReport = evaluateMemoryPressure()

            if (!hasAnyLoadedModelLocked()) {
                applyHardwareAcceleratorSettingsLocked(snapshot.hardwareAccelerator, memReport.isAnyMemoryTight)
                appliedSettingsSnapshot = snapshot
                idleEvictionJob?.cancel()
                idleEvictionJob = null
                llmIdleEvictionJob?.cancel()
                llmIdleEvictionJob = null
                updateStatusSnapshot(
                    activeSettings = snapshot,
                    phaseOverride = PipelinePhase.UNINITIALIZED,
                    messageOverride = standbyStatusMessageLocked(snapshot)
                )
                return@withLock
            }

            if (!isRecordingActive && !isTranscribing &&
                snapshot.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
            ) {
                applyHardwareAcceleratorSettingsLocked(snapshot.hardwareAccelerator, memReport.isAnyMemoryTight)
                appliedSettingsSnapshot = snapshot
                evictAllModelsLocked(snapshot, "auto-unload set to Immediately")
                return@withLock
            }

            val accelChanged = appliedAccelerator != null && appliedAccelerator != snapshot.hardwareAccelerator
            val modelSelectionUnchanged = appliedSettingsSnapshot != null &&
                appliedSettingsSnapshot?.earEnabled == snapshot.earEnabled &&
                appliedSettingsSnapshot?.punctEnabled == snapshot.punctEnabled &&
                appliedSettingsSnapshot?.gemmaEnabled == snapshot.gemmaEnabled &&
                appliedSettingsSnapshot?.selectedLlmModel == snapshot.selectedLlmModel &&
                appliedSettingsSnapshot?.hardwareAccelerator == snapshot.hardwareAccelerator
            if (!accelChanged &&
                modelSelectionUnchanged &&
                voz != null &&
                areEnabledModelsReadyOrStandbyLocked(snapshot)
            ) {
                appliedSettingsSnapshot = snapshot
                val currentGemma = gemmaProcessor
                if (snapshot.gemmaEnabled && currentGemma != null && loadedLlmChoice == snapshot.selectedLlmModel) {
                    val mem = evaluateMemoryPressure()
                    if (mem.canPrewarmLlmPromptCache(snapshot.selectedLlmModel)) {
                        currentGemma.prewarmSystemPrompt(
                            preset = snapshot.gemmaPromptPreset,
                            customPrompt = snapshot.gemmaCustomPrompt,
                            customVocabulary = snapshot.customVocabulary,
                            disableThinking = snapshot.gemmaDisableThinking,
                            adaptiveThinking = snapshot.gemmaAdaptiveThinking
                        )
                    } else {
                        currentGemma.evictCachedSystemPrompt()
                    }
                }
                val count = countLoadedModelsLocked(snapshot)
                val msg = "$count/${snapshot.enabledModels().size} models ready ($activeAcceleratorDescription)"
                updateStatusSnapshot(
                    activeSettings = snapshot,
                    phaseOverride = PipelinePhase.READY,
                    messageOverride = msg
                )
                resetIdleEvictionTimerLocked()
                return@withLock
            }

            applyHardwareAcceleratorSettingsLocked(snapshot.hardwareAccelerator, memReport.isAnyMemoryTight)

            if (accelChanged) {
                Log.i(TAG, "Hardware accelerator changed to ${snapshot.hardwareAccelerator.displayName}; reloading sessions.")
                DebugLogManager.logInfo(
                    TAG,
                    "Hardware accelerator changed to ${snapshot.hardwareAccelerator.id}; closing active sessions",
                    includeMemory = true
                )
                closeEarLocked()
                closePunctLocked()
                closeGemmaLocked()
                closeVozLocked()
                System.gc()
            } else {
                if (!snapshot.earEnabled) closeEarLocked()
                if (!snapshot.punctEnabled || isActiveLlmReadyOnDisk(snapshot)) closePunctLocked()
                if (!snapshot.gemmaEnabled || (loadedLlmChoice != null && loadedLlmChoice != snapshot.selectedLlmModel)) {
                    closeGemmaLocked()
                    System.gc()
                }
            }

            updateStatusSnapshot(
                activeSettings = snapshot,
                phaseOverride = PipelinePhase.LOADING,
                messageOverride = "Updating model pipeline ($activeAcceleratorDescription)..."
            )

            val vozOk = ensureVozLocked(snapshot, warmUp = true)
            ensureAuxiliaryModelsLocked(
                activeSettings = snapshot,
                warmUp = !memReport.isAnyMemoryTight,
                preloadLlmIfCoResident = false
            )
            appliedSettingsSnapshot = snapshot
            resetIdleEvictionTimerLocked()

            val phase = if (vozOk) PipelinePhase.READY else PipelinePhase.UNINITIALIZED
            val count = countLoadedModelsLocked(snapshot)
            val msg = if (vozOk) {
                "$count/${snapshot.enabledModels().size} models ready ($activeAcceleratorDescription)"
            } else {
                standbyStatusMessageLocked(snapshot)
            }
            updateStatusSnapshot(
                activeSettings = snapshot,
                phaseOverride = phase,
                messageOverride = msg
            )
            DebugLogManager.logInfo(TAG, "reloadSettings finished • $msg", includeMemory = true)
        }
    }

    /**
     * Executes the Desert Ant audio pipeline on 16 kHz mono PCM [samples] in-memory.
     *
     * Key Stability & Anti-OOM Mechanisms:
     * 1. Trims leading/trailing dead air before touching any native buffers.
     * 2. Runs `Ear` language identification once on a short `<= 5.5s` speech slice.
     * 3. Conditions PCM audio (DC offset removal + trailing-word prosodic leveling) with zero native RAM.
     * 4. Splits long recordings at natural silence valleys into bounded chunks (`<= 20s` normal,
     *    `<= 14s` low memory) so `Voz` never exceeds JVM or native RAM limits.
     */
    suspend fun transcribe(
        samples: FloatArray,
        onStageProgress: ((String) -> Unit)? = null,
        languageHint: String? = null,
        onLanguageResolved: ((String) -> Unit)? = null,
        applyPostProcessing: Boolean = true,
        keepModelsWarmForSession: Boolean = false
    ): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""

        // Fast silence trim before acquiring native sessions (uses a whisper-sensitive 0.0020f floor and 400 ms
        // margin so soft trailing fricatives/plosives at the end of a dictation are never clipped).
        var trimmedSamplesRef: FloatArray? = trimLeadingAndTrailingSilence(
            samples = samples,
            threshold = 0.0020f,
            padSamples = 6_400
        )
        val initialTrimmed = trimmedSamplesRef
        if (initialTrimmed == null || initialTrimmed.isEmpty()) return@withContext ""

        val audioDurationMs = ((initialTrimmed.size.toDouble() / SAMPLE_RATE_HZ) * 1000.0).roundToLong()

        pendingInferenceRequests.incrementAndGet()
        try {
            pipelineMutex.withLock {
                idleEvictionJob?.cancel()
                idleEvictionJob = null
                llmIdleEvictionJob?.cancel()
                llmIdleEvictionJob = null
                promptPrewarmJob?.cancel()
                promptPrewarmJob = null
                postProcOverlapJob?.cancel()
                postProcOverlapJob = null
                isTranscribing = true
                val inferenceStartMs = SystemClock.elapsedRealtime()
            try {
                val activeSettings = settings
                val initialMem = evaluateMemoryPressure()
                applyHardwareAcceleratorSettingsLocked(
                    activeSettings.hardwareAccelerator,
                    initialMem.isAnyMemoryTight
                )

                val dynamicStageRelease = shouldStageAuxiliaryModelsDynamically(activeSettings, initialMem)
                // User-configurable chunk limit (default 20 s). With chunking switched off only the 60 s safety
                // ceiling applies; low-memory devices are always additionally capped to protect the native models.
                val chunkSettings = DictationChunkSettings.load(appContext)
                val maxChunkSamples = if (initialMem.isAnyMemoryTight) {
                    min(chunkSettings.effectiveMaxChunkSamples, LOW_MEM_MAX_CHUNK_SAMPLES)
                } else {
                    chunkSettings.effectiveMaxChunkSamples
                }
                val minChunkSamples = if (initialMem.isAnyMemoryTight) {
                    min(chunkSettings.effectiveOfflineMinChunkSamples, LOW_MEM_MIN_CHUNK_SAMPLES)
                } else {
                    chunkSettings.effectiveOfflineMinChunkSamples
                }

                val chunkRanges = computeSmartChunkRanges(
                    samples = initialTrimmed,
                    maxChunkSamples = maxChunkSamples,
                    minChunkSamples = minChunkSamples,
                    silenceThreshold = chunkSettings.silenceThreshold
                )
                val totalChunks = chunkRanges.size

                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() start • raw=${samples.size}, trimmed=${initialTrimmed.size} (${audioDurationMs}ms), " +
                        "chunks=$totalChunks, criticalStaging=$dynamicStageRelease, " +
                        "jvmFree=${initialMem.jvmFreeHeadroomMb}MB, sysAvail=${initialMem.systemAvailMb}MB",
                    includeMemory = true
                )

                if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)) {
                    throw IllegalStateException("Voz speech recognition model is not downloaded.")
                }

                // =========================================================================
                // Stage 1: Spoken Language Identification (`Ear`) on a bounded 5.5s slice
                // =========================================================================
                val firstRange = chunkRanges.first()
                val firstChunkEnd = min(firstRange.endExclusive, firstRange.start + MAX_EAR_WINDOW_SAMPLES)
                val resolvedLanguage = languageHint?.takeIf { it in Voz.supportedLanguages }
                    ?: run {
                        val earSlice = if (firstRange.start == 0 && firstChunkEnd == initialTrimmed.size) {
                            initialTrimmed
                        } else {
                            initialTrimmed.copyOfRange(firstRange.start, firstChunkEnd)
                        }
                        resolveLanguageLocked(
                            samples = earSlice,
                            activeSettings = activeSettings,
                            onStageProgress = onStageProgress
                        )
                    }
                onLanguageResolved?.invoke(resolvedLanguage)
                if (dynamicStageRelease && ear != null) {
                    DebugLogManager.logInfo(TAG, "Critical memory staging: releasing Ear session before heavy stages")
                    closeEarLocked()
                }

                // =========================================================================
                // Stage 2..3: Process each bounded audio chunk sequentially (lazy slicing)
                // =========================================================================
                val segmentTranscripts = ArrayList<String>(totalChunks)

                for ((chunkIdx, range) in chunkRanges.withIndex()) {
                    val sourceSamples = trimmedSamplesRef ?: break
                    val rawChunk = if (range.start == 0 && range.endExclusive == sourceSamples.size) {
                        sourceSamples
                    } else {
                        sourceSamples.copyOfRange(range.start, range.endExclusive)
                    }
                    if (chunkIdx == totalChunks - 1) {
                        // Drop outer reference to the full trimmed audio buffer as soon as the final chunk is sliced
                        trimmedSamplesRef = null
                    }

                    val chunkSuffix = if (totalChunks > 1) " (${chunkIdx + 1}/$totalChunks)" else ""
                    val preparedChunk = prepareChunkForInference(
                        samples = rawChunk,
                        minLeadSilencePadSamples = 5_120,
                        minTailSilencePadSamples = 10_240,
                        minTotalSamples = 25_600,
                        edgeFadeSamples = 160,
                        silenceThreshold = 0.0020f
                    )
                    val conditionedChunk = conditionAudioWhenClearDisabled(
                        samples = preparedChunk,
                        inPlace = preparedChunk !== rawChunk && preparedChunk !== samples
                    )

                    // Stage 2: Multilingual Speech-to-Text (`Voz`)
                    ensureMemoryHeadroomForFfiLocked(conditionedChunk.size)
                    if (voz == null) {
                        updateSingleModelStateLocked(
                            activeSettings = activeSettings,
                            model = DesertAntModel.VOZ,
                            state = ModelLoadState.LOADING,
                            message = "Loading Voz speech engine ($activeAcceleratorDescription)..."
                        )
                        onStageProgress?.invoke("Loading Voz...")
                        ensureVozLocked(activeSettings, warmUp = false)
                    }
                    setModelActiveStatusLocked(
                        activeSettings,
                        DesertAntModel.VOZ,
                        "Transcribing with Voz ($resolvedLanguage)$chunkSuffix..."
                    )
                    onStageProgress?.invoke("Voz ($resolvedLanguage)$chunkSuffix")
                    DebugLogManager.logMemorySnapshot("Before Voz.transcribe ($resolvedLanguage)$chunkSuffix")

                    val transcription = runVozWithRetryLocked(
                        samples = conditionedChunk,
                        language = resolvedLanguage,
                        customVocabulary = activeSettings.customVocabulary,
                        activeSettings = activeSettings
                    )

                    // Stage 3: Text Normalization & Custom Vocabulary Biasing
                    val segmentText = reconcileTranscript(
                        transcription = transcription,
                        customVocabulary = activeSettings.customVocabulary
                    )
                    if (segmentText.isNotBlank()) {
                        segmentTranscripts.add(segmentText)
                    }
                }
                trimmedSamplesRef = null

                val stitchedTranscript = stitchChunkTranscripts(
                    chunks = segmentTranscripts,
                    language = resolvedLanguage,
                    customVocabulary = activeSettings.customVocabulary
                )

                // =========================================================================
                // Stage 4..5: Post-Processing (`Punct` & Experimental `Gemma 4 E2B`)
                // =========================================================================
                val finalTranscript = if (applyPostProcessing && stitchedTranscript.isNotBlank()) {
                    runPostProcessingStagesLocked(
                        text = stitchedTranscript,
                        resolvedLanguage = resolvedLanguage,
                        activeSettings = activeSettings,
                        dynamicStageRelease = dynamicStageRelease,
                        onStageProgress = onStageProgress
                    )
                } else {
                    stitchedTranscript
                }

                val immediateUnloadNow = !keepModelsWarmForSession &&
                    !isRecordingActive &&
                    activeSettings.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY

                if (!immediateUnloadNow) {
                    // Re-attach core & auxiliary models in ready state if memory is healthy,
                    // guaranteeing Voz and the selected LLM never co-reside when free RAM cannot hold both.
                    val postMem = evaluateMemoryPressure()
                    if (voz == null && !postMem.isCriticalPressure) {
                        val activeLlm = loadedLlmChoice ?: activeSettings.selectedLlmModel
                        if (gemmaProcessor != null &&
                            !postMem.canKeepVozAndLlmCoResident(
                                llmChoice = activeLlm,
                                vozAlreadyLoaded = false,
                                llmAlreadyLoaded = true
                            )
                        ) {
                            DebugLogManager.logInfo(
                                TAG,
                                "Unloading ${activeLlm.title} before reloading Voz so low/moderate-RAM device stays OOM-safe (sysAvail=${postMem.systemAvailMb}MB)"
                            )
                            closeGemmaLocked()
                            System.gc()
                        }
                        ensureVozLocked(activeSettings, warmUp = false)
                    }
                    if (!postMem.isCriticalPressure) {
                        ensureAuxiliaryModelsLocked(
                            activeSettings = activeSettings,
                            warmUp = false,
                            preloadLlmIfCoResident = false
                        )
                    }
                    if (applyPostProcessing && activeSettings.gemmaEnabled && gemmaProcessor != null) {
                        schedulePostDictationPromptPrewarm()
                    }
                }

                val totalInferenceMs = (SystemClock.elapsedRealtime() - inferenceStartMs).coerceAtLeast(1L)
                _pipelineStatus.value = _pipelineStatus.value.copy(
                    lastInferenceMs = totalInferenceMs,
                    lastAudioDurationMs = audioDurationMs
                )
                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() completed in ${totalInferenceMs}ms for ${audioDurationMs}ms audio " +
                        "(chunks=$totalChunks, chars=${finalTranscript.length}, lang=$resolvedLanguage, postProcess=$applyPostProcessing)",
                    includeMemory = true
                )
                finalTranscript
            } catch (t: Throwable) {
                DebugLogManager.logError(
                    TAG,
                    "transcribe() failed after ${SystemClock.elapsedRealtime() - inferenceStartMs}ms",
                    t
                )
                throw t
            } finally {
                isTranscribing = false
                val snap = settings
                if (!keepModelsWarmForSession &&
                    !isRecordingActive &&
                    snap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
                ) {
                    evictAllModelsLocked(snap, "immediate auto-unload after dictation")
                } else {
                    val count = countLoadedModelsLocked(snap)
                    updateStatusSnapshot(
                        activeSettings = snap,
                        phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                        messageOverride = if (hasAnyLoadedModelLocked()) {
                            "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                        } else {
                            standbyStatusMessageLocked(snap)
                        }
                    )
                    resetIdleEvictionTimerLocked()
                }
            }
            }
        } finally {
            pendingInferenceRequests.decrementAndGet()
        }
    }

    /**
     * Pillar 3: Returns `true` when an on-device LLM (or `Punct` when no LLM is active) is enabled,
     * downloaded, and has sufficient free RAM to clean finalized prefixes alongside `Voz` during live streaming.
     */
    fun canOverlapStreamingLlmCleanup(): Boolean {
        val snap = settings
        val mem = evaluateMemoryPressure()
        if (isActiveLlmReadyOnDisk(snap)) {
            return mem.canKeepVozAndLlmCoResident(
                llmChoice = snap.selectedLlmModel,
                vozAlreadyLoaded = voz != null,
                llmAlreadyLoaded = gemmaProcessor != null && loadedLlmChoice == snap.selectedLlmModel
            )
        }
        if (snap.punctEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)) {
            return !mem.isAnyMemoryTight
        }
        return false
    }

    /**
     * Pillar 3: Cleans a finalized streaming prefix while the user continues speaking the next chunk.
     * Returns `null` immediately (without unloading `Voz`) if co-residency RAM headroom is insufficient.
     */
    suspend fun postProcessStreamingSegment(
        text: String,
        languageHint: String?,
        precedingCleanedTail: String?,
        applyLeadingCursorContinuation: Boolean
    ): String? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext text
        val snapSettings = settings
        val useLlm = isActiveLlmReadyOnDisk(snapSettings)
        val usePunct = !useLlm &&
            snapSettings.punctEnabled &&
            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
        if (!useLlm && !usePunct) return@withContext null

        pendingInferenceRequests.incrementAndGet()
        try {
            pipelineMutex.withLock {
                val activeSettings = settings
                val activeLlm = isActiveLlmReadyOnDisk(activeSettings)
                val activePunct = !activeLlm &&
                    activeSettings.punctEnabled &&
                    DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
                if (!activeLlm && !activePunct) return@withLock null

                val mem = evaluateMemoryPressure()
                if (activeLlm) {
                    val selectedLlm = activeSettings.selectedLlmModel
                    val canOverlap = mem.canKeepVozAndLlmCoResident(
                        llmChoice = selectedLlm,
                        vozAlreadyLoaded = voz != null,
                        llmAlreadyLoaded = gemmaProcessor != null && loadedLlmChoice == selectedLlm
                    )
                    if (!canOverlap) return@withLock null
                } else if (mem.isAnyMemoryTight) {
                    return@withLock null
                }

                idleEvictionJob?.cancel()
                idleEvictionJob = null
                llmIdleEvictionJob?.cancel()
                llmIdleEvictionJob = null
                promptPrewarmJob?.cancel()
                promptPrewarmJob = null
                isTranscribing = true
                try {
                    val resolvedLanguage = languageHint?.takeIf { it in Voz.supportedLanguages }
                        ?: activeSettings.preferredLanguage
                    if (activeLlm) {
                        val (cleaned, succeeded) = runGemmaLocked(
                            text = text,
                            language = resolvedLanguage,
                            activeSettings = activeSettings,
                            dynamicStageRelease = false,
                            onStageProgress = null,
                            precedingCleanedTail = precedingCleanedTail,
                            applyLeadingCursorContinuation = applyLeadingCursorContinuation,
                            keepWarmForStreamingOverlap = true
                        )
                        if (succeeded) cleaned else null
                    } else {
                        runPunctLocked(
                            text = text,
                            language = resolvedLanguage,
                            activeSettings = activeSettings,
                            onStageProgress = null
                        )
                    }
                } finally {
                    isTranscribing = false
                    val snap = settings
                    val count = countLoadedModelsLocked(snap)
                    updateStatusSnapshot(
                        activeSettings = snap,
                        phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                        messageOverride = "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                    )
                    resetIdleEvictionTimerLocked()
                }
            }
        } finally {
            pendingInferenceRequests.decrementAndGet()
        }
    }

    /**
     * Runs single-model AI post-processing (or `Punct` if no LLM is active) once over the
     * complete stitched transcript (or remaining uncleaned tail) at the end of a live-streaming dictation session.
     */
    suspend fun postProcessFinalTranscript(
        text: String,
        languageHint: String? = null,
        onStageProgress: ((String) -> Unit)? = null,
        precedingCleanedTail: String? = null,
        applyLeadingCursorContinuation: Boolean = true
    ): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext text
        val snapSettings = settings
        val needsPunct = snapSettings.punctEnabled &&
            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
        val needsGemma = isActiveLlmReadyOnDisk(snapSettings)
        if (!needsPunct && !needsGemma) return@withContext text

        pendingInferenceRequests.incrementAndGet()
        try {
            pipelineMutex.withLock {
                idleEvictionJob?.cancel()
                idleEvictionJob = null
                llmIdleEvictionJob?.cancel()
                llmIdleEvictionJob = null
                promptPrewarmJob?.cancel()
                promptPrewarmJob = null
                isTranscribing = true
                try {
                    val activeSettings = settings
                    val resolvedLanguage = languageHint?.takeIf { it in Voz.supportedLanguages }
                        ?: activeSettings.preferredLanguage
                    val mem = evaluateMemoryPressure()
                    val result = runPostProcessingStagesLocked(
                        text = text,
                        resolvedLanguage = resolvedLanguage,
                        activeSettings = activeSettings,
                        dynamicStageRelease = shouldStageAuxiliaryModelsDynamically(activeSettings, mem),
                        onStageProgress = onStageProgress,
                        precedingCleanedTail = precedingCleanedTail,
                        applyLeadingCursorContinuation = applyLeadingCursorContinuation
                    )
                    val immediateUnloadNow = !isRecordingActive &&
                        activeSettings.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
                    if (!immediateUnloadNow) {
                        val postMem = evaluateMemoryPressure()
                        if (voz == null && !postMem.isCriticalPressure) {
                            val activeLlm = loadedLlmChoice ?: activeSettings.selectedLlmModel
                            if (gemmaProcessor != null &&
                                !postMem.canKeepVozAndLlmCoResident(
                                    llmChoice = activeLlm,
                                    vozAlreadyLoaded = false,
                                    llmAlreadyLoaded = true
                                )
                            ) {
                                DebugLogManager.logInfo(
                                    TAG,
                                    "Unloading ${activeLlm.title} before reloading Voz after streaming session (sysAvail=${postMem.systemAvailMb}MB)"
                                )
                                closeGemmaLocked()
                                System.gc()
                            }
                            ensureVozLocked(activeSettings, warmUp = false)
                        }
                        if (activeSettings.gemmaEnabled && gemmaProcessor != null) {
                            schedulePostDictationPromptPrewarm()
                        }
                    }
                    result
                } finally {
                    isTranscribing = false
                    val snap = settings
                    if (!isRecordingActive &&
                        snap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
                    ) {
                        evictAllModelsLocked(snap, "immediate auto-unload after streaming session")
                    } else {
                        val count = countLoadedModelsLocked(snap)
                        updateStatusSnapshot(
                            activeSettings = snap,
                            phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                            messageOverride = if (hasAnyLoadedModelLocked()) {
                                "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                            } else {
                                standbyStatusMessageLocked(snap)
                            }
                        )
                        resetIdleEvictionTimerLocked()
                    }
                }
            }
        } finally {
            pendingInferenceRequests.decrementAndGet()
        }
    }

    /**
     * Runs an on-demand speed & quality benchmark on the currently selected [LlmModelChoice]
     * (comparing cold/uncached system prompt vs warm KV-cached system prompt in tokens/sec)
     * while enforcing strict single-LLM RAM bounds and keeping the screen awake.
     */
    suspend fun runLlmBenchmark(
        inputText: String,
        onProgress: ((String) -> Unit)? = null
    ): LlmBenchmarkResult = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            val activeSettings = settings
            val selectedLlm = activeSettings.selectedLlmModel
            if (!DesertAntModelStorage.isModelDownloaded(appContext, selectedLlm.desertAntModel)) {
                throw IllegalStateException("${selectedLlm.title} is not downloaded yet.")
            }

            idleEvictionJob?.cancel()
            idleEvictionJob = null
            llmIdleEvictionJob?.cancel()
            llmIdleEvictionJob = null
            promptPrewarmJob?.cancel()
            promptPrewarmJob = null
            isTranscribing = true

            try {
                // Strictly 1 LLM loaded at a time
                if (gemmaProcessor != null && loadedLlmChoice != selectedLlm) {
                    closeGemmaLocked()
                    System.gc()
                }
                if (punctRestorer != null && activeSettings.gemmaEnabled) {
                    closePunctLocked()
                }

                if (gemmaProcessor == null) {
                    val preLoadMem = evaluateMemoryPressure()
                    val earReleaseThresholdMb = selectedLlm.estimatedStandaloneRamMb.toLong() + 650L
                    if (preLoadMem.isAnyMemoryTight || preLoadMem.systemAvailMb < earReleaseThresholdMb) {
                        closeEarLocked()
                    }
                    val memAfterAux = evaluateMemoryPressure()
                    if (voz != null &&
                        !memAfterAux.canKeepVozAndLlmCoResident(
                            llmChoice = selectedLlm,
                            vozAlreadyLoaded = true,
                            llmAlreadyLoaded = false
                        )
                    ) {
                        DebugLogManager.logInfo(
                            TAG,
                            "Temporarily staging Voz out of RAM to safely benchmark ${selectedLlm.title} (sysAvail=${memAfterAux.systemAvailMb}MB)",
                            includeMemory = true
                        )
                        closeVozLocked()
                        System.gc()
                    }
                }

                val instance = gemmaProcessor ?: run {
                    val modelDir = DesertAntModelStorage.modelRevisionDir(appContext, selectedLlm.desertAntModel)
                    val memNow = evaluateMemoryPressure()
                    onProgress?.invoke("Loading ${selectedLlm.title} into RAM...")
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = selectedLlm.desertAntModel,
                        state = ModelLoadState.LOADING,
                        message = "Loading ${selectedLlm.title} for benchmark..."
                    )
                    GemmaTextProcessor(
                        context = appContext,
                        modelDir = modelDir,
                        accelerator = activeSettings.hardwareAccelerator,
                        memoryMode = memNow.gemmaMemoryMode,
                        llmChoice = selectedLlm
                    ).also {
                        gemmaProcessor = it
                        loadedLlmChoice = selectedLlm
                    }
                }

                setModelActiveStatusLocked(
                    activeSettings,
                    selectedLlm.desertAntModel,
                    "Benchmarking ${selectedLlm.title} (${instance.activeBackendSummary})..."
                )

                val benchResult = instance.benchmarkPromptCaching(
                    rawInput = inputText,
                    languageCode = activeSettings.preferredLanguage,
                    preset = activeSettings.gemmaPromptPreset,
                    customPrompt = activeSettings.gemmaCustomPrompt,
                    customVocabulary = activeSettings.customVocabulary,
                    disableThinking = activeSettings.gemmaDisableThinking,
                    adaptiveThinking = activeSettings.gemmaAdaptiveThinking,
                    onProgress = onProgress
                )

                val immediateUnloadNow = !isRecordingActive &&
                    activeSettings.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
                if (!immediateUnloadNow) {
                    val postMem = evaluateMemoryPressure()
                    val cannotKeepWithVoz = !postMem.canKeepVozAndLlmCoResident(
                        llmChoice = selectedLlm,
                        vozAlreadyLoaded = voz != null,
                        llmAlreadyLoaded = true
                    )
                    val minPostPassAvailMb = if (selectedLlm == LlmModelChoice.GEMMA_4_E2B) 950L else 700L
                    if (!activeSettings.gemmaEnabled || postMem.systemAvailMb < minPostPassAvailMb || cannotKeepWithVoz) {
                        closeGemmaLocked()
                        System.gc()
                    }
                    if (activeSettings.gemmaEnabled && gemmaProcessor != null) {
                        schedulePostDictationPromptPrewarm()
                    }
                }

                benchResult
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                closeGemmaLocked()
                throw t
            } finally {
                isTranscribing = false
                val snap = settings
                if (!isRecordingActive &&
                    snap.modelAutoUnloadMinutes == ModelSettings.AUTO_UNLOAD_IMMEDIATELY
                ) {
                    evictAllModelsLocked(snap, "immediate auto-unload after benchmark")
                } else {
                    val count = countLoadedModelsLocked(snap)
                    updateStatusSnapshot(
                        activeSettings = snap,
                        phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                        messageOverride = if (hasAnyLoadedModelLocked()) {
                            "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                        } else {
                            standbyStatusMessageLocked(snap)
                        }
                    )
                    resetIdleEvictionTimerLocked()
                }
            }
        }
    }

    /**
     * Unloads ALL models (`Voz`, `Ear`, `Punct`, and active `LiteRT-LM` LLM + KV cache) down to `0 MB`
     * in RAM when idle. Models stay unloaded until the floating bubble is tapped (or manually loaded).
     */
    suspend fun evictAllModels(reason: String = "idle timeout") = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            if (!isTranscribing && !isRecordingActive && hasAnyLoadedModelLocked()) {
                evictAllModelsLocked(settings, reason)
            }
        }
    }

    /**
     * Asynchronous fire-and-forget wrapper around [evictAllModels] for UI actions.
     */
    fun evictAllModelsAsync(reason: String = "manual unload") {
        idleScope.launch {
            evictAllModels(reason)
        }
    }

    /**
     * Evicts an idle on-demand LLM after [LLM_IDLE_TIMEOUT_MS] so RAM returns to baseline between dictation bursts.
     */
    private suspend fun evictIdleLlm() = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            if (!isTranscribing && gemmaProcessor != null) {
                val evictedName = loadedLlmChoice?.title ?: "LLM"
                Log.i(TAG, "Evicting idle $evictedName after 5m inactivity; keeping Voz warm.")
                DebugLogManager.logInfo(TAG, "Evicting idle $evictedName from RAM (on-demand standby)", includeMemory = true)
                closeGemmaLocked()
                System.gc()
                val snap = settings
                val count = countLoadedModelsLocked(snap)
                updateStatusSnapshot(
                    activeSettings = snap,
                    phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                    messageOverride = "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                )
            }
        }
    }

    /**
     * Evicts optional models (`Ear`, `Punct`, active LLM) from native RAM while keeping `Voz` resident.
     */
    suspend fun evictOptionalModels() = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            if (!isTranscribing) {
                llmIdleEvictionJob?.cancel()
                llmIdleEvictionJob = null
                Log.i(TAG, "Evicting optional models (Ear, Punct, LLM) to free native RAM; keeping Voz resident.")
                DebugLogManager.logInfo(TAG, "Evicting optional models (Ear, Punct, LLM) from native RAM", includeMemory = true)
                closeEarLocked()
                closePunctLocked()
                closeGemmaLocked()
                System.gc()
                val snap = settings
                updateStatusSnapshot(
                    activeSettings = snap,
                    phaseOverride = if (voz != null) PipelinePhase.STANDBY_EVICTED else PipelinePhase.UNINITIALIZED,
                    messageOverride = "Voz loaded in RAM • Optional models on dynamic standby"
                )
            }
        }
    }

    /**
     * Multi-tier Android OS memory pressure handler:
     * - Moderate / UI-hidden pressure: immediately evicts the LLM's standby system-prompt KV cache
     *   without unloading model weights.
     * - Running-low / background pressure: evicts optional native models (`Ear`, `Punct`, active LLM).
     * - Critical pressure with critically low physical RAM: also unloads `Voz` when idle so LMKD never kills
     *   the foreground dictation service (`Voz` reloads transparently on the next tap).
     */
    fun onTrimMemory(level: Int) {
        DebugLogManager.logTrimMemory("SmartPipeline", level)
        if (!hasPendingOrActiveInference) {
            gemmaProcessor?.evictCachedSystemPrompt()
        }
        val action = classifyTrimMemoryAction(level)
        if (action == TrimMemoryAction.DROP_KV_CACHE_ONLY || hasPendingOrActiveInference || isRecordingActive) {
            return
        }

        if (action == TrimMemoryAction.SEVERE_EVICT) {
            idleScope.launch {
                pipelineMutex.withLock {
                    if (!isTranscribing && !isRecordingActive) {
                        llmIdleEvictionJob?.cancel()
                        llmIdleEvictionJob = null
                        closeEarLocked()
                        closePunctLocked()
                        closeGemmaLocked()
                        @Suppress("DEPRECATION")
                        val unloadVozToo = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                            evaluateMemoryPressure().isSystemRamCritical
                        if (unloadVozToo) {
                            DebugLogManager.logWarn(
                                TAG,
                                "Severe OS memory trim ($level); unloading Voz to prevent LMKD kill"
                            )
                            closeVozLocked()
                        }
                        System.gc()
                        val snap = settings
                        updateStatusSnapshot(
                            activeSettings = snap,
                            phaseOverride = if (voz != null) PipelinePhase.STANDBY_EVICTED else PipelinePhase.UNINITIALIZED,
                            messageOverride = if (voz != null) {
                                "Voz loaded in RAM • Optional models on dynamic standby"
                            } else {
                                standbyStatusMessageLocked(snap)
                            }
                        )
                    }
                }
            }
        } else {
            idleScope.launch {
                evictOptionalModels()
            }
        }
    }

    /**
     * Releases all native LiteRT/ONNX/LiteRT-LM sessions (`Ear`, `Punct`, active LLM, and `Voz`).
     */
    suspend fun shutdown() = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            idleEvictionJob?.cancel()
            idleEvictionJob = null
            llmIdleEvictionJob?.cancel()
            llmIdleEvictionJob = null
            promptPrewarmJob?.cancel()
            promptPrewarmJob = null
            closeEarLocked()
            closePunctLocked()
            closeGemmaLocked()
            closeVozLocked()
            System.gc()
            val snap = settings
            updateStatusSnapshot(
                activeSettings = snap,
                phaseOverride = PipelinePhase.UNINITIALIZED,
                messageOverride = "Models unloaded from RAM"
            )
        }
    }

    // =========================================================================
    // Internal Stage, Memory & Hardware Helpers (Called inside `pipelineMutex`)
    // =========================================================================

    private fun applyHardwareAcceleratorSettingsLocked(
        accelerator: HardwareAccelerator,
        lowMemoryPressure: Boolean
    ) {
        if (appliedAccelerator == accelerator &&
            appliedLowMemoryMode == lowMemoryPressure &&
            activeAcceleratorDescription.isNotEmpty()
        ) {
            return
        }
        activeAcceleratorDescription = NpuHardwareManager.configureNativeRuntime(
            context = appContext,
            requested = accelerator,
            lowMemoryPressure = lowMemoryPressure
        )
        appliedAccelerator = accelerator
        appliedLowMemoryMode = lowMemoryPressure
    }

    private fun evaluateMemoryPressure(): MemoryPressureReport {
        val rt = Runtime.getRuntime()
        val jvmUsedBytes = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        val jvmMaxBytes = rt.maxMemory().coerceAtLeast(1L)
        val jvmFreeHeadroomBytes = (jvmMaxBytes - jvmUsedBytes).coerceAtLeast(0L)
        val nativeAllocatedBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

        var sysAvailMb = 2_048L
        var sysTotalMb = 4_096L
        var sysLowFlag = false
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                sysAvailMb = memInfo.availMem / (1024L * 1024L)
                sysTotalMb = memInfo.totalMem / (1024L * 1024L)
                sysLowFlag = memInfo.lowMemory
            }
        } catch (_: Throwable) {
        }

        return MemoryPressureReport(
            systemAvailMb = sysAvailMb,
            systemTotalMb = sysTotalMb,
            systemLowMemoryFlag = sysLowFlag,
            jvmUsedMb = jvmUsedBytes / (1024L * 1024L),
            jvmMaxMb = jvmMaxBytes / (1024L * 1024L),
            jvmFreeHeadroomMb = jvmFreeHeadroomBytes / (1024L * 1024L),
            nativeAllocatedMb = nativeAllocatedBytes / (1024L * 1024L)
        )
    }

    private fun shouldStageAuxiliaryModelsDynamically(
        @Suppress("UNUSED_PARAMETER") activeSettings: ModelSettings,
        mem: MemoryPressureReport
    ): Boolean {
        // Never destroy/recreate LiteRT/EGL environments mid-pipeline during normal operation;
        // only release auxiliary sessions mid-transcription if the OS or JVM heap is in critical distress.
        return mem.isCriticalPressure
    }

    /**
     * Proactively reclaims JVM heap garbage before `FfiWriter.floats()` allocates its
     * `ByteArrayOutputStream` + `toByteArray()` buffers (~8-12 bytes per audio sample).
     * Rate-limited so sequential live chunks do not trigger repeated stop-the-world GC pauses.
     */
    private fun ensureMemoryHeadroomForFfiLocked(sampleCount: Int) {
        val estimatedFfiMegabytes = ((sampleCount.toLong() * 12L) / (1024L * 1024L)).coerceAtLeast(4L)
        val report = evaluateMemoryPressure()
        if (report.isJvmHeapTight || report.jvmFreeHeadroomMb < estimatedFfiMegabytes * 3L) {
            val now = SystemClock.elapsedRealtime()
            if (report.isJvmHeapCritical || now - lastForcedGcAtMs >= 1_500L) {
                lastForcedGcAtMs = now
                System.gc()
                System.runFinalization()
            }
        }
    }

    private fun isActiveLlmReadyOnDisk(activeSettings: ModelSettings): Boolean {
        return activeSettings.gemmaEnabled &&
            DesertAntModelStorage.isModelDownloaded(appContext, activeSettings.selectedLlmModel.desertAntModel)
    }

    private fun areEnabledModelsReadyOrStandbyLocked(activeSettings: ModelSettings): Boolean {
        if (voz == null) return false
        if (!activeSettings.gemmaEnabled && gemmaProcessor != null) return false
        if (activeSettings.gemmaEnabled && loadedLlmChoice != null && loadedLlmChoice != activeSettings.selectedLlmModel) {
            return false
        }
        val activeLlmOnDisk = isActiveLlmReadyOnDisk(activeSettings)
        if (activeLlmOnDisk && punctRestorer != null) return false

        val mem = evaluateMemoryPressure()
        if (!mem.isCriticalPressure) {
            if (activeSettings.earEnabled &&
                DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR) &&
                ear == null
            ) {
                return false
            }
            if (activeSettings.punctEnabled &&
                !activeLlmOnDisk &&
                DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT) &&
                punctRestorer == null
            ) {
                return false
            }
        }
        return true
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
        // Very short utterances (< 0.9s) do not have enough acoustic context for reliable language ID
        if (samples.size < 14_400) {
            return fallbackLanguage
        }
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR)) {
            return fallbackLanguage
        }

        val mem = evaluateMemoryPressure()
        if (mem.isCriticalPressure) {
            DebugLogManager.logWarn(TAG, "Skipping Ear language ID due to critical memory pressure; using $fallbackLanguage")
            closeEarLocked()
            return fallbackLanguage
        }

        val earInstance = ear ?: run {
            val earDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.EAR).absolutePath
            try {
                Ear(appContext, earDir).also { ear = it }
            } catch (t: Throwable) {
                DebugLogManager.logWarn(TAG, "Failed to dynamically load Ear: ${t.message}")
                return fallbackLanguage
            }
        }

        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.EAR, "Detecting language with Ear...")
            onStageProgress?.invoke("Ear")
            val rawEarInput = if (samples.size > MAX_EAR_WINDOW_SAMPLES) {
                samples.copyOfRange(0, MAX_EAR_WINDOW_SAMPLES)
            } else {
                samples
            }
            val earInput = conditionAudioWhenClearDisabled(rawEarInput)
            val detection = earInstance.identify(
                samples = earInput,
                sampleRate = SAMPLE_RATE_HZ,
                options = ai.desertant.ear.Options(windows = 2)
            )
            earWarmedUp = true
            val detectedLang = detection.language?.lowercase(Locale.ROOT)
            if (detection.isReliable && detectedLang != null && detectedLang in Voz.supportedLanguages) {
                detectedLang
            } else {
                fallbackLanguage
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Ear language detection failed, falling back to $fallbackLanguage: ${t.message}")
            DebugLogManager.logWarn(TAG, "Ear language detection failed, falling back to $fallbackLanguage", t)
            closeEarLocked()
            System.gc()
            fallbackLanguage
        }
    }

    private suspend fun runVozWithRetryLocked(
        samples: FloatArray,
        language: String,
        customVocabulary: List<String>,
        activeSettings: ModelSettings
    ): Transcription {
        val firstVoz = voz ?: run {
            ensureVozLocked(activeSettings, warmUp = false)
            voz
        } ?: throw IllegalStateException("Voz speech engine could not be initialized.")

        return try {
            firstVoz.transcribeWithContext(
                samples = samples,
                sampleRate = SAMPLE_RATE_HZ,
                language = language,
                customVocabulary = customVocabulary
            ).also {
                vozWarmedUp = true
            }
        } catch (firstError: Throwable) {
            // A cancelled dictation must never trigger the expensive "shed all sessions + reload Voz" recovery path.
            if (firstError is CancellationException) throw firstError
            Log.w(TAG, "Voz inference failed on first attempt (${firstError.message}); freeing auxiliary RAM and retrying.")
            DebugLogManager.logWarn(
                TAG,
                "Voz inference failed on first attempt; shedding auxiliary sessions, switching to safe memory mode, and retrying",
                firstError
            )
            // Free all optional native sessions & compact JVM heap before retrying Voz
            closeEarLocked()
            closePunctLocked()
            closeGemmaLocked()
            closeVozLocked()
            System.gc()
            System.runFinalization()

            // Re-configure native runtime with conservative thread count / low-memory safe flags
            applyHardwareAcceleratorSettingsLocked(activeSettings.hardwareAccelerator, lowMemoryPressure = true)

            if (!ensureVozLocked(activeSettings, warmUp = false)) {
                throw IllegalStateException("Transcription failed after re-initialization: ${firstError.message}", firstError)
            }
            val retryVoz = voz ?: throw IllegalStateException("Voz unavailable on retry.", firstError)

            // If the chunk is > 10s, split it at the best silence valley on retry to halve peak native & FFI memory
            if (samples.size > 10 * 16_000) {
                val mid = findBestSplitPoint(
                    samples = samples,
                    rangeStart = samples.size / 3,
                    rangeEndExclusive = (samples.size * 2) / 3
                ).coerceIn(samples.size / 3, (samples.size * 2) / 3)
                val left = prepareChunkForInference(
                    samples = samples.copyOfRange(0, mid),
                    minLeadSilencePadSamples = 5_120,
                    minTailSilencePadSamples = 10_240,
                    minTotalSamples = 25_600
                )
                val right = prepareChunkForInference(
                    samples = samples.copyOfRange(mid, samples.size),
                    minLeadSilencePadSamples = 5_120,
                    minTailSilencePadSamples = 10_240,
                    minTotalSamples = 25_600
                )
                val t1 = retryVoz.transcribeWithContext(left, SAMPLE_RATE_HZ, language, customVocabulary)
                System.gc()
                val t2 = retryVoz.transcribeWithContext(right, SAMPLE_RATE_HZ, language, customVocabulary)
                val leftDurationSec = left.size.toDouble() / SAMPLE_RATE_HZ
                val shiftedRightWords = t2.words.map { w ->
                    Word(w.text, w.start + leftDurationSec, w.end + leftDurationSec)
                }
                vozWarmedUp = true
                Transcription(
                    text = stitchChunkTranscripts(
                        chunks = listOf(t1.text, t2.text),
                        language = language,
                        customVocabulary = customVocabulary
                    ),
                    words = t1.words + shiftedRightWords,
                    duration = t1.duration + t2.duration
                )
            } else {
                retryVoz.transcribeWithContext(
                    samples = samples,
                    sampleRate = SAMPLE_RATE_HZ,
                    language = language,
                    customVocabulary = customVocabulary
                ).also {
                    vozWarmedUp = true
                }
            }
        }
    }

    private suspend fun ensureVozLocked(activeSettings: ModelSettings, warmUp: Boolean): Boolean {
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)) {
            closeVozLocked()
            return false
        }

        if (voz == null) {
            try {
                val mem = evaluateMemoryPressure()
                if (mem.isJvmHeapTight) {
                    System.gc()
                }
                updateSingleModelStateLocked(
                    activeSettings = activeSettings,
                    model = DesertAntModel.VOZ,
                    state = ModelLoadState.LOADING,
                    message = "Loading Voz speech recognition model..."
                )
                DebugLogManager.logInfo(TAG, "Loading Voz native model into RAM...", includeMemory = true)
                val vozDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.VOZ).absolutePath
                voz = Voz(appContext, vozDir)
                vozWarmedUp = false
                DebugLogManager.logInfo(TAG, "Voz native model loaded into RAM", includeMemory = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize Voz: ${t.message}", t)
                DebugLogManager.logError(TAG, "Failed to initialize Voz native model", t)
                closeVozLocked()
                return false
            }
        }

        val currentVoz = voz ?: return false
        if (warmUp && !vozWarmedUp) {
            try {
                updateSingleModelStateLocked(
                    activeSettings = activeSettings,
                    model = DesertAntModel.VOZ,
                    state = ModelLoadState.LOADING,
                    message = "Warming up Voz on $activeAcceleratorDescription..."
                )
                val warmupSilence = FloatArray(1_600) // Fast 100 ms silent frame to compile accelerator kernels
                currentVoz.transcribe(samples = warmupSilence, sampleRate = SAMPLE_RATE_HZ)
                vozWarmedUp = true
                DebugLogManager.logInfo(TAG, "Voz accelerator warm-up complete", includeMemory = true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "Voz warm-up deferred to first dictation: ${t.message}")
                DebugLogManager.logWarn(TAG, "Voz warm-up deferred to first dictation", t)
            }
        }
        return true
    }

    private suspend fun ensureAuxiliaryModelsLocked(
        activeSettings: ModelSettings,
        warmUp: Boolean,
        preloadLlmIfCoResident: Boolean = false
    ) {
        val mem = evaluateMemoryPressure()
        val activeLlmOnDisk = isActiveLlmReadyOnDisk(activeSettings)

        if (mem.isCriticalPressure) {
            DebugLogManager.logInfo(
                TAG,
                "Critical memory pressure detected (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB); auxiliary models will stage on-demand"
            )
        }

        // Ear
        if (activeSettings.earEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR)) {
            if (ear == null && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.EAR,
                        state = ModelLoadState.LOADING,
                        message = "Loading Ear language detector..."
                    )
                    val earDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.EAR).absolutePath
                    ear = Ear(appContext, earDir)
                    earWarmedUp = false
                    DebugLogManager.logInfo(TAG, "Ear model loaded into RAM", includeMemory = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not initialize Ear: ${t.message}")
                    DebugLogManager.logError(TAG, "Could not initialize Ear model", t)
                    closeEarLocked()
                }
            }
            val currentEar = ear
            if (warmUp && currentEar != null && !earWarmedUp && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.EAR,
                        state = ModelLoadState.LOADING,
                        message = "Warming up Ear language detector..."
                    )
                    val warmupSilence = FloatArray(3_200)
                    currentEar.identify(
                        samples = warmupSilence,
                        sampleRate = SAMPLE_RATE_HZ,
                        options = ai.desertant.ear.Options(windows = 1)
                    )
                    earWarmedUp = true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Ear warm-up deferred: ${t.message}")
                    DebugLogManager.logWarn(TAG, "Ear warm-up deferred", t)
                }
            }
        } else if (!activeSettings.earEnabled) {
            closeEarLocked()
        }

        // Punct (Neural Punctuation & Casing Restoration on NPU/XNNPACK)
        // Never keep Punct loaded in RAM when an on-device LLM is enabled & downloaded (LLM handles punctuation in one pass)
        if (activeLlmOnDisk) {
            if (punctRestorer != null) {
                DebugLogManager.logInfo(TAG, "Unloading Punct because ${activeSettings.selectedLlmModel.title} handles punctuation & formatting")
                closePunctLocked()
            }
        } else if (activeSettings.punctEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)) {
            if (punctRestorer == null && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.PUNCT,
                        state = ModelLoadState.LOADING,
                        message = "Loading Punct text cleanup model on NPU..."
                    )
                    val punctDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.PUNCT)
                    punctRestorer = PunctuationRestorer(
                        context = appContext,
                        modelDir = punctDir,
                        accelerator = activeSettings.hardwareAccelerator,
                        lowMemoryPressure = mem.isAnyMemoryTight
                    )
                    punctWarmedUp = false
                    DebugLogManager.logInfo(TAG, "Punct model loaded into RAM", includeMemory = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not initialize Punct: ${t.message}")
                    DebugLogManager.logError(TAG, "Could not initialize Punct model", t)
                    closePunctLocked()
                }
            }
            val currentPunct = punctRestorer
            if (warmUp && currentPunct != null && !punctWarmedUp && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.PUNCT,
                        state = ModelLoadState.LOADING,
                        message = "Warming up Punct on NPU..."
                    )
                    currentPunct.warmUp()
                    punctWarmedUp = true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Punct warm-up deferred: ${t.message}")
                    DebugLogManager.logWarn(TAG, "Punct warm-up deferred", t)
                }
            }
        } else if (!activeSettings.punctEnabled) {
            closePunctLocked()
        }

        // Single Selected On-Device LLM (LiteRT-LM on NPU / GPU / CPU)
        // Strict single-model rule: immediately unload any previously loaded LLM before considering the selected one.
        val selectedLlm = activeSettings.selectedLlmModel
        if (!activeSettings.gemmaEnabled || (loadedLlmChoice != null && loadedLlmChoice != selectedLlm)) {
            closeGemmaLocked()
        }

        if (activeLlmOnDisk) {
            val llmMem = evaluateMemoryPressure()
            val canKeepCoResident = llmMem.canKeepVozAndLlmCoResident(
                llmChoice = selectedLlm,
                vozAlreadyLoaded = voz != null,
                llmAlreadyLoaded = gemmaProcessor != null
            )
            if (gemmaProcessor == null && preloadLlmIfCoResident && canKeepCoResident) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = selectedLlm.desertAntModel,
                        state = ModelLoadState.LOADING,
                        message = "Loading ${selectedLlm.title} on LiteRT-LM..."
                    )
                    val modelDir = DesertAntModelStorage.modelRevisionDir(appContext, selectedLlm.desertAntModel)
                    gemmaProcessor = GemmaTextProcessor(
                        context = appContext,
                        modelDir = modelDir,
                        accelerator = activeSettings.hardwareAccelerator,
                        memoryMode = llmMem.gemmaMemoryMode,
                        llmChoice = selectedLlm
                    )
                    loadedLlmChoice = selectedLlm
                    gemmaWarmedUp = false
                    DebugLogManager.logInfo(
                        TAG,
                        "${selectedLlm.title} loaded into RAM (${gemmaProcessor?.activeBackendSummary}, memMode=${llmMem.gemmaMemoryMode})",
                        includeMemory = true
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not pre-load ${selectedLlm.title} (will retry dynamically): ${t.message}")
                    DebugLogManager.logError(TAG, "Could not pre-load ${selectedLlm.title}", t)
                    closeGemmaLocked()
                }
            } else if (gemmaProcessor != null && !canKeepCoResident) {
                DebugLogManager.logInfo(
                    TAG,
                    "Releasing co-resident ${selectedLlm.title} to preserve Voz RAM headroom (sysAvail=${llmMem.systemAvailMb}MB)"
                )
                closeGemmaLocked()
            } else if (gemmaProcessor == null) {
                DebugLogManager.logInfo(
                    TAG,
                    "Keeping ${selectedLlm.title} on zero-RAM standby until recording starts (sysAvail=${llmMem.systemAvailMb}MB, total=${llmMem.systemTotalMb}MB)"
                )
            }

            val currentGemma = gemmaProcessor
            if (warmUp && currentGemma != null && !gemmaWarmedUp && !llmMem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = selectedLlm.desertAntModel,
                        state = ModelLoadState.LOADING,
                        message = "Warming up ${selectedLlm.title} & caching system prompt (${currentGemma.activeBackendSummary})..."
                    )
                    currentGemma.warmUp(
                        preset = activeSettings.gemmaPromptPreset,
                        customPrompt = activeSettings.gemmaCustomPrompt,
                        customVocabulary = activeSettings.customVocabulary,
                        disableThinking = activeSettings.gemmaDisableThinking,
                        adaptiveThinking = activeSettings.gemmaAdaptiveThinking
                    )
                    gemmaWarmedUp = true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "${selectedLlm.title} warm-up deferred: ${t.message}")
                    DebugLogManager.logWarn(TAG, "${selectedLlm.title} warm-up deferred", t)
                }
            }
        }
    }

    private suspend fun runPostProcessingStagesLocked(
        text: String,
        resolvedLanguage: String,
        activeSettings: ModelSettings,
        dynamicStageRelease: Boolean,
        onStageProgress: ((String) -> Unit)?,
        precedingCleanedTail: String? = null,
        applyLeadingCursorContinuation: Boolean = true
    ): String {
        if (text.isBlank()) return text
        var current = text
        val activeLlmOnDisk = isActiveLlmReadyOnDisk(activeSettings)

        // When the selected LLM is enabled & downloaded, never load or run Punct alongside it
        // (the LLM restores punctuation, casing, numbers, and formatting in a single pass).
        if (activeLlmOnDisk) {
            if (punctRestorer != null) {
                closePunctLocked()
            }
            val (llmResult, llmSucceeded) = runGemmaLocked(
                text = current,
                language = resolvedLanguage,
                activeSettings = activeSettings,
                dynamicStageRelease = dynamicStageRelease,
                onStageProgress = onStageProgress,
                precedingCleanedTail = precedingCleanedTail,
                applyLeadingCursorContinuation = applyLeadingCursorContinuation,
                keepWarmForStreamingOverlap = false
            )
            current = llmResult
            if (!llmSucceeded &&
                activeSettings.punctEnabled &&
                DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
            ) {
                current = runPunctLocked(
                    text = current,
                    language = resolvedLanguage,
                    activeSettings = activeSettings,
                    onStageProgress = onStageProgress
                )
                if (dynamicStageRelease && punctRestorer != null) {
                    closePunctLocked()
                }
            }
            return current
        }

        // Stage 4: Neural Punctuation & Casing Restoration (`Punct` — only when no LLM is active)
        if (activeSettings.punctEnabled &&
            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.PUNCT)
        ) {
            current = runPunctLocked(
                text = current,
                language = resolvedLanguage,
                activeSettings = activeSettings,
                onStageProgress = onStageProgress
            )
            if (dynamicStageRelease && punctRestorer != null) {
                closePunctLocked()
            }
        }

        return current
    }

    private fun runPunctLocked(
        text: String,
        language: String,
        activeSettings: ModelSettings,
        onStageProgress: ((String) -> Unit)?
    ): String {
        val mem = evaluateMemoryPressure()
        val instance = punctRestorer ?: run {
            val punctDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.PUNCT)
            try {
                PunctuationRestorer(
                    context = appContext,
                    modelDir = punctDir,
                    accelerator = activeSettings.hardwareAccelerator,
                    lowMemoryPressure = mem.isAnyMemoryTight
                ).also { punctRestorer = it }
            } catch (t: Throwable) {
                DebugLogManager.logWarn(TAG, "Failed to dynamically load Punct: ${t.message}")
                return text
            }
        }

        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.PUNCT, "Cleaning punctuation & casing with Punct...")
            onStageProgress?.invoke("Punct")
            val restored = instance.restore(
                text = text,
                languageCode = language,
                customVocabulary = activeSettings.customVocabulary
            )
            punctWarmedUp = true
            restored
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Punct inference failed, keeping Voz transcript: ${t.message}")
            DebugLogManager.logWarn(TAG, "Punct inference failed, keeping Voz transcript", t)
            closePunctLocked()
            text
        }
    }

    private suspend fun runGemmaLocked(
        text: String,
        language: String,
        activeSettings: ModelSettings,
        dynamicStageRelease: Boolean,
        onStageProgress: ((String) -> Unit)?,
        precedingCleanedTail: String? = null,
        applyLeadingCursorContinuation: Boolean = true,
        keepWarmForStreamingOverlap: Boolean = false
    ): Pair<String, Boolean> {
        if (text.isBlank()) return text to true
        val selectedLlm = activeSettings.selectedLlmModel

        // Enforce strict single-LLM residency: close any different LLM immediately
        if (loadedLlmChoice != null && loadedLlmChoice != selectedLlm) {
            closeGemmaLocked()
            System.gc()
        }
        // Never keep Punct loaded alongside an active LLM
        if (punctRestorer != null) {
            closePunctLocked()
        }

        // Pillar 1: Evaluate Zero-LLM Fast-Path BEFORE unloading Voz or loading an unloaded LLM into RAM!
        val trimmed = text.trim()
        val rawFieldCtx = GemmaTextProcessor.inspectActiveFieldContextStatic(appContext)
        val rootFieldCtx = if (applyLeadingCursorContinuation) {
            rawFieldCtx
        } else {
            rawFieldCtx.copy(
                isMidSentenceContinuation = false,
                continuingNumberedListFrom = null,
                isContinuingBulletList = false
            )
        }
        val fastPathFieldCtx = if (precedingCleanedTail != null && rootFieldCtx.appCategory == AppContextCategory.EMAIL) {
            rootFieldCtx.copy(appCategory = AppContextCategory.GENERAL)
        } else {
            rootFieldCtx
        }
        if (GemmaTextProcessor.canBypassLlmFastPath(trimmed, activeSettings.gemmaPromptPreset, fastPathFieldCtx)) {
            val bypassed = GemmaTextProcessor.applyDeterministicFastPathCleanup(
                text = trimmed,
                preset = activeSettings.gemmaPromptPreset,
                languageCode = language,
                customVocabulary = activeSettings.customVocabulary,
                fieldContext = rootFieldCtx
            )
            DebugLogManager.logInfo(
                TAG,
                "Zero-LLM fast-path bypassed ${selectedLlm.title} in <1ms (${trimmed.length} chars; llmLoaded=${gemmaProcessor != null})"
            )
            return bypassed to true
        }

        if (gemmaProcessor == null) {
            val preLoadMem = evaluateMemoryPressure()
            val earReleaseThresholdMb = selectedLlm.estimatedStandaloneRamMb.toLong() + 650L
            if (preLoadMem.isAnyMemoryTight || preLoadMem.systemAvailMb < earReleaseThresholdMb) {
                closeEarLocked()
            }
            val memAfterAux = evaluateMemoryPressure()
            if (voz != null &&
                !memAfterAux.canKeepVozAndLlmCoResident(
                    llmChoice = selectedLlm,
                    vozAlreadyLoaded = true,
                    llmAlreadyLoaded = false
                )
            ) {
                if (keepWarmForStreamingOverlap) {
                    // During live streaming overlap, never unload Voz mid-recording
                    return text to false
                }
                DebugLogManager.logInfo(
                    TAG,
                    "Temporarily releasing Voz session to guarantee OOM-safe RAM headroom for ${selectedLlm.title} (sysAvail=${memAfterAux.systemAvailMb}MB, total=${memAfterAux.systemTotalMb}MB)",
                    includeMemory = true
                )
                closeVozLocked()
                System.gc()
            }
        }

        val instance = gemmaProcessor ?: run {
            val modelDir = DesertAntModelStorage.modelRevisionDir(appContext, selectedLlm.desertAntModel)
            val memNow = evaluateMemoryPressure()
            try {
                updateSingleModelStateLocked(
                    activeSettings = activeSettings,
                    model = selectedLlm.desertAntModel,
                    state = ModelLoadState.LOADING,
                    message = "Loading ${selectedLlm.title} on LiteRT-LM..."
                )
                onStageProgress?.invoke("Loading ${selectedLlm.shortLabel}")
                GemmaTextProcessor(
                    context = appContext,
                    modelDir = modelDir,
                    accelerator = activeSettings.hardwareAccelerator,
                    memoryMode = memNow.gemmaMemoryMode,
                    llmChoice = selectedLlm
                ).also {
                    gemmaProcessor = it
                    loadedLlmChoice = selectedLlm
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "Failed to dynamically load ${selectedLlm.title}: ${t.message}")
                DebugLogManager.logError(TAG, "Failed to dynamically load ${selectedLlm.title}", t)
                closeGemmaLocked()
                return text to false
            }
        }

        return try {
            val presetLabel = activeSettings.gemmaPromptPreset.shortLabel
            val thinkingLabel = when {
                !selectedLlm.supportsThinking -> "Direct"
                activeSettings.gemmaDisableThinking -> "No-Think"
                activeSettings.gemmaAdaptiveThinking -> "Auto-Think"
                else -> "Thinking"
            }
            setModelActiveStatusLocked(
                activeSettings,
                selectedLlm.desertAntModel,
                "Cleaning with ${selectedLlm.title} ($presetLabel • $thinkingLabel • ${instance.activeBackendSummary})..."
            )
            onStageProgress?.invoke(selectedLlm.shortLabel)
            val processed = instance.process(
                text = text,
                preset = activeSettings.gemmaPromptPreset,
                customPrompt = activeSettings.gemmaCustomPrompt,
                languageCode = language,
                customVocabulary = activeSettings.customVocabulary,
                disableThinking = activeSettings.gemmaDisableThinking,
                adaptiveThinking = activeSettings.gemmaAdaptiveThinking,
                precedingSegmentTailOverride = precedingCleanedTail,
                applyLeadingCursorContinuation = applyLeadingCursorContinuation
            )
            gemmaWarmedUp = true
            val postGemmaMem = evaluateMemoryPressure()
            val cannotKeepAlongsideVoz = !postGemmaMem.canKeepVozAndLlmCoResident(
                llmChoice = selectedLlm,
                vozAlreadyLoaded = voz != null,
                llmAlreadyLoaded = true
            )
            val minPostPassAvailMb = if (selectedLlm == LlmModelChoice.GEMMA_4_E2B) 950L else 700L
            if (!keepWarmForStreamingOverlap &&
                (dynamicStageRelease || postGemmaMem.systemAvailMb < minPostPassAvailMb || cannotKeepAlongsideVoz)
            ) {
                DebugLogManager.logInfo(
                    TAG,
                    "Releasing ${selectedLlm.title} session after pass so Voz & system RAM stay healthy (sysAvail=${postGemmaMem.systemAvailMb}MB)"
                )
                closeGemmaLocked()
                System.gc()
            } else if (keepWarmForStreamingOverlap && postGemmaMem.isCriticalPressure) {
                closeGemmaLocked()
            }
            processed to true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "${selectedLlm.title} inference failed, keeping transcript: ${t.message}")
            DebugLogManager.logWarn(TAG, "${selectedLlm.title} inference failed, keeping transcript", t)
            closeGemmaLocked()
            text to false
        }
    }

    private fun countLoadedModelsLocked(activeSettings: ModelSettings): Int {
        var count = 0
        if (voz != null) count++
        if (activeSettings.earEnabled && ear != null) count++
        if (activeSettings.punctEnabled && punctRestorer != null) count++
        if (activeSettings.gemmaEnabled && gemmaProcessor != null && loadedLlmChoice == activeSettings.selectedLlmModel) {
            count++
        }
        return count
    }

    private fun computeModelStatesSnapshot(
        activeSettings: ModelSettings,
        activeStageModel: DesertAntModel? = null,
        loadingModel: DesertAntModel? = null
    ): Map<DesertAntModel, ModelLoadState> {
        val result = linkedMapOf<DesertAntModel, ModelLoadState>()
        for (model in DesertAntModel.entries) {
            val enabled = activeSettings.isModelEnabled(model)
            val downloaded = try {
                DesertAntModelStorage.isModelDownloaded(appContext, model)
            } catch (_: Throwable) {
                false
            }
            val instanceLoaded = when {
                model == DesertAntModel.VOZ -> voz != null
                model == DesertAntModel.EAR -> ear != null
                model == DesertAntModel.PUNCT -> punctRestorer != null
                model.isLlmModel -> gemmaProcessor != null && loadedLlmChoice?.desertAntModel == model
                else -> false
            }
            result[model] = when {
                !enabled -> ModelLoadState.DISABLED
                !downloaded -> ModelLoadState.NOT_DOWNLOADED
                activeStageModel == model -> ModelLoadState.ACTIVE
                loadingModel == model -> ModelLoadState.LOADING
                instanceLoaded -> ModelLoadState.LOADED
                else -> ModelLoadState.STANDBY
            }
        }
        return result
    }

    private fun updateSingleModelStateLocked(
        activeSettings: ModelSettings,
        model: DesertAntModel,
        state: ModelLoadState,
        message: String
    ) {
        val map = computeModelStatesSnapshot(
            activeSettings = activeSettings,
            activeStageModel = if (state == ModelLoadState.ACTIVE) model else null,
            loadingModel = if (state == ModelLoadState.LOADING) model else null
        )
        _pipelineStatus.value = _pipelineStatus.value.copy(
            phase = if (state == ModelLoadState.ACTIVE || isTranscribing) {
                PipelinePhase.TRANSCRIBING
            } else {
                PipelinePhase.LOADING
            },
            configuredAccelerator = activeSettings.hardwareAccelerator,
            activeAcceleratorLabel = activeAcceleratorDescription,
            activeGemmaBackendLabel = gemmaProcessor?.activeBackendSummary,
            detectedNpuSummary = NpuHardwareManager.detectHardwareProfile().summaryLabel,
            modelStates = map,
            statusMessage = message,
            loadedCount = countLoadedModelsLocked(activeSettings),
            enabledCount = activeSettings.enabledModels().size
        )
    }

    private fun setModelActiveStatusLocked(
        activeSettings: ModelSettings,
        model: DesertAntModel,
        message: String
    ) {
        updateSingleModelStateLocked(
            activeSettings = activeSettings,
            model = model,
            state = ModelLoadState.ACTIVE,
            message = message
        )
    }

    private fun hasAnyLoadedModelLocked(): Boolean {
        return voz != null || ear != null || punctRestorer != null || gemmaProcessor != null
    }

    private fun standbyStatusMessageLocked(@Suppress("UNUSED_PARAMETER") activeSettings: ModelSettings): String {
        val vozDownloaded = try {
            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)
        } catch (_: Throwable) {
            false
        }
        return if (!vozDownloaded) {
            "Models not downloaded yet"
        } else {
            "Standby — 0 MB in RAM (loads when bubble is tapped)"
        }
    }

    private fun evictAllModelsLocked(activeSettings: ModelSettings, reason: String) {
        idleEvictionJob?.cancel()
        idleEvictionJob = null
        llmIdleEvictionJob?.cancel()
        llmIdleEvictionJob = null
        promptPrewarmJob?.cancel()
        promptPrewarmJob = null
        Log.i(TAG, "Unloading all models from RAM ($reason) -> 0 MB Standby.")
        DebugLogManager.logInfo(TAG, "Unloading all models from RAM ($reason) -> 0 MB Standby", includeMemory = true)
        closeEarLocked()
        closePunctLocked()
        closeGemmaLocked()
        closeVozLocked()
        System.gc()
        updateStatusSnapshot(
            activeSettings = activeSettings,
            phaseOverride = PipelinePhase.UNINITIALIZED,
            messageOverride = standbyStatusMessageLocked(activeSettings)
        )
    }

    private fun updateStatusSnapshot(
        activeSettings: ModelSettings,
        phaseOverride: PipelinePhase?,
        messageOverride: String?
    ) {
        val states = computeModelStatesSnapshot(activeSettings)
        val loaded = countLoadedModelsLocked(activeSettings)
        val enabled = activeSettings.enabledModels().size
        val anyLoaded = hasAnyLoadedModelLocked()
        val resolvedPhase = phaseOverride ?: when {
            isTranscribing -> PipelinePhase.TRANSCRIBING
            anyLoaded -> PipelinePhase.READY
            else -> PipelinePhase.UNINITIALIZED
        }
        val resolvedMsg = messageOverride ?: if (!anyLoaded && !isTranscribing) {
            standbyStatusMessageLocked(activeSettings)
        } else {
            _pipelineStatus.value.statusMessage
        }
        _pipelineStatus.value = _pipelineStatus.value.copy(
            phase = resolvedPhase,
            configuredAccelerator = activeSettings.hardwareAccelerator,
            activeAcceleratorLabel = activeAcceleratorDescription,
            activeGemmaBackendLabel = gemmaProcessor?.activeBackendSummary,
            detectedNpuSummary = NpuHardwareManager.detectHardwareProfile().summaryLabel,
            modelStates = states,
            statusMessage = resolvedMsg,
            loadedCount = loaded,
            enabledCount = enabled
        )
    }

    private fun resetIdleEvictionTimerLocked() {
        idleEvictionJob?.cancel()
        idleEvictionJob = null
        llmIdleEvictionJob?.cancel()
        llmIdleEvictionJob = null

        if (isTranscribing || isRecordingActive || !hasAnyLoadedModelLocked()) {
            return
        }

        val activeSnap = settings
        val unloadMinutes = activeSnap.modelAutoUnloadMinutes
        val effectiveTimeoutMs = computeEffectiveIdleTimeoutMs(unloadMinutes, isScreenInteractiveState)
        when {
            effectiveTimeoutMs == null -> {
                // Keep loaded models warm in RAM indefinitely unless OS memory pressure occurs
            }
            effectiveTimeoutMs <= 0L -> {
                evictAllModelsLocked(activeSnap, "immediate auto-unload")
            }
            else -> {
                val reason = if (!isScreenInteractiveState && effectiveTimeoutMs <= SCREEN_OFF_DEEP_SLEEP_UNLOAD_MS) {
                    "screen-off deep-sleep battery saver"
                } else {
                    "auto-unload after ${unloadMinutes}m idle"
                }
                idleEvictionJob = idleScope.launch {
                    delay(effectiveTimeoutMs)
                    evictAllModels(reason)
                }
                if (gemmaProcessor != null && effectiveTimeoutMs > LLM_IDLE_TIMEOUT_MS) {
                    llmIdleEvictionJob = idleScope.launch {
                        delay(LLM_IDLE_TIMEOUT_MS)
                        evictIdleLlm()
                    }
                }
            }
        }
    }

    private fun closeVozLocked() {
        val instance = voz
        voz = null
        vozWarmedUp = false
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
        earWarmedUp = false
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun closePunctLocked() {
        val instance = punctRestorer
        punctRestorer = null
        punctWarmedUp = false
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeGemmaLocked() {
        val instance = gemmaProcessor
        gemmaProcessor = null
        loadedLlmChoice = null
        gemmaWarmedUp = false
        if (instance != null) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
    }
}

internal data class ChunkSliceRange(val start: Int, val endExclusive: Int)

internal fun computeSmartChunkRanges(
    samples: FloatArray,
    maxChunkSamples: Int = SmartPipeline.DEFAULT_MAX_CHUNK_SAMPLES,
    minChunkSamples: Int = SmartPipeline.DEFAULT_MIN_CHUNK_SAMPLES,
    pauseWindowSamples: Int = 1_280, // minimum pause length (80 ms @ 16 kHz)
    silenceThreshold: Float = DEFAULT_SILENCE_RMS
): List<ChunkSliceRange> {
    if (samples.isEmpty()) return emptyList()
    val safeMax = max(3_200, maxChunkSamples)
    if (samples.size <= safeMax) return listOf(ChunkSliceRange(0, samples.size))

    val safeMin = minChunkSamples.coerceIn(1_600, (safeMax * 3) / 4)
    val minPauseFrames = max(2, pauseWindowSamples.coerceIn(320, 16_000) / CHUNK_FRAME_SAMPLES)
    // Never leave a sub-second orphan tail: a few hundred samples fed to Clear/Voz yields hallucinated or empty text.
    // Bounded by (safeMax - safeMin) so the split range below can never become empty.
    val minTailSamples = min(8_000, safeMax - safeMin)
    val ranges = ArrayList<ChunkSliceRange>((samples.size / safeMin) + 2)

    var cursor = 0
    while (cursor < samples.size) {
        val remaining = samples.size - cursor
        if (remaining <= safeMax) {
            ranges.add(ChunkSliceRange(cursor, samples.size))
            break
        }

        val searchStart = cursor + safeMin
        val latestSplit = min(cursor + safeMax, samples.size - minTailSamples)
        val bestSplitIndex = if (searchStart < latestSplit) {
            findBestSplitPoint(samples, searchStart, latestSplit, silenceThreshold, minPauseFrames)
        } else {
            latestSplit
        }

        val splitAt = bestSplitIndex.coerceIn(cursor + safeMin, latestSplit)
        ranges.add(ChunkSliceRange(cursor, splitAt))
        cursor = splitAt
    }

    return ranges
}

/**
 * Splits a long 16 kHz PCM [samples] buffer into natural speech segments of at most [maxChunkSamples].
 *
 * Each cut is placed by [findBestSplitPoint] inside `minChunkSamples..maxChunkSamples`: it prefers the
 * longest real pause (adaptive noise-floor threshold, cut at the centre of the pause, latest of the
 * comparably long pauses) and only falls back to the quietest 80 ms window if no pause exists. A minimum
 * 0.5 s tail is always preserved so no orphan chunk is produced.
 *
 * This prevents quadratic Conformer attention memory blowups and JNI/FFI array OOMs on long dictations
 * while ensuring splits never cut through the middle of a spoken syllable.
 */
fun splitAudioIntoSmartChunks(
    samples: FloatArray,
    maxChunkSamples: Int = SmartPipeline.DEFAULT_MAX_CHUNK_SAMPLES,
    minChunkSamples: Int = SmartPipeline.DEFAULT_MIN_CHUNK_SAMPLES,
    pauseWindowSamples: Int = 1_280, // minimum pause length (80 ms @ 16 kHz)
    silenceThreshold: Float = DEFAULT_SILENCE_RMS
): List<FloatArray> {
    if (samples.isEmpty()) return emptyList()
    val safeMax = max(3_200, maxChunkSamples)
    if (samples.size <= safeMax) return listOf(samples)

    val ranges = computeSmartChunkRanges(
        samples = samples,
        maxChunkSamples = maxChunkSamples,
        minChunkSamples = minChunkSamples,
        pauseWindowSamples = pauseWindowSamples,
        silenceThreshold = silenceThreshold
    )
    return ranges.map { range ->
        if (range.start == 0 && range.endExclusive == samples.size) {
            samples
        } else {
            samples.copyOfRange(range.start, range.endExclusive)
        }
    }
}

/**
 * Prepares an individual 16 kHz PCM chunk for native inference (`Clear`, `Uhm`, `Voz`):
 * 1. Applies a smooth [edgeFadeSamples] (10 ms = 160 samples) linear fade-in/fade-out at the array boundaries
 *    so slicing a buffer inside room noise or a vocal dip never creates a rectangular-window step click in
 *    the STFT mel-spectrogram.
 * 2. Guarantees at least [minSilencePadSamples] (200 ms = 3,200 samples) of quiet acoustic runway before the
 *    first voiced sample and after the last voiced sample so `Clear`'s 2.0s STFT window and `Voz`'s Conformer
 *    convolutional subsampling front-end never clip boundary phonemes.
 */
fun prepareChunkForInference(
    samples: FloatArray,
    minSilencePadSamples: Int = 3_200,
    edgeFadeSamples: Int = 160,
    silenceThreshold: Float = 0.004f
): FloatArray {
    return prepareChunkForInference(
        samples = samples,
        minLeadSilencePadSamples = minSilencePadSamples,
        minTailSilencePadSamples = minSilencePadSamples,
        minTotalSamples = 0,
        edgeFadeSamples = edgeFadeSamples,
        silenceThreshold = silenceThreshold
    )
}

/**
 * Full Conformer-Transducer chunk preparation with asymmetric leading/trailing silence runway and minimum
 * total length:
 * - [minLeadSilencePadSamples] (`5,120` = `320 ms` = 4 Conformer frames) ensures the first word onset is
 *   never clipped by convolutional subsampling.
 * - [minTailSilencePadSamples] (`10,240` = `640 ms` = 8 Conformer frames) gives `Voz`'s Token-and-Duration
 *   Transducer (TDT / RNN-T) full right-context lookahead after the final consonant so the last word of a
 *   chunk or dictation is never truncated before emission.
 * - [minTotalSamples] (`25,600` = `1.60 s` = 20 Conformer frames) pads short post-pause tail chunks with
 *   extra trailing silence so the Conformer depthwise convolution kernel (`31 * 80 ms`) never starves on
 *   short 1–2 word utterances.
 */
fun prepareChunkForInference(
    samples: FloatArray,
    minLeadSilencePadSamples: Int,
    minTailSilencePadSamples: Int,
    minTotalSamples: Int = 0,
    edgeFadeSamples: Int = 160,
    silenceThreshold: Float = 0.0020f
): FloatArray {
    if (samples.isEmpty()) return samples
    val safeLeadPad = minLeadSilencePadSamples.coerceAtLeast(0)
    val safeTailPad = minTailSilencePadSamples.coerceAtLeast(0)
    val safeFade = min(edgeFadeSamples.coerceAtLeast(0), samples.size / 4)

    var firstVoice = -1
    for (i in samples.indices) {
        if (abs(samples[i]) >= silenceThreshold) {
            firstVoice = i
            break
        }
    }
    if (firstVoice == -1) return samples

    var lastVoice = firstVoice
    for (i in samples.lastIndex downTo firstVoice) {
        if (abs(samples[i]) >= silenceThreshold) {
            lastVoice = i
            break
        }
    }

    val existingLeadSilence = firstVoice
    val existingTailSilence = (samples.size - 1 - lastVoice).coerceAtLeast(0)
    val extraLead = (safeLeadPad - existingLeadSilence).coerceAtLeast(0)
    val baseExtraTail = (safeTailPad - existingTailSilence).coerceAtLeast(0)
    val baseTotal = extraLead + samples.size + baseExtraTail
    val extraMinLenTail = (minTotalSamples - baseTotal).coerceAtLeast(0)
    val extraTail = baseExtraTail + extraMinLenTail

    if (extraLead == 0 && extraTail == 0 && safeFade == 0) {
        return samples
    }

    val out = FloatArray(extraLead + samples.size + extraTail)
    System.arraycopy(samples, 0, out, extraLead, samples.size)

    if (safeFade > 0) {
        for (i in 0 until safeFade) {
            val scale = (i + 1).toFloat() / (safeFade + 1).toFloat()
            out[extraLead + i] *= scale
            out[extraLead + samples.size - 1 - i] *= scale
        }
    }

    return out
}

/**
 * Fast (< 0.4 ms), zero-native-RAM speech conditioner used whenever the `Clear` neural enhancer is disabled
 * (the default configuration!).
 *
 * Solves the root cause of sentence-final and chunk-final word dropouts when `Clear` is off:
 * 1. **DC Offset Removal:** Centers raw microphone PCM around `0.0f` while preserving existing zero-padded
 *    silence runways.
 * 2. **Prosodic Declination & Trailing-Word Leveling:** Human speakers naturally drop 8–14 dB in volume on
 *    the last 1–3 words of a clause or dictation. Computes a smooth 400 ms windowed speech envelope over
 *    voiced frames (plus 100 ms trailing hangover so soft final consonants `/t/`, `/k/`, `/s/`, `/f/`, `/n/`
 *    receive the same boost as their vowel) and gently boosts quiet phrases up to `3.0x` toward a consistent
 *    target speech RMS (`0.14f`) while leaving background silence at unity gain (`1.0x`).
 * 3. **Soft-Knee Limiting:** Smoothly limits peaks above `0.85f` into `[0.85f, 0.96f]` so boosted speech
 *    never hard-clips.
 */
@JvmOverloads
fun conditionAudioWhenClearDisabled(samples: FloatArray, inPlace: Boolean = false): FloatArray {
    if (samples.isEmpty()) return samples
    val frameLen = CHUNK_FRAME_SAMPLES // 320 samples = 20 ms @ 16 kHz
    val numFrames = samples.size / frameLen
    if (numFrames < 3) return samples

    // 1. Estimate DC offset over non-zero active samples
    var dcSum = 0.0
    var dcCount = 0
    for (s in samples) {
        if (s.isFinite() && abs(s) > 1e-6f) {
            dcSum += s.toDouble()
            dcCount++
        }
    }
    val dcOffset = if (dcCount > 0) (dcSum / dcCount).toFloat().coerceIn(-0.05f, 0.05f) else 0f

    // 2. Compute per-frame RMS after DC removal
    val frameRms = FloatArray(numFrames)
    for (f in 0 until numFrames) {
        val base = f * frameLen
        var sumSq = 0.0
        for (i in 0 until frameLen) {
            val s = samples[base + i]
            val centered = if (s.isFinite() && abs(s) > 1e-6f) s - dcOffset else 0f
            sumSq += centered * centered
        }
        frameRms[f] = kotlin.math.sqrt(sumSq / frameLen).toFloat()
    }

    // 3. Identify voiced frames vs background noise floor (using primitive FloatArray copy + sort to avoid boxed List<Float> allocations)
    var nonZeroCount = 0
    for (r in frameRms) {
        if (r > 0.0005f) nonZeroCount++
    }
    if (nonZeroCount == 0) return samples
    val nonZeroRms = FloatArray(nonZeroCount)
    var nzIdx = 0
    for (r in frameRms) {
        if (r > 0.0005f) {
            nonZeroRms[nzIdx++] = r
        }
    }
    nonZeroRms.sort()
    val noiseFloor = nonZeroRms[(nonZeroCount * 0.08f).toInt().coerceIn(0, nonZeroCount - 1)]
    val voiceGate = max(noiseFloor * 2.2f, 0.0025f).coerceAtMost(0.018f)

    val voiced = BooleanArray(numFrames) { frameRms[it] >= voiceGate }
    // Extend voiced mask by 3 pre-onset frames (60 ms) and 5 hangover frames (100 ms) so soft initial and
    // trailing consonants (/t/, /k/, /s/, /f/, /n/, /m/) ride the exact same gain envelope as their word
    val baseVoiced = voiced.copyOf()
    for (f in 0 until numFrames) {
        if (baseVoiced[f]) {
            for (pre in 1..3) {
                if (f - pre >= 0 && frameRms[f - pre] >= 0.0012f) voiced[f - pre] = true
            }
            for (post in 1..5) {
                if (f + post < numFrames && frameRms[f + post] >= 0.0012f) voiced[f + post] = true
            }
        }
    }

    var voicedCount = 0
    for (f in 0 until numFrames) {
        if (voiced[f] && frameRms[f] >= voiceGate) voicedCount++
    }
    if (voicedCount == 0) return samples
    val voicedLevels = FloatArray(voicedCount)
    var vIdx = 0
    for (f in 0 until numFrames) {
        if (voiced[f] && frameRms[f] >= voiceGate) {
            voicedLevels[vIdx++] = frameRms[f]
        }
    }
    voicedLevels.sort()
    val p80Speech = voicedLevels[(voicedCount * 0.80f).toInt().coerceIn(0, voicedCount - 1)]
    val targetRms = p80Speech.coerceIn(0.13f, 0.17f)

    // 4. Compute local 400 ms (+/- 10 frame) voiced RMS envelope and per-frame target gain
    val desiredGain = FloatArray(numFrames) { 1.0f }
    val halfWin = 10
    for (f in 0 until numFrames) {
        if (!voiced[f]) {
            desiredGain[f] = 1.0f
            continue
        }
        var sumSq = 0.0
        var count = 0
        val wStart = max(0, f - halfWin)
        val wEnd = min(numFrames - 1, f + halfWin)
        for (k in wStart..wEnd) {
            if (voiced[k] && frameRms[k] >= voiceGate * 0.7f) {
                val r = frameRms[k].toDouble()
                sumSq += r * r
                count++
            }
        }
        val localRms = if (count > 0) {
            kotlin.math.sqrt(sumSq / count).toFloat()
        } else {
            max(frameRms[f], voiceGate)
        }
        if (localRms > 0.002f) {
            desiredGain[f] = (targetRms / localRms).coerceIn(1.0f, 3.0f)
        }
    }

    // 5. Bidirectional envelope smoothing so gain transitions across word boundaries are completely seamless
    val smoothedGain = desiredGain.copyOf()
    for (f in 1 until numFrames) {
        smoothedGain[f] = smoothedGain[f - 1] * 0.72f + smoothedGain[f] * 0.28f
    }
    for (f in (numFrames - 2) downTo 0) {
        smoothedGain[f] = max(smoothedGain[f], smoothedGain[f + 1] * 0.82f)
            .coerceIn(1.0f, 3.0f)
    }
    // Keep pure zero-padded runway frames at 1.0f
    for (f in 0 until numFrames) {
        if (frameRms[f] < 0.0005f) smoothedGain[f] = 1.0f
    }

    // 6. Apply linearly interpolated gain + soft-knee limiter above 0.85f
    // When `inPlace == true` (e.g. freshly allocated `preparedChunk`), mutate `samples` directly to avoid a duplicate MB-scale FloatArray!
    val out = if (inPlace) samples else FloatArray(samples.size)
    for (f in 0 until numFrames) {
        val g0 = smoothedGain[f]
        val g1 = if (f + 1 < numFrames) smoothedGain[f + 1] else g0
        val base = f * frameLen
        for (i in 0 until frameLen) {
            val idx = base + i
            val raw = samples[idx]
            if (!raw.isFinite() || abs(raw) <= 1e-6f) {
                out[idx] = 0f
                continue
            }
            val g = g0 + (g1 - g0) * (i.toFloat() / frameLen.toFloat())
            val y = (raw - dcOffset) * g
            out[idx] = softLimitSample(y)
        }
    }
    for (idx in (numFrames * frameLen) until samples.size) {
        val raw = samples[idx]
        if (!raw.isFinite() || abs(raw) <= 1e-6f) {
            out[idx] = 0f
        } else {
            val g = smoothedGain[numFrames - 1]
            out[idx] = softLimitSample((raw - dcOffset) * g)
        }
    }
    return out
}

private fun softLimitSample(x: Float): Float {
    val ax = abs(x)
    if (ax <= 0.85f) return x
    val excess = ax - 0.85f
    val compressed = 0.85f + 0.12f * (excess / (excess + 0.12f))
    return if (x >= 0f) compressed else -compressed
}

/**
 * Trims leading and trailing dead silence below [threshold] while preserving up to [padSamples]
 * of natural speech margin on both ends. Returns an empty array if the entire buffer is silence.
 */
fun trimLeadingAndTrailingSilence(
    samples: FloatArray,
    threshold: Float = 0.0035f,
    padSamples: Int = 2_560
): FloatArray {
    if (samples.isEmpty()) return samples
    var firstVoice = -1
    for (i in samples.indices) {
        if (abs(samples[i]) >= threshold) {
            firstVoice = i
            break
        }
    }
    if (firstVoice == -1) return FloatArray(0)

    var lastVoice = firstVoice
    for (i in samples.lastIndex downTo firstVoice) {
        if (abs(samples[i]) >= threshold) {
            lastVoice = i
            break
        }
    }

    val start = max(0, firstVoice - padSamples)
    val endExclusive = min(samples.size, lastVoice + padSamples + 1)
    if (start == 0 && endExclusive == samples.size) {
        return samples
    }
    return samples.copyOfRange(start, endExclusive)
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
 * Unambiguous vocal hesitation tokens across English and German that are never valid lexical words.
 */
private val UNAMBIGUOUS_HESITATION_TOKENS = setOf(
    "uh", "uhh", "uhhh",
    "uhm", "uhmm",
    "umm", "ummm",
    "erm", "ermm",
    "äh", "ähh",
    "ähm", "ähmm",
    "öhm",
    "hm", "hmm", "hmmm",
    "mhm", "mmh", "mmhm",
    "uh-huh", "mm-hmm"
)

/**
 * Function words after which a comma is never grammatically valid unless `Voz` inserted a parenthetical
 * hesitation (`"so that, uh, the"` -> `"so that the"`).
 */
private val NO_COMMA_AFTER_WORDS = setOf(
    // English determiners, prepositions, conjunctions, pronouns, auxiliaries
    "the", "a", "an", "to", "of", "in", "on", "at", "for", "with", "from", "by", "into", "onto",
    "and", "or", "but", "so", "that", "which", "who", "whose", "because", "if", "when", "while",
    "as", "than", "like", "is", "are", "was", "were", "be", "been", "have", "has", "had",
    "do", "does", "did", "will", "would", "can", "could", "should", "must", "may", "might",
    "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her", "its", "our", "their",
    "this", "these", "those", "very", "really", "quite", "too",
    // German articles, prepositions, conjunctions, pronouns, auxiliaries
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
    "und", "oder", "aber", "denn", "sondern", "weil", "dass", "ob", "wenn", "als", "wie",
    "in", "im", "ins", "an", "am", "auf", "für", "mit", "von", "vom", "zu", "zum", "zur",
    "bei", "beim", "nach", "aus", "über", "unter", "vor", "durch", "gegen", "ohne", "um",
    "ich", "du", "er", "sie", "es", "wir", "ihr", "man", "mein", "meine", "dein", "deine",
    "sein", "seine", "unser", "unsere", "kein", "keine", "ist", "sind", "war", "waren",
    "wird", "werden", "hat", "haben", "kann", "können", "muss", "müssen", "soll", "sollen",
    "will", "wollen", "sehr", "ganz", "ziemlich"
)

internal fun extractWordCore(token: String): String {
    val start = token.indexOfFirst { it.isLetterOrDigit() }
    if (start < 0) return ""
    val end = token.indexOfLast { it.isLetterOrDigit() }
    return token.substring(start, end + 1)
}

internal fun isUnambiguousHesitationToken(token: String): Boolean {
    val core = extractWordCore(token).lowercase(Locale.ROOT)
    return core.isNotEmpty() && core in UNAMBIGUOUS_HESITATION_TOKENS
}

/**
 * Normalizes a `Voz` [Transcription], applying [customVocabulary] biasing and removing unambiguous
 * lexical hesitation noises (`"uh"`, `"uhm"`, `"äh"`, `"ähm"`, `"hm"`, `"hmm"`).
 */
fun reconcileTranscript(
    transcription: Transcription,
    customVocabulary: List<String> = emptyList()
): String {
    val words = transcription.words
    val rawJoined = if (transcription.text.isNotBlank()) {
        transcription.text
    } else if (words.isNotEmpty()) {
        words.joinToString(" ") { it.text }
    } else {
        ""
    }
    return normalizeTranscriptText(
        rawText = applyCustomVocabulary(rawJoined, customVocabulary),
        capitalizeLeadingWord = false
    )
}

private val LEADING_ORPHAN_PUNCT_REGEX = Regex("^[.,;:\\-–—]+\\s*")
private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([.,!?:;%€])")
private val DUPLICATE_PUNCT_REGEX = Regex("([,;:])\\s*[,;:]+")
private val MULTI_SPACE_REGEX = Regex("\\s+")

/**
 * Strips standalone unambiguous hesitation tokens (such as `"uh"`, `"uhm"`, `"erm"`, `"äh"`, `"ähm"`, `"hm"`,
 * `"hmm"`) from a raw text line while preserving surrounding sentence structure and capitalization.
 */
private fun stripUnambiguousTextHesitations(rawText: String): Pair<String, Boolean> {
    val tokens = rawText.trim().split(MULTI_SPACE_REGEX)
    if (tokens.isEmpty() || tokens.none { isUnambiguousHesitationToken(it) }) {
        return rawText to false
    }
    val kept = ArrayList<String>(tokens.size)
    var removedLeadingCapital = false
    var lastRemovedFillerHadCommaOrPeriod = false
    var trailingTerminalPunct: Char? = null

    for (token in tokens) {
        if (isUnambiguousHesitationToken(token)) {
            val trimmedToken = token.trim()
            if (kept.isEmpty()) {
                val firstLetter = trimmedToken.firstOrNull { it.isLetter() }
                if (firstLetter != null && firstLetter.isUpperCase()) {
                    removedLeadingCapital = true
                }
            } else {
                val prev = kept.last()
                val prevCore = extractWordCore(prev).lowercase(Locale.ROOT)
                if (prev.endsWith(",") && prevCore in NO_COMMA_AFTER_WORDS) {
                    kept[kept.lastIndex] = prev.dropLast(1)
                }
                if (trimmedToken.endsWith(",") || trimmedToken.endsWith(".")) {
                    lastRemovedFillerHadCommaOrPeriod = true
                }
                val lastChar = trimmedToken.lastOrNull()
                if (lastChar == '.' || lastChar == '!' || lastChar == '?') {
                    trailingTerminalPunct = lastChar
                }
            }
        } else {
            var adjustedToken = token
            if (lastRemovedFillerHadCommaOrPeriod && kept.isNotEmpty()) {
                val prevCore = extractWordCore(kept.last()).lowercase(Locale.ROOT)
                val currCore = extractWordCore(token)
                if (prevCore in NO_COMMA_AFTER_WORDS &&
                    currCore.length >= 2 &&
                    currCore[0].isUpperCase() &&
                    currCore.substring(1).all { it.isLowerCase() } &&
                    currCore != "I" &&
                    currCore.lowercase(Locale.ROOT) in LOWERCASEABLE_CONTINUATION_WORDS
                ) {
                    adjustedToken = lowercaseFirstLetterOfToken(token)
                }
            }
            lastRemovedFillerHadCommaOrPeriod = false
            trailingTerminalPunct = null
            kept.add(adjustedToken)
        }
    }

    if (kept.isEmpty()) return "" to false

    if (trailingTerminalPunct != null) {
        val last = kept.last()
        if (last.endsWith(",") || last.endsWith(";") || last.endsWith(":")) {
            kept[kept.lastIndex] = last.dropLast(1) + trailingTerminalPunct
        } else if (last.last().isLetterOrDigit()) {
            kept[kept.lastIndex] = last + trailingTerminalPunct
        }
    }

    return kept.joinToString(" ") to removedLeadingCapital
}

/**
 * Cleans up orphaned punctuation, duplicate spaces, unambiguous vocal hesitation tokens, and
 * re-capitalizes the new leading word when a sentence-initial filler word was removed.
 */
fun normalizeTranscriptText(
    rawText: String,
    capitalizeLeadingWord: Boolean
): String {
    if (rawText.isBlank()) return ""

    val (withoutHesitations, removedLeadingCap) = stripUnambiguousTextHesitations(rawText)
    if (withoutHesitations.isBlank()) return ""

    var cleaned = withoutHesitations
        .trim()
        // Strip leading orphaned punctuation left behind after a leading filler removal (e.g. ", ich...")
        .replace(LEADING_ORPHAN_PUNCT_REGEX, "")
        // Collapse spaces before punctuation
        .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
        // Collapse duplicate punctuation (e.g. ", ," -> ",")
        .replace(DUPLICATE_PUNCT_REGEX, "$1")
        // Collapse multiple spaces
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()

    if (cleaned.isEmpty()) return ""

    if (capitalizeLeadingWord || removedLeadingCap) {
        val firstLetterIdx = cleaned.indexOfFirst { it.isLetter() }
        if (firstLetterIdx >= 0 && cleaned[firstLetterIdx].isLowerCase()) {
            val ch = cleaned[firstLetterIdx].titlecase(Locale.getDefault())
            cleaned = cleaned.substring(0, firstLetterIdx) + ch + cleaned.substring(firstLetterIdx + 1)
        }
    }

    return cleaned
}

/**
 * Words that cannot grammatically end a standalone sentence when another spoken chunk immediately follows.
 * If `Voz` placed a synthetic `.` or `,` at the end of a chunk after one of these words because of a
 * mid-clause pause, [stitchChunkTranscripts] strips the synthetic punctuation and continues the clause.
 */
private val DANGLING_CHUNK_END_WORDS = setOf(
    // English articles, determiners, prepositions, conjunctions, auxiliaries, degree adverbs
    "the", "a", "an", "my", "your", "our", "their", "his", "its", "every",
    "to", "of", "in", "into", "onto", "for", "with", "without", "from", "by", "at",
    "between", "among", "through", "during", "including", "against", "towards", "toward", "upon", "within",
    "and", "or", "nor", "but", "so", "because", "since", "if", "when", "while", "although", "though",
    "unless", "until", "whether", "that", "which", "who", "whom", "whose", "as", "than", "like",
    "very", "really", "quite", "extremely",
    // German articles, determiners, prepositions, conjunctions, degree adverbs
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
    "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine", "sein", "seine", "unser", "unsere",
    "kein", "keine", "keinen", "keinem", "keiner",
    "und", "oder", "aber", "denn", "sondern", "weil", "dass", "sodass", "ob", "wenn", "als", "falls",
    "damit", "obwohl", "obgleich", "während", "indem", "bevor", "nachdem", "sobald", "solange",
    "in", "im", "ins", "an", "am", "ans", "auf", "aufs", "für", "fürs", "mit", "von", "vom",
    "zu", "zum", "zur", "bei", "beim", "nach", "aus", "über", "unter", "vor", "hinter", "neben",
    "zwischen", "durch", "gegen", "ohne", "um", "ums", "seit", "trotz", "wegen", "statt", "anstatt",
    "sehr", "ganz", "ziemlich", "besonders",
    // Romance / Dutch common articles, prepositions & conjunctions
    "le", "la", "les", "un", "une", "des", "du", "au", "aux", "et", "ou", "mais", "que", "pour", "dans", "avec",
    "el", "los", "las", "una", "unos", "unas", "y", "o", "pero", "porque", "para", "con", "por",
    "il", "lo", "gli", "uno", "e", "ma", "perché", "per", "con", "nel", "nella",
    "os", "as", "um", "uma", "mas", "para", "com",
    "het", "een", "en", "maar", "omdat", "voor", "met", "van"
)

/**
 * Clause-continuation connectors at the start of Chunk $N+1$ that join directly onto Chunk $N$ without
 * a preceding comma (stripping any synthetic `.` that `Voz` placed at the end of Chunk $N$).
 */
private val NO_COMMA_CONTINUATION_CONNECTORS = setOf(
    "and", "or", "nor", "because", "since", "unless", "until", "whether", "than", "as", "like",
    "with", "without", "into", "onto", "from", "between", "through", "about", "of", "by", "via",
    "und", "oder", "sowie",
    "et", "ou", "y", "e", "en", "of"
)

/**
 * Clause-continuation connectors (subordinating, contrastive, or relative) at the start of Chunk $N+1$
 * that join onto Chunk $N$ with a comma `,` instead of a synthetic full stop `.`.
 */
private val COMMA_CONTINUATION_CONNECTORS = setOf(
    "but", "yet", "although", "though", "whereas", "while", "which", "who", "whom", "whose", "whereby",
    "especially", "particularly", "including",
    "aber", "denn", "sondern", "weil", "dass", "sodass", "ob", "obwohl", "obgleich",
    "während", "indem", "damit", "bevor", "nachdem", "sobald", "solange", "falls", "sofern",
    "insbesondere", "zumal",
    "mais", "car", "parce", "puisque", "quoique",
    "pero", "sino", "porque", "aunque",
    "ma", "perché", "sebbene",
    "mas", "embora",
    "maar", "want", "omdat", "hoewel"
)

/**
 * Words that can follow `"that"` at the start of Chunk $N+1$ when `"that"` is a relative/complementizer
 * continuation of Chunk $N$ (e.g. `"the text. That is spoken"` -> `"the text that is spoken"`).
 */
private val THAT_CLAUSE_FOLLOWERS = setOf(
    "is", "are", "was", "were", "be", "been", "has", "have", "had", "does", "do", "did",
    "doesn't", "don't", "didn't", "isn't", "aren't", "wasn't", "weren't", "won't", "wouldn't",
    "can't", "cannot", "couldn't", "shouldn't", "mustn't",
    "will", "would", "can", "could", "should", "must", "may", "might",
    "makes", "made", "works", "worked", "happens", "happened", "means", "meant", "needs", "needed",
    "looks", "looked", "feels", "felt", "seems", "seemed", "stays", "stayed", "comes", "came",
    "goes", "went", "takes", "took", "gives", "gave", "gets", "got", "keeps", "kept",
    "i", "you", "we", "they", "he", "she", "it", "there", "the", "a", "an", "this", "these", "those",
    "my", "your", "our", "their", "his", "her", "its"
)

/**
 * Infinitive verbs that follow `"to"` at the start of Chunk $N+1$ when `"to ..."` continues Chunk $N$.
 */
private val TO_INFINITIVE_FOLLOWERS = setOf(
    "be", "have", "do", "make", "get", "take", "go", "come", "see", "know", "think", "look",
    "want", "give", "use", "find", "tell", "ask", "work", "seem", "feel", "try", "leave", "call",
    "keep", "let", "begin", "help", "talk", "turn", "start", "show", "hear", "play", "run",
    "move", "like", "live", "believe", "hold", "bring", "happen", "write", "provide", "sit",
    "stand", "lose", "pay", "meet", "include", "continue", "set", "learn", "change", "lead",
    "understand", "watch", "follow", "stop", "create", "speak", "read", "allow", "add", "spend",
    "grow", "open", "walk", "win", "offer", "remember", "love", "consider", "appear", "buy",
    "wait", "serve", "die", "send", "expect", "build", "stay", "fall", "cut", "reach", "kill",
    "remain", "suggest", "raise", "pass", "sell", "require", "report", "decide", "pull",
    "test", "fix", "update", "optimize", "improve", "prevent", "avoid", "ensure", "check", "enable",
    "disable", "configure", "transcribe", "record", "split", "process", "release", "publish"
)

private val GERMAN_DA_SUBORDINATE_FOLLOWERS = setOf(
    "ich", "du", "er", "sie", "es", "wir", "ihr", "man", "sich",
    "der", "die", "das", "den", "dem", "ein", "eine", "einen", "einem", "kein", "keine",
    "dies", "diese", "dieser", "dieses", "mein", "meine", "dein", "deine", "sein", "seine", "unser", "unsere"
)

private val GERMAN_ARTICLE_OR_POSSESSIVE_WORDS = setOf(
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einen", "einem", "einer", "eines",
    "kein", "keine", "keinen", "keinem", "keiner", "keines",
    "mein", "meine", "meinen", "meinem", "meiner", "meines",
    "dein", "deine", "deinen", "deinem", "deiner", "deines",
    "sein", "seine", "seinen", "seinem", "seiner", "seines",
    "unser", "unsere", "unseren", "unserem", "unserer", "unseres",
    "euer", "eure", "euren", "eurem", "eurer",
    "dieser", "diese", "dieses", "diesen", "diesem",
    "jeder", "jede", "jedes", "jeden", "jedem",
    "welcher", "welche", "welches", "welchen", "welchem",
    "im", "ins", "am", "ans", "vom", "zum", "zur", "beim", "fürs", "aufs", "ums"
)

/**
 * Words across English, German, and supported European languages that are always lowercase when they
 * appear mid-sentence (excludes German nouns and English `"I"`).
 */
private val LOWERCASEABLE_CONTINUATION_WORDS = setOf(
    // English function words, pronouns (except I), auxiliaries, modals, common verbs, adverbs, adjectives
    "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
    "my", "your", "his", "its", "our", "their", "mine", "yours", "ours", "theirs",
    "myself", "yourself", "himself", "herself", "itself", "ourselves", "themselves",
    "this", "that", "these", "those", "who", "whom", "whose", "which", "what", "where", "when", "why", "how",
    "there", "here", "whereby", "wherein", "wherever", "whenever", "whatever", "whichever", "whoever",
    "the", "a", "an", "some", "any", "no", "none", "every", "each", "all", "both", "either", "neither",
    "another", "other", "others", "such", "many", "much", "more", "most", "few", "fewer", "fewest",
    "little", "less", "least", "several", "enough", "own", "same", "different", "next", "last",
    "first", "second", "third", "new", "old", "good", "better", "best", "bad", "worse", "worst",
    "big", "small", "long", "short", "high", "low", "great", "little", "right", "wrong", "able",
    "available", "possible", "impossible", "sure", "certain", "clear", "free", "full", "empty",
    "ready", "easy", "hard", "fast", "slow", "early", "late", "important", "whole", "true", "false",
    "and", "or", "nor", "but", "so", "yet", "for", "because", "although", "though", "while", "whereas",
    "if", "unless", "until", "since", "whether", "as", "than", "like", "once",
    "in", "on", "at", "to", "of", "off", "from", "by", "with", "without", "into", "onto", "upon",
    "out", "up", "down", "over", "under", "above", "below", "between", "among", "through", "during",
    "before", "after", "around", "about", "against", "across", "behind", "beyond", "within", "along",
    "towards", "toward", "despite", "except", "plus", "via", "including", "excluding",
    "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "having", "do", "does", "did", "doing", "done",
    "will", "would", "shall", "should", "can", "could", "may", "might", "must", "need", "needs", "needed",
    "isn't", "aren't", "wasn't", "weren't", "haven't", "hasn't", "hadn't",
    "don't", "doesn't", "didn't", "won't", "wouldn't", "shan't", "shouldn't", "can't", "cannot", "couldn't", "mustn't",
    "you're", "you've", "you'll", "you'd", "he's", "he'll", "he'd", "she's", "she'll", "she'd",
    "it's", "it'll", "we're", "we've", "we'll", "we'd", "they're", "they've", "they'll", "they'd",
    "that's", "that'll", "that'd", "there's", "here's", "what's", "who's", "where's", "how's", "let's",
    "not", "no", "yes", "just", "only", "also", "too", "very", "really", "quite", "rather", "almost",
    "nearly", "still", "already", "even", "ever", "never", "always", "often", "sometimes", "usually",
    "maybe", "perhaps", "probably", "actually", "basically", "especially", "particularly", "specifically",
    "currently", "recently", "finally", "immediately", "directly", "completely", "totally", "exactly",
    "simply", "quickly", "slowly", "easily", "automatically", "properly", "correctly",
    "now", "then", "today", "tomorrow", "yesterday", "soon", "later", "again", "back", "away", "together",
    "well", "far", "near", "ahead", "instead", "otherwise", "however", "therefore", "thus", "furthermore",
    "go", "goes", "going", "went", "gone", "get", "gets", "getting", "got", "gotten",
    "make", "makes", "making", "made", "take", "takes", "taking", "took", "taken",
    "see", "sees", "seeing", "saw", "seen", "know", "knows", "knowing", "knew", "known",
    "think", "thinks", "thinking", "thought", "come", "comes", "coming", "came",
    "want", "wants", "wanted", "wanting", "use", "uses", "using", "used",
    "find", "finds", "finding", "found", "give", "gives", "giving", "gave", "given",
    "tell", "tells", "telling", "told", "work", "works", "working", "worked",
    "call", "calls", "calling", "called", "try", "tries", "trying", "tried",
    "ask", "asks", "asking", "asked", "feel", "feels", "feeling", "felt",
    "become", "becomes", "becoming", "became", "leave", "leaves", "leaving", "left",
    "put", "puts", "putting", "mean", "means", "meaning", "meant",
    "keep", "keeps", "keeping", "kept", "let", "lets", "letting",
    "begin", "begins", "beginning", "began", "begun", "seem", "seems", "seeming", "seemed",
    "help", "helps", "helping", "helped", "talk", "talks", "talking", "talked",
    "turn", "turns", "turning", "turned", "start", "starts", "starting", "started",
    "show", "shows", "showing", "showed", "shown", "hear", "hears", "hearing", "heard",
    "play", "plays", "playing", "played", "run", "runs", "running", "ran",
    "move", "moves", "moving", "moved", "live", "lives", "living", "lived",
    "believe", "believes", "believing", "believed", "bring", "brings", "bringing", "brought",
    "happen", "happens", "happening", "happened", "write", "writes", "writing", "wrote", "written",
    "sit", "sits", "sitting", "sat", "stand", "stands", "standing", "stood",
    "lose", "loses", "losing", "lost", "pay", "pays", "paying", "paid",
    "meet", "meets", "meeting", "met", "include", "includes", "including", "included",
    "continue", "continues", "continuing", "continued", "set", "sets", "setting",
    "learn", "learns", "learning", "learned", "change", "changes", "changing", "changed",
    "lead", "leads", "leading", "led", "understand", "understands", "understanding", "understood",
    "watch", "watches", "watching", "watched", "follow", "follows", "following", "followed",
    "stop", "stops", "stopping", "stopped", "create", "creates", "creating", "created",
    "speak", "speaks", "speaking", "spoke", "spoken", "read", "reads", "reading",
    "allow", "allows", "allowing", "allowed", "add", "adds", "adding", "added",
    "spend", "spends", "spending", "spent", "grow", "grows", "growing", "grew", "grown",
    "open", "opens", "opening", "opened", "walk", "walks", "walking", "walked",
    "win", "wins", "winning", "won", "offer", "offers", "offering", "offered",
    "remember", "remembers", "remembering", "remembered", "consider", "considers", "considering", "considered",
    "appear", "appears", "appearing", "appeared", "buy", "buys", "buying", "bought",
    "wait", "waits", "waiting", "waited", "send", "sends", "sending", "sent",
    "build", "builds", "building", "built", "stay", "stays", "staying", "stayed",
    "cut", "cuts", "cutting", "reach", "reaches", "reaching", "reached",
    "remain", "remains", "remaining", "remained", "suggest", "suggests", "suggesting", "suggested",
    "update", "updates", "updating", "updated", "fix", "fixes", "fixing", "fixed",
    "optimize", "optimizes", "optimizing", "optimized", "mess", "messes", "messing", "messed",
    // German function words, pronouns, articles, prepositions, conjunctions, auxiliaries, modals, verbs, adverbs, adjectives
    "ich", "du", "er", "sie", "es", "wir", "ihr", "mich", "dich", "sich", "uns", "euch", "mir", "dir", "ihm", "ihnen",
    "mein", "meine", "meinen", "meinem", "meiner", "meines",
    "dein", "deine", "deinen", "deinem", "deiner", "deines",
    "sein", "seine", "seinen", "seinem", "seiner", "seines",
    "unser", "unsere", "unseren", "unserem", "unserer", "unseres",
    "euer", "eure", "euren", "eurem", "eurer",
    "dies", "diese", "dieser", "dieses", "diesen", "diesem",
    "jener", "jene", "jenes", "jenen", "jenem",
    "welche", "welcher", "welches", "welchen", "welchem",
    "jeder", "jede", "jedes", "jeden", "jedem",
    "wer", "was", "wo", "wann", "warum", "wie", "wohin", "woher", "womit", "wofür", "worüber", "wodurch", "woran", "worauf",
    "man", "jemand", "niemand", "etwas", "nichts", "alles", "alle", "allen", "aller", "allem",
    "viele", "vielen", "vieler", "viel", "wenig", "wenige", "wenigen", "einige", "einigen", "manche", "manchen",
    "andere", "anderen", "anderer", "anderem", "anderes", "beide", "beiden",
    "kein", "keine", "keinen", "keinem", "keiner", "keines",
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
    "und", "oder", "aber", "denn", "sondern", "doch", "jedoch", "weil", "da", "dass", "sodass",
    "ob", "wenn", "als", "falls", "sofern", "sobald", "solange", "bevor", "ehe", "nachdem", "während",
    "indem", "damit", "obwohl", "obgleich", "trotzdem", "deshalb", "deswegen", "darum", "daher",
    "sonst", "außerdem", "zudem", "sowie", "weder", "noch", "entweder", "je", "desto", "umso", "zumal",
    "in", "im", "ins", "an", "am", "ans", "auf", "aufs", "für", "fürs", "mit", "von", "vom",
    "zu", "zum", "zur", "bei", "beim", "nach", "aus", "über", "unter", "vor", "hinter", "neben",
    "zwischen", "durch", "gegen", "ohne", "um", "ums", "wider", "seit", "trotz", "wegen",
    "statt", "anstatt", "außer", "entlang", "gegenüber", "gemäß", "laut", "zufolge", "ab", "bis", "pro", "per",
    "ist", "sind", "war", "waren", "gewesen", "bin", "bist", "seid", "sei", "seien", "wäre", "wären",
    "wird", "werden", "wurde", "wurden", "geworden", "würde", "würden",
    "hat", "haben", "hatte", "hatten", "gehabt", "habe", "hast", "habt", "hätte", "hätten",
    "kann", "können", "konnte", "konnten", "gekonnt", "ре", "kanst", "kannst", "könnt", "könnte", "könnten",
    "muss", "müssen", "musste", "mussten", "gemusst", "musst", "müsst", "müsste", "müssten",
    "soll", "sollen", "sollte", "sollten", "gesollt", "sollst", "sollt",
    "will", "wollen", "wollte", "wollten", "gewollt", "willst", "wollt",
    "darf", "dürfen", "durfte", "durften", "gedurft", "darfst", "dürft", "dürfte", "dürften",
    "mag", "mögen", "mochte", "mochten", "gemocht", "möchte", "möchten", "möchtest",
    "nicht", "auch", "noch", "schon", "erst", "nur", "sehr", "ganz", "recht", "ziemlich", "etwa",
    "fast", "kaum", "vielleicht", "wahrscheinlich", "natürlich", "eigentlich", "wirklich", "immer",
    "nie", "niemals", "oft", "manchmal", "meistens", "heute", "morgen", "gestern", "jetzt", "nun",
    "dann", "danach", "dabei", "dafür", "dagegen", "dahin", "damit", "daneben", "darauf", "daran",
    "darin", "darüber", "darunter", "davon", "davor", "dazu", "dazwischen", "hier", "dort", "gerade",
    "gleich", "eben", "mal", "ja", "nein", "wohl", "ebenfalls", "besonders", "insbesondere",
    "vorbei", "weiter", "wieder", "zurück", "zusammen", "allein", "alleine", "bereit", "fertig",
    "gut", "besser", "besten", "schlecht", "schlechter", "neu", "neue", "neuen", "neuer", "neues", "neuem",
    "alt", "alte", "alten", "alter", "altes", "groß", "große", "großen", "großer", "großes",
    "klein", "kleine", "kleinen", "kleiner", "kleines", "lang", "lange", "langen", "kurz", "kurze", "kurzen",
    "erste", "ersten", "erster", "erstes", "letzte", "letzten", "letzter", "letztes",
    "nächste", "nächsten", "nächster", "nächstes", "eigene", "eigenen", "eigener", "eigenes",
    "gleiche", "gleichen", "ganze", "ganzen", "aktuelle", "aktuellen", "wichtig", "wichtige", "wichtigen",
    "möglich", "mögliche", "möglichen", "schnell", "schneller", "langsam", "einfach", "automatisch", "sofort",
    "mache", "machst", "macht", "machen", "gemacht", "gehe", "gehst", "geht", "gehen", "gegangen",
    "komme", "kommst", "kommt", "kommen", "gekommen", "sehe", "siehst", "sieht", "sehen", "gesehen",
    "sage", "sagst", "sagt", "sagen", "gesagt", "gebe", "gibst", "gibt", "geben", "gegeben",
    "nehme", "nimmst", "nimmt", "nehmen", "genommen", "finde", "findest", "findet", "finden", "gefunden",
    "bleibe", "bleibst", "bleibt", "bleiben", "geblieben", "lasse", "lässt", "lassen", "gelassen",
    "stehe", "stehst", "steht", "stehen", "gestanden", "liege", "liegst", "liegt", "liegen", "gelegen",
    "fahre", "fährst", "fährt", "fahren", "gefahren", "arbeite", "arbeitest", "arbeitet", "arbeiten", "gearbeitet",
    "spreche", "sprichst", "spricht", "sprechen", "gesprochen", "laufe", "läufst", "läuft", "laufen", "gelaufen",
    "versuche", "versuchst", "versucht", "versuchen", "funktioniere", "funktioniert", "funktionieren",
    "teste", "testest", "testet", "testen", "getestet", "nutze", "nutzt", "nutzen", "genutzt",
    "brauche", "brauchst", "braucht", "brauchen", "gebraucht", "denke", "denkst", "denkt", "denken", "gedacht",
    "glaube", "glaubst", "glaubt", "glauben", "weiß", "weißt", "wissen", "gewusst",
    // Romance / Dutch common continuation words
    "et", "ou", "mais", "donc", "car", "ni", "que", "qui", "quoi", "dont", "où", "si", "comme", "quand",
    "parce", "puisque", "lorsque", "quoique", "pour", "dans", "sur", "sous", "avec", "sans", "chez",
    "vers", "contre", "entre", "par", "de", "du", "des", "au", "aux", "le", "la", "les", "un", "une",
    "ce", "cet", "cette", "ces", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
    "notre", "nos", "votre", "vos", "leur", "leurs", "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles",
    "est", "sont", "pas", "plus", "très", "bien", "aussi", "encore", "déjà", "toujours", "jamais",
    "y", "e", "o", "u", "pero", "sino", "porque", "pues", "aunque", "cuando", "donde", "como",
    "para", "por", "con", "sin", "sobre", "hacia", "hasta", "desde", "según", "durante", "mediante",
    "el", "los", "las", "una", "unos", "unas", "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas",
    "mi", "mis", "tus", "su", "sus", "también", "ya", "muy", "más", "menos",
    "ma", "perché", "sebbene", "quando", "dove", "anche", "non", "più", "molto", "il", "lo", "gli", "uno",
    "mas", "embora", "onde", "não", "também", "muito", "mais", "os", "as", "um", "uma",
    "en", "maar", "want", "omdat", "hoewel", "dat", "dit", "deze", "het", "een", "voor", "met", "van", "aan", "op", "uit", "niet", "ook"
)

/**
 * Common proper nouns in English/European languages that must stay capitalized even mid-sentence when
 * `!isGerman` allows general mid-sentence lowercasing.
 */
private val PROPER_NOUNS_KEEP_CAPITALIZED = setOf(
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
    "english", "german", "french", "spanish", "italian", "portuguese", "dutch", "russian", "polish",
    "chinese", "japanese", "korean", "arabic", "hindi", "turkish",
    "america", "american", "europe", "european", "germany", "france", "spain", "italy", "england", "britain", "british",
    "google", "android", "apple", "microsoft", "windows", "linux", "github", "openflow", "voz", "litert", "qualcomm", "snapdragon", "samsung", "pixel"
)

private fun shouldLowercaseMidSentenceToken(
    firstToken: String,
    secondToken: String?,
    lastPrevWord: String,
    isGerman: Boolean,
    allowGeneralEnglishLowercase: Boolean,
    customVocabLower: Set<String>
): Boolean {
    val core = extractWordCore(firstToken)
    if (core.isEmpty()) return false
    val lower = core.lowercase(Locale.ROOT)
    if (lower in customVocabLower) return false
    // Never lowercase English "I" or contractions "I'm", "I've", "I'll", "I'd"
    if (core == "I" || core.startsWith("I'") || core.startsWith("I’")) return false
    // Never lowercase acronyms or CamelCase words (uppercase letter after index 0)
    for (idx in 1 until core.length) {
        if (core[idx].isUpperCase()) return false
    }
    if (!core[0].isUpperCase()) return false

    if (lower in LOWERCASEABLE_CONTINUATION_WORDS) return true

    if (isGerman) {
        // In German, if the previous chunk ended with an article/possessive ("der", "die", "das", "ein", "im")
        // and this word has an inflected adjective ending ("-en", "-em", "-er", "-es", "-e") followed by a
        // capitalized German noun, then this word is an attributive adjective and should be lowercase.
        if (lastPrevWord in GERMAN_ARTICLE_OR_POSSESSIVE_WORDS && secondToken != null) {
            val secondCore = extractWordCore(secondToken)
            val hasAdjectiveSuffix = lower.endsWith("en") || lower.endsWith("em") ||
                lower.endsWith("er") || lower.endsWith("es") || lower.endsWith("e")
            if (hasAdjectiveSuffix && secondCore.isNotEmpty() && secondCore[0].isUpperCase()) {
                return true
            }
        }
        return false
    }

    // In non-German languages (e.g. English), ordinary nouns/gerunds/verbs ("chunking", "text", "bubble")
    // are lowercase mid-sentence unless they are proper nouns.
    if (allowGeneralEnglishLowercase && lower !in PROPER_NOUNS_KEEP_CAPITALIZED) {
        return true
    }
    return false
}

private fun lowercaseFirstLetterOfToken(token: String): String {
    val idx = token.indexOfFirst { it.isLetter() }
    if (idx < 0 || !token[idx].isUpperCase()) return token
    return token.substring(0, idx) + token[idx].lowercaseChar() + token.substring(idx + 1)
}

/**
 * Stitches the transcripts of sequential audio chunks (from either live pause-chunking or offline
 * auto-chunking) into a single, grammatically continuous transcript.
 *
 * Solves the root causes of chunked text degradation:
 * 1. **Boundary Word Deduplication:** Deduplicates 1- or 2-word stutters/overlaps across chunk cuts
 *    (e.g. `"so that."` + `"That the chunking"` -> `"so that the chunking"`).
 * 2. **Dangling Non-Terminal Word Repair:** When Chunk $N$ ends on an article, preposition, conjunction,
 *    or auxiliary (`"the."`, `"to."`, `"that."`, `"and."`, `"der."`, `"die."`, `"weil."`), strips the
 *    synthetic period `.` added by `Voz` and lowercases the continuation in Chunk $N+1$.
 * 3. **Clause-Continuation Punctuation & Casing Reconciliation:** When Chunk $N$ ends with `.` and
 *    Chunk $N+1$ begins with a coordinating or subordinating continuation (`"And ..."`, `"Because ..."`,
 *    `"Or ..."`, `"Which ..."`, `"But ..."`, `"Und ..."`, `"Weil ..."`, `"Dass ..."`), removes or converts
 *    the synthetic `.` to `,` and lowercases the connector while preserving legitimate multi-sentence
 *    boundaries and German noun capitalization.
 */
fun stitchChunkTranscripts(
    chunks: List<String>,
    language: String? = null,
    customVocabulary: List<String> = emptyList()
): String {
    if (chunks.isEmpty()) return ""
    val isGerman = language?.lowercase(Locale.ROOT)?.startsWith("de") == true
    val customVocabLower = if (customVocabulary.isEmpty()) {
        emptySet()
    } else {
        customVocabulary.mapNotNull { term ->
            val clean = extractWordCore(term.trim()).lowercase(Locale.ROOT)
            clean.ifEmpty { null }
        }.toSet()
    }

    val cleanedChunks = ArrayList<String>(chunks.size)
    for (raw in chunks) {
        val norm = normalizeTranscriptText(raw, capitalizeLeadingWord = false)
        if (norm.any { it.isLetterOrDigit() }) {
            cleanedChunks.add(norm)
        }
    }
    if (cleanedChunks.isEmpty()) return ""
    if (cleanedChunks.size == 1) {
        val single = normalizeTranscriptText(cleanedChunks[0], capitalizeLeadingWord = true)
        return applyCustomVocabulary(single, customVocabulary)
    }

    var accumulated = normalizeTranscriptText(cleanedChunks[0], capitalizeLeadingWord = true)
    for (idx in 1 until cleanedChunks.size) {
        accumulated = mergeTwoChunkTranscripts(
            prev = accumulated,
            next = cleanedChunks[idx],
            isGerman = isGerman,
            customVocabLower = customVocabLower
        )
    }

    val finalNormalized = normalizeTranscriptText(accumulated, capitalizeLeadingWord = true)
    return applyCustomVocabulary(finalNormalized, customVocabulary)
}

private fun mergeTwoChunkTranscripts(
    prev: String,
    next: String,
    isGerman: Boolean,
    customVocabLower: Set<String>
): String {
    var prevTrimmed = prev.trim()
    val nextTrimmed = next.trim()
    if (prevTrimmed.isEmpty()) return nextTrimmed
    if (nextTrimmed.isEmpty()) return prevTrimmed

    val prevTokens = prevTrimmed.split(MULTI_SPACE_REGEX).toMutableList()
    val nextTokens = nextTrimmed.split(MULTI_SPACE_REGEX).toMutableList()
    if (prevTokens.isEmpty()) return nextTrimmed
    if (nextTokens.isEmpty()) return prevTrimmed

    // 1. Check 2-word boundary repetition (e.g. "... to the." + "To the store.")
    if (prevTokens.size >= 2 && nextTokens.size >= 2) {
        val prevCore1 = extractWordCore(prevTokens[prevTokens.size - 2]).lowercase(Locale.ROOT)
        val prevCore2 = extractWordCore(prevTokens[prevTokens.size - 1]).lowercase(Locale.ROOT)
        val nextCore1 = extractWordCore(nextTokens[0]).lowercase(Locale.ROOT)
        val nextCore2 = extractWordCore(nextTokens[1]).lowercase(Locale.ROOT)
        if (prevCore1.isNotEmpty() && prevCore2.isNotEmpty() &&
            prevCore1 == nextCore1 && prevCore2 == nextCore2
        ) {
            val secondNextTrailingPunct = nextTokens[1].takeLastWhile { !it.isLetterOrDigit() }
            nextTokens.removeAt(0)
            nextTokens.removeAt(0)
            val strippedLastPrev = prevTokens.last().dropLastWhile { it in ".,!?;:-–—" }
            prevTokens[prevTokens.lastIndex] = if (nextTokens.isEmpty() && secondNextTrailingPunct.isNotEmpty()) {
                strippedLastPrev + secondNextTrailingPunct
            } else {
                strippedLastPrev
            }
            if (nextTokens.isEmpty()) {
                return prevTokens.joinToString(" ")
            }
            prevTrimmed = prevTokens.joinToString(" ")
        }
    }

    // 2. Check 1-word boundary repetition (e.g. "... so that." + "That the chunking...")
    val lastPrevCore = extractWordCore(prevTokens.last()).lowercase(Locale.ROOT)
    val firstNextCore = extractWordCore(nextTokens.first()).lowercase(Locale.ROOT)
    if (lastPrevCore.isNotEmpty() && lastPrevCore == firstNextCore) {
        val dupNextTrailingPunct = nextTokens.first().takeLastWhile { !it.isLetterOrDigit() }
        nextTokens.removeAt(0)
        val strippedLastPrev = prevTokens.last().dropLastWhile { it in ".,!?;:-–—" }
        prevTokens[prevTokens.lastIndex] = if (nextTokens.isEmpty() && dupNextTrailingPunct.isNotEmpty()) {
            strippedLastPrev + dupNextTrailingPunct
        } else {
            strippedLastPrev
        }
        if (nextTokens.isEmpty()) {
            return prevTokens.joinToString(" ")
        }
        prevTrimmed = prevTokens.joinToString(" ")
    }

    val updatedLastPrevCore = extractWordCore(prevTokens.last()).lowercase(Locale.ROOT)
    val firstNextToken = nextTokens[0]
    val firstNextWord = extractWordCore(firstNextToken).lowercase(Locale.ROOT)
    val secondNextToken = nextTokens.getOrNull(1)
    val secondNextWord = secondNextToken?.let { extractWordCore(it).lowercase(Locale.ROOT) } ?: ""

    val prevEndsWithPeriod = prevTrimmed.endsWith(".") && !prevTrimmed.endsWith("...")
    val prevEndsWithTerminal = prevEndsWithPeriod ||
        prevTrimmed.endsWith("!") ||
        prevTrimmed.endsWith("?") ||
        prevTrimmed.endsWith("…") ||
        prevTrimmed.endsWith("...")

    // Case A: Previous chunk ends on a dangling word ("the.", "to.", "that.", "and.", "der.", "weil.")
    if (updatedLastPrevCore in DANGLING_CHUNK_END_WORDS) {
        val cleanedPrev = prevTrimmed.dropLastWhile { it in ".,!?;:-–—" }
        if (shouldLowercaseMidSentenceToken(
                firstToken = firstNextToken,
                secondToken = secondNextToken,
                lastPrevWord = updatedLastPrevCore,
                isGerman = isGerman,
                allowGeneralEnglishLowercase = true,
                customVocabLower = customVocabLower
            )
        ) {
            nextTokens[0] = lowercaseFirstLetterOfToken(firstNextToken)
        }
        return "$cleanedPrev ${nextTokens.joinToString(" ")}"
    }

    // Case B: Previous chunk does NOT end with terminal sentence punctuation (ends with bare word or `,`, `;`, `:`, `-`)
    if (!prevEndsWithTerminal) {
        val cleanedPrev = if (prevTrimmed.endsWith(",") && updatedLastPrevCore in NO_COMMA_AFTER_WORDS) {
            prevTrimmed.dropLast(1)
        } else {
            prevTrimmed
        }
        if (shouldLowercaseMidSentenceToken(
                firstToken = firstNextToken,
                secondToken = secondNextToken,
                lastPrevWord = updatedLastPrevCore,
                isGerman = isGerman,
                allowGeneralEnglishLowercase = true,
                customVocabLower = customVocabLower
            )
        ) {
            nextTokens[0] = lowercaseFirstLetterOfToken(firstNextToken)
        }
        return "$cleanedPrev ${nextTokens.joinToString(" ")}"
    }

    // Case C: Previous chunk ends with a synthetic `.` and next chunk begins with a clause-continuation connector
    if (prevEndsWithPeriod && firstNextWord !in customVocabLower) {
        val isTwoWordNoCommaConnector =
            (firstNextWord == "so" && secondNextWord == "that") ||
                (firstNextWord == "as" && secondNextWord == "well") ||
                (firstNextWord == "in" && secondNextWord == "order") ||
                (firstNextWord == "or" && secondNextWord == "at")
        val isThatContinuation = firstNextWord == "that" && secondNextWord in THAT_CLAUSE_FOLLOWERS
        val isToInfinitiveContinuation = firstNextWord == "to" && secondNextWord in TO_INFINITIVE_FOLLOWERS
        val isForContinuation = firstNextWord == "for" &&
            secondNextWord.isNotEmpty() &&
            secondNextWord != "example" &&
            secondNextWord != "instance" &&
            !firstNextToken.endsWith(",")

        val isNoCommaContinuation = firstNextWord in NO_COMMA_CONTINUATION_CONNECTORS ||
            isTwoWordNoCommaConnector ||
            isThatContinuation ||
            isToInfinitiveContinuation ||
            isForContinuation

        val isTwoWordCommaConnector =
            (firstNextWord == "even" && secondNextWord == "though") ||
                (firstNextWord == "such" && secondNextWord == "as") ||
                (firstNextWord == "vor" && secondNextWord == "allem")
        val isGermanDaSubordinate = isGerman && firstNextWord == "da" && secondNextWord in GERMAN_DA_SUBORDINATE_FOLLOWERS

        val isCommaContinuation = !isNoCommaContinuation && (
            firstNextWord in COMMA_CONTINUATION_CONNECTORS ||
                isTwoWordCommaConnector ||
                isGermanDaSubordinate
            )

        if (isNoCommaContinuation) {
            val prevWithoutPeriod = prevTrimmed.dropLast(1)
            nextTokens[0] = lowercaseFirstLetterOfToken(firstNextToken)
            return "$prevWithoutPeriod ${nextTokens.joinToString(" ")}"
        }

        if (isCommaContinuation) {
            val prevWithComma = prevTrimmed.dropLast(1) + ","
            nextTokens[0] = lowercaseFirstLetterOfToken(firstNextToken)
            return "$prevWithComma ${nextTokens.joinToString(" ")}"
        }
    }

    return "$prevTrimmed ${nextTokens.joinToString(" ")}"
}

/**
 * Compiled per-term regex cache. `applyCustomVocabulary` runs once per audio chunk (and again per word-level
 * pass), so recompiling `Regex(...)` for every vocabulary term on every call was pure allocation churn.
 */
private val CUSTOM_VOCAB_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
private const val CUSTOM_VOCAB_REGEX_CACHE_MAX = 512

private fun customVocabularyRegex(trimmed: String): Regex {
    CUSTOM_VOCAB_REGEX_CACHE[trimmed]?.let { return it }
    val escaped = Regex.escape(trimmed)
    val leadingBoundary = if (trimmed.first().isLetterOrDigit()) "\\b" else "(?<!\\w)"
    val trailingBoundary = if (trimmed.last().isLetterOrDigit()) "\\b" else "(?!\\w)"
    val compiled = Regex("$leadingBoundary$escaped$trailingBoundary", RegexOption.IGNORE_CASE)
    if (CUSTOM_VOCAB_REGEX_CACHE.size >= CUSTOM_VOCAB_REGEX_CACHE_MAX) {
        CUSTOM_VOCAB_REGEX_CACHE.clear()
    }
    CUSTOM_VOCAB_REGEX_CACHE[trimmed] = compiled
    return compiled
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
        // Replace case-insensitive matches with the user's canonical casing, supporting both
        // alphanumeric terms and terms starting/ending with punctuation (e.g. ".NET", "C++").
        updated = updated.replace(
            customVocabularyRegex(trimmed),
            Regex.escapeReplacement(trimmed)
        )
    }
    return updated
        .split(' ')
        .joinToString(" ") { token -> biasWordWithCustomVocabulary(token, customVocabulary) }
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
        // Zero-allocation single-character edit distance biasing for words >= 5 characters
        if (core.length >= 5 && cleanTerm.length >= 5 && isSingleEditDistanceIgnoreCase(core, cleanTerm)) {
            return leadingPunct + cleanTerm + trailingPunct
        }
    }
    return wordText
}

/**
 * Fast $O(N)$ zero-allocation check for whether [a] and [b] are at most 1 Levenshtein edit apart
 * (case-insensitive), avoiding `IntArray` allocations per word-vocabulary pair.
 */
internal fun isSingleEditDistanceIgnoreCase(a: String, b: String): Boolean {
    val lenA = a.length
    val lenB = b.length
    if (abs(lenA - lenB) > 1) return false

    var i = 0
    var j = 0
    var edits = 0
    while (i < lenA && j < lenB) {
        if (a[i].equals(b[j], ignoreCase = true)) {
            i++
            j++
        } else {
            edits++
            if (edits > 1) return false
            when {
                lenA > lenB -> i++
                lenB > lenA -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
    }
    if (i < lenA || j < lenB) {
        edits++
    }
    return edits == 1
}
