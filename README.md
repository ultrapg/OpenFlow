# OpenFlow

Welcome to **OpenFlow**, an offline-capable, high-accuracy Speech-to-Text (STT) dictation app for Android with a system-wide floating bubble and automatic accessibility text injection.

## 🚀 Features

- **100% On-Device Audio AI Pipeline & Universal NPU Acceleration:** All audio inference runs locally on your smartphone via **LiteRT** with selectable **Hardware Acceleration (`Auto`, `NPU`, `GPU (OpenCL)`, or `CPU (XNNPACK)`)** and automatic SoC NPU configuration for **Qualcomm Hexagon NPU (QNN HTP / FastRPC CDSP V66–V79)**, **MediaTek NeuroPilot APU**, **Google Tensor TPU**, **Samsung Exynos ENN/EDEN**, **HiSilicon Kirin HiAI**, **Rockchip RKNN**, and **Android NNAPI**. Your voice recordings never leave your device.
- **4-Stage Desert Ant Labs Audio Ecosystem:**
  - **`Voz` (1,187 MB — Enabled by Default):** High-accuracy multilingual transcription across 25 European languages with automatic punctuation, capitalization, word-level timestamps (`80 ms` resolution), and custom vocabulary biasing.
  - **`Ear` (10 MB — Enabled by Default):** Spoken language identification (107 languages) that automatically routes dictation across supported European languages or falls back to your preferred language.
  - **`Clear` (`clear-studio`, 47 MB — Optional):** Neural speech enhancement and denoising at native `16 kHz` (`Mastering.BYPASS`) for noisy environments.
  - **`Uhm` (23 MB — Optional):** Temporal acoustic filler detector (`20 ms` frame resolution) that strips hesitations (`uh`, `um`, `hmm`) by cross-referencing `Voz` word timestamps without deleting legitimate words.
- **Anti-OOM Engine Stability & Dynamic Stage Management:**
  - **JVM + System RAM Awareness:** Monitors ART JVM heap headroom and OS `ActivityManager.MemoryInfo` simultaneously to prevent `OutOfMemoryError` crashes even on devices with 8–16 GB of physical RAM.
  - **Single-Window Sequential `Clear` Denoising:** Executes `Clear` in bounded `<= 1.90s` (`30,400` sample) windows with `20 ms` crossfades (`DAL_RUN_DEPTH=1`), preventing multi-threaded OpenCL/EGL context collisions inside `libClearAndroid.so` / `libLiteRt.so`.
  - **Silence-Aware Smart Audio Chunking:** Automatically splits long dictations at natural 80 ms speech pauses (`<= 20s` normal, `<= 14s` low-memory) and trims leading/trailing dead air so Conformer attention tensors stay strictly bounded.
  - **Live Model & NPU Telemetry:** Real-time indicators in Settings, on the Floating Bubble, and in the status-bar notification show detected NPU hardware, per-model RAM states (**Loaded in RAM**, **Loading**, **Running Inference**, **Standby**), last dictation latency, and real-time speedup (`× real-time`).
- **Built-In Developer Mode & Persistent Crash / OOM Logger:** Tap the version number (`Version 2.5.3`) under the Settings title **5 times fast** to unlock **Developer Mode** with live JVM/Native/System RAM gauges and persistent rolling `debug_log.txt` and `crash_log.txt` writers (including Android 11+ `ApplicationExitInfo` LMKD/native crash diagnostics).
- **Resumable Background Model Downloads & Instant Streaming SHA-256:**
  - **Lightweight APK (~73 MB for `arm64-v8a`):** Speech models are downloaded in-app on first launch (or when toggled on in Settings) with full local storage management.
  - **Zero-Wait Streaming SHA-256 Verification:** SHA-256 digests are computed incrementally while chunks stream over HTTP and persisted in `.openflow_verified`, eliminating post-download freezes and native memory spikes.
  - **Background Execution & HTTP Range Resume:** Runs in a foreground service with a live status-bar progress notification, real-time speed/ETA telemetry, and `Range: bytes=<offset>-` support so downloads continue when switching apps and survive unstable connections.
- **Dictate Everywhere:** Automatically injects transcribed text into any focused input field via Android's Accessibility Service (with multi-window focus recovery), plus automatic clipboard fallback.
- **Minimalistic Material Design 3 Floating Bubble:** Clean, borderless Google Material 3 tonal bubble that automatically appears only when the on-screen keyboard is open, smoothly spring-expands into an exact-fit pill showing the active pipeline stage (`Clear`, `Ear`, `Uhm`, `Voz`), confirms completion with a centered green checkmark, and hides after a configurable disappear delay (`0.0s` – `5.0s`).

## 📦 Installation

### Download APK (Recommended)
Download the latest signed `openflow-release.apk` directly from [**GitHub Releases**](https://github.com/ultrapg/OpenFlow/releases/latest) (or from [`release/openflow-release.apk`](release/openflow-release.apk)) and install it on your Android device (`arm64-v8a`, Android 8.0+).

On first launch, OpenFlow enables **`Voz`** (speech recognition) and **`Ear`** (auto language detection) by default (`1,197 MB` total), and lets you optionally enable **`Clear`** (noise suppression) and **`Uhm`** (filler word removal) anytime in Settings.

### Build from Source
Clone the repository and build directly with Gradle or Android Studio—no manual model download scripts required:

```bash
# Build debug APK
./gradlew assembleDebug

# Build signed arm64-v8a release APK
./gradlew assembleRelease
```

## 🛠️ Built With
- **Powered by [Desert Ant Labs](https://desertant.com)** (`Voz`, `Ear`, `Clear`, `Uhm` v4.0.0 on LiteRT)
- Android Jetpack Compose & Material Design 3
- Kotlin Coroutines & Room

## 📜 License & Attribution
- **OpenFlow Source Code:** Licensed under the **Apache License, Version 2.0** (`Apache-2.0`). See [`LICENSE`](LICENSE) for full terms.
- **Desert Ant Labs SDKs & Model Weights:** Provided under the [Desert Ant Labs Source-Available License v1.0](https://license.desertant.com/1.0). Free for personal, academic, and commercial products up to **100,000 Monthly Active Users / Devices (MAU/MAD)** per model, per platform (`Voz`, `Ear`, `Clear`, `Uhm`). Commercial deployments exceeding 100K MAU/MAD require a commercial license from Desert Ant Labs.

---
Developed by [@ultrapg](https://github.com/ultrapg)
