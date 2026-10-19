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

package com.voicebubble.openflow

import ai.desertant.uhm.FrameClassification
import ai.desertant.uhm.mergeFillerFrames
import ai.desertant.voz.Transcription
import ai.desertant.voz.Word
import com.voicebubble.openflow.core.speech.reconcileTranscript
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import ai.desertant.voz.Voz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPipelineTest {

    @Test
    fun `mergeFillerFrames merges non-zero frames separated by at most 40ms`() {
        val frames = listOf(
            FrameClassification(0, 0L, 20L, 0, 0.95f),
            FrameClassification(1, 20L, 40L, 1, 0.80f),
            FrameClassification(2, 40L, 60L, 1, 0.85f),
            // 40ms gap (60..100ms is class 0) -> should merge with next filler at 100ms
            FrameClassification(3, 60L, 80L, 0, 0.90f),
            FrameClassification(4, 80L, 100L, 0, 0.90f),
            FrameClassification(5, 100L, 120L, 2, 0.90f),
            // 60ms gap (120..180ms is class 0) -> exceeds 40ms, must start a second span
            FrameClassification(9, 180L, 200L, 3, 0.75f)
        )

        val spans = mergeFillerFrames(frames, maxGapMs = 40L)
        assertEquals(2, spans.size)
        assertEquals(20L, spans[0].startMs)
        assertEquals(120L, spans[0].endMs)
        assertEquals(180L, spans[1].startMs)
        assertEquals(200L, spans[1].endMs)
    }

    @Test
    fun `reconcileTranscript strips leading filler and capitalizes new leading word`() {
        val transcription = Transcription(
            text = "Ähm, ich komme gleich vorbei.",
            words = listOf(
                Word("Ähm,", 0.10, 0.42),
                Word("ich", 0.50, 0.74),
                Word("komme", 0.76, 1.10),
                Word("gleich", 1.12, 1.45),
                Word("vorbei.", 1.48, 1.95)
            ),
            duration = 2.0
        )

        // Uhm detects filler from 120ms to 380ms (260ms inside [100ms, 420ms] -> >50% overlap)
        val frames = (6 until 19).map { idx ->
            FrameClassification(
                frameIndex = idx,
                startMs = idx * 20L,
                endMs = (idx + 1) * 20L,
                classId = 2, // um
                confidence = 0.92f
            )
        }

        val cleaned = reconcileTranscript(
            transcription = transcription,
            uhmFrames = frames,
            uhmEnabled = true
        )

        assertEquals("Ich komme gleich vorbei.", cleaned)
    }

    @Test
    fun `reconcileTranscript preserves legitimate word when overlap ratio is below 50 percent`() {
        val transcription = Transcription(
            text = "Wir fahren nach Ulm morgen.",
            words = listOf(
                Word("Wir", 0.0, 0.30),
                Word("fahren", 0.32, 0.70),
                Word("nach", 0.72, 0.96),
                Word("Ulm", 1.00, 1.60), // 600ms word
                Word("morgen.", 1.64, 2.10)
            ),
            duration = 2.2
        )

        // Tiny 20ms frame at 1000..1020ms: with +/-80ms tolerance, overlap with [1000, 1600] is [1000, 1100] = 100ms
        // 100ms / 600ms = 16.7% < 50% -> "Ulm" must be preserved!
        val frames = listOf(
            FrameClassification(50, 1000L, 1020L, 2, 0.65f)
        )

        val cleaned = reconcileTranscript(
            transcription = transcription,
            uhmFrames = frames,
            uhmEnabled = true
        )

        assertEquals("Wir fahren nach Ulm morgen.", cleaned)
    }

    @Test
    fun `custom vocabulary applies canonical casing and single-edit biasing`() {
        val transcription = Transcription(
            text = "openflow nutzt litert und paraket.",
            words = listOf(
                Word("openflow", 0.0, 0.5),
                Word("nutzt", 0.5, 0.8),
                Word("litert", 0.8, 1.2),
                Word("und", 1.2, 1.4),
                Word("paraket.", 1.4, 2.0)
            ),
            duration = 2.0
        )

        val cleaned = reconcileTranscript(
            transcription = transcription,
            uhmFrames = emptyList(),
            uhmEnabled = false,
            customVocabulary = listOf("OpenFlow", "LiteRT", "Parakeet")
        )

        assertEquals("OpenFlow nutzt LiteRT und Parakeet.", cleaned)
    }

    @Test
    fun `ModelSettings toggle state matrix matches Specification Section 5_4`() {
        assertEquals(1680, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(1267, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(1530, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(1220, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(1600, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(1244, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(1450, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(1197, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(1630, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(1257, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(1480, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(1210, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(1550, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(1234, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(1400, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(1187, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = false).activeDiskFootprintMb)

        val defaultSettings = ModelSettings()
        assertTrue(defaultSettings.vozEnabled)
        assertTrue(defaultSettings.earEnabled)
        assertFalse(defaultSettings.clearEnabled)
        assertFalse(defaultSettings.uhmEnabled)
        assertEquals(1450, defaultSettings.estimatedPeakRamMb)
        assertEquals(1197, defaultSettings.activeDiskFootprintMb)

        for (model in com.voicebubble.openflow.settings.DesertAntModel.entries) {
            assertEquals(
                "expectedBytes mismatch for $model",
                com.voicebubble.openflow.settings.ModelDownloadManager.modelTotalBytes(model),
                model.expectedBytes
            )
        }

        assertEquals(25, Voz.supportedLanguages.size)
        assertTrue("de" in Voz.supportedLanguages && "en" in Voz.supportedLanguages)
    }

    @Test
    fun `DownloadSessionState formats speed, ETA, and byte progress accurately`() {
        assertEquals("Connecting...", com.voicebubble.openflow.settings.DownloadSessionState.formatSpeed(0L))
        assertEquals("512 KB/s", com.voicebubble.openflow.settings.DownloadSessionState.formatSpeed(512L * 1024L))
        assertEquals("2.50 MB/s", com.voicebubble.openflow.settings.DownloadSessionState.formatSpeed((2.5 * 1024 * 1024).toLong()))

        assertEquals("Calculating time left...", com.voicebubble.openflow.settings.DownloadSessionState.formatEta(null))
        assertEquals("Almost done...", com.voicebubble.openflow.settings.DownloadSessionState.formatEta(0L))
        assertEquals("~45s left", com.voicebubble.openflow.settings.DownloadSessionState.formatEta(45L))
        assertEquals("~2m 05s left", com.voicebubble.openflow.settings.DownloadSessionState.formatEta(125L))
        assertEquals("~1h 05m left", com.voicebubble.openflow.settings.DownloadSessionState.formatEta(3900L))

        for (model in com.voicebubble.openflow.settings.DesertAntModel.entries) {
            val spec = com.voicebubble.openflow.settings.ModelDownloadManager.MODEL_SPECS[model]
            assertTrue("Missing spec for $model", spec != null)
            val (revision, artifacts) = spec!!
            assertTrue("Blank revision for $model", revision.startsWith("v"))
            assertTrue("Empty artifacts for $model", artifacts.isNotEmpty())
            for (artifact in artifacts) {
                assertTrue("Invalid size for ${artifact.relativePath}", artifact.sizeBytes > 0L)
                assertEquals("Invalid SHA-256 length for ${artifact.relativePath}", 64, artifact.sha256.length)
            }
        }
    }

    @Test
    fun `trimLeadingAndTrailingSilence removes dead air while preserving speech padding`() {
        // 1. Empty input -> empty output
        assertEquals(0, com.voicebubble.openflow.core.speech.trimLeadingAndTrailingSilence(FloatArray(0)).size)

        // 2. All silence below threshold -> empty output (skips native inference completely)
        val pureSilence = FloatArray(16_000) { 0.0005f }
        assertEquals(0, com.voicebubble.openflow.core.speech.trimLeadingAndTrailingSilence(pureSilence).size)

        // 3. 1s silence + 0.5s speech + 1s silence -> trimmed to 0.5s speech + 2 * padSamples
        val samples = FloatArray(40_000) { idx ->
            if (idx in 16_000 until 24_000) 0.25f else 0.0002f
        }
        val pad = 1_600 // 100 ms pad
        val trimmed = com.voicebubble.openflow.core.speech.trimLeadingAndTrailingSilence(
            samples = samples,
            threshold = 0.0035f,
            padSamples = pad
        )
        val expectedLength = (24_000 - 16_000) + (2 * pad)
        assertEquals(expectedLength, trimmed.size)
        // First sample after leading pad should be speech
        assertEquals(0.25f, trimmed[pad], 1e-5f)

        // 4. Speech from first to last sample -> returns original array unchanged
        val fullSpeech = FloatArray(4_000) { 0.15f }
        val unchanged = com.voicebubble.openflow.core.speech.trimLeadingAndTrailingSilence(fullSpeech)
        assertEquals(fullSpeech.size, unchanged.size)
    }

    @Test
    fun `DesertAntModelStorage fast verification checks sizes part files and stamps without JNI`() {
        val tempRoot = java.nio.file.Files.createTempDirectory("openflow_model_test").toFile()
        try {
            val earArtifacts = com.voicebubble.openflow.settings.ModelDownloadManager.MODEL_SPECS[
                com.voicebubble.openflow.settings.DesertAntModel.EAR
            ]!!.second

            // Initially empty directory -> not complete
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(tempRoot, earArtifacts))

            // Create files with wrong sizes -> not complete
            for (artifact in earArtifacts) {
                java.io.File(tempRoot, artifact.relativePath).writeBytes(ByteArray(16))
            }
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(tempRoot, earArtifacts))

            // Use synthetic small artifacts to test exact byte-size + .part + stamp logic
            val testArtifacts = listOf(
                com.voicebubble.openflow.settings.ModelFileArtifact("a.tflite", 128L, "a".repeat(64)),
                com.voicebubble.openflow.settings.ModelFileArtifact("b.litert", 64L, "b".repeat(64))
            )
            val subDir = java.io.File(tempRoot, "synthetic").apply { mkdirs() }
            java.io.File(subDir, "a.tflite").writeBytes(ByteArray(128))
            java.io.File(subDir, "b.litert").writeBytes(ByteArray(64))

            // Calling isModelDirectoryComplete must create .dal-meta/manifest but MUST NOT forge .openflow_verified stamps
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(subDir, testArtifacts))
            assertTrue(java.io.File(subDir, ".dal-meta/manifest").exists())
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.isArtifactAlreadyVerified(subDir, testArtifacts[0]))
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.areAllArtifactsVerified(subDir, testArtifacts))

            // Write manifest and verification stamp -> both directory and per-artifact checks are true
            com.voicebubble.openflow.settings.DesertAntModelStorage.writeModelManifestAndStamp(subDir, testArtifacts)
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(subDir, testArtifacts))
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.areAllArtifactsVerified(subDir, testArtifacts))
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.isArtifactAlreadyVerified(subDir, testArtifacts[0]))
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.isArtifactAlreadyVerified(subDir, testArtifacts[1]))

            // If an in-progress .part file exists -> not complete and not verified
            val partFile = java.io.File(subDir, "a.tflite.part").apply { writeBytes(ByteArray(10)) }
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(subDir, testArtifacts))
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.areAllArtifactsVerified(subDir, testArtifacts))
            assertFalse(com.voicebubble.openflow.settings.DesertAntModelStorage.isArtifactAlreadyVerified(subDir, testArtifacts[0]))
            partFile.delete()
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDirectoryComplete(subDir, testArtifacts))
            assertTrue(com.voicebubble.openflow.settings.DesertAntModelStorage.areAllArtifactsVerified(subDir, testArtifacts))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `ModelDownloadManager verifySha256Incremental validates digest and reports progress`() = kotlinx.coroutines.runBlocking {
        val tempFile = java.io.File.createTempFile("openflow_sha256_test", ".bin")
        try {
            val payload = ByteArray(1_200_000) { (it % 251).toByte() }
            tempFile.writeBytes(payload)

            val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }

            val progressUpdates = mutableListOf<Int>()
            val valid = com.voicebubble.openflow.settings.ModelDownloadManager.verifySha256Incremental(
                file = tempFile,
                expectedHex = expectedDigest,
                onVerifyProgress = { pct -> progressUpdates.add(pct) }
            )
            assertTrue(valid)
            assertTrue(progressUpdates.isNotEmpty())
            assertEquals(100, progressUpdates.last())

            val invalid = com.voicebubble.openflow.settings.ModelDownloadManager.verifySha256Incremental(
                file = tempFile,
                expectedHex = "0".repeat(64)
            )
            assertFalse(invalid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `HardwareAccelerator resolves ids and defaults safely`() {
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId("auto")
        )
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.NPU,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId("npu")
        )
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.GPU,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId("gpu")
        )
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.CPU,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId("cpu")
        )
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId("unknown_value")
        )
        assertEquals(
            com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
            com.voicebubble.openflow.settings.HardwareAccelerator.fromId(null)
        )
    }

    @Test
    fun `splitAudioIntoSmartChunks splits long recordings at silence valleys without losing samples`() {
        // 1. Short clip <= maxChunkSamples stays a single chunk
        val shortClip = FloatArray(16_000 * 8) { 0.1f }
        val singleChunk = com.voicebubble.openflow.core.speech.splitAudioIntoSmartChunks(
            samples = shortClip,
            maxChunkSamples = 16_000 * 20,
            minChunkSamples = 16_000 * 10
        )
        assertEquals(1, singleChunk.size)
        assertEquals(shortClip.size, singleChunk[0].size)

        // 2. 45-second clip with a clear silence valley at 15.0s..15.3s and 31.0s..31.3s
        val totalSamples = 45 * 16_000
        val pause1Start = 15 * 16_000
        val pause1End = pause1Start + (16_000 / 3)
        val pause2Start = 31 * 16_000
        val pause2End = pause2Start + (16_000 / 3)

        val longClip = FloatArray(totalSamples) { idx ->
            when (idx) {
                in pause1Start until pause1End -> 0.0001f
                in pause2Start until pause2End -> 0.0001f
                else -> 0.25f
            }
        }

        val chunks = com.voicebubble.openflow.core.speech.splitAudioIntoSmartChunks(
            samples = longClip,
            maxChunkSamples = 20 * 16_000,
            minChunkSamples = 11 * 16_000
        )

        assertEquals(3, chunks.size)
        // Total samples across all chunks must equal original sample count
        assertEquals(totalSamples, chunks.sumOf { it.size })
        // First split should occur inside the first silence valley [pause1Start..pause1End]
        assertTrue(
            "Expected first chunk split (${chunks[0].size}) inside [$pause1Start..$pause1End]",
            chunks[0].size in pause1Start..pause1End
        )
        // Every chunk must respect maxChunkSamples
        assertTrue(chunks.all { it.size <= 20 * 16_000 })
    }

    @Test
    fun `NpuHardwareManager detects Qualcomm Hexagon and common mobile NPUs accurately`() {
        val qcomElite = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("Qualcomm SM8750 Sun")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.QUALCOMM_HEXAGON, qcomElite.first)
        assertEquals("Hexagon V79 HTP", qcomElite.second)

        val qcomGen3 = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("QCOM SM8650 Pineapple")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.QUALCOMM_HEXAGON, qcomGen3.first)
        assertEquals("Hexagon V75 HTP", qcomGen3.second)

        val qcomGen2 = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("Snapdragon SM8550 Kalama")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.QUALCOMM_HEXAGON, qcomGen2.first)
        assertEquals("Hexagon V73 HTP", qcomGen2.second)

        val mtk9400 = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("MediaTek MT6991 Dimensity 9400")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.MEDIATEK_NEUROPILOT, mtk9400.first)
        assertEquals("APU 890 (Gen 8)", mtk9400.second)

        val tensorG4 = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("Google Tensor G4 zumapro")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.GOOGLE_TENSOR, tensorG4.first)
        assertEquals("Tensor G4 Rio TPU", tensorG4.second)

        val exynos2400 = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("Samsung s5e9945 Exynos 2400")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.SAMSUNG_EXYNOS, exynos2400.first)
        assertEquals("Exynos 2400 Dual-NPU", exynos2400.second)

        val kirin = com.voicebubble.openflow.core.speech.NpuHardwareManager.classifySocAndVendor("HiSilicon Kirin 9000s")
        assertEquals(com.voicebubble.openflow.core.speech.NpuVendor.HISILICON_KIRIN, kirin.first)
    }

    @Test
    fun `MemoryPressureReport detects JVM heap exhaustion even when system RAM is plentiful`() {
        // Device has 8 GB of free system RAM, but JVM ART heap has only 24 MB free out of 256 MB
        val jvmTightOnHighRamPhone = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 8_192L,
            systemTotalMb = 12_288L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 232L,
            jvmMaxMb = 256L,
            jvmFreeHeadroomMb = 24L,
            nativeAllocatedMb = 600L
        )
        assertFalse(jvmTightOnHighRamPhone.isSystemRamTight)
        assertTrue(jvmTightOnHighRamPhone.isJvmHeapTight)
        assertTrue(jvmTightOnHighRamPhone.isJvmHeapCritical)
        assertTrue(jvmTightOnHighRamPhone.isAnyMemoryTight)
        assertTrue(jvmTightOnHighRamPhone.isCriticalPressure)

        // Samsung Galaxy S25 (SM-S931B) telemetry: 4647 MB native virtual GPU mapping, 496 MB JVM free, 2161 MB SysRAM free
        val s25OpenClVirtualMapping = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 2_161L,
            systemTotalMb = 11_113L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 16L,
            jvmMaxMb = 512L,
            jvmFreeHeadroomMb = 496L,
            nativeAllocatedMb = 4_647L
        )
        assertTrue(s25OpenClVirtualMapping.isNativeHeapHigh)
        assertFalse(s25OpenClVirtualMapping.isJvmHeapTight)
        assertFalse(s25OpenClVirtualMapping.isSystemRamTight)
        assertFalse(s25OpenClVirtualMapping.isAnyMemoryTight)
        assertFalse(s25OpenClVirtualMapping.isCriticalPressure)
    }

    @Test
    fun `enhanceAudioInSingleWindowSlices guarantees every slice stays within Clear single-window limit`() {
        // User's exact crash-log audio length: 64,960 samples (4.06s @ 16 kHz)
        val input = FloatArray(64_960) { idx -> kotlin.math.sin(idx * 0.02).toFloat() * 0.5f }
        val observedSliceSizes = mutableListOf<Int>()

        val enhanced = com.voicebubble.openflow.core.speech.enhanceAudioInSingleWindowSlices(
            samples = input,
            maxWindowSamples = com.voicebubble.openflow.core.speech.SmartPipeline.MAX_CLEAR_SINGLE_WINDOW_SAMPLES,
            crossfadeSamples = com.voicebubble.openflow.core.speech.SmartPipeline.CLEAR_WINDOW_CROSSFADE_SAMPLES
        ) { slice ->
            observedSliceSizes.add(slice.size)
            FloatArray(slice.size) { i -> slice[i] * 0.8f }
        }

        assertEquals(input.size, enhanced.size)
        assertTrue(observedSliceSizes.size >= 3)
        assertTrue(
            "Every slice must be <= 30,400 samples so Clear ParallelRuns.run always sees count == 1",
            observedSliceSizes.all { it <= com.voicebubble.openflow.core.speech.SmartPipeline.MAX_CLEAR_SINGLE_WINDOW_SAMPLES }
        )
        for (i in input.indices) {
            assertEquals(input[i] * 0.8f, enhanced[i], 1e-5f)
        }
    }

    @Test
    fun `enhanceAudioInSingleWindowSlices sanitizes NaN samples and falls back to raw on truncated slices`() {
        val input = FloatArray(5_000) { idx -> (idx % 100) * 0.01f }
        val enhanced = com.voicebubble.openflow.core.speech.enhanceAudioInSingleWindowSlices(
            samples = input,
            maxWindowSamples = 2_000,
            crossfadeSamples = 200
        ) { slice ->
            // Return a truncated slice with a NaN value at index 10
            FloatArray(slice.size / 2) { i ->
                if (i == 10) Float.NaN else slice[i]
            }
        }

        assertEquals(input.size, enhanced.size)
        assertTrue("Enhanced output must never contain NaN or Infinite floats", enhanced.all { it.isFinite() })
        for (i in input.indices) {
            assertEquals(input[i], enhanced[i], 1e-5f)
        }
    }

    @Test
    fun `applyCustomVocabulary supports punctuation terms and zero-allocation single-edit matching`() {
        val text = "Wir entwickeln mit c++ und .net für openfloe."
        val vocab = listOf("C++", ".NET", "OpenFlow")
        val result = com.voicebubble.openflow.core.speech.applyCustomVocabulary(text, vocab)
        assertEquals("Wir entwickeln mit C++ und .NET für OpenFlow.", result)

        assertTrue(com.voicebubble.openflow.core.speech.isSingleEditDistanceIgnoreCase("paraket", "Parakeet"))
        assertTrue(com.voicebubble.openflow.core.speech.isSingleEditDistanceIgnoreCase("OpenFloe", "OpenFlow"))
        assertFalse(com.voicebubble.openflow.core.speech.isSingleEditDistanceIgnoreCase("OpenFlow", "OpenFlow"))
        assertFalse(com.voicebubble.openflow.core.speech.isSingleEditDistanceIgnoreCase("short", "very_long_word"))
    }

    @Test
    fun `resolveNativeDeviceId falls back to cpu under low memory pressure`() {
        assertEquals(
            "cpu",
            com.voicebubble.openflow.core.speech.NpuHardwareManager.resolveNativeDeviceId(
                com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
                lowMemoryPressure = true
            )
        )
        assertEquals(
            "cpu",
            com.voicebubble.openflow.core.speech.NpuHardwareManager.resolveNativeDeviceId(
                com.voicebubble.openflow.settings.HardwareAccelerator.CPU,
                lowMemoryPressure = false
            )
        )
        assertEquals(
            null,
            com.voicebubble.openflow.core.speech.NpuHardwareManager.resolveNativeDeviceId(
                com.voicebubble.openflow.settings.HardwareAccelerator.GPU,
                lowMemoryPressure = false
            )
        )
    }

    @Test
    fun `DebugLogManager readTailText reads bounded tail cleanly aligned to newline`() {
        val tempFile = java.io.File.createTempFile("openflow_log_tail_test", ".txt")
        try {
            val sb = StringBuilder()
            for (i in 1..400) {
                sb.append("[2026-10-19 18:00:00.000] [INFO/Test] Log line number ").append(i).append('\n')
            }
            tempFile.writeText(sb.toString())

            val tail = com.voicebubble.openflow.core.debug.DebugLogManager.readTailText(tempFile, maxChars = 1_500)
            assertTrue(tail.contains("Log line number 400"))
            assertTrue(tail.startsWith("... [Showing last 1500 chars] ..."))
        } finally {
            tempFile.delete()
        }
    }
}
