# Changelog

All notable changes to the **OpenFlow** project are documented in this file.

## [2.5.5] - 2026-10-20

### Fixed
- **Floating Bubble Re-Appearance After Screen Off / Screen On (`FloatingBubbleService`, `TextInjectionAccessibilityService`, `BubbleOverlayComponent`):**
  - Moved the authoritative floating bubble visibility & `WindowManager.LayoutParams` (`alpha`, `FLAG_NOT_TOUCHABLE`) controller out of `@Composable LaunchedEffect` (which depends on `Choreographer` VSYNC frames and skipped `true -> false -> true` state transitions across `Display.STATE_OFF`) and directly into `FloatingBubbleService.serviceScope` (`Dispatchers.Main` via `Looper.getMainLooper()`) with a `200 ms` main-thread heartbeat and staggered wake-up refreshes.
  - Fixed a lock-state race condition where `KeyguardManager.isKeyguardLocked` remained `true` during `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` keyguard exit animations (or when keyguard was occluded), preventing `TextInjectionAccessibilityService` and `FloatingBubbleService` from detecting the on-screen keyboard after waking the screen.
  - Ensured `BubbleOverlayComponent` renders at `alpha = 1.0f` immediately on the first visible frame so interrupted `Choreographer` animation frames during display sleep can never leave the bubble transparent.

## [2.5.4] - 2026-10-20

