# OpenFlow

Welcome to **OpenFlow**, an offline-capable, high-accuracy Speech-to-Text (STT) dictation app for Android with a system-wide floating bubble and automatic accessibility text injection.

## 🚀 Features

- **100% On-Device Audio AI Pipeline & Universal NPU Acceleration:** All audio inference runs locally on your smartphone via **LiteRT** with selectable **Hardware Acceleration (`Auto`, `NPU`, `GPU (OpenCL)`, or `CPU (XNNPACK)`)** and automatic SoC NPU configuration for **Qualcomm Hexagon NPU (QNN HTP / FastRPC CDSP V66–V79)**, **MediaTek NeuroPilot APU**, **Google Tensor TPU**, **Samsung Exynos ENN/EDEN**, **HiSilicon Kirin HiAI**, **Rockchip RKNN**, and **Android NNAPI**. Your voice recordings never leave your device.
- **Streamlined On-Device Audio & Text AI Ecosystem (`Voz` + `Ear` + optional `Punct` + 6 Selectable Local LLMs):**
  - **`Voz` (1,187 MB — Enabled by Default):** High-accuracy multilingual transcription across 25 European languages with automatic punctuation, capitalization, word-level timestamps (`80 ms` resolution), and custom vocabulary biasing.
  - **`Ear` (10 MB — Enabled by Default):** Spoken language identification (107 languages) that automatically routes dictation across supported European languages or falls back to your preferred language.
  - **`Punct` (282 MB — Optional, Off by Default):** Non-generative INT8 `XLMRobertaForTokenClassification` neural punctuation & casing restorer (`onnx-community/punctuate-all-ONNX`) running on the NPU via QNN HTP / NNAPI (`USE_FP16`) / XNNPACK. Automatically bypassed & kept out of RAM whenever an AI Post-Processing LLM is active.
  - **6 Selectable Local AI Post-Processing LLMs (`LiteRT-LM`, Optional, Off by Default):** Strictly **1 AI model is ever loaded on demand when you tap the bubble** (models start at **`0 MB` in RAM**, auto-unload after a configurable idle timer, and bypass `Punct` automatically):
    - **`Qwen 3 0.6B Lite` (`0.32 GB`):** 4-bit light cleanup.
    - **`Qwen 3 0.6B` (`0.57 GB`):** Compact multilingual cleanup across all 25 European languages.
    - **`Qwen 3 1.7B` (`0.91 GB`):** Mid-size multi-clause edits.
    - **`Liquid AI LFM3 1.7B` (`1.34 GB`, Default — Thinking Disabled by Default):** Fast Liquid AI tri-hybrid (`13` gated short convolutions + `5` SWA + `6` GQA) auto-editor with a custom `< 245`-token `prefill_256` system prompt and zero-thought direct output by default.
    - **`Qwen 2.5 1.5B Instruct` (`1.49 GB`):** Direct non-thinking instruct model.
    - **`Gemma 4 E2B` (`2.41 GB`, High-End):** High-end reasoning & structured formatting with Adaptive Smart Thinking and NPU/GPU/CPU + MTP support.
  - **6-Pillar On-Device LLM Speed & Efficiency Engine:**
    - **Zero-LLM Fast Path (`< 1 ms` Clean Utterance Bypass):** Instantly completes already-clean concise utterances (`1–28` words) without waking the NPU/GPU or loading an unloaded LLM into RAM.
    - **Per-Model Thinking Defaults & Adaptive Smart Auto-Thinking:** Defaults **`Disable Model Thinking`** to **ON** on **`Liquid AI LFM3 1.7B`** for instant zero-thought output, and to **OFF** (with **Adaptive Smart Thinking** enabled) on **`Gemma 4 E2B`** and **`Qwen 3`** so reasoning activates only on mid-sentence backtracks, spoken lists, and structural rewrites.
    - **Live Streaming Chunk-by-Chunk LLM Overlap:** Cleans closed earlier chunks in the background *while you continue speaking* when RAM co-residency permits, leaving only the short tail chunk after you tap Stop.
    - **Compact Span-Patch Mode & Input-Grounded Sentence Sieve:** Emits minimal `lhs => rhs` / `NONE` span diffs on long prose (`3×–8×` fewer tokens) and preserves clean sentences verbatim (`0` tokens) in multi-sentence dictations while sending only dirty sentence spans to the LLM.
    - **LiteRT-LM KV & Greedy Decoding Tuning + Drop-In Task LoRA Support:** Right-sizes `maxNumTokens` (`1,536` / `2,048`), strips reasoning channel tokens from KV cache (`filterChannelContentFromKvCache`), uses pure greedy argmax decoding (`topK = 1, temp = 0.0`) on built-in presets, streams via `sendMessageAsync` with instant runaway/loop `cancelProcess()` early-abort, pre-warms system prompts (`prefillPrefaceOnInit`), and auto-detects optional `<modelId>.lora` / `adapter.lora` adapters for ~30-token zero-example prefill.
    - **Built-In On-Device LLM Speed Benchmark (Uncached vs Cached System Prompt):** Collapsible benchmark tool at the bottom of *AI Post-Processing & Cleanup* (for any downloaded model) with 5 everyday dictation examples, 2 complex multi-edit stress tests, custom text input, side-by-side Uncached vs KV-Cached tokens/sec & TTFT comparison, and live formatted output preview.
