# Changelog

All notable changes to the **OpenFlow** project are documented in this file.

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