### Fixed
- **Screen-Off & Lockscreen Floating Bubble Persistence Bug (`FloatingBubbleService`, `TextInjectionAccessibilityService`):**
  - Registered a real-time screen-state `BroadcastReceiver` (`ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, `ACTION_USER_PRESENT`) and `PowerManager.isInteractive` / `KeyguardManager.isKeyguardLocked` checks in both `FloatingBubbleService` and `TextInjectionAccessibilityService`.
  - When the screen turns off or the lockscreen engages, the floating bubble surface is now hidden synchronously with `0 ms` delay (bypassing Compose `Choreographer` VSYNC, which stops ticking when the display powers off) and any active recording is cleanly stopped.
- **Notification Dismissal & Task Removal Disappearance Bug (`FloatingBubbleService`, `AndroidManifest.xml`):**
  - Added a `deleteIntent` (`ACTION_RESTORE_NOTIFICATION`) and a 4-second `NotificationManager.activeNotifications` + `composeView.isAttachedToWindow` self-healing watchdog so swiping away the ongoing foreground notification on Android 13–15 immediately restores the notification and keeps the floating bubble alive.
  - Set `android:stopWithTask="false"` and implemented `onTaskRemoved` recovery so swiping OpenFlow away from Recent Apps does not kill the floating bubble.

### Added
- **Auto-Start on Phone Boot & Automatic Crash Recovery (`BootAndRestartReceiver`, `OpenFlowApp`, `FloatingBubbleService`, `TextInjectionAccessibilityService`, `MainActivity`):**
  - Added `BootAndRestartReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`) with Direct Boot storage support and Android 14/15 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` boot fallback so the floating bubble starts automatically when the phone boots.
  - Added multi-layered automatic crash recovery via `OpenFlowApp`'s `UncaughtExceptionHandler` (`AlarmManager` wakeup with a 3-in-60s crash-loop guard), `FloatingBubbleService.onDestroy()` self-healing, and an `AccessibilityService` watchdog (`TextInjectionAccessibilityService`), plus a new **"Auto-start on phone boot & auto-restart after crash"** toggle in Settings.

## [2.5.3] - 2026-10-20

### Removed
- **Removed Experimental Gemma 3 270M SLM Formatting Engine:** Reverted the experimental Stage 5 SLM post-processing engine and restored the clean 4-model Desert Ant Labs audio pipeline (`Voz`, `Ear`, `Clear`, `Uhm`) with native `Voz` punctuation, capitalization, and custom vocabulary biasing.

## [2.4.1] - 2026-10-19

### Fixed
- **Audio Recording Startup & Resource Leak Hardening (`AudioRecordingController`, `FloatingBubbleService`):**
  - Fixed a native `AudioRecord` and 960 KB PCM buffer leak when `AudioRecord` fails to initialize or enter `RECORDSTATE_RECORDING` (e.g., when another app holds an exclusive microphone lock).
  - `AudioRecordingController.startRecording()` now returns a `Boolean` status so `FloatingBubbleService` immediately resets the bubble from `RECORDING` to `IDLE`, clears `FLAG_KEEP_SCREEN_ON`, releases the `WakeLock`, and notifies the user instead of leaving the bubble stuck in a recording state.
- **Custom Vocabulary Punctuation Boundary & Replacement Escaping Bug (`SmartPipeline`):**
  - Fixed `applyCustomVocabulary` failing to match custom vocabulary terms that start or end with non-word characters (such as `C++` or `.NET`) due to `\b` word-boundary assertions, and escaped replacement strings via `Regex.escapeReplacement`.
- **Single-Window `Clear` Slice NaN & Truncation Guard (`SmartPipeline`):**
  - Hardened `enhanceAudioInSingleWindowSlices` to fall back to the corresponding original raw PCM sample (instead of repeating the last sample or propagating `NaN`/`Infinite` GPU shader glitches) if a slice is shorter than expected or contains non-finite floats.
- **Low-Memory Native Device ID Fallback (`NpuHardwareManager`):**
  - Fixed `NpuHardwareManager.resolveNativeDeviceId` so `lowMemoryPressure = true` reliably resolves to `"cpu"` regardless of the requested accelerator mode.
- **Disappear Delay Range Alignment (`FloatingBubbleService`):**
  - Aligned the `disappearDelayMs` clamp range (`0L..5_000L`) in `FloatingBubbleService` with the slider in `MainActivity`.

### Changed / Optimized
- **Zero-Allocation Single-Edit Distance & Pre-Compiled Regexes (`SmartPipeline`):**
  - Replaced the $O(N \times M)$ `IntArray`-allocating `levenshteinDistance` in `biasWordWithCustomVocabulary` with an $O(N)$ zero-allocation `isSingleEditDistanceIgnoreCase` check, and pre-compiled all text-normalization `Regex` instances in `normalizeTranscriptText`.
- **Single-Pass Filler Span Merging (`Uhm`):**
  - Optimized `mergeFillerFrames` to iterate `FrameClassification` lists in a single pass without allocating an intermediate filtered list.
- **Bounded Log Tail Reading & Newline-Aligned Log Truncation (`DebugLogManager`):**
  - Optimized `readTailText` to seek and read only the tail bytes via `RandomAccessFile` instead of loading entire multi-hundred-KB log files into JVM heap memory, aligned `trimLogFileInPlace` to newline boundaries so multi-byte UTF-8 sequences are never split mid-character, and cached `SimpleDateFormat` in a `ThreadLocal`.
- **Eliminated Redundant Pipeline Reloads on UI Settings Changes (`FloatingBubbleService`):**
  - Stopped `ACTION_REFRESH_SETTINGS` from triggering a redundant `smartPipeline.reloadSettings(...)` pass when only bubble UI preferences (size, haptic feedback, disappear delay) are adjusted.

## [2.4.0] - 2026-10-19

### Fixed
- **`Clear` Noise Suppression Native Crash (`SIGSEGV` / `libLiteRt.so` / OpenCL) on Snapdragon 8 Elite & Adreno GPUs (`SmartPipeline`, `NpuHardwareManager`):**
  - **Single-Window Sequential `Clear.enhance` Execution (`enhanceAudioInSingleWindowSlices`):** Native `libClearAndroid.so` (`clear-studio.tflite`) uses a `200`-frame (`2.00s`) STFT window and spawns 2 concurrent Swift `TaskGroup` threads (`ParallelRuns.run` across `session0` and `session1`) whenever audio exceeds `200` frames (`> 1.97s`), triggering concurrent `eglMakeCurrent` + `LiteRtRunCompiledModel` OpenCL calls that crash with `SIGSEGV (status=11)` when `Voz` also holds an OpenCL context. Feeding `Clear.enhance` in sequential `<= 1.90s` (`30,400` sample) windows with a smooth `20 ms` (`320` sample) linear crossfade guarantees `numWindows == 1` on every native invocation so `Clear` runs strictly sequentially on a single worker thread (`DAL_RUN_DEPTH=1`).
  - **Fixed False-Positive `isNativeHeapHigh` Memory Pressure on 64-Bit OpenCL/QNN Virtual Address Mappings (`MemoryPressureReport`):** On Qualcomm Snapdragon 8 Elite (`SM8750` / Samsung Galaxy S25 `SM-S931B`), OpenCL/QNN maps ~4.6 GB of virtual GPU/DSP address space into `Debug.getNativeHeapAllocatedSize()` even when actual RSS is ~1.8 GB and >2 GB of physical RAM is free. Excluded `isNativeHeapHigh` from `isAnyMemoryTight` so `Clear` is pre-loaded and warmed up in RAM (`4/4 models ready`) instead of tearing down `Ear`/`Clear` `LiteRtEnvironment` / `EGLContext` sessions mid-pipeline.
  - **Deduplicated `reloadSettings` Calls (`SmartPipeline`):** Prevented redundant back-to-back `reloadSettings` runs when `SharedPreferences` listeners and UI toggles fire simultaneously.

## [2.3.9] - 2026-10-19

### Added
- **Qualcomm Hexagon & Universal Mobile NPU Hardware Support (`NpuHardwareManager`, `SmartPipeline`, `SpeechRecognitionSettingsSection`):**
  - Added SoC and vendor NPU detection and native runtime dispatch configuration (`LITERT_DISPATCH_LIBRARY_DIR`, `ADSP_LIBRARY_PATH`, `CDSP_LIBRARY_PATH`, `QNN_HTP_PERF_MODE`, `DAL_LITERT_THREADS`, `NNAPI_ALLOW_FP16`, etc.) across:
    - **Qualcomm Hexagon NPU** (QNN HTP / FastRPC CDSP across Hexagon V79 / Snapdragon 8 Elite, V75 / 8 Gen 3, V73 / 8 Gen 2, V69 / 8+ Gen 1, V68 / 888, V66 / 865, and Snapdragon 7-series)
    - **MediaTek NeuroPilot APU** (Dimensity 9400 / 9300 / 9200 / 9000 / 8300 / 8200, `libneuron_adapter.so`, `libapusys.so`)
    - **Google Tensor TPU** (Tensor G1–G5, DarwiNN / EdgeTPU runtime)
    - **Samsung Exynos NPU** (Exynos 2500 / 2400 / 2200 / 2100, ENN / EDEN runtime)
    - **HiSilicon Kirin NPU** (`libhiai.so`), **Rockchip RKNN** (`librknnrt.so`), and **Android NNAPI** (`libneuralnetworks.so`)
  - Exposed live detected NPU hardware profile telemetry (`NPU Hardware`) in the Speech Recognition Settings summary card.

### Fixed
- **Core Speech Engine Anti-OOM Stability & Dynamic Model Staging (`SmartPipeline`, `AudioRecordingController`, `Uhm`):**
  - **Prevented 3× Audio Upsampling in `Clear`:** Explicitly configured `ai.desertant.clear.Options(sampleRate = 16000.0, mastering = Mastering.BYPASS)` so `Clear` never defaults to `48000.0 Hz` and triples downstream JNI/FFI and Conformer tensor allocations.
  - **JVM + Native + System RAM Pressure Tracking (`MemoryPressureReport`):** Monitors ART JVM heap headroom (`jvmFreeHeadroomMb`), native heap (`Debug.getNativeHeapAllocatedSize()`), and OS `ActivityManager.MemoryInfo` simultaneously, preventing `java.lang.OutOfMemoryError` during `FfiWriter.floats()` serialization even on devices with 8–16 GB of physical RAM.
  - **Silence-Aware Smart Audio Chunking (`splitAudioIntoSmartChunks`):** Automatically segments long dictations at natural 80 ms speech pauses (`<= 20s` normal, `<= 14s` under tight memory) so Conformer attention tensors and JNI buffers stay bounded regardless of dictation length.
  - **Dynamic On-Demand Stage Loading & Automatic Low-Memory Retry:** Dynamically loads and releases auxiliary models (`Ear`, `Clear`) between pipeline stages when multiple models are active or memory is tight, and automatically sheds auxiliary sessions, switches to low-memory runtime flags, and splits >10s chunks on retry if `Voz` encounters a native driver error.
  - **Zero-Fragmentation Audio Recording Buffer (`AudioRecordingController`):** Replaced `MutableList<FloatArray>` chunk lists with a single contiguous primitive `FloatArray` accumulator.

## [2.3.8] - 2026-10-19

### Added
- **Secret Developer Mode & Persistent OOM / Crash / Debug Logger (`DebugLogManager`, `DeveloperModeSection`, `MainActivity`, `OpenFlowApp`, `SmartPipeline`):**
  - Added a version subtitle (`Version 2.3.8`) directly under the **OpenFlow Settings** title. Tapping the version number **5 times fast** secretly unlocks **Developer Mode** (with countdown toast hints on taps 3 and 4).
  - **OOM & Native Crash Detection (`ApplicationExitInfo` + `UncaughtExceptionHandler`):** Inspects Android 11+ `ActivityManager.getHistoricalProcessExitReasons` on startup to capture silent OS Out-Of-Memory kills (`REASON_LOW_MEMORY` / LMKD), native `libLiteRt.so` / OpenCL crashes (`REASON_CRASH_NATIVE` SIGSEGV/SIGABRT), and ANRs alongside last recorded PSS/RSS memory usage, plus uncaught JVM exceptions and `OutOfMemoryError` traces with live heap snapshots.
  - **Persistent Rolling Debug & Memory Telemetry (`debug_log.txt` & `crash_log.txt`):** Logs JVM heap, native heap (`Debug.getNativeHeapAllocatedSize()`), and system available RAM (`ActivityManager.MemoryInfo`) before/after model loads, inference stages (`Clear`, `Ear`, `Uhm`, `Voz`), and `onTrimMemory` callbacks directly to disk so logs survive sudden OOM process termination.
  - **Developer Mode Panel (`DeveloperModeSection`):** Provides live auto-refreshing heap/RAM telemetry, a toggle to enable/pause disk debug logging, filter chips (`All Logs`, `Crash & OOM Only`, `Debug & Memory Only`), an in-app scrollable monospace log viewer, **Copy Logs**, **Share**, **Log RAM Now**, **Clear Logs**, and **Hide Dev Mode** controls.

## [2.3.7] - 2026-10-19

### Fixed
- **Adaptive Status Bar & System Bar Icon Appearance (`Theme`, `MainActivity`):**
  - Updated status bar and navigation bar appearance logic (`WindowCompat.getInsetsController` with `isAppearanceLightStatusBars = !darkTheme` and `enableEdgeToEdge` with `SystemBarStyle.auto`) so status bar icons automatically switch between dark icons in light mode and light icons in dark mode.

## [2.3.6] - 2026-10-19

### Fixed
- **System Inset & Safe Drawing Padding (`MainActivity`, `OnboardingScreen`):**
  - Applied `Modifier.safeDrawingPadding()` (`WindowInsets.safeDrawing`) to the UI containers in `MainActivity` and `OnboardingScreen` so content respects status bar, navigation bar, display cutout, and IME insets on Android edge-to-edge displays.

## [2.3.5] - 2026-10-18

### Fixed
- **Accurate Storage & RAM Consumption Figures (`ModelSettings`, `OnboardingScreen`, `SpeechRecognitionSettingsSection`, `README.md`):**
  - Updated `DesertAntModel` storage sizes (`sizeMb` and `expectedBytes`) to match the exact on-disk LiteRT v4.0.0 model artifacts: **`Voz` (`1,187 MB` / `1,244,258,225 B`)**, **`Ear` (`10 MB` / `10,738,698 B`)**, **`Clear` (`47 MB` / `49,285,108 B`)**, and **`Uhm` (`23 MB` / `23,919,709 B`)** — yielding `1,197 MB` for the default `Voz + Ear` configuration and `1,267 MB` with all 4 models enabled.
  - Updated `estimatedPeakRamMb` across all 8 model combinations (`1,400 MB` for `Voz` only, `1,450 MB` for default `Voz + Ear`, up to `1,680 MB` with all 4 models enabled) to reflect actual loaded LiteRT model weights and runtime buffers.

## [2.3.4] - 2026-10-18

### Fixed
- **Centered Checkmark Fade-In & Content Alignment During Bubble Morphing (`BubbleOverlayComponent`, `FloatingBubbleService`):**
  - Removed the nested `SizeTransform` on `AnimatedContent` and set `contentAlignment = Alignment.Center` + `Modifier.fillMaxSize()` so `AnimatedContent` no longer lags behind the outer `Surface` width animation and pulls the completion checkmark (`Icons.Rounded.Check`) to the left while shrinking into a circle.
  - Replaced scale/size transitions inside `AnimatedContent` with a centered crossfade (`fadeIn` / `fadeOut` with `SizeTransform = null`) and preserved `processingProgress` until returning to `IDLE` so the green checkmark fades in directly in the center of the bubble.
- **GitHub `README.md` Formatting & Installation Guide:**
  - Cleaned up `README.md` formatting for GitHub (removed raw LaTeX math tags, added direct APK installation instructions linking to GitHub Releases alongside source build instructions, and updated floating bubble documentation).

## [2.3.3] - 2026-10-18

### Changed
- **Green Success Confirmation & Exact-Fit Stage Pill Width (`BubbleOverlayComponent`):**
  - Restored the dedicated Material Green (`#00C853`) bubble surface with a crisp white checkmark icon for `OverlayBubbleState.SUCCESS` instead of Material You `tertiaryContainer` (which could render pink depending on wallpaper palette).
  - Replaced estimated character-count width calculation with exact `rememberTextMeasurer()` pixel measurement (`measuredTextWidthDp + 45.dp`) so the expanding pill during `Ear`, `Clear`, `Uhm`, and `Voz` stages is only as wide as the spinner and text require with snug `12.dp` horizontal padding.

## [2.3.2] - 2026-10-18

### Changed
- **Minimalistic Google Material Design 3 Floating Bubble (`BubbleOverlayComponent`):**
  - Removed outlines/borders, glossy inner gradients, corner dots, and container pulsing during dictation for a clean, flat Google Material 3 aesthetic.
  - Kept the tonal Material 3 color palette (`primaryContainer`, `errorContainer`, `secondaryContainer`, `tertiaryContainer`) and dynamic spring-expanding pill that adapts its width to display the full active pipeline stage text (`Denoising...`, `Detecting Lang...`, `Filtering Fillers...`, `Transcribing...`) without truncation.
  - Simplified the recording equalizer to a steady 3-bar voice indicator that reacts strictly to real microphone voice amplitude (`rms`) alongside a clean `Stop` icon.

## [2.3.1] - 2026-10-18

### Added
- **Configurable Bubble Disappear Delay (`0.0s` – `5.0s`):**
  - Added a **Bubble Disappear Delay** slider in **Floating Bubble Settings** (`KEY_BUBBLE_DISAPPEAR_DELAY_MS`, default `0.5s` / `500 ms`, adjustable from `Instant (0.0s)` to `5.0s` in `0.25s` increments) controlling how long the floating bubble remains on screen after the keyboard closes.

### Fixed
- **Strict On-Screen Keyboard (`TYPE_INPUT_METHOD` & `WindowInsets.Type.ime()`) Visibility Enforcement:**
  - Removed the false-positive hardware keyboard fallback (`Configuration.keyboard`) and `else -> true` Accessibility Service bypass that previously caused the bubble to stay visible when no keyboard was open.
  - Required a visible `TYPE_INPUT_METHOD` root hierarchy (`imeRoot.isVisibleToUser && imeRoot.childCount > 0`) or active `WindowInsetsCompat.Type.ime()` inset, bumped the preference key to `show_only_on_inputs_v3` (default `true`), and set `WindowManager.LayoutParams.alpha = 0f` + `FLAG_NOT_TOUCHABLE` after the exit animation so `SurfaceFlinger` never leaves a frozen surface frame on screen when the keyboard closes.

## [2.3.0] - 2026-10-18

### Added
- **Material Design 3 Floating Bubble with Expressive Spring UI Physics (`BubbleOverlayComponent` & `Theme`):**
  - Redesigned the floating overlay bubble around Material Design 3 semantic color roles (`primaryContainer`/`onPrimaryContainer`, `errorContainer`/`onErrorContainer`/`error`, `secondaryContainer`/`onSecondaryContainer`, `tertiaryContainer`/`onTertiaryContainer`) with Material You dynamic color support and a custom M3 Indigo/Teal fallback palette.
  - Added M3 Expressive shape morphing from a `32%` squircle FAB in `IDLE` into a `50%` Stadium Pill during `RECORDING` and `PROCESSING`, driven by `Spring.DampingRatioLowBouncy` physics.
  - Added interactive touch-press squish (`0.90×`), drag-lift (`1.08×` scale + `12.dp` elevation), voice-reactive breathing pulse, `AnimatedVisibility` spring entrance/exit transitions, and a 4-bar spring-driven audio equalizer with a filled M3 stop button badge.

### Changed
- **Default Model Selection (`Voz` + `Ear` Only):**
  - Updated `ModelSettings` defaults so only **`Voz`** (required speech recognition) and **`Ear`** (auto language detection) are enabled by default (`359 MB` download, `~650 MB` peak RAM), while **`Clear`** and **`Uhm`** remain available as optional toggles.
- **On-Screen Keyboard (`TYPE_INPUT_METHOD`) Visibility Detection for Bubble Auto-Show/Hide:**
  - Updated `TextInjectionAccessibilityService` and `accessibility_service_config.xml` (`typeWindowsChanged`) to detect on-screen soft keyboard (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`) visibility across all apps (native `EditText`, Jetpack Compose, Chrome/WebViews, Flutter) alongside hardware keyboard input focus, and automatically toggle `FLAG_NOT_TOUCHABLE` when the bubble is hidden.

## [2.2.0] - 2026-10-18

### Added
- **Hardware Acceleration Selector (`Auto`, `NPU`, `GPU`, `CPU`):**
  - Added `HardwareAccelerator` configuration in Settings allowing users to choose between `Auto (NPU / GPU / CPU)`, `NPU / AI Accelerator`, `GPU (OpenCL)`, and `CPU (Multi-Thread XNNPACK)` with automatic `DAL_LITERT_THREADS` thread-pool tuning and live active-delegate reporting.
- **Live Model Load Status & Performance Telemetry:**
  - Exposed real-time `PipelineStatus` (`StateFlow`) tracking overall pipeline phase (`Models Loaded & Ready`, `Loading Models...`, `Warming Up Accelerator...`, `Transcribing...`) and per-model RAM states (`Loaded in RAM`, `Loading...`, `Running Inference`, `Standby (On Disk)`, `Not Downloaded`, `Disabled`) across Settings, the Floating Bubble badge, and the Foreground Service notification.
  - Added transcription latency and real-time speedup telemetry (`Last dictation: X ms for Ys audio (Z× real-time)`) and a manual **Load & Warm Up Models in RAM** action button.
- **Streaming 1 MB SHA-256 Verification & `.openflow_verified` Stamps:**
  - `ModelDownloadManager` now streams SHA-256 verification on `Dispatchers.IO` with a 1 MB buffer, live percentage progress (`Verifying 0%..100%`), dynamic queue appending when enabling multiple optional models, and bounded integrity retries (`MAX_INTEGRITY_RETRIES = 3`).
  - Persisted `.openflow_verified` stamps (`size:mtime:sha256`) allow already-verified artifacts to be skipped in O(1) time without re-hashing the 1.2 GB `encoder.tflite` on subsequent checks or resumed sessions.

### Fixed
- **Multi-Model Loading & Native Memory Crashes (`Clear` + `Ear` + `Uhm` + `Voz`):**
  - Eliminated native 1.2 GB `POSIXFileSystem.read` + pure-Swift `SoftwareSHA256` memory spikes and UI freezes by passing explicit verified revision directories (`DesertAntModelStorage.modelRevisionDir`) directly to `Voz(context, directory)`, `Ear(context, directory)`, `Clear(context, directory)`, and `Uhm(context, directory)`, routing through `ModelDistribution.load(from: directory)` instead of `ModelStore`.
  - Replaced JNI `Voz(context).isDownloaded()` calls in `DesertAntModelStorage.isModelDownloaded()` with an O(1) non-JNI file size, `.part` absence, and `.openflow_verified` stamp check.
  - Changed `SmartPipeline.transcribe()` to execute all 4 native LiteRT stages (`Clear` $\rightarrow$ `Ear` $\rightarrow$ `Uhm` $\rightarrow$ `Voz`) strictly sequentially under `pipelineMutex` and keep `Clear` warm in RAM across dictations (avoiding mid-pipeline `eglTerminate` on the shared `EGLDisplay`), preventing concurrent OpenCL / `libLiteRt.so` driver race crashes.
  - Enabled `android:largeHeap="true"` and extended warm model retention in RAM to 30 minutes.
- **Inference Latency Optimization:**
  - Added background LiteRT session pre-warming (`initializeAsync(warmUp = true)`) on app launch and service start so the first dictation runs without lazy graph compilation lag.
  - Added `trimLeadingAndTrailingSilence()` to strip dead air before running `Clear`, `Ear`, `Uhm`, and `Voz`, and bounded `Ear` language identification to a 5.5-second window (`windows = 2`).
- **Floating Bubble & Background Service Stability:**
  - Configured `FloatingBubbleService` with `microphone|specialUse` foreground service types and immediate `startForeground()` in `onCreate()` with Android 14+ `SecurityException` fallback so the service starts reliably even when launched before microphone focus is active.
  - Fixed the `stopService()` + `startForegroundService()` race condition in `MainActivity` by refreshing the running service in-place via `ACTION_REFRESH_SETTINGS`, and removed `smartPipeline.shutdown()` from `FloatingBubbleService.onDestroy()`.
  - Changed the default of "Only show bubble when a text field is focused" (`show_only_on_inputs_v2`) to `false`, ensured the bubble never hides when `TextInjectionAccessibilityService` is disconnected, and added a 2-second focus grace period plus multi-window `TYPE_APPLICATION` focus search so tapping the overlay never hides the bubble or loses the target input field.
  - Added screen-bounds clamping (`coerceIn(0, maxX/maxY)`) and `NaN` drag guards so the bubble can never be dragged off-screen, and acquired a partial `WakeLock` during recording and transcription.

## [2.1.0] - 2026-10-18

### Added
- **Desert Ant Labs 4-Stage On-Device Audio AI Pipeline (`SmartPipeline`):**
  - **Stage 1 (`Clear` v4.0.0, `clear-studio` profile, 24 MB):** Optional neural speech enhancement and room-noise suppression operating directly on in-memory 16 kHz mono PCM samples.
  - **Stage 2 (`Ear` v4.0.0, 22 MB $\parallel$ `Uhm` v4.0.0, 24 MB):** Concurrent coroutine execution of spoken language identification (across 107 languages, routing to `Voz` when reliable and supported, or falling back to `ModelSettings.preferredLanguage`) and 20 ms acoustic filler frame classification (`uh`, `um`, `hmm`, `and`, `other`).
  - **Stage 3 (`Voz` v4.0.0, 337 MB):** Multilingual speech-to-text across 25 European languages powered by LiteRT 2.2.0 (with built-in GPU/OpenCL acceleration and automatic CPU XNNPACK fallback), word-level timestamps ($80\text{ ms}$ resolution), custom vocabulary biasing, and automatic single-retry recovery.
  - **Stage 4 (Temporal Filler Subtraction & Text Normalization):** Merges contiguous/near-contiguous non-zero filler frames separated by $\le 40\text{ ms}$, expands filler spans by $\tau = 80\text{ ms}$ boundary tolerance, removes `Voz` word tokens whose overlap ratio is $\ge 50\%$ of word duration, re-capitalizes sentence-initial words when leading fillers are stripped, and cleans up orphaned punctuation.
- **Resumable Background Model Download Manager (`ModelDownloadManager` & `ModelDownloadService`):**
  - Byte-exact HTTP Range (`Range: bytes=<offset>-`) resumable downloads into `.part` files using `RandomAccessFile`, preserving progress across manual pauses, connection drops, and app restarts.
  - Slow-internet resilience with 16 KB streaming buffers, generous socket timeouts (45s connect, 90s read), and up to 15 automatic exponential-backoff retries per stall (resetting whenever $\ge 64\text{ KB}$ of forward progress is made).
  - Live download telemetry in both Onboarding and Settings: 4-second rolling window download speed (`MB/s` or `KB/s`), estimated time remaining (`~Xm Ys left`), active file name, and `downloaded MB / total MB` counters with **Pause** and **Resume** controls.
  - Foreground service (`ModelDownloadService`, `dataSync`) with partial `WakeLock` and live status-bar progress notification so downloads continue when switching apps or locking the screen.
- **Thread-Safe Lifecycle & Memory Management:**
  - Single coroutine `Mutex` serializing `initialize()`, `transcribe()`, `reloadSettings()`, `evictOptionalModels()`, and `shutdown()`.
  - Warm-session retention with a 180-second inactivity timer and `ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` callback to evict optional models (`Clear`, `Ear`, `Uhm`) while keeping `Voz` resident.
- **First-Launch Onboarding Flow (`OnboardingScreen`):**
  - Pre-download model customization (`Voz` required; `Ear`, `Clear`, `Uhm` optional and enabled by default) with dynamic download size and peak RAM estimates.
  - Sequential download queue on `Dispatchers.IO` with SHA-256 and `isDownloaded()` post-download integrity verification, and automatic bypass on subsequent launches.
- **Speech Recognition Settings & Storage Management (`SpeechRecognitionSettingsSection`):**
  - Resource Summary Card displaying active disk footprint (`337 MB`–`407 MB`), estimated peak RAM (`~550 MB`–`~850 MB`), and downloaded model count (`X / 4`).
  - Per-model toggles with resumable background download progress when enabling uncached optional models.
  - Always-visible Manual / Fallback Language selector chips across all 25 `Voz.supportedLanguages`.
  - Custom Vocabulary chip editor persisted in `SharedPreferences`.
  - Storage management controls to delete individual or all inactive optional models from disk.

### Changed
- **Unified English UI & Documentation:**
  - Standardized all application screens, notifications, permission prompts, toast messages, accessibility service descriptions, and documentation in English.
- **License Migration (`GPL-3.0` $\rightarrow$ `Apache-2.0`):**
  - Migrated the OpenFlow repository license from `GPL-3.0` to `Apache-2.0` across `LICENSE`, `README.md`, `app/build.gradle.kts`, and source headers to ensure legal compatibility with the **Desert Ant Labs Source-Available License v1.0** (`https://license.desertant.com/1.0`).
  - **Commercial Threshold Notice:** Under the Desert Ant Labs Source-Available License v1.0, each model (`Voz`, `Ear`, `Clear`, `Uhm`) is free for personal, academic, and commercial use up to **100,000 Monthly Active Users / Devices (MAU/MAD)** per model, per platform. Commercial deployments exceeding 100,000 MAU/MAD on Android require a separate commercial license from Desert Ant Labs.

### Removed
- Purged legacy speech recognition binaries, `.aar` archives, bundled model assets, legacy speech engine wrapper, and `setup_project.sh`, producing a slim ~73 MB `arm64-v8a` release APK with in-app model delivery.
