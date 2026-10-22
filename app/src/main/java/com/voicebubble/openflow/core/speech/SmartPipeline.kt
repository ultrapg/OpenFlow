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
    private var appliedSettingsSnapshot: ModelSettings? = null
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
        onStageProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""

        // Fast silence trim before acquiring native sessions
        val trimmedSamples = trimLeadingAndTrailingSilence(samples)
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
                val maxChunkSamples = if (initialMem.isAnyMemoryTight) {
                    LOW_MEM_MAX_CHUNK_SAMPLES
                } else {
                    DEFAULT_MAX_CHUNK_SAMPLES
                }
                val minChunkSamples = if (initialMem.isAnyMemoryTight) {
                    LOW_MEM_MIN_CHUNK_SAMPLES
                } else {
                    DEFAULT_MIN_CHUNK_SAMPLES
                }

                val audioChunks = splitAudioIntoSmartChunks(
                    samples = trimmedSamples,
                    maxChunkSamples = maxChunkSamples,
                    minChunkSamples = minChunkSamples
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
                val resolvedLanguage = resolveLanguageLocked(
                    samples = audioChunks.first(),
                    activeSettings = activeSettings,
                    onStageProgress = onStageProgress
                )
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
                    ensureMemoryHeadroomForFfiLocked(rawChunk.size)

                    // Stage 2A: Audio Enhancement (`Clear`)
                    val enhancedChunk = runClearStageLocked(
                        rawSamples = rawChunk,
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

                val finalTranscript = normalizeTranscriptText(
                    rawText = segmentTranscripts.joinToString(" "),
                    capitalizeLeadingWord = false
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
        activeAcceleratorDescription = NpuHardwareManager.configureNativeRuntime(
            context = appContext,
            requested = accelerator,
            lowMemoryPressure = lowMemoryPressure
        )
        appliedAccelerator = accelerator
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
     */
    private fun ensureMemoryHeadroomForFfiLocked(sampleCount: Int) {
        val estimatedFfiMegabytes = ((sampleCount.toLong() * 12L) / (1024L * 1024L)).coerceAtLeast(4L)
        val report = evaluateMemoryPressure()
        if (report.isJvmHeapTight || report.jvmFreeHeadroomMb < estimatedFfiMegabytes * 3L) {
            System.gc()
            System.runFinalization()
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
        if (!activeSettings.clearEnabled) return rawSamples
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            return rawSamples
        }

        val mem = evaluateMemoryPressure()
        if (mem.isCriticalPressure) {
            Log.i(TAG, "Skipping Clear due to critical memory pressure (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB).")
            DebugLogManager.logWarn(
                TAG,
                "Skipping Clear stage due to critical memory pressure (jvmFree=${mem.jvmFreeHeadroomMb}MB, sysAvail=${mem.systemAvailMb}MB)"
            )
            closeClearLocked()
            return rawSamples
        }

        val clearInstance = clear ?: run {
            val clearDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.CLEAR).absolutePath
            try {
                Clear(appContext, clearDir).also { clear = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to create Clear instance: ${t.message}")
                DebugLogManager.logWarn(TAG, "Failed to create Clear instance", t)
                return rawSamples
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
            if (enhanced.isNotEmpty()) enhanced else rawSamples
        } catch (t: Throwable) {
            Log.w(TAG, "Clear enhancement failed, falling back to raw PCM audio: ${t.message}")
            DebugLogManager.logWarn(TAG, "Clear enhancement failed, falling back to raw PCM audio", t)
            closeClearLocked()
            System.gc()
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
            val earInput = if (samples.size > MAX_EAR_WINDOW_SAMPLES) {
                samples.copyOfRange(0, MAX_EAR_WINDOW_SAMPLES)
            } else {
                samples
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
            Log.w(TAG, "Uhm filler inference failed; bypassing filler removal: ${t.message}")
            DebugLogManager.logWarn(TAG, "Uhm filler inference failed; bypassing filler removal", t)
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

            // If the chunk is > 10s, split it in half on retry to halve peak native & FFI memory
            if (samples.size > 10 * 16_000) {
                val mid = samples.size / 2
                val left = samples.copyOfRange(0, mid)
                val right = samples.copyOfRange(mid, samples.size)
                val t1 = retryVoz.transcribeWithContext(left, SAMPLE_RATE_HZ, language, customVocabulary)
                System.gc()
                val t2 = retryVoz.transcribeWithContext(right, SAMPLE_RATE_HZ, language, customVocabulary)
                val leftDurationSec = left.size.toDouble() / SAMPLE_RATE_HZ
                val shiftedRightWords = t2.words.map { w ->
                    Word(w.text, w.start + leftDurationSec, w.end + leftDurationSec)
                }
                vozWarmedUp = true
                Transcription(
                    text = listOf(t1.text.trim(), t2.text.trim()).filter { it.isNotEmpty() }.joinToString(" "),
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
 * Splits a long 16 kHz PCM [samples] buffer into natural speech segments of at most [maxChunkSamples]
 * by locating the lowest-energy 80 ms pause window within `minChunkSamples..maxChunkSamples`.
 *
 * This prevents quadratic Conformer attention memory blowups and JNI/FFI array OOMs on long dictations
 * while ensuring splits never cut through the middle of a spoken syllable.
 */
fun splitAudioIntoSmartChunks(
    samples: FloatArray,
    maxChunkSamples: Int = SmartPipeline.DEFAULT_MAX_CHUNK_SAMPLES,
    minChunkSamples: Int = SmartPipeline.DEFAULT_MIN_CHUNK_SAMPLES,
    pauseWindowSamples: Int = 1_280 // 80 ms @ 16 kHz
): List<FloatArray> {
    if (samples.isEmpty()) return emptyList()
    val safeMax = max(3_200, maxChunkSamples)
    if (samples.size <= safeMax) return listOf(samples)

    val safeMin = minChunkSamples.coerceIn(1_600, (safeMax * 3) / 4)
    val window = pauseWindowSamples.coerceIn(320, 3_200)
    val step = max(160, window / 4)
    // Never leave a sub-second orphan tail: a few hundred samples fed to Clear/Voz yields hallucinated or empty text.
    // Bounded by (safeMax - safeMin) so the split window below can never become empty.
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
        val searchEnd = min(samples.size - window, latestSplit)
        var bestSplitIndex = latestSplit
        var lowestEnergy = Double.MAX_VALUE

        var pos = searchStart
        while (pos <= searchEnd) {
            var energy = 0.0
            for (i in 0 until window) {
                val s = samples[pos + i].toDouble()
                energy += s * s
            }
            if (energy < lowestEnergy) {
                lowestEnergy = energy
                bestSplitIndex = pos + (window / 2)
            }
            pos += step
        }

        val splitAt = bestSplitIndex.coerceIn(cursor + safeMin, latestSplit)
        chunks.add(samples.copyOfRange(cursor, splitAt))
        cursor = splitAt
    }

    return chunks
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

private val LEADING_ORPHAN_PUNCT_REGEX = Regex("^[.,;:\\-–—]+\\s*")
private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([.,!?:;%€])")
private val DUPLICATE_PUNCT_REGEX = Regex("([,;:])\\s*[,;:]+")
private val MULTI_SPACE_REGEX = Regex("\\s+")

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
        .replace(LEADING_ORPHAN_PUNCT_REGEX, "")
        // Collapse spaces before punctuation
        .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
        // Collapse duplicate punctuation (e.g. ", ," -> ",")
        .replace(DUPLICATE_PUNCT_REGEX, "$1")
        // Collapse multiple spaces
        .replace(MULTI_SPACE_REGEX, " ")
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
