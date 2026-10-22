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
import ai.desertant.clear.Mastering
import ai.desertant.ear.Ear
import ai.desertant.uhm.FillerSpan
import ai.desertant.uhm.FrameClassification
import ai.desertant.uhm.Uhm
import ai.desertant.uhm.mergeFillerFrames
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
    UNINITIALIZED("Not Loaded"),
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
}

/**
 * Observable snapshot of the speech pipeline's loaded models, active hardware accelerator,
 * detected NPU profile, and most recent inference performance.
 */
data class PipelineStatus(
    val phase: PipelinePhase = PipelinePhase.UNINITIALIZED,
    val configuredAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO,
    val activeAcceleratorLabel: String = "Auto (NPU / GPU / CPU)",
    val detectedNpuSummary: String = NpuHardwareManager.detectHardwareProfile().summaryLabel,
    val dynamicLoadingMode: String = "Adaptive Stage Management",
    val modelStates: Map<DesertAntModel, ModelLoadState> = mapOf(
        DesertAntModel.VOZ to ModelLoadState.STANDBY,
        DesertAntModel.EAR to ModelLoadState.STANDBY,
        DesertAntModel.CLEAR to ModelLoadState.STANDBY,
        DesertAntModel.UHM to ModelLoadState.STANDBY
    ),
    val statusMessage: String = "Models on disk — ready to load",
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
 * Orchestrates the 4-stage Desert Ant Labs on-device audio AI pipeline:
 * 1. `Clear` (`clear-studio` profile) — Speech enhancement & noise suppression
 * 2. `Ear` -> `Uhm` — Sequential language identification & 20 ms acoustic filler frame detection
 * 3. `Voz` — Multilingual speech-to-text with word-level timestamps (80 ms resolution)
 * 4. Temporal Filler Subtraction & Text Normalization
 *
 * Stability, OOM Prevention & NPU Hardware Acceleration Guarantees:
 * - **JVM + System RAM Awareness**: Monitors ART JVM heap headroom (`jvmFreeHeadroomMb`)
 *   alongside `ActivityManager.MemoryInfo`, preventing `java.lang.OutOfMemoryError` during
 *   JNI/FFI array serialization even on 8–16 GB RAM devices.
 * - **Single-Window Sequential `Clear` Execution**: Feeds `Clear` in bounded `<= 1.90s` (`30,400`
 *   sample) windows with 20 ms crossfades so `libClearAndroid.so` (`200`-frame STFT window) always
 *   executes with `count = 1` on a single session instead of spawning 2 concurrent OpenCL `TaskGroup`
 *   threads that collide on Adreno/LiteRT GPU contexts.
 * - **Silence-Aware Smart Audio Chunking**: Automatically splits long dictations (> 20s normal /
 *   > 14s tight memory) at natural speech pauses so Conformer encoder attention tensors and FFI
 *   buffers stay strictly bounded regardless of dictation length.
 * - **Qualcomm Hexagon & Universal Mobile NPU Support**: Configures QNN HTP / FastRPC CDSP,
 *   MediaTek NeuroPilot APU, Google Tensor TPU, Samsung Exynos ENN, HiSilicon Kirin HiAI, and NNAPI
 *   via [NpuHardwareManager].
 */
class SmartPipeline(
    context: Context,
    initialSettings: ModelSettings = ModelSettings.load(context)
) {

    companion object {
        private const val TAG = "SmartPipeline"
        const val SAMPLE_RATE_HZ: Double = 16_000.0
        const val IDLE_TIMEOUT_MS: Long = 20 * 60_000L // 20 minutes warm retention
        const val FILLER_MERGE_MAX_GAP_MS: Long = 40L
        const val FILLER_BOUNDARY_TOLERANCE_MS: Long = 80L
        const val FILLER_OVERLAP_THRESHOLD: Double = 0.50
        private const val SILENCE_THRESHOLD: Float = 0.0035f
        private const val SILENCE_PAD_SAMPLES: Int = 2_560 // 160 ms @ 16 kHz

        // Chunking bounds to guarantee bounded Conformer & STFT memory on arbitrarily long dictations
        const val DEFAULT_MAX_CHUNK_SAMPLES: Int = 20 * 16_000 // 20.0s @ 16 kHz
        const val DEFAULT_MIN_CHUNK_SAMPLES: Int = 11 * 16_000 // 11.0s @ 16 kHz
        const val LOW_MEM_MAX_CHUNK_SAMPLES: Int = 14 * 16_000 // 14.0s @ 16 kHz
        const val LOW_MEM_MIN_CHUNK_SAMPLES: Int = 8 * 16_000  // 8.0s @ 16 kHz
        private const val MAX_EAR_WINDOW_SAMPLES: Int = 88_000 // 5.5s @ 16 kHz

        // Native libClearAndroid.so uses a 200-frame (2.00s) STFT window and spawns 2 concurrent
        // OpenCL threads when numFrames > 200. Slicing at 30,400 samples (1.90s = 192 STFT frames)
        // guarantees count == 1 inside ParallelRuns.run, eliminating multi-threaded OpenCL SIGSEGVs.
        const val MAX_CLEAR_SINGLE_WINDOW_SAMPLES: Int = 30_400 // 1.90s @ 16 kHz
        const val CLEAR_WINDOW_CROSSFADE_SAMPLES: Int = 320     // 20 ms @ 16 kHz

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

    private var appliedAccelerator: HardwareAccelerator? = null
    private var appliedLowMemoryMode: Boolean? = null
    private var appliedSettingsSnapshot: ModelSettings? = null
    private var lastForcedGcAtMs: Long = 0L
    private var activeAcceleratorDescription: String =
        NpuHardwareManager.configureNativeRuntime(appContext, initialSettings.hardwareAccelerator, false)

    private var voz: Voz? = null
    private var ear: Ear? = null
    private var clear: Clear? = null
    private var uhm: Uhm? = null

    private var vozWarmedUp: Boolean = false
    private var earWarmedUp: Boolean = false
    private var clearWarmedUp: Boolean = false

    @Volatile
    private var isTranscribing: Boolean = false

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
                messageOverride = "Loading enabled models on $activeAcceleratorDescription..."
            )

            val vozLoaded = ensureVozLocked(currentSettings, warmUp = warmUp)
            ensureAuxiliaryModelsLocked(currentSettings, warmUp = warmUp && !memReport.isAnyMemoryTight)
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
     */
    suspend fun reloadSettings(newSettings: ModelSettings) = withContext(Dispatchers.Default) {
        settings = newSettings
        pipelineMutex.withLock {
            val snapshot = settings
            val accelChanged = appliedAccelerator != null && appliedAccelerator != snapshot.hardwareAccelerator
            if (!accelChanged &&
                appliedSettingsSnapshot == snapshot &&
                voz != null &&
                areEnabledModelsReadyOrStandbyLocked(snapshot)
            ) {
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

            val memReport = evaluateMemoryPressure()
            applyHardwareAcceleratorSettingsLocked(snapshot.hardwareAccelerator, memReport.isAnyMemoryTight)

            if (accelChanged) {
                Log.i(TAG, "Hardware accelerator changed to ${snapshot.hardwareAccelerator.displayName}; reloading sessions.")
                DebugLogManager.logInfo(
                    TAG,
                    "Hardware accelerator changed to ${snapshot.hardwareAccelerator.displayName}; closing active sessions",
                    includeMemory = true
                )
                closeClearLocked()
                closeEarLocked()
                closeUhmLocked()
                closeVozLocked()
                System.gc()
            } else {
                if (!snapshot.clearEnabled) closeClearLocked()
                if (!snapshot.earEnabled) closeEarLocked()
                if (!snapshot.uhmEnabled) closeUhmLocked()
            }

            updateStatusSnapshot(
                activeSettings = snapshot,
                phaseOverride = PipelinePhase.LOADING,
                messageOverride = "Updating model pipeline ($activeAcceleratorDescription)..."
            )

            val vozOk = ensureVozLocked(snapshot, warmUp = true)
            ensureAuxiliaryModelsLocked(snapshot, warmUp = !memReport.isAnyMemoryTight)
            appliedSettingsSnapshot = snapshot
            resetIdleEvictionTimerLocked()

            val phase = if (vozOk) PipelinePhase.READY else PipelinePhase.UNINITIALIZED
            val count = countLoadedModelsLocked(snapshot)
            val msg = if (vozOk) {
                "$count/${snapshot.enabledModels().size} models ready ($activeAcceleratorDescription)"
            } else {
                "Waiting for model download"
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
     * Executes the 4-stage Desert Ant audio pipeline on 16 kHz mono PCM [samples] in-memory.
     *
     * Key Stability & Anti-OOM Mechanisms:
     * 1. Trims leading/trailing dead air before touching any native buffers.
     * 2. Runs `Ear` language identification once on a short `<= 5.5s` speech slice.
     * 3. Runs `Clear` in single-window (`<= 1.90s` / `30,400` sample) sequential slices with
     *    20 ms crossfades so `libClearAndroid.so` never spawns 2 concurrent OpenCL `TaskGroup`
     *    threads on the same GPU.
     * 4. Splits long recordings at natural silence valleys into bounded chunks (`<= 20s` normal,
     *    `<= 14s` low memory) so `Clear`, `Uhm`, and `Voz` never exceed JVM or native RAM limits.
     */
    suspend fun transcribe(
        samples: FloatArray,
        onStageProgress: ((String) -> Unit)? = null,
        languageHint: String? = null,
        onLanguageResolved: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""

        // Fast silence trim before acquiring native sessions (uses a whisper-sensitive 0.0020f floor and 400 ms
        // margin so soft trailing fricatives/plosives at the end of a dictation are never clipped).
        val trimmedSamples = trimLeadingAndTrailingSilence(
            samples = samples,
            threshold = 0.0020f,
            padSamples = 6_400
        )
        if (trimmedSamples.isEmpty()) return@withContext ""

        val audioDurationMs = ((trimmedSamples.size.toDouble() / SAMPLE_RATE_HZ) * 1000.0).roundToLong()

        pipelineMutex.withLock {
            idleEvictionJob?.cancel()
            idleEvictionJob = null
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

                val audioChunks = splitAudioIntoSmartChunks(
                    samples = trimmedSamples,
                    maxChunkSamples = maxChunkSamples,
                    minChunkSamples = minChunkSamples,
                    silenceThreshold = chunkSettings.silenceThreshold
                )

                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() start • raw=${samples.size}, trimmed=${trimmedSamples.size} (${audioDurationMs}ms), " +
                        "chunks=${audioChunks.size}, criticalStaging=$dynamicStageRelease, " +
                        "jvmFree=${initialMem.jvmFreeHeadroomMb}MB, sysAvail=${initialMem.systemAvailMb}MB",
                    includeMemory = true
                )

                if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.VOZ)) {
                    throw IllegalStateException("Voz speech recognition model is not downloaded.")
                }

                // =========================================================================
                // Stage 1: Spoken Language Identification (`Ear`) on a bounded 5.5s slice
                // =========================================================================
                val resolvedLanguage = languageHint?.takeIf { it in Voz.supportedLanguages }
                    ?: resolveLanguageLocked(
                        samples = audioChunks.first(),
                        activeSettings = activeSettings,
                        onStageProgress = onStageProgress
                    )
                onLanguageResolved?.invoke(resolvedLanguage)
                if (dynamicStageRelease && ear != null) {
                    DebugLogManager.logInfo(TAG, "Critical memory staging: releasing Ear session before heavy stages")
                    closeEarLocked()
                }

                // =========================================================================
                // Stage 2..4: Process each bounded audio chunk sequentially
                // =========================================================================
                val segmentTranscripts = ArrayList<String>(audioChunks.size)
                val totalChunks = audioChunks.size

                for ((chunkIdx, rawChunk) in audioChunks.withIndex()) {
                    val chunkSuffix = if (totalChunks > 1) " (${chunkIdx + 1}/$totalChunks)" else ""
                    val preparedChunk = prepareChunkForInference(
                        samples = rawChunk,
                        minLeadSilencePadSamples = 5_120,
                        minTailSilencePadSamples = 10_240,
                        minTotalSamples = 25_600,
                        edgeFadeSamples = 160,
                        silenceThreshold = 0.0020f
                    )
                    ensureMemoryHeadroomForFfiLocked(preparedChunk.size)

                    // Stage 2A: Audio Enhancement (`Clear` when enabled, or lightweight speech leveling when disabled)
                    val enhancedChunk = runClearStageLocked(
                        rawSamples = preparedChunk,
                        activeSettings = activeSettings,
                        stageSuffix = chunkSuffix,
                        onStageProgress = onStageProgress
                    )

                    if (dynamicStageRelease && clear != null) {
                        DebugLogManager.logInfo(TAG, "Critical memory staging: releasing Clear session before Voz$chunkSuffix")
                        closeClearLocked()
                    }

                    // Stage 2B: Acoustic Filler Detection (`Uhm`)
                    val uhmFrames = runUhmStageLocked(
                        samples = enhancedChunk,
                        activeSettings = activeSettings,
                        stageSuffix = chunkSuffix,
                        onStageProgress = onStageProgress
                    )

                    // Stage 3: Multilingual Speech-to-Text (`Voz`)
                    ensureMemoryHeadroomForFfiLocked(enhancedChunk.size)
                    setModelActiveStatusLocked(
                        activeSettings,
                        DesertAntModel.VOZ,
                        "Transcribing with Voz ($resolvedLanguage)$chunkSuffix..."
                    )
                    onStageProgress?.invoke("Voz ($resolvedLanguage)$chunkSuffix")
                    DebugLogManager.logMemorySnapshot("Before Voz.transcribe ($resolvedLanguage)$chunkSuffix")

                    val transcription = runVozWithRetryLocked(
                        samples = enhancedChunk,
                        language = resolvedLanguage,
                        customVocabulary = activeSettings.customVocabulary,
                        activeSettings = activeSettings
                    )

                    // Stage 4: Temporal Filler Subtraction & Text Normalization
                    val segmentText = reconcileTranscript(
                        transcription = transcription,
                        uhmFrames = uhmFrames,
                        uhmEnabled = activeSettings.uhmEnabled,
                        customVocabulary = activeSettings.customVocabulary
                    )
                    if (segmentText.isNotBlank()) {
                        segmentTranscripts.add(segmentText)
                    }
                }

                val finalTranscript = stitchChunkTranscripts(
                    chunks = segmentTranscripts,
                    language = resolvedLanguage,
                    customVocabulary = activeSettings.customVocabulary
                )

                // Re-attach auxiliary models in ready state if memory is healthy
                val postMem = evaluateMemoryPressure()
                if (!postMem.isCriticalPressure) {
                    ensureAuxiliaryModelsLocked(activeSettings, warmUp = false)
                }

                val totalInferenceMs = (SystemClock.elapsedRealtime() - inferenceStartMs).coerceAtLeast(1L)
                _pipelineStatus.value = _pipelineStatus.value.copy(
                    lastInferenceMs = totalInferenceMs,
                    lastAudioDurationMs = audioDurationMs
                )
                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() completed in ${totalInferenceMs}ms for ${audioDurationMs}ms audio " +
                        "(chunks=$totalChunks, chars=${finalTranscript.length}, lang=$resolvedLanguage)",
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
                val count = countLoadedModelsLocked(snap)
                updateStatusSnapshot(
                    activeSettings = snap,
                    phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                    messageOverride = "$count/${snap.enabledModels().size} models ready ($activeAcceleratorDescription)"
                )
                resetIdleEvictionTimerLocked()
            }
        }
    }

    /**
     * Evicts optional models (`Clear`, `Ear`, `Uhm`) from native RAM while keeping `Voz` resident.
     */
    suspend fun evictOptionalModels() = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            if (!isTranscribing) {
                Log.i(TAG, "Evicting optional models (Clear, Ear, Uhm) to free native RAM; keeping Voz resident.")
                DebugLogManager.logInfo(TAG, "Evicting optional models (Clear, Ear, Uhm) from native RAM", includeMemory = true)
                closeClearLocked()
                closeEarLocked()
                closeUhmLocked()
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
     * Handles Android OS memory pressure callbacks (`TRIM_MEMORY_RUNNING_LOW` or higher).
     */
    fun onTrimMemory(level: Int) {
        DebugLogManager.logTrimMemory("SmartPipeline", level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !isTranscribing) {
            idleScope.launch {
                evictOptionalModels()
            }
        }
    }

    /**
     * Releases all native LiteRT sessions (`Clear`, `Ear`, `Uhm`, and `Voz`).
     */
    suspend fun shutdown() = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            idleEvictionJob?.cancel()
            idleEvictionJob = null
            closeClearLocked()
            closeEarLocked()
            closeUhmLocked()
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
     * Rate-limited so sequential Clear slices / live chunks do not trigger repeated stop-the-world GC pauses.
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

    private fun areEnabledModelsReadyOrStandbyLocked(activeSettings: ModelSettings): Boolean {
        if (voz == null) return false
        val critical = evaluateMemoryPressure().isCriticalPressure
        if (!critical) {
            if (activeSettings.earEnabled &&
                DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR) &&
                ear == null
            ) {
                return false
            }
            if (activeSettings.clearEnabled &&
                DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR) &&
                clear == null
            ) {
                return false
            }
        }
        if (activeSettings.uhmEnabled &&
            DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.UHM) &&
            uhm == null
        ) {
            return false
        }
        return true
    }

    private suspend fun runClearStageLocked(
        rawSamples: FloatArray,
        activeSettings: ModelSettings,
        stageSuffix: String = "",
        onStageProgress: ((String) -> Unit)?
    ): FloatArray {
        if (!activeSettings.clearEnabled) {
            return conditionAudioWhenClearDisabled(rawSamples)
        }
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            return conditionAudioWhenClearDisabled(rawSamples)
        }

        val mem = evaluateMemoryPressure()
        if (mem.isCriticalPressure) {
            Log.i(TAG, "Skipping Clear due to critical memory pressure (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB).")
            DebugLogManager.logWarn(
                TAG,
                "Skipping Clear stage due to critical memory pressure (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB)"
            )
            closeClearLocked()
            return conditionAudioWhenClearDisabled(rawSamples)
        }

        val clearInstance = clear ?: run {
            val clearDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.CLEAR).absolutePath
            try {
                Clear(appContext, clearDir).also { clear = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to create Clear instance: ${t.message}")
                DebugLogManager.logWarn(TAG, "Failed to create Clear instance", t)
                return conditionAudioWhenClearDisabled(rawSamples)
            }
        }

        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.CLEAR, "Enhancing audio with Clear$stageSuffix...")
            onStageProgress?.invoke("Clear$stageSuffix")
            DebugLogManager.logMemorySnapshot("Before Clear.enhance$stageSuffix (${rawSamples.size} samples)")

            // Explicitly set sampleRate = 16000.0 and Mastering.BYPASS so Clear never upsamples output to 48 kHz.
            // Feed Clear in sequential <= 1.90s (30,400 sample) windows with 20ms crossfade so native
            // libClearAndroid.so (which uses 200-frame / 2.00s STFT windows) always sees count == 1
            // and never spawns 2 concurrent Swift TaskGroup OpenCL threads on the same GPU.
            val options = ai.desertant.clear.Options(
                strength = 1.0,
                mastering = Mastering.BYPASS,
                sampleRate = SAMPLE_RATE_HZ,
                channelMode = ChannelMode.MONO
            )
            val enhanced = enhanceAudioInSingleWindowSlices(
                samples = rawSamples,
                maxWindowSamples = MAX_CLEAR_SINGLE_WINDOW_SAMPLES,
                crossfadeSamples = CLEAR_WINDOW_CROSSFADE_SAMPLES
            ) { slice ->
                ensureMemoryHeadroomForFfiLocked(slice.size)
                clearInstance.enhance(
                    samples = slice,
                    sampleRate = SAMPLE_RATE_HZ,
                    options = options
                ).samples
            }
            clearWarmedUp = true
            DebugLogManager.logMemorySnapshot("After Clear.enhance$stageSuffix (${enhanced.size} samples)")
            if (enhanced.isNotEmpty()) enhanced else conditionAudioWhenClearDisabled(rawSamples)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Clear enhancement failed, falling back to conditioned PCM audio: ${t.message}")
            DebugLogManager.logWarn(TAG, "Clear enhancement failed, falling back to conditioned PCM audio", t)
            closeClearLocked()
            System.gc()
            conditionAudioWhenClearDisabled(rawSamples)
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
            val earInput = if (!activeSettings.clearEnabled) {
                conditionAudioWhenClearDisabled(rawEarInput)
            } else {
                rawEarInput
            }
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

    private suspend fun runUhmStageLocked(
        samples: FloatArray,
        activeSettings: ModelSettings,
        stageSuffix: String = "",
        onStageProgress: ((String) -> Unit)?
    ): List<FrameClassification> {
        if (!activeSettings.uhmEnabled) return emptyList()
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.UHM)) {
            return emptyList()
        }
        val mem = evaluateMemoryPressure()
        if (mem.isCriticalPressure) {
            DebugLogManager.logWarn(TAG, "Skipping Uhm stage due to critical memory pressure")
            return emptyList()
        }

        val uhmInstance = uhm ?: run {
            val uhmDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.UHM).absolutePath
            try {
                Uhm(appContext, uhmDir).also { uhm = it }
            } catch (_: Throwable) {
                return emptyList()
            }
        }

        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.UHM, "Analyzing hesitations with Uhm$stageSuffix...")
            onStageProgress?.invoke("Uhm$stageSuffix")
            uhmInstance.detect(samples, SAMPLE_RATE_HZ)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Uhm filler inference failed; resetting Uhm session and bypassing filler removal: ${t.message}")
            DebugLogManager.logWarn(TAG, "Uhm filler inference failed; resetting Uhm session and bypassing filler removal", t)
            closeUhmLocked()
            emptyList()
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
            closeClearLocked()
            closeEarLocked()
            closeUhmLocked()
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
                val warmupSilence = FloatArray(3_200) // 200 ms silent frame
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

    private suspend fun ensureAuxiliaryModelsLocked(activeSettings: ModelSettings, warmUp: Boolean) {
        val mem = evaluateMemoryPressure()

        if (mem.isCriticalPressure) {
            DebugLogManager.logInfo(
                TAG,
                "Critical memory pressure detected (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB); auxiliary models will stage on-demand"
            )
        }

        // 1. Ear
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

        // 2. Clear — pre-load and warm up alongside Voz so OpenCL/LiteRT contexts are compiled once
        // on the background thread rather than mid-dictation
        if (activeSettings.clearEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            if (clear == null && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.CLEAR,
                        state = ModelLoadState.LOADING,
                        message = "Loading Clear noise suppression..."
                    )
                    val clearDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.CLEAR).absolutePath
                    clear = Clear(appContext, clearDir)
                    clearWarmedUp = false
                    DebugLogManager.logInfo(TAG, "Clear model loaded into RAM", includeMemory = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not initialize Clear: ${t.message}")
                    DebugLogManager.logError(TAG, "Could not initialize Clear model", t)
                    closeClearLocked()
                }
            }
            val currentClear = clear
            if (warmUp && currentClear != null && !clearWarmedUp && !mem.isCriticalPressure) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.CLEAR,
                        state = ModelLoadState.LOADING,
                        message = "Warming up Clear noise suppression..."
                    )
                    val warmupSilence = FloatArray(1_600)
                    currentClear.enhance(
                        samples = warmupSilence,
                        sampleRate = SAMPLE_RATE_HZ,
                        options = ai.desertant.clear.Options(
                            strength = 1.0,
                            mastering = Mastering.BYPASS,
                            sampleRate = SAMPLE_RATE_HZ,
                            channelMode = ChannelMode.MONO
                        )
                    )
                    clearWarmedUp = true
                    DebugLogManager.logInfo(TAG, "Clear accelerator warm-up complete", includeMemory = true)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Clear warm-up deferred: ${t.message}")
                    DebugLogManager.logWarn(TAG, "Clear warm-up deferred", t)
                }
            }
        } else if (!activeSettings.clearEnabled) {
            closeClearLocked()
        }

        // 3. Uhm (lightweight JVM acoustic detector, < 1 MB resident footprint)
        if (activeSettings.uhmEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.UHM)) {
            if (uhm == null) {
                try {
                    updateSingleModelStateLocked(
                        activeSettings = activeSettings,
                        model = DesertAntModel.UHM,
                        state = ModelLoadState.LOADING,
                        message = "Loading Uhm filler detector..."
                    )
                    val uhmDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.UHM).absolutePath
                    uhm = Uhm(appContext, uhmDir)
                    DebugLogManager.logInfo(TAG, "Uhm model loaded into RAM", includeMemory = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not initialize Uhm: ${t.message}")
                    DebugLogManager.logError(TAG, "Could not initialize Uhm model", t)
                    closeUhmLocked()
                }
            }
        } else if (!activeSettings.uhmEnabled) {
            closeUhmLocked()
        }
    }

    private fun countLoadedModelsLocked(activeSettings: ModelSettings): Int {
        var count = 0
        if (voz != null) count++
        if (activeSettings.earEnabled && ear != null) count++
        if (activeSettings.clearEnabled && clear != null) count++
        if (activeSettings.uhmEnabled && uhm != null) count++
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
            val instanceLoaded = when (model) {
                DesertAntModel.VOZ -> voz != null
                DesertAntModel.EAR -> ear != null
                DesertAntModel.CLEAR -> clear != null
                DesertAntModel.UHM -> uhm != null
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
            phase = if (state == ModelLoadState.ACTIVE) PipelinePhase.TRANSCRIBING else PipelinePhase.LOADING,
            configuredAccelerator = activeSettings.hardwareAccelerator,
            activeAcceleratorLabel = activeAcceleratorDescription,
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

    private fun updateStatusSnapshot(
        activeSettings: ModelSettings,
        phaseOverride: PipelinePhase?,
        messageOverride: String?
    ) {
        val states = computeModelStatesSnapshot(activeSettings)
        val loaded = countLoadedModelsLocked(activeSettings)
        val enabled = activeSettings.enabledModels().size
        val resolvedPhase = phaseOverride ?: when {
            isTranscribing -> PipelinePhase.TRANSCRIBING
            voz != null -> PipelinePhase.READY
            else -> PipelinePhase.UNINITIALIZED
        }
        val resolvedMsg = messageOverride ?: _pipelineStatus.value.statusMessage
        _pipelineStatus.value = _pipelineStatus.value.copy(
            phase = resolvedPhase,
            configuredAccelerator = activeSettings.hardwareAccelerator,
            activeAcceleratorLabel = activeAcceleratorDescription,
            detectedNpuSummary = NpuHardwareManager.detectHardwareProfile().summaryLabel,
            modelStates = states,
            statusMessage = resolvedMsg,
            loadedCount = loaded,
            enabledCount = enabled
        )
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

    private fun closeClearLocked() {
        val instance = clear
        clear = null
        clearWarmedUp = false
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
}

/**
 * Feeds `Clear` audio enhancement in sequential single-window slices of at most [maxWindowSamples]
 * (`30,400` samples = `1.90s` @ 16 kHz = `192` STFT frames `<= 200`) with a smooth linear crossfade
 * of [crossfadeSamples] (`320` samples = `20 ms`).
 *
 * Root-cause rationale (verified from `libClearAndroid.so` at `0x499790` & `0x4a04ec..0x4a06ac`):
 * `Clear.enhance` internally divides input audio into `200`-frame (`2.00s`) STFT windows and calls
 * `Inference.ParallelRuns.run(count: numWindows, sessions: [session0, session1])`. Whenever
 * `numWindows >= 2` (audio `> 1.97s`), `ParallelRuns.run` spawns 2 concurrent Swift `TaskGroup`
 * threads that call `eglMakeCurrent` + `LiteRtRunCompiledModel` simultaneously on the Adreno OpenCL
 * driver, causing `SIGSEGV (status=11)` when `Voz` also holds an OpenCL context.
 * Slicing at `<= 30,400` samples guarantees `numWindows == 1` on every native call so `Clear`
 * executes strictly sequentially on a single thread (`session0`) with tiny FFI buffers.
 */
inline fun enhanceAudioInSingleWindowSlices(
    samples: FloatArray,
    maxWindowSamples: Int = SmartPipeline.MAX_CLEAR_SINGLE_WINDOW_SAMPLES,
    crossfadeSamples: Int = SmartPipeline.CLEAR_WINDOW_CROSSFADE_SAMPLES,
    enhanceSlice: (FloatArray) -> FloatArray
): FloatArray {
    if (samples.isEmpty()) return samples
    val safeMaxWindow = max(1_600, maxWindowSamples)
    if (samples.size <= safeMaxWindow) {
        val single = enhanceSlice(samples)
        if (single.isEmpty()) return samples
        if (single.size == samples.size && single.all { it.isFinite() }) {
            return single
        }
        return FloatArray(samples.size) { idx ->
            val candidate = if (idx < single.size) single[idx] else samples[idx]
            if (candidate.isFinite()) candidate else samples[idx]
        }
    }

    val safeFade = crossfadeSamples.coerceIn(0, safeMaxWindow / 4)
    val step = max(1_600, safeMaxWindow - safeFade)
    val output = FloatArray(samples.size)

    var start = 0
    var isFirstSlice = true
    while (start < samples.size) {
        val end = min(samples.size, start + safeMaxWindow)
        val rawSlice = samples.copyOfRange(start, end)
        val enhancedSlice = enhanceSlice(rawSlice)
        val sliceLen = rawSlice.size

        if (enhancedSlice.isEmpty()) {
            // Fallback to raw slice for this window if native returned empty
            for (i in 0 until sliceLen) {
                val outIdx = start + i
                if (isFirstSlice || i >= safeFade || safeFade <= 0) {
                    output[outIdx] = rawSlice[i]
                } else {
                    val alpha = i.toFloat() / safeFade.toFloat()
                    output[outIdx] = output[outIdx] * (1.0f - alpha) + rawSlice[i] * alpha
                }
            }
        } else {
            for (i in 0 until sliceLen) {
                val outIdx = start + i
                val rawCandidate = if (i < enhancedSlice.size) enhancedSlice[i] else rawSlice[i]
                val sampleVal = if (rawCandidate.isFinite()) rawCandidate else rawSlice[i]
                if (isFirstSlice || i >= safeFade || safeFade <= 0) {
                    output[outIdx] = sampleVal
                } else {
                    val alpha = i.toFloat() / safeFade.toFloat()
                    output[outIdx] = output[outIdx] * (1.0f - alpha) + sampleVal * alpha
                }
            }
        }

        if (end >= samples.size) break
        start += step
        isFirstSlice = false
    }

    return output
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

    val safeMin = minChunkSamples.coerceIn(1_600, (safeMax * 3) / 4)
    val minPauseFrames = max(2, pauseWindowSamples.coerceIn(320, 16_000) / CHUNK_FRAME_SAMPLES)
    // Never leave a sub-second orphan tail: a few hundred samples fed to Clear/Voz yields hallucinated or empty text.
    // Bounded by (safeMax - safeMin) so the split range below can never become empty.
    val minTailSamples = min(8_000, safeMax - safeMin)
    val chunks = mutableListOf<FloatArray>()

    var cursor = 0
    while (cursor < samples.size) {
        val remaining = samples.size - cursor
        if (remaining <= safeMax) {
            chunks.add(samples.copyOfRange(cursor, samples.size))
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
        chunks.add(samples.copyOfRange(cursor, splitAt))
        cursor = splitAt
    }

    return chunks
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
fun conditionAudioWhenClearDisabled(samples: FloatArray): FloatArray {
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
    val out = FloatArray(samples.size)
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
 * (Language-ambiguous tokens like `"um"` or `"er"` are intentionally excluded here and only removed when
 * the acoustic `Uhm` model flags their audio frames.)
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
 * Stage 4: Reconciles `Voz` word-level timestamps with `Uhm` 20 ms filler frames using the
 * deterministic overlap formula from Specification Section 4.2, and removes unambiguous lexical
 * hesitation noises (`"uh"`, `"uhm"`, `"äh"`, `"ähm"`, `"hm"`, `"hmm"`).
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

    val fillerSpans = if (uhmEnabled && uhmFrames.isNotEmpty()) {
        mergeFillerFrames(
            frames = uhmFrames,
            maxGapMs = SmartPipeline.FILLER_MERGE_MAX_GAP_MS
        )
    } else {
        emptyList()
    }

    // When Uhm filler removal is disabled (or Uhm found no acoustic filler spans), prefer Voz's full
    // detokenized `transcription.text` directly so any boundary word omitted from `transcription.words`
    // timestamp alignment is never lost. `normalizeTranscriptText` below still cleans up unambiguous
    // lexical hesitations ("uh", "uhm", "äh", "ähm", "hm", "hmm") and punctuation.
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

    // Filter word tokens using >= 50% acoustic overlap ratio (+/- 80 ms tolerance) + unambiguous lexical hesitations
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
 *
 * Also cleans up filler-induced punctuation (such as commas attached to function words immediately before
 * a removed mid-sentence `"uh"`, or terminal punctuation attached to a removed sentence-final `"uh."`).
 */
fun filterWordsByFillerOverlap(
    words: List<Word>,
    fillerSpans: List<FillerSpan>,
    boundaryToleranceMs: Long = SmartPipeline.FILLER_BOUNDARY_TOLERANCE_MS,
    overlapThreshold: Double = SmartPipeline.FILLER_OVERLAP_THRESHOLD
): WordReconciliationResult {
    if (words.isEmpty()) {
        return WordReconciliationResult(
            keptWords = words,
            removedWords = emptyList(),
            removedLeadingCapitalizedWord = false
        )
    }
    val hasLexicalFiller = words.any { isUnambiguousHesitationToken(it.text) }
    if (fillerSpans.isEmpty() && !hasLexicalFiller) {
        return WordReconciliationResult(
            keptWords = words,
            removedWords = emptyList(),
            removedLeadingCapitalizedWord = false
        )
    }

    val kept = ArrayList<Word>(words.size)
    val removed = ArrayList<Word>()
    var removedLeadingCapitalized = false
    var lastRemovedFillerHadCommaOrPeriod = false
    var trailingRemovedTerminalPunct: Char? = null

    for ((index, word) in words.withIndex()) {
        val sWordMs = (word.start * 1000.0).roundToLong()
        val eWordMs = max(sWordMs + 1L, (word.end * 1000.0).roundToLong())
        val dWordMs = max(1L, eWordMs - sWordMs)

        var isFillerWord = isUnambiguousHesitationToken(word.text)
        if (!isFillerWord && fillerSpans.isNotEmpty()) {
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
        }

        if (isFillerWord) {
            removed.add(word)
            val trimmedWord = word.text.trim()
            if (kept.isEmpty()) {
                val firstLetter = trimmedWord.firstOrNull { it.isLetter() }
                if (firstLetter != null && firstLetter.isUpperCase()) {
                    removedLeadingCapitalized = true
                }
            } else {
                // If a mid-sentence filler was preceded by a function word with a filler-induced comma
                // (e.g. "so that, uh, the"), strip the comma from the preceding kept word.
                val prevWord = kept.last()
                val prevCore = extractWordCore(prevWord.text).lowercase(Locale.ROOT)
                if (prevWord.text.endsWith(",") && prevCore in NO_COMMA_AFTER_WORDS) {
                    kept[kept.lastIndex] = prevWord.copy(text = prevWord.text.dropLast(1))
                }
                if (trimmedWord.endsWith(",") || trimmedWord.endsWith(".")) {
                    lastRemovedFillerHadCommaOrPeriod = true
                }
                val lastChar = trimmedWord.lastOrNull()
                if (lastChar == '.' || lastChar == '!' || lastChar == '?') {
                    trailingRemovedTerminalPunct = lastChar
                }
            }
        } else {
            var adjustedWord = word
            if (lastRemovedFillerHadCommaOrPeriod && kept.isNotEmpty()) {
                val prevCore = extractWordCore(kept.last().text).lowercase(Locale.ROOT)
                val currCore = extractWordCore(word.text)
                if (prevCore in NO_COMMA_AFTER_WORDS &&
                    currCore.length >= 2 &&
                    currCore[0].isUpperCase() &&
                    currCore.substring(1).all { it.isLowerCase() } &&
                    currCore != "I" &&
                    currCore.lowercase(Locale.ROOT) in LOWERCASEABLE_CONTINUATION_WORDS
                ) {
                    val firstLetterIdx = word.text.indexOfFirst { it.isLetter() }
                    if (firstLetterIdx >= 0) {
                        val lowered = word.text.substring(0, firstLetterIdx) +
                            word.text[firstLetterIdx].lowercaseChar() +
                            word.text.substring(firstLetterIdx + 1)
                        adjustedWord = word.copy(text = lowered)
                    }
                }
            }
            lastRemovedFillerHadCommaOrPeriod = false
            trailingRemovedTerminalPunct = null
            kept.add(adjustedWord)
        }
    }

    // If the utterance ended with a removed filler that carried the terminal punctuation (e.g. "know, uh."),
    // transfer that terminal punctuation to the last remaining kept word.
    if (trailingRemovedTerminalPunct != null && kept.isNotEmpty()) {
        val lastKept = kept.last()
        val trimmed = lastKept.text.trimEnd()
        if (trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")) {
            kept[kept.lastIndex] = lastKept.copy(text = trimmed.dropLast(1) + trailingRemovedTerminalPunct)
        } else if (trimmed.isNotEmpty() && trimmed.last().isLetterOrDigit()) {
            kept[kept.lastIndex] = lastKept.copy(text = trimmed + trailingRemovedTerminalPunct)
        }
    }

    return WordReconciliationResult(
        keptWords = kept,
        removedWords = removed,
        removedLeadingCapitalizedWord = removedLeadingCapitalized
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
