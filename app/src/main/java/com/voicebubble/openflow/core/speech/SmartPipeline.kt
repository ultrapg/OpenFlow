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
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.HardwareAccelerator
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
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
    STANDBY("Standby (On Disk)"),
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
 * Observable snapshot of the speech pipeline's loaded models, active hardware accelerator,
 * and most recent inference performance.
 */
data class PipelineStatus(
    val phase: PipelinePhase = PipelinePhase.UNINITIALIZED,
    val configuredAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO,
    val activeAcceleratorLabel: String = "Auto (GPU / CPU)",
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
 * Concurrency, Speed & Memory Guarantees:
 * - Passes explicit verified revision directories to `Voz`, `Ear`, `Clear`, and `Uhm`, bypassing
 *   `ModelStore`'s 1.2 GB pure-Swift `SoftwareSHA256` memory spike on load and inference.
 * - Executes all native LiteRT stages strictly sequentially under [pipelineMutex] to prevent
 *   concurrent OpenCL / `libLiteRt.so` driver race crashes when all 4 models are enabled.
 * - Pre-warms `Voz` during [initialize] so the first dictation starts with zero compilation delay.
 * - Trims leading/trailing dead silence before inference and bounds `Ear` language identification
 *   to a 5.5-second speech window for faster turnaround.
 */
class SmartPipeline(
    context: Context,
    initialSettings: ModelSettings = ModelSettings.load(context)
) {

    companion object {
        private const val TAG = "SmartPipeline"
        const val SAMPLE_RATE_HZ: Double = 16_000.0
        const val IDLE_TIMEOUT_MS: Long = 30 * 60_000L // 30 minutes warm retention
        const val FILLER_MERGE_MAX_GAP_MS: Long = 40L
        const val FILLER_BOUNDARY_TOLERANCE_MS: Long = 80L
        const val FILLER_OVERLAP_THRESHOLD: Double = 0.50
        private const val SILENCE_THRESHOLD: Float = 0.0035f
        private const val SILENCE_PAD_SAMPLES: Int = 2_560 // 160 ms @ 16 kHz
        private const val MAX_CLEAR_DURATION_SAMPLES: Int = 25 * 16_000 // 25s cap for 48kHz STFT
        private const val MAX_EAR_WINDOW_SAMPLES: Int = 88_000 // 5.5s @ 16 kHz

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
            activeAcceleratorLabel = resolveAcceleratorLabel(initialSettings.hardwareAccelerator),
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
     * Initializes `Voz` and any currently enabled & downloaded auxiliary models inside [pipelineMutex],
     * pre-warming LiteRT sessions so the first dictation has zero compilation lag.
     */
    suspend fun initialize(warmUp: Boolean = true): Boolean = withContext(Dispatchers.Default) {
        pipelineMutex.withLock {
            val startMs = SystemClock.elapsedRealtime()
            val currentSettings = settings
            applyHardwareAcceleratorSettingsLocked(currentSettings.hardwareAccelerator)
            DebugLogManager.logInfo(
                TAG,
                "initialize(warmUp=$warmUp) start • enabled=${currentSettings.enabledModels().map { it.id }} • accel=${currentSettings.hardwareAccelerator.displayName}",
                includeMemory = true
            )

            updateStatusSnapshot(
                activeSettings = currentSettings,
                phaseOverride = PipelinePhase.LOADING,
                messageOverride = "Loading enabled models on ${resolveAcceleratorLabel(currentSettings.hardwareAccelerator)}..."
            )

            val vozLoaded = ensureVozLocked(currentSettings, warmUp = warmUp)
            ensureAuxiliaryModelsLocked(currentSettings, warmUp = warmUp)
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
                    "$count/${currentSettings.enabledModels().size} models loaded in RAM (${resolveAcceleratorLabel(currentSettings.hardwareAccelerator)})"
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
    fun initializeAsync(warmUp: Boolean = true) {
        idleScope.launch {
            initialize(warmUp = warmUp)
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
            applyHardwareAcceleratorSettingsLocked(snapshot.hardwareAccelerator)

            if (accelChanged) {
                Log.i(TAG, "Hardware accelerator changed to ${snapshot.hardwareAccelerator.displayName}; reloading sessions.")
                DebugLogManager.logInfo(TAG, "Hardware accelerator changed to ${snapshot.hardwareAccelerator.displayName}; closing active sessions", includeMemory = true)
                closeClearLocked()
                closeEarLocked()
                closeUhmLocked()
                closeVozLocked()
            } else {
                if (!snapshot.clearEnabled) closeClearLocked()
                if (!snapshot.earEnabled) closeEarLocked()
                if (!snapshot.uhmEnabled) closeUhmLocked()
            }

            updateStatusSnapshot(
                activeSettings = snapshot,
                phaseOverride = PipelinePhase.LOADING,
                messageOverride = "Updating model pipeline (${resolveAcceleratorLabel(snapshot.hardwareAccelerator)})..."
            )

            val vozOk = ensureVozLocked(snapshot, warmUp = true)
            ensureAuxiliaryModelsLocked(snapshot, warmUp = true)
            resetIdleEvictionTimerLocked()

            val phase = if (vozOk) PipelinePhase.READY else PipelinePhase.UNINITIALIZED
            val count = countLoadedModelsLocked(snapshot)
            val msg = if (vozOk) {
                "$count/${snapshot.enabledModels().size} models loaded in RAM (${resolveAcceleratorLabel(snapshot.hardwareAccelerator)})"
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
     * All stages execute strictly sequentially to prevent concurrent OpenCL / LiteRT native crashes.
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
                applyHardwareAcceleratorSettingsLocked(activeSettings.hardwareAccelerator)
                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() start • rawSamples=${samples.size}, trimmedSamples=${trimmedSamples.size} (${audioDurationMs}ms) • models=${activeSettings.enabledModels().map { it.id }}",
                    includeMemory = true
                )

                // Ensure models are ready
                if (!ensureVozLocked(activeSettings, warmUp = false)) {
                    throw IllegalStateException("Voz speech recognition model is not downloaded.")
                }
                ensureAuxiliaryModelsLocked(activeSettings, warmUp = false)

                // =========================================================================
                // Stage 1: Audio Enhancement (`Clear` with `clear-studio` profile)
                // =========================================================================
                val enhancedSamples = runClearStageLocked(trimmedSamples, activeSettings, onStageProgress)

                // Only release Clear's native session between stages if the OS reports critical memory pressure.
                // Keeping Clear warmed up avoids calling eglTerminate on the shared process EGLDisplay
                // and prevents re-compiling clear-studio.tflite on every dictation.
                if (activeSettings.clearEnabled && isSystemMemoryLow()) {
                    DebugLogManager.logWarn(TAG, "Low system memory detected after Clear stage; releasing Clear buffers before Voz")
                    releaseClearNativeBuffersBetweenStagesLocked()
                }

                // =========================================================================
                // Stage 2A: Spoken Language Identification (`Ear`) — Strictly Sequential
                // =========================================================================
                val resolvedLanguage = resolveLanguageLocked(enhancedSamples, activeSettings, onStageProgress)

                // =========================================================================
                // Stage 2B: Acoustic Filler Detection (`Uhm`) — Strictly Sequential
                // =========================================================================
                val uhmFrames = runUhmStageLocked(enhancedSamples, activeSettings, onStageProgress)

                // =========================================================================
                // Stage 3: Multilingual Speech-to-Text (`Voz`) — Strictly Sequential
                // =========================================================================
                setModelActiveStatusLocked(activeSettings, DesertAntModel.VOZ, "Transcribing with Voz ($resolvedLanguage)...")
                onStageProgress?.invoke("Voz ($resolvedLanguage)")
                DebugLogManager.logMemorySnapshot("Before Voz.transcribe ($resolvedLanguage)")
                val transcription = runVozWithRetryLocked(
                    samples = enhancedSamples,
                    language = resolvedLanguage,
                    customVocabulary = activeSettings.customVocabulary,
                    activeSettings = activeSettings
                )

                // =========================================================================
                // Stage 4: Temporal Filler Subtraction & Text Normalization
                // =========================================================================
                val finalTranscript = reconcileTranscript(
                    transcription = transcription,
                    uhmFrames = uhmFrames,
                    uhmEnabled = activeSettings.uhmEnabled,
                    customVocabulary = activeSettings.customVocabulary
                )

                val totalInferenceMs = (SystemClock.elapsedRealtime() - inferenceStartMs).coerceAtLeast(1L)
                _pipelineStatus.value = _pipelineStatus.value.copy(
                    lastInferenceMs = totalInferenceMs,
                    lastAudioDurationMs = audioDurationMs
                )
                DebugLogManager.logInfo(
                    TAG,
                    "transcribe() completed in ${totalInferenceMs}ms for ${audioDurationMs}ms audio (chars=${finalTranscript.length}, lang=$resolvedLanguage)",
                    includeMemory = true
                )
                finalTranscript
            } catch (t: Throwable) {
                DebugLogManager.logError(TAG, "transcribe() failed after ${SystemClock.elapsedRealtime() - inferenceStartMs}ms", t)
                throw t
            } finally {
                isTranscribing = false
                val snap = settings
                val count = countLoadedModelsLocked(snap)
                updateStatusSnapshot(
                    activeSettings = snap,
                    phaseOverride = if (voz != null) PipelinePhase.READY else PipelinePhase.UNINITIALIZED,
                    messageOverride = "$count/${snap.enabledModels().size} models loaded in RAM (${resolveAcceleratorLabel(snap.hardwareAccelerator)})"
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
                val snap = settings
                updateStatusSnapshot(
                    activeSettings = snap,
                    phaseOverride = if (voz != null) PipelinePhase.STANDBY_EVICTED else PipelinePhase.UNINITIALIZED,
                    messageOverride = "Voz loaded in RAM • Optional models on standby"
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
            val snap = settings
            updateStatusSnapshot(
                activeSettings = snap,
                phaseOverride = PipelinePhase.UNINITIALIZED,
                messageOverride = "Models unloaded from RAM"
            )
        }
    }

    // =========================================================================
    // Internal Stage & Hardware Helpers (Must be called inside `pipelineMutex.withLock`)
    // =========================================================================

    private fun applyHardwareAcceleratorSettingsLocked(accelerator: HardwareAccelerator) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val (numThreads, useNpu, useGpu) = when (accelerator) {
            HardwareAccelerator.CPU -> Triple(min(8, max(4, cores - 1)), "0", "0")
            HardwareAccelerator.NPU -> Triple(min(6, max(4, (cores * 2) / 3)), "1", "1")
            HardwareAccelerator.GPU -> Triple(min(4, max(2, cores / 2)), "0", "1")
            HardwareAccelerator.AUTO -> Triple(min(6, max(4, cores / 2)), "1", "1")
        }
        try {
            Os.setenv("DAL_LITERT_THREADS", numThreads.toString(), true)
            Os.setenv("DAL_ACCELERATOR", accelerator.id, true)
            Os.setenv("LITERT_USE_NPU", useNpu, true)
            Os.setenv("LITERT_USE_GPU", useGpu, true)
        } catch (_: Throwable) {
        }
        appliedAccelerator = accelerator
    }

    private fun resolveAcceleratorLabel(accelerator: HardwareAccelerator): String {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val hasOpenClGpu = hasOpenClGpuSupport()
        val hasNpu = hasNpuSupport()
        return when (accelerator) {
            HardwareAccelerator.AUTO -> when {
                hasNpu && hasOpenClGpu -> "Auto (NPU / OpenCL GPU)"
                hasOpenClGpu -> "Auto (GPU OpenCL + XNNPACK)"
                else -> "Auto (CPU XNNPACK ${min(8, max(4, cores - 1))}T)"
            }
            HardwareAccelerator.NPU -> if (hasNpu) {
                "NPU Accelerator (+ GPU/CPU Fallback)"
            } else if (hasOpenClGpu) {
                "GPU OpenCL (NPU Fallback)"
            } else {
                "CPU XNNPACK (NPU Fallback)"
            }
            HardwareAccelerator.GPU -> if (hasOpenClGpu) {
                "GPU (OpenCL) + XNNPACK"
            } else {
                "CPU XNNPACK (GPU Fallback)"
            }
            HardwareAccelerator.CPU -> "CPU (XNNPACK ${min(8, max(4, cores - 1))}-Thread)"
        }
    }

    private fun hasOpenClGpuSupport(): Boolean {
        return try {
            val openClPaths = listOf(
                "/vendor/lib64/libOpenCL.so",
                "/system/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/vendor/lib64/egl/libGLES_mali.so"
            )
            openClPaths.any { File(it).exists() }
        } catch (_: Throwable) {
            true
        }
    }

    private fun hasNpuSupport(): Boolean {
        return try {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).lowercase(Locale.ROOT)
            } else {
                (Build.HARDWARE + " " + Build.BOARD).lowercase(Locale.ROOT)
            }
            soc.contains("qcom") || soc.contains("qualcomm") || soc.contains("snapdragon") ||
                soc.contains("mediatek") || soc.contains("mt") || soc.contains("dimensity") ||
                soc.contains("tensor") || soc.contains("exynos") ||
                File("/vendor/lib64/libcdsprpc.so").exists() ||
                File("/vendor/lib64/libneuron_adapter.so").exists()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isSystemMemoryLow(): Boolean {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024L * 1024L)
            memInfo.lowMemory || availMb < 450L
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun runClearStageLocked(
        rawSamples: FloatArray,
        activeSettings: ModelSettings,
        onStageProgress: ((String) -> Unit)?
    ): FloatArray {
        if (!activeSettings.clearEnabled) return rawSamples
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            return rawSamples
        }
        // Guard against huge 48 kHz STFT allocations on very long recordings or critically low RAM
        if (rawSamples.size > MAX_CLEAR_DURATION_SAMPLES || isSystemMemoryLow()) {
            Log.i(TAG, "Skipping Clear on long/low-memory clip (${rawSamples.size} samples) to preserve RAM for Voz.")
            return rawSamples
        }

        val clearInstance = clear ?: run {
            val clearDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.CLEAR).absolutePath
            try {
                Clear(appContext, clearDir).also { clear = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to recreate Clear instance: ${t.message}")
                return rawSamples
            }
        }

        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.CLEAR, "Enhancing audio with Clear...")
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
            clearWarmedUp = true
            val enhanced = result.samples
            if (enhanced.isNotEmpty()) enhanced else rawSamples
        } catch (t: Throwable) {
            Log.w(TAG, "Clear enhancement failed, falling back to raw PCM audio: ${t.message}")
            closeClearLocked()
            System.gc()
            rawSamples
        }
    }

    private fun releaseClearNativeBuffersBetweenStagesLocked() {
        val existing = clear ?: return
        try {
            existing.close()
        } catch (_: Throwable) {
        }
        clear = null
        clearWarmedUp = false
        if (DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            try {
                val clearDir = DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.CLEAR).absolutePath
                clear = Clear(appContext, clearDir)
            } catch (_: Throwable) {
            }
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

        val earInstance = ear ?: return fallbackLanguage

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
            closeEarLocked()
            fallbackLanguage
        }
    }

    private suspend fun runUhmStageLocked(
        samples: FloatArray,
        activeSettings: ModelSettings,
        onStageProgress: ((String) -> Unit)?
    ): List<FrameClassification> {
        if (!activeSettings.uhmEnabled) return emptyList()
        if (!DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.UHM)) {
            return emptyList()
        }
        val uhmInstance = uhm ?: return emptyList()
        return try {
            setModelActiveStatusLocked(activeSettings, DesertAntModel.UHM, "Analyzing hesitations with Uhm...")
            onStageProgress?.invoke("Uhm")
            uhmInstance.detect(samples, SAMPLE_RATE_HZ)
        } catch (t: Throwable) {
            Log.w(TAG, "Uhm filler inference failed; bypassing filler removal: ${t.message}")
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
            Log.w(TAG, "Voz inference failed on first attempt (${firstError.message}); freeing auxiliary RAM and retrying once.")
            DebugLogManager.logWarn(TAG, "Voz inference failed on first attempt; freeing auxiliary RAM and retrying once", firstError)
            // Free optional native sessions before retrying Voz to guarantee maximum memory headroom
            closeClearLocked()
            closeEarLocked()
            closeVozLocked()
            System.gc()
            if (!ensureVozLocked(activeSettings, warmUp = false)) {
                throw IllegalStateException("Transcription failed after re-initialization: ${firstError.message}", firstError)
            }
            val retryVoz = voz ?: throw IllegalStateException("Voz unavailable on retry.", firstError)
            retryVoz.transcribeWithContext(
                samples = samples,
                sampleRate = SAMPLE_RATE_HZ,
                language = language,
                customVocabulary = customVocabulary
            ).also {
                vozWarmedUp = true
                // Restore auxiliary model handles after retry succeeds
                ensureAuxiliaryModelsLocked(activeSettings, warmUp = false)
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
                    message = "Warming up Voz on ${resolveAcceleratorLabel(activeSettings.hardwareAccelerator)}..."
                )
                val warmupSilence = FloatArray(3_200) // 200 ms silent frame
                currentVoz.transcribe(samples = warmupSilence, sampleRate = SAMPLE_RATE_HZ)
                vozWarmedUp = true
                DebugLogManager.logInfo(TAG, "Voz accelerator warm-up complete", includeMemory = true)
            } catch (t: Throwable) {
                Log.w(TAG, "Voz warm-up deferred to first dictation: ${t.message}")
                DebugLogManager.logWarn(TAG, "Voz warm-up deferred to first dictation", t)
            }
        }
        return true
    }

    private suspend fun ensureAuxiliaryModelsLocked(activeSettings: ModelSettings, warmUp: Boolean) {
        val lowMem = isSystemMemoryLow()
        if (lowMem) {
            DebugLogManager.logWarn(TAG, "System memory is low during ensureAuxiliaryModelsLocked; skipping auxiliary warm-up")
        }

        // 1. Ear
        if (activeSettings.earEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.EAR)) {
            if (ear == null) {
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
            if (warmUp && currentEar != null && !earWarmedUp && !lowMem) {
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

        // 2. Clear
        if (activeSettings.clearEnabled && DesertAntModelStorage.isModelDownloaded(appContext, DesertAntModel.CLEAR)) {
            if (clear == null) {
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
            if (warmUp && currentClear != null && !clearWarmedUp && !lowMem) {
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
                            sampleRate = SAMPLE_RATE_HZ,
                            channelMode = ChannelMode.MONO
                        )
                    )
                    clearWarmedUp = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Clear warm-up deferred: ${t.message}")
                    DebugLogManager.logWarn(TAG, "Clear warm-up deferred", t)
                }
            }
        } else if (!activeSettings.clearEnabled) {
            closeClearLocked()
        }

        // 3. Uhm
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
            activeAcceleratorLabel = resolveAcceleratorLabel(activeSettings.hardwareAccelerator),
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
            activeAcceleratorLabel = resolveAcceleratorLabel(activeSettings.hardwareAccelerator),
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