- **Configurable Auto-Unload Battery Saver, Parallel On-Tap Loading & Screen-Off Deep Sleep:**
  - **0 MB Standby, `t = 0 ms` Parallel On-Tap Load & Screen-Off Deep Sleep (`Immediately`, `1m`, `2m`, `5m` [default], `10m`, `15m`, `30m`, `Never`):** Keeps **all** models (`Voz`, `Ear`, `Punct`, and the selected `LiteRT-LM` LLM) unloaded at **`0 MB` in RAM** on startup, starts loading `Ear` + `Voz` at `t = 0 ms` in parallel with `AudioRecord` init when you tap the bubble (with lock-free preemption and `2.0s` deferred LLM overlap), unloads within **`45s`** when the display turns off for deep sleep, and unloads after your configured idle timer while interactive.
  - **Event-Driven 0%-CPU Screen-Off Background Services:** Both `FloatingBubbleService` and `TextInjectionAccessibilityService` suspend completely (`0` CPU wakeups) while the display is off, deduplicate `getWindows()` polling with an adaptive Main-Looper heartbeat while interactive, and hide the overlay via zero-cost `alpha = 0.0f` + `FLAG_NOT_TOUCHABLE` surface compositing skip.
  - **Zero Unnecessary Multi-Model RAM Loading:** Strictly enforces single-LLM residency, automatically bypasses `Punct` when an LLM is active, scales `Voz` + LLM co-residency thresholds per model size (`canKeepVozAndLlmCoResident`), scales KV context windows (`4,096` / `2,048` / `1,536` tokens), and accurately classifies Android's two-band OS `onTrimMemory` callbacks (`classifyTrimMemoryAction`).
  - **Silence-Aware Smart Audio Chunking & Seamless Cross-Chunk Stitching:** Splits long dictations at natural pauses (`4.0s` min chunk, `550 ms` pause, `16.0s` max chunk) using dual-threshold consonant hangover, both-sided silence cushioning (`200–240 ms` runway + `10 ms` boundary tapers), and intelligent cross-chunk transcript stitching (`stitchChunkTranscripts`) that reconciles mid-sentence periods, capitalization, continuation conjunctions, and boundary word repetitions across chunks.
  - **Transcribe While Still Recording + Live Language Pre-Detection:** Peeks at the first `~1.8s` of live speech to pre-detect spoken language via `Ear` mid-sentence before the first pause, coalesces speech during cold `Voz` loads, and sends each finished pause chunk to `Voz` immediately while you keep speaking (overlapping prefix cleanup with `LiteRT-LM` or `Punct`). After you tap Stop, only the final tail remains, making even long multi-paragraph dictations finish nearly instantly.
  - **Live Model & NPU Telemetry:** Real-time indicators in Settings and on the Floating Bubble show detected NPU hardware, per-model RAM states (**Loaded in RAM**, **Loading**, **Running Inference**, **Standby**), last dictation latency, and real-time speedup (`× real-time`).
