# OpenFlow

Welcome to **OpenFlow**, an offline-capable, high-accuracy Speech-to-Text (STT) dictation app for Android with a system-wide floating bubble and automatic accessibility text injection.

## 🚀 Features

- **100% On-Device Audio AI Pipeline & Universal NPU Acceleration:** All audio inference runs locally on your smartphone via **LiteRT** with selectable **Hardware Acceleration (`Auto`, `NPU`, `GPU (OpenCL)`, or `CPU (XNNPACK)`)** and automatic SoC NPU configuration for **Qualcomm Hexagon NPU (QNN HTP / FastRPC CDSP V66–V79)**, **MediaTek NeuroPilot APU**, **Google Tensor TPU**, **Samsung Exynos ENN/EDEN**, **HiSilicon Kirin HiAI**, **Rockchip RKNN**, and **Android NNAPI**. Your voice recordings never leave your device.
- **Streamlined On-Device Audio & Text AI Ecosystem (`Voz` + `Ear` + optional `Punct` + 5 Selectable Local LLMs):**
  - **`Voz` (1,187 MB — Enabled by Default):** High-accuracy multilingual transcription across 25 European languages with automatic punctuation, capitalization, word-level timestamps (`80 ms` resolution), and custom vocabulary biasing.
  - **`Ear` (10 MB — Enabled by Default):** Spoken language identification (107 languages) that automatically routes dictation across supported European languages or falls back to your preferred language.
  - **`Punct` (282 MB — Optional, Off by Default):** Non-generative INT8 `XLMRobertaForTokenClassification` neural punctuation & casing restorer (`onnx-community/punctuate-all-ONNX`) running on the NPU via QNN HTP / NNAPI (`USE_FP16`) / XNNPACK. Automatically bypassed & kept out of RAM whenever an AI Post-Processing LLM is active.
  - **5 Selectable Local AI Post-Processing LLMs — Fast & Lightweight to Flagship (`LiteRT-LM`, Optional, Off by Default):** Choose the ideal balance of speed, download size, and RAM for your phone—strictly **1 AI model is ever loaded on demand when dictating** (idle LLM RAM stays at **`0 MB`**, with automatic 5-minute burst eviction and instant unload when switching models):
    - **`Qwen 3 0.6B Lite` (329 MB • INT4 • +350 MB RAM):** Smallest download & ultra-fast cleanup for 4 GB+ devices.
    - **`Qwen 3 0.6B` (585 MB • Recommended Default • +450 MB RAM):** Best speed-to-accuracy sweet spot across all 25 European languages.
    - **`Qwen 3 1.7B` (932 MB • INT4 • +650 MB RAM):** Stronger mid-sentence backtrack, number, and list formatting on 6 GB+ devices.
    - **`Qwen 2.5 1.5B Instruct` (1,524 MB • INT8 • +750 MB RAM):** Direct non-thinking model with zero reasoning overhead and GPU/CPU acceleration.
    - **`Gemma 4 E2B` (2,468 MB • Experimental Flagship • +850 MB RAM):** Google's highest-accuracy on-device formatting engine with NPU/GPU/CPU + MTP support for 8 GB+ devices.
  - **Per-Model Optimized Prompts & System-Prompt KV-Cache Pre-Warming:** Built-in presets (especially the default **`Wispr Flow Auto-Edit`**) automatically adapt system instructions, few-shot examples, token budgets, and sampling parameters to your selected model tier, while **`prefillPrefaceOnInit`** pre-warms the system prompt KV cache in the background while you speak. Supports **`Disable Model Thinking`** (**off by default**, or always-direct on `Qwen 2.5 1.5B Instruct`) and fully editable **Custom System Prompts**.
- **Anti-OOM Engine Stability & Dynamic Stage Management:**
  - **Zero Unnecessary Multi-Model RAM Loading:** Strictly enforces single-LLM residency, keeps LLMs on zero-RAM standby during cold start/idle browsing, automatically bypasses `Punct` when an LLM is active, scales `Voz` + LLM co-residency thresholds per model size (`canKeepVozAndLlmCoResident`), scales KV context windows (`4,096` / `2,048` / `1,536` tokens), and responds to multi-tier OS `onTrimMemory` callbacks.
  - **Silence-Aware Smart Audio Chunking & Seamless Cross-Chunk Stitching:** Splits long dictations at natural pauses using dual-threshold consonant hangover, both-sided silence cushioning (`200–240 ms` runway + `10 ms` boundary tapers), and intelligent cross-chunk transcript stitching (`stitchChunkTranscripts`) that reconciles mid-sentence periods, capitalization, continuation conjunctions, and boundary word repetitions across chunks.
  - **Transcribe While Still Recording:** When a pause is detected during recording, the finished chunk is sent to the pipeline immediately (ordered single worker, language detected once per dictation, post-pause silent/tap tails automatically skipped). After you stop, only the last chunk remains, so long dictations feel almost instant. Pause length, minimum/maximum chunk length, silence level and language reuse are configurable under *Dictation Chunking* in Settings.
  - **Live Model & NPU Telemetry:** Real-time indicators in Settings and on the Floating Bubble show detected NPU hardware, per-model RAM states (**Loaded in RAM**, **Loading**, **Running Inference**, **Standby**), last dictation latency, and real-time speedup (`× real-time`).
- **Built-In Developer Mode & Persistent Crash / OOM Logger:** Tap the version number (`Version 2.8.0`) under the Settings title **5 times fast** to unlock **Developer Mode** with live JVM/Native/System RAM gauges and persistent rolling `debug_log.txt` and `crash_log.txt` writers (including Android 11+ `ApplicationExitInfo` LMKD/native crash diagnostics).
- **Resumable Background Model Downloads & Instant Streaming SHA-256:**
  - **Lightweight APK:** Speech and AI models are downloaded in-app on first launch (or when toggled on in Settings) with full local storage management.
  - **Zero-Wait Streaming SHA-256 Verification:** SHA-256 digests are computed incrementally while chunks stream over HTTP and persisted in `.openflow_verified`, eliminating post-download freezes and native memory spikes.
  - **Background Execution & HTTP Range Resume:** Runs in a foreground service with a live status-bar progress notification, real-time speed/ETA telemetry, and `Range: bytes=<offset>-` support so downloads continue when switching apps and survive unstable connections.
- **Dictate Everywhere:** Automatically injects transcribed text into any focused input field via Android's Accessibility Service (with multi-window focus recovery), plus automatic clipboard fallback.
- **Minimalistic Material Design 3 Floating Bubble:** Clean, borderless Google Material 3 tonal bubble that automatically appears only when the on-screen keyboard is open, smoothly spring-expands into an exact-fit pill showing the active pipeline stage (`Ear`, `Voz`), confirms completion with a centered green checkmark, and hides after a configurable disappear delay (`0.0s` – `5.0s`).

## 📦 Installation

### Download APK (Recommended)
Download the latest signed `openflow-release.apk` directly from [**GitHub Releases**](https://github.com/ultrapg/OpenFlow/releases/latest) (or from [`release/openflow-release.apk`](release/openflow-release.apk)) and install it on your Android device (`arm64-v8a`, Android 8.0+).

On first launch, OpenFlow enables **`Voz`** (speech recognition) and **`Ear`** (auto language detection) by default (`1,197 MB` total), with **`Punct`** (`282 MB` neural punctuation & casing cleanup) and **5 selectable local AI Post-Processing models** (defaulting to **`Qwen 3 0.6B`**, `585 MB`, from `329 MB` up to `2,468 MB`) available as optional toggles.

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
