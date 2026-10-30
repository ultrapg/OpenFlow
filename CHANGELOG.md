# Changelog

All notable changes to the **OpenFlow** project are documented in this file.

## [2.14.1] - 2026-10-30

### Fixed & Improved
- **Eliminated `Liquid AI LFM3 1.7B` Native Loading Crashes & Hardened Tri-Hybrid Runtime Stability (`build.gradle.kts`, `ModelSettings`, `GemmaTextProcessor`, `SmartPipeline`):**
  - **Fixed Fatal Native `Backend.NPU` / `Backend.GOOGLE_TENSOR` JIT Compiler Crash on Load:** `LFM3-1.7B_mixed_int4_ekv2048.litertlm` omits `supported_backends` in its `LlmMetadata` header (`emptySet()`), which caused `v2.14.0`'s non-empty backend check to fall through and invoke `LiteRtCompilerPluginCompile` on `LFM3`'s `1.44 GB` unpartitioned tri-hybrid graph (`13` custom `kv_cache_c_*` short-conv tensors, `"No NPU file is part of this release"`), fatally crashing in native C++ inside `Engine.initialize()`. Set `LFM_3_1_7B.supportsNpuBackend = false` and gated NPU probing via `shouldAllowNpuBackend(...)` so standard `LFM3` goes directly to `Backend.GPU()` -> `Backend.CPU()` in both `AUTO` and `NPU` modes (unless a custom `*_npu.litertlm` bundle is side-loaded).
  - **Upgraded `litertlm-android` to `0.19.0` & Unified `litert` / `litert-gpu` `2.3.2` Shared Libraries:** Upgraded `com.google.ai.edge.litertlm:litertlm-android` from `0.18.1` to **`0.19.0`** (adding native GPU subgraph cache binding for `LFM3`'s `kv_cache_c_*` ping-pong short-conv state buffers) and packaged LiteRT **`2.3.2`** `libLiteRt.so` & `libLiteRtClGlAccelerator.so` ahead of `core:4.0.0`'s older `2.2.0` copies (with verified 100% C-API symbol compatibility for `Voz` and `Ear`).
  - **Locked Static `ekv2048` Context Window & Preserved Native Subgraph Activations:** Locked `selectTokenWindowCandidates` on `LFM_3_1_7B` (and non-dynamic `.litertlm` graphs) to `2,048` tokens across **all** memory modes including `LOW_MEMORY` (preventing `maxNumTokens = 1,536` / `4,096` native LiteRT buffer-size `ABSL_CHECK` aborts), kept `activationDataType = null` on `LFM3`, disabled positional `filterChannelContentFromKvCache` rewinding on non-positional `kv_cache_c_*` state, and packed `LFM3` prompt + transcript into a single unsplit `prefill_256` pass without dummy warm-up turns.
  - **Crash-Sentinel Shader-Cache Recovery, Runtime Cache Stamp & OOM Guards:** Added automatic runtime-version cache purging (`ensureCacheRuntimeStamp`) to wipe any incompatible `v2.14.0` / `0.18.1` cache files on upgrade, synced `.lock` crash-sentinel recovery (`prepareBackendInitAttempt`) that purges half-written OpenCL shader caches and retries with `":nocache"` (or skips a repeatedly crashing GPU driver after 2 native crashes), a `1,700 MB` free-RAM guard before `LFM3`'s `2.21 GB` CPU `XNNPACK` fallback, and realistic `1,250 MB` / `2,400 MB` `SmartPipeline` RAM staging thresholds so `Voz` swaps out cleanly before loading `LFM3` on RAM-constrained phones.

## [2.14.0] - 2026-10-30

### Added, Improved & Fixed
- **Liquid AI `LFM3 1.7B` Added as Default Formatting LLM & `Gemma 4 E2B` Positioned for High-End Devices (`ModelSettings`, `ModelDownloadManager`, `SpeechRecognitionSettingsSection`, `OnboardingScreen`, `GemmaTextProcessor`, `SmartPipeline`):**
  - **Added Official `Liquid AI LFM3 1.7B` (`litert-community/LFM3-1.7B-LiteRT`, `1.34 GB`):** Integrated `LFM3-1.7B_mixed_int4_ekv2048.litertlm` (`1,441,620,928` bytes, SHA-256 verified, tri-hybrid architecture with `13` gated short convolutions + `5` sliding-window attention + `6` GQA layers and `~1,000 MB` standalone RAM) and set **`LFM_3_1_7B` (`DEFAULT`)** as OpenFlow's default AI post-processing model while preserving **`Gemma 4 E2B` (`HIGH-END`)** unchanged with its full flagship prompt and Adaptive Smart Thinking for high-end devices.
  - **Thinking Disabled by Default on `LFM3` (`defaultDisableThinking`):** Added per-model `defaultDisableThinking` defaults (`true` on `LFM3 1.7B` for instant zero-thought direct output; `false` on `Gemma 4 E2B` & `Qwen 3` models). Switching between models in Settings or auto-enabling after download automatically applies each model's optimal default while preserving manual toggles on the active model.
  - **`prefill_256`-Engineered `LlmPromptTier.LIQUID_LFM_3` System Prompt (`4×` Faster Prefill):** Because `LFM3-1.7B` ships prefill signatures `{1, 4, 16, 64, 256, 1024}` with **no `512` bucket**, any system prompt exceeding `256` tokens pads `4×` up to `prefill_1024`. Built a dedicated slash-condensed `< 245`-token `LIQUID_LFM_3` prompt tier (including ChatML wrapper tags, app-context hint, and 1 grounded example) so `prefillPrefaceOnInit = true` lands inside `prefill_256`.
  - **Zero-Waste `AUTO` Backend Selection, Fast `1.2s` Recording Overlap & `LFM3` Special-Token Sanitization:** Skips wasted `Backend.NPU` init probes in `AUTO` mode when `LFM3`'s static `ModelInfo` declares only `[CPU, GPU]` (while still probing NPU when `HardwareAccelerator.NPU` is selected or an `*_npu.litertlm` bundle is present), includes `LFM3 1.7B` in `SmartPipeline`'s `1,200 ms` `FAST_POST_PROC_OVERLAP_MS` pre-warm path (`t = 0 ms` start on bubble tap), and strips orphan closing `</think>` prefixes, `<tool_call>` blocks, `<|startoftext|>`, and `<|pad|>`.
- **Fixed Missing / Dropped Spoken Text Across Audio Capture, Chunking, `Voz` STT & LLM Post-Processing (`AudioRecordingController`, `SmartPipeline`, `AudioChunking`, `GemmaTextProcessor`, `FloatingBubbleService`):**
  - **3-Tier Post-Stop AudioFlinger / DSP HAL Flush (`computeStopFlushGraceMs`):** Fixed final spoken words getting cut off when tapping Stop right at the end of a phrase (where `80–130 ms` of final speech is still in flight inside Android's AudioFlinger/DSP pipeline while the last `80 ms` of `pcmBuffer` sat in a stop-consonant closure). `stopRecording()` now waits `165 ms` when speech is active in the trailing `160 ms`, `95 ms` to flush HAL buffers whenever speech occurred within the last `450 ms`, and `0 ms` when already silent `>= 450 ms`, followed by a `32`-iteration non-blocking HAL buffer drain.
  - **Full Parakeet TDT Conformer Right-Context & Kernel Padding in `SmartPipeline.transcribe()`:** Restored `minLeadSilencePadSamples = 5_120` (`320 ms`), `minTailSilencePadSamples = 10_240` (`640 ms` = `8` Conformer frames of TDT right-context lookahead), `minTotalSamples = 25_600` (`1.60 s` for the `31 * 80 ms` depthwise conv kernel), and `0.0018f` / `500 ms` outer silence-trim margins so `Voz` never withholds final words or starves on short 1–2 word utterances.
  - **`150 ms` Forced-Cut Micro-Overlap & Sensitive `0.0020f` Tail Speech Detection (`AudioChunking`):** Added `FORCED_CUT_OVERLAP_SAMPLES = 2_400` (`150 ms`) strictly on forced non-pause live cuts (`usable >= maxChunk`) so continuous speech is never severed mid-syllable (`stitchChunkTranscripts` deduplicates boundary words), and lowered `hasAnyTailSpeech`'s RMS floor to `0.0020f` (`4` frames / `80 ms`) so quiet 1-syllable final words (`"no"`, `"it"`, `"so"`, `"ok"`) after a live pause cut are never dropped while rejecting `40 ms` button taps.
  - **Decimal-Safe Sentence Sieve & LLM Anti-Truncation Recovery (`GemmaTextProcessor`):** Replaced `SENTENCE_TOKENIZE_REGEX` with disjoint $O(N)$ lookahead `(?:[^.!?…\n]|\.(?=\d))+(?:[.!?…]+|$)` and exempted already-formatted `version 2.8.0` tokens from `NUMBER_AND_UNIT_TRIGGER_REGEX` so decimals/versions never split sentences mid-number or miss fast-path sieve bypass, raised `1,536`-window `maxBaseCap` to `448` tokens, and added lexical & tail-anchor anti-truncation recovery (`isSuspiciouslyTruncatedOutput`) so any premature `<|im_end|>` sentence drop on non-backtrack transcripts safely preserves 100% of spoken words.

## [2.13.0] - 2026-10-29

### Added, Improved & Fixed
- **NPU Runtime 2.3.2 Upgrade & Fixed Native NPU Dispatch / Compiler-Plugin Discovery (`build.gradle.kts`, `NpuHardwareManager`, `GemmaTextProcessor`, `ModelSettings`):**
  - **Upgraded `litert-npu-runtime-{qualcomm,mediatek,google-tensor}` from `2.3.0` to `2.3.2`:** Pulls in the latest Qualcomm QNN HTP (`v73` / `v75` / `v79`), MediaTek NeuroPilot (`v8` / `v9`), and Google Tensor (`G3` / `G4` / `G5` Artisan) dispatch & on-device compiler plugins.
  - **Fixed `LITERT_DISPATCH_LIBRARY_DIR` / `LITERT_COMPILER_PLUGIN_LIBRARY_DIR` Path Resolution:** Pointed LiteRT's dispatch and compiler-plugin environment variables in `NpuHardwareManager.configureNativeRuntime()` to `context.applicationInfo.nativeLibraryDir` (where `libLiteRtDispatch_*.so` and `libLiteRtCompilerPlugin_*.so` are extracted by `useLegacyPackaging = true`), while combining `nativeLibraryDir` and `/vendor/lib64` in `QNN_LIBRARY_PATH`. Previously, pointing `LITERT_DISPATCH_LIBRARY_DIR` solely at `/vendor/lib64` prevented `libLiteRt.so` from finding the bundled NPU dispatch and JIT compiler libraries.
  - **Unconditional NPU Candidate Probing & Drop-In NPU Model Support (`resolveBestModelFile`):** Enabled `supportsNpuBackend = true` across all `LlmModelChoice` models, prioritized `Backend.GOOGLE_TENSOR()` on Pixel Tensor devices alongside `Backend.NPU(nativeLibraryDir)`, and added automatic detection of NPU-specific `.litertlm` files (`*_npu.litertlm`, `*_NPU_Uncompiled.litertlm`) in the model directory with graceful cascade to `GPU` -> `CPU`.
- **Faster On-Device LLM Inference, `FLOAT16` Activations & Instant KV-Cache Pre-Warming (`GemmaTextProcessor`, `SmartPipeline`):**
  - **Removed Production `ExperimentalFlags.enableBenchmark = true` Overhead:** Stopped enabling LiteRT-LM's native per-token benchmark profiling mode during normal production `Engine.initialize()` (retaining it strictly inside the Settings Speed Benchmark), eliminating synchronous JNI/GPU/NPU profiling barriers on every token.
  - **`ActivationDataType.FLOAT16` on GPU & NPU:** Configured `EngineConfig(activationDataType = ActivationDataType.FLOAT16)` on GPU/NPU backends (with automatic fallback if a specific model signature rejects it) to halve activation memory bandwidth during prefill and decode.
  - **Instant (`0 ms`) Warm-Model Prompt Pre-Warming & Tiered Recording Overlap:** Reduced recording-overlap delay from a flat `4,500 ms` to **`0 ms` immediately after `Voz` is ready** when the LLM is already resident in RAM (so `prewarmSystemPrompt` finishes while the user is still speaking their first second of audio), **`1,200 ms`** (`FAST_POST_PROC_OVERLAP_MS`) for compact models (`Qwen 3 0.6B` / `Lite` / `Punct`), and **`2,000 ms`** (`DEFERRED_POST_PROC_OVERLAP_MS`) for larger models; tightened `schedulePostDictationPromptPrewarm()` delay from `400 ms` to `40 ms` so back-to-back dictations hit a warm KV cache (`~20–35 ms` TTFT).
  - **Expanded `< 1 ms` Zero-LLM Fast Path (`canBypassLlmFastPath`):** Expanded deterministic fast-path eligibility from `18` words (`140` chars) to `28` words (`220` chars) for clean, well-punctuated sentences.
- **End-to-End Audio, Conformer & Text-Injection Latency Reductions (`AudioRecordingController`, `SmartPipeline`, `StreamingDictationSession`, `TextInjectionAccessibilityService`):**
  - **Smart Trailing-Silence Stop Grace Skip (`hasTrailingSpeechEnergy`):** `AudioRecordingController.stopRecording()` now inspects the trailing `80 ms` (`1,280` samples) of the capture buffer and skips the post-stop wait in **`0 ms`** when the user already paused before tapping Stop (and uses a tight `75 ms` grace window instead of `180 ms` when tapped mid-word), saving **`105–180 ms`** on every dictation stop.
  - **Tighter `Voz` Conformer Padding & Faster Single-Window `Ear` Pre-Detection:** Reduced `prepareChunkForInference` silence runway from `960 ms` (`5,120` lead + `10,240` tail, `25,600` min) to `480 ms` (`2,560` lead + `5,120` tail, `16,000` min), cutting 6 Conformer encoder frames per chunk; lowered live `Ear` pre-detection threshold to `1.2s` (`19,200` samples) with `Options(windows = 1)` on short `< 2.5s` slices and `85 ms` active streaming poll cadence.
  - **Progressive Low-Latency Paste Verification:** Replaced fixed `35 ms + 35 ms + 45 ms + 65 ms` sleeps in `TextInjectionAccessibilityService` with `8 ms` clipboard settle, `15 ms` focus settle, and progressive `12 ms -> 28 ms -> 55 ms` verification polling, saving **`~60–75 ms`** on every paste into standard Android & Compose inputs while keeping full `95 ms` cumulative patience for slow WebViews.

## [2.12.2] - 2026-10-28

### Fixed & Improved
- **Eliminated Gray Placeholder / Hint Text Typing Bug & Upgraded Text-Field Input System (`TextInjectionAccessibilityService`, `GemmaTextProcessor`):**
  - **Widget-Native `ACTION_PASTE`-First Injection:** Switched primary text injection on focused inputs to verified widget-native `ACTION_PASTE` (ensuring target `ACTION_FOCUS` + refreshed `hasPrimaryClip()` state first for both AOSP `TextView` and Jetpack Compose `SemanticsActions.PasteText`), operating directly on the widget's real internal buffer (`EditText.Editable`, Compose `TextFieldValue`/`TextFieldState`, Chromium DOM) so screen-reader placeholder/hint strings in `AccessibilityNodeInfo.getText()` are never typed out into the text field, while preserving rich-text spans and Undo/Redo history.
  - **Multi-Signal Disguised Placeholder Sanitizer (`sanitizeEditableNodeText` / `extractSanitizedEditableText`):** Strips unmarked gray placeholders, Material `TextInputLayout` `placeholderText` / `", <hint>"` suffixes, Jetpack Compose `DecorationBox` merged child `Text` placeholders, static `-1..-1` wrapper labels, and ellipsis prompts (`"..."` / `"…"`) both on sanitized `ACTION_SET_TEXT` fallback and in `GemmaTextProcessor.inspectActiveFieldContextStatic` (preventing placeholder text from falsely triggering mid-sentence lowercase continuation).
  - **Smart Context-Aware Paste Spacing & Deeper Focus Resolution (`computeSmartPastePayload`):** Automatically inserts smart boundary spaces on back-to-back dictations and mid-sentence cursor insertions (`selectionStart > 0 || selectionEnd > 0`) while suppressing auto-spaces in empty/placeholder fields (`0..0`), email, phone/number, password, and spaceless URL inputs; expanded paste-time-only focus search bounds (`MAX_BFS_NODES = 320`, `MAX_DRILL_NODES = 192`) and depth-2 `TextInputLayout` event-source capture.
- **Fixed Duplicate Clipboard Copies (`FloatingBubbleService`, `TextInjectionAccessibilityService`):**
  - **Strict Single-Write Clipboard Contract (`TextInjectionResult`):** Removed redundant pre-injection and post-injection `ClipboardManager.setPrimaryClip` calls in `FloatingBubbleService.processAudio()` so dictated text is written to the system clipboard **strictly once per dictation**—eliminating duplicate keyboard clipboard-history entries and duplicate clipboard notifications.

## [2.12.1] - 2026-10-28

### Fixed
- **Fixed Floating Bubble Dictation Regression Producing No Text Field or Clipboard Output (`TextInjectionAccessibilityService`, `FloatingBubbleService`, `AudioRecordingController`, `BubbleOverlayComponent`, `SmartPipeline`, `GemmaTextProcessor`, `accessibility_service_config.xml`):**
  - **Eliminated Main-Looper Accessibility IPC Freezes:** Removed multi-window Accessibility subtree BFS walks (`resolveBestTargetInputNode`) from periodic keyboard visibility checks (`refreshKeyboardVisibilityState()`), keystroke event listeners (`typeViewTextChanged`), `t = 0 ms` recording-start focus pinning (`captureTargetNodeForDictation`), and background context inspection (`GemmaTextProcessor.inspectActiveFieldContextStatic`). Keyboard detection now uses strictly `O(1)` `WindowMetrics` IME insets and `TYPE_INPUT_METHOD` window geometry checks with zero node-tree walks on `Looper.getMainLooper()`.
  - **Off-Main-Thread `AudioRecord` Capture & Race-Free Recording Cycles:** Moved `AudioRecordingController.startRecording()` and `stopRecording()` onto `Dispatchers.IO`, guarded start/stop transitions with `recordingCycleId` + `rememberUpdatedState` in `BubbleOverlayComponent`, and deferred heavy `Gemma 4 E2B` live-recording overlap (`4.5s` + `2.9 GB` free-RAM guard) plus restored `200 ms` (`3,200`-sample) `Voz` warm-up buffers so speech capture and stop-tap transcription never stall or trigger low-memory kills.
  - **Post-Action Verified Text Injection (`hasConfirmedTextInjection`) + Guaranteed Intact Clipboard:** Every non-blank transcription is now copied to the clipboard upfront as plain `ClipData` (`clearPrimaryClip()` and `EXTRA_IS_SENSITIVE` masking removed so dictated text is never wiped or hidden on Android 10–15). Both `ACTION_SET_TEXT` and `ACTION_PASTE` now verify via `AccessibilityNodeInfo.refresh()` (`+45 ms` / `+110 ms`) that the focused input's text, placeholder-hint state, or cursor actually changed—automatically falling back between `ACTION_SET_TEXT`, `ACTION_PASTE`, focus restoration, and `"Copied to clipboard"` notification whenever a wrapper or custom editor silently ignores an action, and removing unfocused single-editable guessing so text is never sent to an unrelated view.
  - **Explicit No-Speech Feedback:** Blank/silent captures now return cleanly to `IDLE` with a `"No speech detected"` toast instead of displaying a misleading green completion checkmark with zero output.

## [2.12.0] - 2026-10-27

### Added, Improved & Fixed
- **Zero-Wait Parallel Model Loading & Live Transcription While Recording (`SmartPipeline`, `StreamingDictationSession`, `FloatingBubbleService`, `AudioRecordingController`, `DictationChunkSettings`):**
  - **`t = 0 ms` Parallel Model Load on Bubble Tap:** Starts `SmartPipeline.onRecordingStartedAsync()` simultaneously with `AudioRecord` hardware initialization (`80–160 ms` head start), prioritizing `Ear` (`10 MB`) + `Voz` (`1.19 GB`) with a fast `100 ms` (`1,600`-sample) accelerator warm-up.
  - **Lock-Free Inference Preemption (`pendingInferenceRequests`):** Increments `pendingInferenceRequests` *before* acquiring `pipelineMutex` so live audio chunks and Stop-tap transcriptions preempt background synthetic warm-up, LLM loading, and system-prompt pre-warming in `0 ms`—never blocking speech transcription behind multi-GB model loading.
  - **Live Mid-Sentence `Ear` Language Pre-Detection:** Peeks at the leading `~1.8s–3.5s` of live speech (`AudioRecordingController.peekLeadingSamples`) while you are still speaking your first sentence so `Ear` resolves spoken language *before* Chunk `#0` is even cut.
  - **Deferred & Inter-Chunk Post-Processor Overlap + Snappier Chunking Defaults (`DEFAULTS_VERSION = 4`):** Defers opportunistic co-resident LLM / `Punct` loading by `2.0s` while recording (keeping `< 2s` quick replies on the `< 1 ms` Zero-LLM Fast Path at `0 MB` LLM RAM), prepares/pre-warms the post-processor during inter-chunk idle gaps, supports live prefix cleanup for both `LiteRT-LM` and `Punct`, coalesces speech while `Voz` cold-loads, uses adaptive buffer-aware polling (`110 ms` active / `450 ms` buffer-fill sleep), and tunes default live chunking to `4.0s` min chunk, `550 ms` pause, and `16.0s` max chunk.
- **Background Efficiency & Deep-Sleep Battery Optimization (`SmartPipeline`, `FloatingBubbleService`, `TextInjectionAccessibilityService`):**
  - **Fixed `TRIM_MEMORY_UI_HIDDEN` False Eviction Bug (`classifyTrimMemoryAction`):** Fixed Android's two-band `ComponentCallbacks2` level comparison where leaving OpenFlow Settings (`TRIM_MEMORY_UI_HIDDEN == 20 > TRIM_MEMORY_RUNNING_LOW == 10`) unintentionally triggered low-memory model eviction when switching into a chat app; `UI_HIDDEN` now drops only standby KV cache while preserving loaded weights.
  - **45-Second Screen-Off Deep-Sleep Auto-Unload (`onScreenInteractiveChanged`):** Turning the display OFF immediately drops standby LLM KV cache and caps idle model retention at `45s` (`computeEffectiveIdleTimeoutMs`), releasing NPU/GPU/CPU sessions for deep sleep without penalizing brief lock/unlock checks.
  - **Adaptive Main-Looper Heartbeat & Deduplicated Window Polling:** Scaled `FloatingBubbleService` heartbeat (`300 ms` active dictation / `550 ms` visible bubble / `1,100 ms` hidden standby, `0%` CPU when screen is OFF) and switched `TextInjectionAccessibilityService` to lightweight `3.5s` watchdog mode whenever `FloatingBubbleService` is active, eliminating duplicate `getWindows()` IPC polling.
- **Bulletproof Focused Text-Field Auto-Paste (`TextInjectionAccessibilityService`, `GemmaTextProcessor`, `accessibility_service_config.xml`):**
  - **5-Tier Focused Input Resolution + Focus Pinning:** Captures the active editable target at `t = 0 ms` on bubble tap (`captureTargetNodeForDictation`), tracks focus/click/selection/text-changed events, skips `TYPE_APPLICATION_OVERLAY` and `TYPE_INPUT_METHOD` windows, drills into `WebView` / `AndroidComposeView` subtrees via bounded BFS (`<= 420` nodes) to find inner `<input>`, `contenteditable`, and Jetpack Compose `TextField`/`BasicTextField` nodes, and falls back to pinned/recent nodes or sole visible editable inputs when OEM ROMs momentarily drop `FOCUS_INPUT`.
  - **Eliminated `AccessibilityNodeInfo.recycle()` Cache Poisoning:** Removed deprecated `.recycle()` calls in `GemmaTextProcessor.inspectActiveFieldContextStatic` and `TextInjectionAccessibilityService` that mutated shared `AccessibilityCache` nodes right before text injection.
  - **Direct `ACTION_SET_TEXT` Priority + Placeholder Hint Protection + Smart Spacing:** Clears `isShowingHintText` placeholder hints (`"Message"`, `"Search..."`) so hint strings are never concatenated, uses direct Accessibility IPC `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` first on empty and cursor-known fields (completely bypassing Android 12–15 background clipboard restrictions) with automatic `ACTION_PASTE` fallback (`750 ms` async clipboard restore), smart boundary spacing (`spliceTextForSetText`), and automatic `ACTION_FOCUS` restoration + `90 ms` transition retry.

## [2.11.1] - 2026-10-27

### Fixed
- **Fixed `GemmaTextProcessor` Crash in Speed Benchmark & AI Post-Processing (`NoClassDefFoundError: com.voicebubble.openflow.core.speech.GemmaTextProcessor`):**
  - Removed the unbounded `\s+` quantifier inside the negative lookbehind of `SPOKEN_COLON_BEFORE_LIST_REGEX` (`semicolon\s+or` -> `or`) in `GemmaTextProcessor.kt`, which passed OpenJDK desktop regex compilation in JUnit but threw `PatternSyntaxException` (`U_REGEX_LOOK_BEHIND_LIMIT`) inside Android's ICU4C regex engine during `<clinit>` class initialization.
  - Added automated JUnit reflection verification across all `Regex` fields in `GemmaTextProcessor`, `SmartPipeline`, `PunctuationRestorer`, `StreamingDictationSession`, and `FloatingBubbleService` to enforce strict Android ICU4C bounded-lookbehind compliance.
- **Fixed Floating Dictation Bubble Appearance, Surface Attachment & Dictation Responsiveness (`FloatingBubbleService`, `TextInjectionAccessibilityService`, `BubbleOverlayComponent`, `SmartPipeline`):**
  - Restored `ComposeView` `View.VISIBLE` surface attachment on `WindowManager` (`alpha = 0.0f` + `FLAG_NOT_TOUCHABLE` when hidden) instead of `View.GONE` / `View.INVISIBLE`, preventing unallocated surfaces, missing root `WindowInsets`, and untouchable overlay states on Android 11–15 / Samsung One UI.
  - Moved `TextInjectionAccessibilityService` keyboard inspection onto `Dispatchers.Main` with staggered post-event animation checks (`80 ms` – `600 ms`) and restored unconditional Main-Looper `refreshNow()` checks while the display is interactive so `AccessibilityService.getWindows()` reliably detects `TYPE_INPUT_METHOD` soft keyboards on Samsung One UI and all OEM ROMs—while keeping **true `0%` CPU suspension (`isScreenInteractive.first { it }`)** whenever the screen is off.
  - Decoupled `SmartPipeline.onRecordingStartedAsync()` into separate lock phases so `Voz` speech loading releases `pipelineMutex` immediately before opportunistic co-resident LLM loading / prompt pre-warming, and added immediate `"Loading Voz..."` / `"Transcribing..."` pill progress feedback when stopping a dictation from `0 MB` Standby.

## [2.11.0] - 2026-10-27

### Added & Improved
- **Configurable Auto-Unload of All Models + Lazy On-Tap Loading (`ModelSettings`, `SmartPipeline`, `StreamingDictationSession`, `FloatingBubbleService`, `MainActivity`, `SpeechRecognitionSettingsSection`):**
  - Added a configurable **Auto-Unload All Models When Idle (Battery Saver)** setting (`Immediately (0m)`, `1m`, `2m`, `5m` [default], `10m`, `15m`, `30m`, `Never`), plus an instant **Unload Now** action in Settings.
  - **Zero-RAM Cold Start & Lazy On-Tap Loading:** App launch and background service startup no longer eagerly load `Voz`, `Ear`, or `Punct` into RAM (`0 MB` Standby by default). Models are loaded lazily on demand as soon as the floating dictation bubble is tapped (`onRecordingStartedAsync()`), overlapping model initialization and LLM KV-cache pre-warming with live speech capture so medium and long dictations finish seamlessly.
  - **Full Pipeline Idle Eviction:** When the configured idle timeout expires (or immediately after dictation when set to `Immediately`), OpenFlow unloads **every** model (`Voz`, `Ear`, `Punct`, AND the active `LiteRT-LM` LLM + standby KV cache) back to `0 MB` in RAM.
- **Background Service Battery Optimization (`FloatingBubbleService`, `TextInjectionAccessibilityService`, `BubbleOverlayComponent`):**
  - **True 0% CPU While Screen Is Off:** Eliminated continuous background polling loops while the screen is off in both `FloatingBubbleService` and `TextInjectionAccessibilityService`—both coroutines now suspend completely on `isScreenInteractive.first { it }` (`0` CPU wakeups until `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`).
  - **Event-Driven Keyboard Tracking & Low-Power Safety Cadence:** Replaced `180–200 ms` polling with instant `AccessibilityEvent`-driven keyboard detection (with keystroke selection-event throttling and debounced slide-in verification) plus a low-power safety backoff (`1.2s` active / `3.5s–4.0s` idle, `15s` watchdog).
  - **Zero Idle Overlay Redraws:** Hidden overlay states now switch `ComposeView` to `View.GONE` on Android 11+ (`View.INVISIBLE` on Android 8–10) to suspend Compose layout/draw passes, and `audioController.rmsLevel` is collected strictly during `OverlayBubbleState.RECORDING`.
- **Concise GB-Only LLM Cards with Zero Speed or RAM Claims (`ModelSettings`, `SpeechRecognitionSettingsSection`):**
  - Simplified all 5 local LLM selector cards to display exact download sizes in **GB** (`0.32 GB`, `0.57 GB`, `0.91 GB`, `1.49 GB`, `2.41 GB`) alongside ultra-short 3–4 word descriptions and download/load state, removing duplicate MB download badges and removing all speed (`tok/s`) and device RAM (`4 GB+ RAM` / `~MB RAM`) claims.
- **Major `Gemma 4 E2B` System Prompt & Structural Formatting Upgrade (`ModelSettings`, `GemmaTextProcessor`):**
  - Replaced `Gemma 4 E2B`'s unstructured wall-of-text system prompt with crisp, numbered, dedicated `FLAGSHIP_GEMMA_4` instructions across all 6 presets (`CLEAN_AND_POLISH`, `STRICT_CLEANUP`, `CONCISE_DIRECT`, `PROFESSIONAL_MESSAGE`, `STRUCTURED_NOTES`, `CUSTOM`), paired with multi-paragraph/numbered-list/spoken-email/German few-shot grounding and a `<35 words` telegraphic reasoning rule (`thinkingTokenBudgetCap = 256`).
  - Enforced vertical newline formatting on sequential numbered lists (`expandInlineSequentialLists`, spoken `"colon"` normalization before list items, expanded `"number one ... number two ..."` sequence detection, and paragraph-preserving `splitLongTranscript`) so multi-item lists never collapse onto a single paragraph line.

## [2.10.0] - 2026-10-27

### Added & Improved
- **Smart Screen Keep-Awake While Bubble Is Visible, Transcribing & Running LLM (`FloatingBubbleService`, `SmartPipeline`):**
  - Keeps the display bright and prevents screen dimming/timeout whenever the floating dictation bubble is visible on screen (`IDLE`), while recording (`RECORDING`), and throughout speech transcription (`Ear` / `Voz` / `Punct`) and on-device LLM loading & post-processing (`PROCESSING` / `PipelinePhase.TRANSCRIBING` / `isActivelyProcessing`).
  - Removed the premature `setKeepScreenOn(false)` call in `onStopRecording` that previously allowed the display to sleep mid-transcription/LLM pass.
  - Combines `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` + `ComposeView.keepScreenOn` with a bounded, auto-renewed `PowerManager` display wake lock (`SCREEN_BRIGHT_WAKE_LOCK or ON_AFTER_RELEASE`, without `ACQUIRE_CAUSES_WAKEUP`) so OEM Android skins that ignore `FLAG_KEEP_SCREEN_ON` on `FLAG_NOT_FOCUSABLE` overlays also keep the display on, while immediately releasing in `0 ms` when the bubble hides, on manual hardware Power-button screen off (`ACTION_SCREEN_OFF`), or in `onDestroy()`.
- **Collapsible On-Device LLM Speed Benchmark — Uncached vs Cached System Prompt (`SpeechRecognitionSettingsSection`, `GemmaTextProcessor`, `SmartPipeline`, `ModelSettings`):**
  - Added a collapsible (**collapsed by default**, expandable on tap) **Speed Benchmark** section at the bottom of *AI Post-Processing & Cleanup* whenever the selected LLM is downloaded on disk.
  - Includes a sample selector dropdown with **5 realistic everyday dictation examples**, **2 complex multi-clause/self-correction/spec stress-test examples**, and a **Custom Text Input** option.
  - Benchmarks the selected model with your active system prompt preset & thinking configuration across **Prompt Uncached** (cold prefill) vs **Prompt Cached** (warm `prefillPrefaceOnInit` KV cache) in **tokens/sec** (end-to-end effective `tok/s` + decode `tok/s` + prefill `tok/s`), **TTFT (`ms`)**, **total latency (`ms`)**, and **KV-cache speedup (`×`)**, and displays the actual cleaned **Model Output** below.

## [2.9.0] - 2026-10-27

### Added & Improved
- **6-Pillar On-Device LLM Speed & Efficiency Engine (`GemmaTextProcessor`, `SmartPipeline`, `StreamingDictationSession`, `ModelSettings`, `SpeechRecognitionSettingsSection`):**
  1. **Zero-LLM Fast Path (`< 1 ms` Clean Utterance Bypass):** Evaluates `canBypassLlmFastPath` *before* unloading `Voz` or loading an unloaded LLM into RAM—completing clean, punctuated concise utterances (`1–18` words, zero fillers/stutters/backtracks/spoken numbers/acronyms/list cues) deterministically in `< 1 ms` with `0 MB` LLM RAM and `0` battery cost, while applying custom vocabulary casing and mid-sentence cursor continuation.
  2. **Adaptive Smart Auto-Thinking (`gemmaAdaptiveThinking = true` by Default) + Tight Reasoning Caps:** Keeps `Disable Model Thinking` OFF by default while adding **Adaptive Smart Thinking** (ON by default): automatically skips `<think>` / `<|channel>thought` reasoning tokens (`0` thought tokens + `promptCache = HIT`) on ~85%+ of everyday dictations (including fillers and spoken numbers) for `2×–4×` faster cleanup, and dynamically enables reasoning *only* on mid-sentence backtracks, spoken list enumerations, structural presets (`STRUCTURED_NOTES`, `CONCISE_DIRECT`, `CUSTOM`), or dense multi-clause blocks—enforcing tight GPU/CPU `thinkingTokenBudgetCap` (`48` / `56` / `88` / `144` tokens) and live streaming NPU thought cutoffs.
  3. **Live Streaming Chunk-by-Chunk LLM Overlap While You Speak (`StreamingDictationSession`):** When `Voz` + LLM co-residency RAM is available and the audio chunk queue is momentarily empty while you speak the next chunk, cleans finalized earlier chunks in the background once the lookahead chunk confirms a closed sentence boundary (`canCommitStreamingPrefixForOverlap`) without a leading backtrack or conjunction—leaving only the final tail chunk after tapping Stop.
  4. **Compact Edit-Patch (`SPAN_PATCH`) Mode (`lhs => rhs` / `NONE`) on Long Prose:** On `>= 45`-word (`>= 250`-char) single-paragraph dictations for `Qwen 2.5 1.5B`, `Qwen 3 1.7B`, and `Gemma 4 E2B`, prompts for `NONE` (1 token!) or localized `exact source phrase => edited phrase` replacements (`3×–8×` fewer output tokens), validates every anchor deterministically via `applyVerifiedSpanEdits`, and falls back seamlessly to full rewrite if any anchor is ambiguous.
  5. **`LiteRT-LM` Engine, Right-Sized KV Windows, Pure Greedy Decoding & Streaming Early-Abort:** Right-sizes `maxNumTokens` per model (`1,536` preferred for `0.6B` & `1.5B Direct`, `2,048` preferred for `1.7B` & `Gemma 4 E2B`, cascading up to `4,096` for static NPU graphs), enables `ExperimentalFlags.filterChannelContentFromKvCache = true`, switches non-NPU built-in presets to pure greedy `SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)` + `NoRepeatNgramConfig(6, 64)`, and executes turns via streaming `sendMessageAsync` with instant `cancelProcess()` early-abort on runaway length or 3× tail repetition (`hasRunawayTailRepetition`).
  6. **Input-Grounded Sentence Sieve + Drop-In `LoraConfig` Support:** Partitions multi-sentence transcripts (`>= 3` sentences) via `partitionSentenceSieve` so already-clean sentences are preserved 100% verbatim in `0 ms` (`0` tokens) while only dirty sentence spans are routed to the LLM (coupling short fragments and conjunction/repair-leading sentences with their preceding neighbor), and auto-detects optional `<modelId>.lora` / `adapter.lora` adapters in the model directory (`LoraConfig` + ~30-token `buildCompactLoraSystemInstruction` for ~90% faster prefill).

## [2.8.0] - 2026-10-27

### Added & Improved
- **5 Selectable Local AI Post-Processing LLMs — Mainly Smaller & Faster (`LlmModelChoice`, `DesertAntModel`, `ModelDownloadManager`, `GemmaTextProcessor`):**
  - Expanded on-device AI cleanup & reformatting beyond the 2.5 GB `Gemma 4 E2B` with 4 smaller, faster, un-gated, multilingual (all 25 European languages) official Google AI Edge `LiteRT-LM` models:
    - **`Qwen 3 0.6B Lite` (`329 MB`, INT4 dynamic `wi4b32`):** Tiny download & ultra-fast cleanup (`+350 MB` incremental RAM, `4 GB+` phones).
    - **`Qwen 3 0.6B` (`585 MB`, Recommended Default):** Ideal speed-to-accuracy sweet spot across European languages (`+450 MB` incremental RAM, `6 GB+` phones; existing `Gemma 4 E2B` users automatically retain their selection on upgrade).
    - **`Qwen 3 1.7B` (`932 MB`, INT4 dynamic `wi4b32`):** Balanced accuracy & fast cleanup on mid-sentence backtracks, numbers, and lists (`+650 MB` incremental RAM, `6 GB+` phones).
    - **`Qwen 2.5 1.5B Instruct` (`1,524 MB`, INT8 multi-prefill):** Direct non-thinking instruct model with zero reasoning overhead (`+750 MB` incremental RAM, `6 GB+` phones).
    - **`Gemma 4 E2B` (`2,468 MB`, Experimental Flagship):** Maximum accuracy with Qualcomm QNN / MediaTek NeuroPilot / Google Tensor NPU + GPU/CPU MTP support (`+850 MB` incremental RAM, `8 GB+` phones).
- **Zero Unnecessary Multi-Model RAM Loading (`SmartPipeline`, `ModelSettings`, `ModelDownloadManager`):**
  - **Strict Single-LLM Residency:** At most 1 LLM can ever be enabled or loaded into RAM at any instant; switching models or disabling AI post-processing immediately closes and frees the previous `Engine` + KV cache before loading anything else.
  - **Zero `Punct` + LLM Overlap:** Automatically unloads and bypasses `Punct` (~120 MB) whenever the selected LLM is enabled & downloaded (retaining `Punct` purely as an automatic fallback if an LLM fails to load).
  - **Zero-RAM Cold Start & 5-Minute Burst Eviction:** Keeps LLMs at **`0 MB` RAM** during app launch and idle Settings browsing, overlaps loading + system-prompt KV-cache pre-warming inside `onRecordingStartedAsync()` *while you speak* when `canKeepVozAndLlmCoResident(...)` allows, and automatically evicts idle LLMs after `5 minutes` while keeping `Voz` warm for `20 minutes`.
- **Per-Model Prompt, Token-Budget & Sampler Optimization (`GemmaPromptPreset.promptForModel`, `GemmaTextProcessor`):**
  - Tailored system instructions (especially the default **`Wispr Flow Auto-Edit`** preset), role framing, few-shot example density, reasoning-token caps, `SamplerConfig`, and `ModelInfo` capability detection across `COMPACT_0_6B`, `DIRECT_INSTRUCT_1_5B`, `BALANCED_1_7B`, and `FLAGSHIP_GEMMA_4` tiers, plus Qwen `<think>...</think>` / `<|im_end|>` output sanitization.
- **Intuitive AI Post-Processing Selector & Clean Storage UI (`SpeechRecognitionSettingsSection`, `OnboardingScreen`):**
  - Added a clean 5-model selector showing speed, download size, RAM impact, live status badges (**Loaded in RAM** / **Ready on Disk** / **Downloading**), model-aware **`Disable Model Thinking`** state, per-model optimized prompt preview, and streamlined status/storage lists.

## [2.7.2] - 2026-10-26

### Added & Improved
- **System-Prompt KV-Cache Pre-Warming for `Gemma 4 E2B` (`GemmaTextProcessor`, `SmartPipeline`, `FloatingBubbleService`):**
  - Pre-computes and caches a single-slot standby `Conversation` (`ConversationConfig.prefillPrefaceOnInit = true`) containing the ~500-token system prompt + few-shot examples while you speak (`onRecordingStartedAsync()` using non-blocking `pipelineMutex.tryLock()`), during engine warm-up, on settings changes, and asynchronously post-dictation when RAM headroom permits.
  - Decouples dynamic spoken language detection from the cached system prompt by passing resolved `Ear` language metadata via `<transcript lang="..." language="...">` on the user message, achieving near-100% KV-cache hits even on multilingual dictations while strictly closing any previous standby `Conversation` before allocating a new one.
- **Comprehensive End-to-End RAM Awareness & Anti-OOM Hardening (`SmartPipeline`, `GemmaTextProcessor`, `PunctuationRestorer`, `AudioRecordingController`, `StreamingDictationSession`, `FloatingBubbleService`, `OpenFlowApp`):**
  - **Co-Residency OOM Elimination (`canKeepVozAndGemmaCoResident`):** Prevents Android LMKD kills on 4–8 GB devices by safely unloading `Voz` (~1.3 GB) before dynamically loading `Gemma 4 E2B` (~1.9 GB)—and unloading `Gemma` before reloading `Voz`—whenever free physical RAM cannot safely hold both models simultaneously, while keeping both warm and co-resident on high-RAM flagships.
  - **Memory-Tiered Gemma KV Context Window (`GemmaMemoryMode`):** Automatically scales Gemma's KV-cache context window across `HIGH_HEADROOM` (`4,096` tokens), `MODERATE_HEADROOM` (`2,048` tokens — halving KV-cache RAM), and `LOW_MEMORY` (`1,536` tokens, MTP disabled, idle KV-cache disabled), and guarantees child `Conversation` teardown before parent native `Engine.close()`.
  - **Zero-Boxing `CompactTokenIdTable` in `Punct` (`PunctuationRestorer`):** Replaced `HashMap<String, Int>(335_000)` (~500,000 boxed `Integer` + `HashMap$Node` objects) with an open-addressed `Array<String?>` + primitive `IntArray` table and zero-allocation `getRange()` Viterbi substring lookups, saving ~12–16 MB of permanent JVM heap.
  - **Lazy Audio Chunk Slicing & In-Place Conditioning (`SmartPipeline`):** Replaced upfront full-recording chunk copies in `transcribe()` with lazy per-iteration `computeSmartChunkRanges` slicing, early buffer release before post-processing, and in-place speech conditioning (`conditionAudioWhenClearDisabled(..., inPlace = true)`) on freshly padded chunks.
  - **Live Recording & Streaming Backpressure (`AudioRecordingController`, `StreamingDictationSession`):** Added JVM free-heap headroom checks before growing `pcmBuffer`, automatic buffer compaction back to `15 s` once drained, live-poller backpressure (`MAX_IN_FLIGHT_LIVE_CHUNKS = 4`), and bounded failed-chunk retry retention (`MAX_FAILED_RETRY_SAMPLES = 45s`).
  - **Multi-Tier OS `onTrimMemory` Eviction (`OpenFlowApp`, `FloatingBubbleService`, `SmartPipeline`):** Immediately evicts Gemma's standby system-prompt KV cache (~150–250 MB) on `TRIM_MEMORY_RUNNING_MODERATE` / `TRIM_MEMORY_UI_HIDDEN`, evicts optional models on `TRIM_MEMORY_RUNNING_LOW` / `TRIM_MEMORY_BACKGROUND`, and unloads idle `Voz` under `TRIM_MEMORY_RUNNING_CRITICAL` when physical RAM is critically low.

## [2.7.1] - 2026-10-26

### Added & Improved
- **Optional `"Disable Model Thinking"` Toggle for Experimental `Gemma 4 E2B` (`ModelSettings`, `SpeechRecognitionSettingsSection`, `GemmaTextProcessor`, `SmartPipeline`):**
  - Added a **`Disable Model Thinking`** switch (**disabled / OFF by default**, `gemmaDisableThinking = false`) inside the Experimental **Gemma 4 E2B — AI Post-Processing** settings card.
  - **Default (`Disable Model Thinking` OFF):** Enables Gemma 4 E2B's native reasoning channel (`ThinkingConfig(enableThinking = true)` + `extraContext = mapOf("enable_thinking" to true)`) with NPU-compatible unconstrained decoding, an expanded `4,096`-token KV context window (with automatic per-backend `2,048`-token fallback), reasoning-aware output token headroom, and automatic single-turn non-thinking retry if a long reasoning trace exhausts the token cap—delivering maximum accuracy on complex backtracks, numbers, and structural edits.
  - **Fast Mode (`Disable Model Thinking` ON):** Skips Gemma 4's reasoning channel (`ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)` + `extraContext = mapOf("enable_thinking" to false)`) so tokens stream directly into the cleaned transcript, significantly speeding up post-processing at the potential cost of reduced accuracy on complex edits.

## [2.7.0] - 2026-10-26

### Improved
- **Wispr Flow–Style On-Device Formatting & Auto-Editing Engine (`GemmaTextProcessor`, `GemmaPromptPreset`):**
  - Re-architected the `Gemma 4 E2B` formatting engine exclusively (keeping audio recording, chunking, `Voz`/`Ear`/`Punct`, floating bubble UI, and accessibility injection untouched) to match Wispr Flow's end-to-end auto-editing pipeline:
    - **Mid-Sentence Course-Correction & Backtrack Resolution:** Recognizes verbal repair cues (`"actually"`, `"wait"`, `"no wait"`, `"sorry"`, `"I mean"`, `"scratch that"`, `"make that"`, `"oder nein"`, `"nein warte"`, `"ich meine"`, `"besser gesagt"`) and discards both the retracted phrase and the repair cue while preserving genuine logical contrasts (`"not X, but Y"`).
    - **Context-Aware Disfluency Stripping:** Removes vocal hesitations (`"um"`, `"uh"`, `"er"`, `"ah"`, `"hmm"`, `"äh"`, `"ähm"`), stutters (`"the the"`, `"I I"`), false starts, and discourse fillers (`"like"`, `"you know"`, `"sort of"`) strictly when used as hesitation—never deleting literal verbs or comparisons (*"I really like this"*).
    - **Automatic Implicit & Explicit List Formatting:** Detects spoken enumerations (`"first... second... third..."`, `"one... two... three..."`, `"erstens... zweitens..."`, `"bullet point..."`) and formats them into clean vertical numbered (`1. `, `2. `) or bulleted (`- `) lists with a colon (`:`) on lead-in clauses while keeping casual inline triplets inline.
    - **App-Context Style Adaptation (`AppContextCategory`):** Reads the target focused app package (`Personal Messaging`, `Work Messaging`, `Email`, `Code & Dev`, `Notes & Docs`) to adapt layout automatically—relaxing chat messages in WhatsApp/Signal/Telegram, keeping Slack/Teams crisp with `@mentions`/`#channels`, formatting greeting/sign-off line breaks in Gmail/Outlook/ProtonMail, and preserving `camelCase`/`snake_case`/CLI syntax in Termux/GitHub.
    - **Privacy-Safe Cursor Continuation (`FlowFieldContext`, `applyCursorContinuationCasing`):** Inspects up to 140 characters before the cursor in non-password, non-hint input fields so mid-sentence continuations automatically lowercase ordinary leading words (while preserving `"I"`/`"I'm"`, acronyms like `"NPU"`, Custom Vocabulary terms, English days/months, and capitalized German nouns) and numbered/bulleted lists continue seamlessly from the preceding item (`#N` → `#(N+1)`).
    - **Hybrid Deterministic Pre-Pass (`preNormalizeSpokenConstructs`):** Deterministically normalizes unambiguous spoken email addresses (`"john dot doe at gmail dot com"` → `john.doe@gmail.com`, `"max punkt mustermann at firma punkt de"` → `max.mustermann@firma.de`) and article-guarded layout/dash commands (`"new paragraph"`, `"new line"`, `"em dash"`) without false positives on literal prose (`"wrote a new paragraph"`, `"new line of products"`, `"look at github dot com"`).
    - **Few-Shot Grounding, Multi-Segment Continuity & Anti-Refusal Guard:** Added compact language-matched few-shot examples, cross-segment continuity tails with echo stripping + list-aware segment joining for long dictations, and an unprompted AI assistant-refusal fallback guard in `sanitizeGemmaOutput`.

## [2.6.9] - 2026-10-26

### Improved
- **Smarter Default `Gemma 4 E2B` System Prompt — Numbers, Units & Structural Formatting (`GemmaPromptPreset`, `GemmaTextProcessor`):**
  - Upgraded the default **`Clean & Polish`** system prompt (and number-handling rules across all built-in presets) with explicit **Inverse Text Normalization (ITN)** and formatting directives:
    - **Numbers, Dates & Units:** Converts spoken numbers into natural written digits matching the transcript language's conventions (e.g., `'3.5'` / `'3,5'`, percentages `'25%'`, currencies `'$50'` / `'49,99 €'`, dates, clock times `'14:30'` / `'3:30 PM'`, version numbers `'2.6.9'`, ranges `'10–15'`, and technical/measurement units `'8 GB'`, `'500 ms'`, `'16 kHz'`, `'24 °C'`), while keeping small idiomatic number words spelled out (`'one of the'`, `'a couple of'`, `'ein paar'`).
    - **Acronyms & Symbols:** Properly capitalizes standard technical acronyms (`NPU`, `GPU`, `CPU`, `RAM`, `LLM`, `API`, `USB`, `OK`) and formats clearly dictated email addresses, URLs, and symbols.
    - **Spoken Formatting Cues & Paragraphs:** Repairs false mid-sentence pause periods, converts explicit spoken formatting cues (`'new line'` / `'neue Zeile'`, `'new paragraph'` / `'neuer Absatz'`) into real line/paragraph breaks, groups distinct topics into clean paragraphs, and refines multi-segment joining for long dictations (`\n` for bullet lists, `\n\n` for paragraph-structured passes).

## [2.6.8] - 2026-10-26

### Added
- **Experimental `Gemma 4 E2B` On-Device NPU AI Post-Processing (`GemmaTextProcessor`, `DesertAntModel.GEMMA_4_E2B`, `GemmaPromptPreset`, `ModelSettings`, `ModelDownloadManager`, `SmartPipeline`, `StreamingDictationSession`, `FloatingBubbleService`, `SpeechRecognitionSettingsSection`, `OnboardingScreen`):**
  - Integrated Google's real **`gemma-4-E2B-it`** (`litert-community/gemma-4-E2B-it-litert-lm`, `2,468 MB`, SHA-256 verified, **Experimental & OFF by default**) via **Google AI Edge `LiteRT-LM` (`litertlm-android:0.18.1`)** for 100% offline spoken filler removal (`"um"`, `"uh"`, `"ähm"`, `"like"`, stutters, spoken self-corrections), smart reformatting, and custom prompt transformations.
  - **NPU-First Hardware Cascade:** Bundles Qualcomm QNN (`litert-npu-runtime-qualcomm:2.3.0`), MediaTek NeuroPilot (`litert-npu-runtime-mediatek:2.3.0`), and Google Tensor (`litert-npu-runtime-google-tensor:2.3.0`) LiteRT compiler/dispatch runtimes and prioritizes `Backend.NPU(nativeLibraryDir)` / `Backend.GOOGLE_TENSOR()`, with automatic zero-crash fallback to `Backend.GPU()` + Multi-Token Prediction (`enableSpeculativeDecoding`) and `Backend.CPU(threads)` + MTP.
  - **Selectable System Prompt Presets + Custom System Prompt:** Added 5 curated system prompt presets (**`Clean & Polish`**, **`Strict Filler & Grammar Fix`**, **`Concise & Direct`**, **`Professional Message / Email`**, **`Bullet Points & Action Items`**) plus a **`Custom System Prompt`** mode with a multi-line prompt editor and one-tap **`Copy & Edit as Custom Prompt`** action in Settings.
  - **Zero-Latency Non-Thinking Mode & Anti-Hallucination Guardrails:** Explicitly disables Gemma 4's thinking channel (`ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)` + `extraContext = mapOf("enable_thinking" to false)`), wraps input transcripts in `<transcript>` delimiters, biases custom vocabulary spellings, locks spoken language (unless a Custom System Prompt requests translation), runs post-processing once over the complete stitched transcript in live-streaming sessions, preserves multi-line paragraphs/bullet lists during text injection, and dynamically stages model sessions on tighter-RAM devices to prevent OOMs.

## [2.6.7] - 2026-10-25

### Added
- **`Punct` — On-Device Neural Punctuation & Casing Restoration Model (`PunctuationRestorer`, `DesertAntModel.PUNCT`, `ModelSettings`, `ModelDownloadManager`, `OnboardingScreen`, `SpeechRecognitionSettingsSection`, `SmartPipeline`):**
  - Integrated the INT8 multilingual `XLMRobertaForTokenClassification` model (`onnx-community/punctuate-all-ONNX` / `kredor/punctuate-all`, `282 MB`, **disabled by default**) for post-stitching punctuation and casing restoration across European languages.
  - **0% Hallucination Guarantee:** Runs a single bidirectional encoder forward pass over the spoken words (`0` = none, `1` = `.`, `2` = `,`, `3` = `?`, `4` = `-`, `5` = `:`) to remove false mid-sentence periods across chunk boundaries, restore missing commas/question marks, and lowercase falsely capitalized continuation words while preserving German noun capitalization, `"I"`, acronyms, and custom vocabulary terms—without ever adding, deleting, or altering any spoken word.
  - **Streaming Unigram SentencePiece Tokenizer (`UnigramSentencePieceTokenizer`):** Parses `tokenizer.json` (`250,002` pieces) via a streaming UTF-8 reader and performs Viterbi maximum-likelihood subword segmentation with `▁` prefixing.
- **Full-Pipeline NPU Acceleration (`NpuHardwareManager`, `PunctuationRestorer`):**
  - Configured ONNX Runtime Android (`onnxruntime-android:1.20.0`) with **Qualcomm QNN HTP (`addQnn`)** and **Android NNAPI (`addNnapi(NNAPIFlags.USE_FP16)`)** execution providers plus **XNNPACK** fallback so `Punct` executes directly on the device NPU alongside `Voz` and `Ear`.

## [2.6.6] - 2026-10-22

### Removed
- **Removed Experimental `Voz` Quantization (`VozTfliteQuantizer`, `ModelSettings`, `SmartPipeline`, `OnboardingScreen`, `SpeechRecognitionSettingsSection`):**
  - Removed the `Voz` quantization selector (`FP16` / `INT8 Hybrid` / `INT8`) and `VozTfliteQuantizer`, keeping the native original `Voz` FP16 model weights for maximum transcription accuracy and native `DAL1` manifest integrity.

## [2.6.5] - 2026-10-22

### Added
- **Selectable `Voz` Model Quantization (`FP16`, `INT8 Hybrid`, `INT8`) (`VozTfliteQuantizer`, `ModelSettings`, `SmartPipeline`, `OnboardingScreen`, `SpeechRecognitionSettingsSection`):**
  - Added selectable weight quantization for the `Voz` Conformer-Transducer model in both Onboarding and Settings:
    - **`FP16 (Original — Best Accuracy)`:** `~1,187 MB` active disk • `~1,400 MB` peak RAM.
    - **`INT8 Hybrid (Recommended Low-RAM)`:** `~605 MB` active disk • `~800 MB` peak RAM (quantizes the 1.17 GB Conformer `encoder.tflite` to symmetric INT8 while keeping `decoder.tflite` and `joiner.tflite` in FP16).
    - **`INT8 (Smallest Footprint)`:** `~598 MB` active disk • `~760 MB` peak RAM (quantizes both `encoder.tflite` and `decoder.tflite` to INT8).
  - Implemented a streaming (< 2 MB heap) on-device TFLite FlatBuffer quantizer/dequantizer (`VozTfliteQuantizer`) that converts `FLOAT16` weight buffers to symmetric `INT8` (updating tensor `QuantizationParameters` scales in the FlatBuffer header) and restores exact `FP16` bit-for-bit offline without re-downloading.

### Removed
- **Removed `Clear` and `Uhm` Models (`app/build.gradle.kts`, `SmartPipeline`, `ModelSettings`, `ModelDownloadManager`, `OnboardingScreen`, `SpeechRecognitionSettingsSection`):**
  - Completely removed the `Clear` neural denoiser and `Uhm` acoustic filler classifier dependencies, JNI libraries (`libClearAndroid.so`), and UI toggles, streamlining the pipeline to `Voz` (required speech recognition) and `Ear` (optional language detection) with lightweight zero-RAM speech conditioning (`conditionAudioWhenClearDisabled`) and lexical hesitation stripping (`reconcileTranscript`).
  - Automatically cleans up any leftover `clear` or `uhm` model directories on disk to reclaim up to 70 MB of device storage.

### Fixed
- **Floating Bubble Visibility When On-Screen Keyboard Is Open (`TextInjectionAccessibilityService`, `FloatingBubbleService`):**
  - Fixed a critical bug where calling `window.recycle()` on cached `AccessibilityWindowInfo` instances returned by `AccessibilityService.getWindows()` invalidated Android's internal `AccessibilityCache` (`window.type = -1`, `bounds = (0,0,0,0)`), causing `TYPE_INPUT_METHOD` keyboard detection to fail permanently after the first check.
  - Removed the `window.root` (`imeRoot.isVisibleToUser`) requirement on `TYPE_INPUT_METHOD` windows (which returned `null` or `false` on Android 14/15 Gboard & Samsung Keyboard when `flagIncludeNotImportantViews` was active), added Android 11+ (`API 30+`) `WindowManager.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.ime())` fallback detection, and restored `TextInjectionAccessibilityService.refreshNow()` inside `FloatingBubbleService`'s 200 ms screen-on heartbeat so the bubble reliably appears whenever a keyboard is open.

## [2.6.4] - 2026-10-22

### Improved & Fixed
- **NPU & Native Runtime Stability (`NpuHardwareManager`, `SmartPipeline`):**
  - **Qualcomm Hexagon HTP Sustained Performance & CDSP Recovery (`NpuHardwareManager`):** Configured `QNN_HTP_PERF_MODE=sustained_high_performance` (replacing `"burst"`), `QNN_HTP_ENABLE_PDR=1` (Process Domain Restart recovery), `FASTRPC_PERF_KERNEL=1`, and persistent compiled-graph cache directories (`LITERT_CACHE_DIR`, `TFLITE_CACHE_DIR`, `OPENCL_CACHE_DIR`) so back-to-back live chunks do not trigger Hexagon HTP thermal/voltage throttling, CDSP FastRPC watchdog resets, or repeated graph recompilation.
  - **Pre-Compiled SoC Detection Regex (`NpuHardwareManager`):** Pre-compiled `QUALCOMM_SOC_REGEX` once as a constant (including Snapdragon 8 Elite `pakala`/`canoe`) instead of recompiling a `Regex` on every classification call.
  - **Idempotent Native Env Var Application & Rate-Limited FFI GC (`SmartPipeline`):** `applyHardwareAcceleratorSettingsLocked` now skips redundant `Os.setenv` calls when `(accelerator, lowMemoryPressure)` has not changed, and `ensureMemoryHeadroomForFfiLocked` rate-limits non-critical `System.gc()` + `System.runFinalization()` calls across sequential `Clear` windows and live chunks to eliminate stop-the-world GC pauses during dictation.
  - **Native Session Fault Recovery & Coroutine Cancellation Safety (`SmartPipeline`):** Re-throws `CancellationException` in `runClearStageLocked`, `resolveLanguageLocked`, and warm-up stages so cancelled coroutines never trigger unnecessary native session teardowns, and resets `Uhm` (`closeUhmLocked()`) if `uhmInstance.detect` throws a native error so subsequent chunks recover cleanly.
  - **Zero-Boxed-Allocation Speech Conditioning (`conditionAudioWhenClearDisabled`) & Cached Model Directory Checks (`DesertAntModelStorage`):** Replaced boxed `List<Float>` allocations in `conditionAudioWhenClearDisabled` with primitive `FloatArray` operations and added an in-memory directory timestamp cache in `isModelDirectoryComplete` to avoid redundant per-chunk disk `stat()` syscalls.

## [2.6.3] - 2026-10-22

### Fixed & Improved
- **Eliminated End-of-Dictation & End-of-Chunk Word Cutoffs (`AudioRecordingController`, `AudioChunking`, `SmartPipeline`, `StreamingDictationSession`):**
  - **Post-Stop Hardware Tail Capture (`AudioRecordingController`):** Added a `180 ms` post-stop tail grace window (`POST_STOP_TAIL_GRACE_MS = 180L`), finer `40 ms` (`640`-sample) capture reads, and a non-blocking `AudioRecord.READ_NON_BLOCKING` hardware buffer drain before stopping `AudioRecord` so the final syllable or consonant still in flight when tapping Stop is never cut off.
  - **Stop-Closure & Plosive-Release Bridging (`AudioChunking.buildSilenceMask`):** Words ending in stop consonants (`/p/`, `/t/`, `/k/`, `/b/`, `/d/`, `/g/`) have a `40–100 ms` (`2–5` frame) silent vocal-tract occlusion followed by a `20–40 ms` (`1–2` frame) plosive release burst or short unstressed syllable. Short bursts within `100 ms` (`5` frames) of a confirmed speech run are now bridged across the stop closure into the word rather than suppressed as clicks, and trailing speech hangover is extended to `100 ms` (`5` frames at `0.45x` threshold).
  - **Conformer-Transducer Lookahead Runway (`SmartPipeline.prepareChunkForInference`):** Expanded leading silence runway to `320 ms` (`5,120` samples = 4 Conformer frames), trailing silence runway to **`640 ms` (`10,240` samples = 8 Conformer frames)**, and minimum chunk length to `1.60 s` (`25,600` samples = 20 Conformer frames) so `Voz`'s Token-and-Duration Transducer (TDT / RNN-T) always has full right-context lookahead to flush the final word of every chunk and short tail.
  - **Sensitive Final Tail Retention (`AudioChunking.hasAnyTailSpeech`, `StreamingDictationSession.finish`):** Preserves any post-pause tail containing even a single soft `60 ms` (`3`-frame) spoken word (`"it"`, `"on"`, `"ok"`) while still skipping pure dead air and isolated button-tap clicks.
- **Default Configuration Optimization When `Clear` & `Uhm` Are Disabled (`SmartPipeline`):**
  - **Lightweight Speech Conditioner (`conditionAudioWhenClearDisabled`):** Whenever the `Clear` neural enhancer is disabled (the default configuration), applies fast (< 0.4 ms), zero-RAM DC-offset removal and 400 ms windowed speech-envelope leveling (boosting quiet sentence-final words up to `3.0x` with soft-knee limiting at `0.85f` while keeping silence at unity gain) so prosodic volume drop-off at the end of a sentence or chunk never causes words to fade out.
  - **Direct Detokenized Text Preservation (`reconcileTranscript`):** When `Uhm` filler removal is disabled, preserves `Voz`'s full detokenized `transcription.text` directly (while still stripping unambiguous hesitation noises `"uh"`, `"uhm"`, `"äh"`, `"ähm"`, `"hm"`, `"hmm"`) so boundary words omitted from `transcription.words` timestamp alignment are never lost.
- **Settings UI (`DictationChunkingSettingsSection`, `MainActivity`):**
  - Hid the `"Detect language once per dictation (faster)"` checkbox whenever `Ear` (spoken language identification) is deactivated.

## [2.6.2] - 2026-10-22

### Improved
- **Seamless Cross-Chunk Transcript Stitching (`stitchChunkTranscripts` in `SmartPipeline`, `StreamingDictationSession`):**
  - Reconciles synthetic periods (`.`) and mid-sentence capitalization that `Voz` inserts when a single spoken sentence is split across multiple live or offline chunks.
  - Repairs dangling non-terminal chunk endings (articles, prepositions, conjunctions, and degree adverbs such as `"the."`, `"to."`, `"that."`, `"and."`, `"der."`, `"die."`, `"weil."`) and reconnects coordinating, subordinating, and relative clause continuations (`and`, `or`, `because`, `which`, `but`, `that`, `und`, `weil`, `dass`, `aber`, etc.) while preserving German noun capitalization and genuine multi-sentence boundaries.
  - Deduplicates 1- and 2-word stutters/overlaps across chunk boundaries.
- **Hesitation & Filler Cleanup (`reconcileTranscript`, `normalizeTranscriptText`):**
  - Automatically removes unambiguous vocal hesitation tokens (`"uh"`, `"uhm"`, `"erm"`, `"äh"`, `"ähm"`, `"hm"`, `"hmm"`) and cleans up filler-induced commas on preceding function words (`"so that, uh, the"` -> `"so that the"`), even when the optional `Uhm` model is disabled.
- **Acoustic Boundary Protection (`AudioChunking`, `AudioRecordingController`, `SmartPipeline`):**
  - Added dual-threshold hysteresis speech hangover in `buildSilenceMask` so soft word-initial consonants and word-final fricative/nasal tails (`s`, `t`, `f`, `k`, `n`, `m`) are never classified as silence or clipped by a split.
  - Added both-sided silence cushioning on live cuts (`240 ms` trailing silence on the outgoing chunk and `200 ms` leading silence retained inside the pause for the next chunk without duplicating any speech samples), plus `prepareChunkForInference` (`200 ms` silence runway and `10 ms` linear edge tapers) to eliminate STFT window boundary clicks.
  - Skips post-pause silent or button-tap-only tail buffers in `StreamingDictationSession.finish()` (`hasUsableSpeech`), eliminating stop-tap hallucinations and post-stop delay.
- **Tuned Default Chunking Settings (`DictationChunkSettings`):**
  - Updated default pause to **650 ms** (with fine 50 ms slider steps) and minimum live chunk length to **5 s** so brief mid-clause hesitations stay inside a single phrase window.

## [2.6.1] - 2026-10-22

### Added
- **Enable audio chunking option (`DictationChunkSettings`, Settings):** new master switch, **on by default**. When off, dictations are processed in one piece and live transcription is unavailable; audio is only split past a 60 s safety ceiling (or when memory is very low) so the native models are never fed multi-minute inputs.
- **Smart pause (adaptive pause length):** the longer a live chunk has been growing without a clear pause, the shorter a gap has to be to count as a cut point (down to half the configured pause at 75% of the maximum length). Natural gaps are now used before a hard forced cut mid-phrase. Can be turned off.

### Improved
- **Better chunking (`AudioChunking`):** clicks, taps and lip smacks (< 60 ms loud bursts surrounded by silence) no longer split a pause into two runs that are each too short to qualify; real short words still do.
- **Tuned defaults:** pause 600 ms -> **500 ms** (phrase ends are typically 400-800 ms, gaps between words almost always < 300 ms) and minimum live chunk 4 s -> **3 s** (enough context for accuracy, starts transcribing earlier). Users who never changed these two sliders are migrated automatically; customised values are kept.

## [2.6.0] - 2026-10-22

### Added
- **Transcribe while still recording (`StreamingDictationSession`, `AudioChunking`, `AudioRecordingController`):** a live pause detector watches the microphone buffer; as soon as a pause is detected the finished chunk is cut out of the recording buffer and sent to the pipeline right away. After you stop, only the final chunk is left to process, so long dictations feel almost instant. Chunks are transcribed strictly in order by a single worker, finished audio is removed from the live buffer (no extra RAM, and recordings are no longer capped by the 5-minute buffer), language identification runs once per dictation, and a chunk that fails is retried once after the tail instead of being lost.
- **Dictation Chunking settings (`DictationChunkingSettings`, Settings screen):** toggle live transcription, pause length (0.25-2 s), minimum live chunk length (2-15 s), maximum chunk length (8-30 s, also used by offline auto-chunking), silence level, and "detect language once per dictation". Takes effect on the next dictation, with a reset-to-defaults button.

### Improved
- **Better auto-chunking (`splitAudioIntoSmartChunks`):** cuts now use an adaptive noise-floor silence threshold, prefer the longest real pause (latest of the comparably long ones for bigger chunks) and cut at the centre of the pause; previously the first quietest 80 ms window was used, which on digital silence always cut at the start of the pause. Falls back to the quietest window only when no pause exists.
- Wake lock for a dictation raised from 3 to 10 minutes to match long live dictations.

## [2.5.9] - 2026-10-22

### Fixed
- **Audio capture busy-loop (`AudioRecordingController`):** `AudioRecord.read()` error codes (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`, ...) were ignored, so a lost microphone (e.g. taken over by another app) made the capture coroutine spin a CPU core at 100% until the user stopped dictation. The loop now logs the error and exits cleanly.
- **Cancelled dictation triggered full model teardown (`SmartPipeline`):** `CancellationException` was swallowed by the broad `catch (Throwable)` around `Voz.transcribe`, which caused the expensive "shed all native sessions, GC and reload Voz" recovery path on a plain cancel. Cancellation is now always rethrown (Voz inference, Voz warm-up, Uhm stage).
- **Orphan trailing audio chunk (`splitAudioIntoSmartChunks`):** Recordings slightly longer than the chunk limit could end with a sub-second tail (a few hundred samples) that was fed to `Clear`/`Voz` and produced empty or hallucinated text. Splits now always leave a tail of at least 0.5 s.
- **Dictated text left in the clipboard (`TextInjectionAccessibilityService`):** When the previous clipboard could not be read (Android 10+ background restriction), the pasted dictation stayed in the clipboard afterwards. It is now cleared after a successful paste, and the temporary clip is flagged sensitive on Android 13+ so it is hidden from clipboard previews.

### Improved
- **Lower idle CPU / battery use:** the bubble heartbeat now runs every 200 ms only while the screen is on (1 s while off) and no longer re-enumerates accessibility windows on the main thread; the accessibility keyboard poller backs off to 1 s while the display is off and tracks the screen state itself.
- **Fewer allocations per dictation:** custom-vocabulary regexes are compiled once and cached, and post-processing regexes in `FloatingBubbleService` are hoisted into constants.

## [2.5.8] - 2026-10-20

### Changed
- **Hide Notification Icon from Top Status Bar (`FloatingBubbleService`):** Migrated the foreground service notification channel to `NotificationManager.IMPORTANCE_MIN` (`NotificationCompat.PRIORITY_MIN`, `setSilent(true)`) and deleted the legacy `IMPORTANCE_LOW` channel so the microphone notification icon no longer clutters the top status bar while keeping the service notification active in the notification shade.

## [2.5.7] - 2026-10-20

### Changed
- **Material Design 3 Microphone Icon (`ic_notification`, `ic_launcher`, `AndroidManifest.xml`):** Updated both the notification icon and the adaptive app launcher icon (including Android 13+ Material You monochrome themed icon support) to the Material Design 3 rounded microphone (`Icons.Rounded.Mic`).

## [2.5.6] - 2026-10-20

### Changed
- **Minimal Foreground Service Notification (`FloatingBubbleService`):** Simplified the ongoing status-bar notification to display only `"OpenFlow Service Active"` as the title with no subtitle/body text.

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