- **Built-In Developer Mode & Persistent Crash / OOM Logger:** Tap the version number (`Version 2.14.1`) under the Settings title **5 times fast** to unlock **Developer Mode** with live JVM/Native/System RAM gauges and persistent rolling `debug_log.txt` and `crash_log.txt` writers (including Android 11+ `ApplicationExitInfo` LMKD/native crash diagnostics).
- **Resumable Background Model Downloads & Instant Streaming SHA-256:**
  - **Lightweight APK:** Speech and AI models are downloaded in-app on first launch (or when toggled on in Settings) with full local storage management.
  - **Zero-Wait Streaming SHA-256 Verification:** SHA-256 digests are computed incrementally while chunks stream over HTTP and persisted in `.openflow_verified`, eliminating post-download freezes and native memory spikes.
  - **Background Execution & HTTP Range Resume:** Runs in a foreground service with a live status-bar progress notification, real-time speed/ETA telemetry, and `Range: bytes=<offset>-` support so downloads continue when switching apps and survive unstable connections.
- **Dictate Everywhere — Widget-Native Focused Text-Field Injection & Single-Copy Clipboard Backup:** Automatically injects transcribed text into the focused input field across native `EditText`, Material `TextInputLayout`, Jetpack Compose `TextField`/`BasicTextField`, and Chrome/WebView `<input>` & `contenteditable` editors using verified widget-native `ACTION_PASTE` first (with disguised placeholder/hint sanitization, smart back-to-back & mid-sentence spacing, URL/email spacing awareness, and sanitized `ACTION_SET_TEXT` fallback), while copying every transcript to the clipboard strictly once.
- **Minimalistic Material Design 3 Floating Bubble & Smart Screen Keep-Awake:** Clean, borderless Google Material 3 tonal bubble that automatically appears only when the on-screen keyboard is open, keeps the display awake whenever the bubble is on screen or while transcribing (`Ear` / `Voz` / `Punct`) and running AI post-processing (`LiteRT-LM`), smoothly spring-expands into an exact-fit pill showing the active pipeline stage, confirms completion with a centered green checkmark, and hides after a configurable disappear delay (`0.0s` – `5.0s`).

## 📦 Installation

### Download APK (Recommended)
Download the latest signed `openflow-release.apk` directly from [**GitHub Releases**](https://github.com/ultrapg/OpenFlow/releases/latest) (or from [`release/openflow-release.apk`](release/openflow-release.apk)) and install it on your Android device (`arm64-v8a`, Android 8.0+).

On first launch, OpenFlow enables **`Voz`** (speech recognition) and **`Ear`** (auto language detection) by default (`1,197 MB` total), with **`Punct`** (`282 MB` neural punctuation & casing cleanup) and **6 selectable local AI Post-Processing models** (defaulting to **`Liquid AI LFM3 1.7B`**, `1,375 MB`, from `329 MB` up to **`Gemma 4 E2B`** `2,468 MB` for high-end devices) available as optional toggles.

### Build from Source
Clone the repository and build directly with Gradle or Android Studio—no manual model download scripts required:

```bash
# Build debug APK
./gradlew assembleDebug

# Build signed arm64-v8a release APK
./gradlew assembleRelease
```

## 🛠️ Built With
- **Powered by [Desert Ant Labs](https://desertant.com)** (`Voz`, `Ear` v4.0.0 on LiteRT)
- Android Jetpack Compose & Material Design 3
- Kotlin Coroutines & Room

## 📜 License & Attribution
- **OpenFlow Source Code:** Licensed under the **Apache License, Version 2.0** (`Apache-2.0`). See [`LICENSE`](LICENSE) for full terms.
- **Desert Ant Labs SDKs & Model Weights:** Provided under the [Desert Ant Labs Source-Available License v1.0](https://license.desertant.com/1.0). Free for personal, academic, and commercial products up to **100,000 Monthly Active Users / Devices (MAU/MAD)** per model, per platform (`Voz`, `Ear`). Commercial deployments exceeding 100K MAU/MAD require a commercial license from Desert Ant Labs.

---
Developed by [@ultrapg](https://github.com/ultrapg)
