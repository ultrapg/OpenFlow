# OpenFlow

Welcome to **OpenFlow**, an offline-capable, high-accuracy Speech-to-Text (STT) dictation app for Android with a system-wide floating bubble and accessibility text injection.

## 🚀 Features
- **On-Device Audio AI Pipeline:** All audio inference runs locally on your smartphone via **LiteRT** with selectable **Hardware Acceleration (`Auto`, `NPU`, `GPU (OpenCL)`, or `CPU (XNNPACK)`)**. Your voice recordings never leave your device.
- **4-Stage Desert Ant Labs Audio Ecosystem:**
  - **`Clear` (`clear-studio`, 24 MB):** Neural speech enhancement and denoising in noisy environments.
  - **`Ear` (22 MB):** Spoken language identification (107 languages) that automatically routes dictation across supported European languages or falls back to your preferred language.
  - **`Voz` (337 MB):** High-accuracy multilingual transcription across 25 European languages with automatic punctuation, capitalization, word-level timestamps ($80\text{ ms}$ resolution), and custom vocabulary biasing.
  - **`Uhm` (24 MB):** Temporal acoustic filler detector ($20\text{ ms}$ frame resolution) that strips hesitations (`uh`, `um`, `hmm`) by cross-referencing `Voz` word timestamps without deleting legitimate words.
- **Fast Inference & Live Model Load Telemetry:**
  - **Pre-Warmed LiteRT Sessions:** Models are loaded and pre-warmed in background RAM on launch so dictation begins with zero compilation delay.
  - **Real-Time Model Status:** Live indicators in Settings, on the Floating Bubble, and in the status bar notification show whether each model (`Voz`, `Ear`, `Clear`, `Uhm`) is **Loaded in RAM**, **Loading**, **Running Inference**, or on **Standby**, along with last dictation latency and real-time speedup (`× real-time`).
  - **Smart Audio Pre-Processing:** Leading/trailing silence trimming and bounded 5.5-second language identification windows minimize inference latency.
- **Resumable Background Model Downloads & Instant Streaming SHA-256:**
  - **Lightweight APK (~73 MB for `arm64-v8a`):** Speech models are downloaded in-app on first launch (or when toggled on in Settings) with full local storage management.
  - **Zero-Wait Streaming SHA-256 Verification:** SHA-256 digests are computed incrementally in L1 cache while chunks stream over HTTP and persisted in `.openflow_verified`, eliminating post-download freezes and native `ModelStore` memory spikes.
  - **Background Execution & HTTP Range Resume:** Runs in a foreground service with a live status-bar progress notification and `Range: bytes=<offset>-` support so downloads continue when switching apps, survive slow/unstable internet connections with automatic exponential-backoff retries, and can be paused and resumed anytime without losing progress.
- **Dictate Everywhere:** Automatically injects transcribed text into any focused input field via Android's Accessibility Service (with multi-window focus recovery and a 2-second focus grace period), plus non-destructive clipboard fallback.
- **Reliable Floating Bubble UI:** Always-accessible, screen-bounds-clamped draggable overlay bubble with real-time RMS audio visualization, model readiness badge, and background `WakeLock` protection during dictation.

## 📦 Building & Installation

Clone the repository and build directly with Gradle or Android Studio—no manual model download scripts required:

```bash
# Build debug APK
./gradlew assembleDebug

# Build signed arm64-v8a release APK (~73 MB)
./gradlew assembleRelease
```

On first launch, OpenFlow's onboarding flow lets you customize which optional models (`Ear`, `Clear`, `Uhm`) to download alongside `Voz`.

## 🛠️ Built With
- **Powered by [Desert Ant Labs](https://desertant.com)** (`Voz`, `Ear`, `Clear`, `Uhm` v4.0.0 on LiteRT)
- Android Jetpack Compose & Material 3
- Kotlin Coroutines

## 📜 License & Attribution
- **OpenFlow Source Code:** Licensed under the **Apache License, Version 2.0** (`Apache-2.0`). See [`LICENSE`](LICENSE) for full terms.
- **Desert Ant Labs SDKs & Model Weights:** Provided under the [Desert Ant Labs Source-Available License v1.0](https://license.desertant.com/1.0). Free for personal, academic, and commercial products up to **100,000 Monthly Active Users / Devices (MAU/MAD)** per model, per platform (`Voz`, `Ear`, `Clear`, `Uhm`). Commercial deployments exceeding 100K MAU/MAD require a commercial license from Desert Ant Labs.
