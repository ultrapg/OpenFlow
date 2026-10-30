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

        val cleaned = reconcileTranscript(
            transcription = transcription
        )

        assertEquals("Ich komme gleich vorbei.", cleaned)
    }

    @Test
    fun `reconcileTranscript preserves legitimate words`() {
        val transcription = Transcription(
            text = "Wir fahren nach Ulm morgen.",
            words = listOf(
                Word("Wir", 0.0, 0.30),
                Word("fahren", 0.32, 0.70),
                Word("nach", 0.72, 0.96),
                Word("Ulm", 1.00, 1.60),
                Word("morgen.", 1.64, 2.10)
            ),
            duration = 2.2
        )

        val cleaned = reconcileTranscript(
            transcription = transcription
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
            customVocabulary = listOf("OpenFlow", "LiteRT", "Parakeet")
        )

        assertEquals("OpenFlow nutzt LiteRT und Parakeet.", cleaned)
    }

    @Test
    fun `ModelSettings toggle state matrix calculates RAM and disk accurately`() {
        assertEquals(1450, ModelSettings(earEnabled = true, punctEnabled = false).estimatedPeakRamMb)
        assertEquals(1197, ModelSettings(earEnabled = true, punctEnabled = false).activeDiskFootprintMb)

        assertEquals(1400, ModelSettings(earEnabled = false, punctEnabled = false).estimatedPeakRamMb)
        assertEquals(1187, ModelSettings(earEnabled = false, punctEnabled = false).activeDiskFootprintMb)

        assertEquals(1570, ModelSettings(earEnabled = true, punctEnabled = true).estimatedPeakRamMb)
        assertEquals(1479, ModelSettings(earEnabled = true, punctEnabled = true).activeDiskFootprintMb)

        val defaultSettings = ModelSettings()
        assertTrue(defaultSettings.vozEnabled)
        assertTrue(defaultSettings.earEnabled)
        assertFalse("Punct must be OFF by default", defaultSettings.punctEnabled)
        assertFalse("AI Post-Processing must be OFF by default", defaultSettings.gemmaEnabled)
        assertTrue("Disable Model Thinking must be ON by default for LFM3 1.7B", defaultSettings.gemmaDisableThinking)
        assertEquals(
            "Default selected LLM must be Liquid AI LFM3 1.7B",
            com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B,
            defaultSettings.selectedLlmModel
        )
        assertFalse(
            "High-end Gemma 4 E2B must keep thinking enabled by default",
            ModelSettings(selectedLlmModel = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B).gemmaDisableThinking
        )
        assertTrue("Gemma 4 E2B must be marked experimental", com.voicebubble.openflow.settings.DesertAntModel.GEMMA_4_E2B.isExperimental)
        assertEquals(
            com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            defaultSettings.gemmaPromptPreset
        )
        assertEquals(1450, defaultSettings.estimatedPeakRamMb)
        assertEquals(1197, defaultSettings.activeDiskFootprintMb)

        // Default LLM (Liquid AI LFM3 1.7B: 1,375 MB disk, +800 MB incremental RAM)
        assertEquals(2250, ModelSettings(earEnabled = true, punctEnabled = false, gemmaEnabled = true).estimatedPeakRamMb)
        assertEquals(2572, ModelSettings(earEnabled = true, punctEnabled = false, gemmaEnabled = true).activeDiskFootprintMb)

        // Punct is never loaded into RAM alongside an active LLM (zero double-loading)
        assertEquals(2250, ModelSettings(earEnabled = true, punctEnabled = true, gemmaEnabled = true).estimatedPeakRamMb)
        assertEquals(2854, ModelSettings(earEnabled = true, punctEnabled = true, gemmaEnabled = true).activeDiskFootprintMb)

        // Flagship Gemma 4 E2B (2,468 MB disk, +850 MB incremental RAM)
        val gemma4Settings = ModelSettings(
            earEnabled = true,
            punctEnabled = false,
            gemmaEnabled = true,
            selectedLlmModel = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
        )
        assertEquals(2300, gemma4Settings.estimatedPeakRamMb)
        assertEquals(3665, gemma4Settings.activeDiskFootprintMb)

        // Ultra-compact Qwen 3 0.6B Lite (329 MB disk, +350 MB incremental RAM)
        val qwenLiteSettings = ModelSettings(
            earEnabled = true,
            punctEnabled = false,
            gemmaEnabled = true,
            selectedLlmModel = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B_LITE
        )
        assertEquals(1800, qwenLiteSettings.estimatedPeakRamMb)
        assertEquals(1526, qwenLiteSettings.activeDiskFootprintMb)

        // Strict single-LLM exclusivity in enabledModels() and isModelEnabled()
        for (choice in com.voicebubble.openflow.settings.LlmModelChoice.entries) {
            val singleActive = ModelSettings(earEnabled = true, gemmaEnabled = true, selectedLlmModel = choice)
            val enabledLlms = singleActive.enabledModels().filter { it.isLlmModel }
            assertEquals("Strictly 1 LLM may be enabled at a time", listOf(choice.desertAntModel), enabledLlms)
            for (other in com.voicebubble.openflow.settings.LlmModelChoice.entries) {
                assertEquals(other == choice, singleActive.isModelEnabled(other.desertAntModel))
            }
        }

        assertEquals(
            listOf(
                com.voicebubble.openflow.settings.DesertAntModel.VOZ,
                com.voicebubble.openflow.settings.DesertAntModel.EAR
            ),
            defaultSettings.enabledModels()
        )
        assertEquals(
            listOf(
                com.voicebubble.openflow.settings.DesertAntModel.VOZ,
                com.voicebubble.openflow.settings.DesertAntModel.EAR,
                com.voicebubble.openflow.settings.DesertAntModel.PUNCT
            ),
            ModelSettings(earEnabled = true, punctEnabled = true).enabledModels()
        )

        for (model in com.voicebubble.openflow.settings.DesertAntModel.entries) {
            assertEquals(
                "expectedBytes mismatch for $model",
                com.voicebubble.openflow.settings.ModelDownloadManager.modelTotalBytes(model),
                model.expectedBytes
            )
            assertTrue(
                "Missing storage revision for $model",
                com.voicebubble.openflow.settings.DesertAntModelStorage.modelRevision(model).startsWith("v")
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
    fun `splitAudioIntoSmartChunks never leaves a tiny orphan tail chunk`() {
        val max = 20 * 16_000
        val min = 11 * 16_000
        // Just a few hundred samples over the limit used to produce a ~0.01s orphan trailing chunk
        for (extra in listOf(1, 100, 400, 1_279, 4_000, 7_999)) {
            val clip = FloatArray(max + extra) { 0.2f }
            val chunks = com.voicebubble.openflow.core.speech.splitAudioIntoSmartChunks(
                samples = clip,
                maxChunkSamples = max,
                minChunkSamples = min
            )
            assertEquals("extra=$extra must not lose samples", clip.size, chunks.sumOf { it.size })
            assertTrue("extra=$extra chunk exceeds max", chunks.all { it.size <= max })
            assertTrue(
                "extra=$extra produced an orphan tail of ${chunks.last().size} samples",
                chunks.size == 1 || chunks.last().size >= 8_000
            )
        }
    }

    @Test
    fun `applyCustomVocabulary is stable across repeated calls using cached patterns`() {
        val vocab = listOf("C++", "OpenFlow", "a.b")
        val text = "ich nutze c++ und openflow, aber nicht axb"
        val first = com.voicebubble.openflow.core.speech.applyCustomVocabulary(text, vocab)
        val second = com.voicebubble.openflow.core.speech.applyCustomVocabulary(text, vocab)
        assertEquals(first, second)
        assertTrue(first.contains("C++"))
        assertTrue(first.contains("OpenFlow"))
        // "a.b" must be matched literally (regex-escaped), never as the wildcard pattern a.b
        assertTrue(first.contains("axb"))
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

    @Test
    fun `BootAndRestartReceiver parseRecentCrashTimestamps filters crashes within rolling 60s window`() {
        val now = 1_000_000L
        val raw = "${now - 120_000L},${now - 45_000L},${now - 10_000L},invalid,${now - 1_000L}"
        val recent = com.voicebubble.openflow.overlay.BootAndRestartReceiver.parseRecentCrashTimestamps(
            raw = raw,
            nowMs = now,
            windowMs = 60_000L
        )
        assertEquals(3, recent.size)
        assertEquals(listOf(now - 45_000L, now - 10_000L, now - 1_000L), recent)
        assertTrue(com.voicebubble.openflow.overlay.BootAndRestartReceiver.parseRecentCrashTimestamps(null, now).isEmpty())
    }

    @Test
    fun `FloatingBubbleService computeShouldShowBubble hides on screen off and restores when screen on and keyboard visible`() {
        // Screen off -> always hidden even if keyboard flag was true
        assertFalse(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldShowBubble(
                isScreenInteractive = false,
                showOnlyOnInputs = true,
                isKeyboardOnScreen = true,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                isDeviceLocked = false
            )
        )

        // Screen on + keyboard visible -> always shown immediately
        assertTrue(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldShowBubble(
                isScreenInteractive = true,
                showOnlyOnInputs = true,
                isKeyboardOnScreen = true,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                isDeviceLocked = false
            )
        )

        // Screen on + keyboard hidden + showOnlyOnInputs=true -> hidden when IDLE
        assertFalse(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldShowBubble(
                isScreenInteractive = true,
                showOnlyOnInputs = true,
                isKeyboardOnScreen = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                isDeviceLocked = false
            )
        )

        // Screen on + showOnlyOnInputs=false -> hidden on locked lockscreen when keyboard closed, visible once unlocked
        assertFalse(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldShowBubble(
                isScreenInteractive = true,
                showOnlyOnInputs = false,
                isKeyboardOnScreen = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                isDeviceLocked = true
            )
        )
        assertTrue(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldShowBubble(
                isScreenInteractive = true,
                showOnlyOnInputs = false,
                isKeyboardOnScreen = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                isDeviceLocked = false
            )
        )
    }

    @Test
    fun `stitchChunkTranscripts repairs mid-sentence splits and hesitation artifacts in English`() {
        val stitched = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "Optimize it so that, uh, uh, the.",
                "Uh, like chunking doesn't mess up the text.",
                "That is spoken.",
                "Or at least make it as good as, uh, possible."
            ),
            language = "en"
        )
        assertEquals(
            "Optimize it so that the like chunking doesn't mess up the text that is spoken or at least make it as good as possible.",
            stitched
        )

        // Contrastive / relative connectors convert synthetic period to comma
        val withRelative = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "When a pause is detected, send the finished chunk right away.",
                "Which makes long dictations feel almost instant."
            ),
            language = "en"
        )
        assertEquals(
            "When a pause is detected, send the finished chunk right away, which makes long dictations feel almost instant.",
            withRelative
        )

        // Boundary 1-word and 2-word repetition deduplication
        val deduplicated = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "I want to go to the.",
                "To the store today.",
                "Today and buy milk."
            ),
            language = "en"
        )
        assertEquals("I want to go to the store today and buy milk.", deduplicated)

        // Legitimate separate sentences preserve their period and capitalization
        val separateSentences = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "I finished testing the new version.",
                "It works much better on my phone now."
            ),
            language = "en"
        )
        assertEquals("I finished testing the new version. It works much better on my phone now.", separateSentences)
    }

    @Test
    fun `stitchChunkTranscripts reconciles German clauses while preserving German noun capitalization`() {
        val germanSubordinate = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "Ich möchte heute ein neues Release machen.",
                "Weil das Chunking jetzt viel besser funktioniert.",
                "Und die Satzzeichen zwischen den Teilen angepasst werden."
            ),
            language = "de"
        )
        assertEquals(
            "Ich möchte heute ein neues Release machen, weil das Chunking jetzt viel besser funktioniert und die Satzzeichen zwischen den Teilen angepasst werden.",
            germanSubordinate
        )

        // Dangling German article before a German noun keeps the noun capitalized
        val germanNounAfterArticle = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "Wir arbeiten an der.",
                "Optimierung für das neue Update."
            ),
            language = "de"
        )
        assertEquals("Wir arbeiten an der Optimierung für das neue Update.", germanNounAfterArticle)

        // Dangling German article before an attributive adjective + noun lowercases the adjective
        val germanAdjectiveBeforeNoun = com.voicebubble.openflow.core.speech.stitchChunkTranscripts(
            chunks = listOf(
                "Wir arbeiten an der.",
                "Neuen Version von OpenFlow."
            ),
            language = "de",
            customVocabulary = listOf("OpenFlow")
        )
        assertEquals("Wir arbeiten an der neuen Version von OpenFlow.", germanAdjectiveBeforeNoun)
    }

    @Test
    fun `prepareChunkForInference adds silence runway and tapers boundary edges`() {
        val raw = FloatArray(8_000) { 0.2f }
        val prepared = com.voicebubble.openflow.core.speech.prepareChunkForInference(
            samples = raw,
            minSilencePadSamples = 3_200,
            edgeFadeSamples = 160
        )
        assertEquals(8_000 + 6_400, prepared.size)
        // Leading and trailing 3,200 samples are pure zero silence runway
        assertEquals(0.0f, prepared[0], 1e-6f)
        assertEquals(0.0f, prepared[3_199], 1e-6f)
        assertEquals(0.0f, prepared[prepared.lastIndex], 1e-6f)
        // First sample after leading pad is tapered smoothly rather than a step jump to 0.2f
        assertTrue(prepared[3_200] in 0.0005f..0.01f)
        assertEquals(0.2f, prepared[3_200 + 200], 1e-5f)

        // Asymmetric Conformer-Transducer runway + minimum 1.60s (25,600 sample) length for short final tails
        val shortTail = FloatArray(4_800) { 0.05f } // 300 ms single-word tail
        val fullPrepared = com.voicebubble.openflow.core.speech.prepareChunkForInference(
            samples = shortTail,
            minLeadSilencePadSamples = 5_120,
            minTailSilencePadSamples = 10_240,
            minTotalSamples = 25_600
        )
        assertEquals(25_600, fullPrepared.size)
        // At least 640 ms (10,240 samples) of trailing silence after the word so TDT lookahead flushes the last word
        for (i in (5_120 + 4_800) until fullPrepared.size) {
            assertEquals(0.0f, fullPrepared[i], 1e-6f)
        }
    }

    @Test
    fun `conditionAudioWhenClearDisabled boosts quiet trailing words without amplifying silence or clipping`() {
        // Simulate 1.0s normal speech (0.14) + 0.6s quiet trailing words at end of sentence (0.03) + 0.5s silence
        val sr = 16_000
        val raw = FloatArray((2.1 * sr).toInt()) { idx ->
            val sign = if (idx % 2 == 0) 1f else -1f
            when {
                idx < sr -> 0.14f * sign + 0.004f // normal start with slight DC offset
                idx < (1.6 * sr).toInt() -> 0.03f * sign + 0.004f // quiet sentence-final words (-13 dB drop)
                else -> 0.0f // trailing silence runway
            }
        }
        val conditioned = com.voicebubble.openflow.core.speech.conditionAudioWhenClearDisabled(raw)
        assertEquals(raw.size, conditioned.size)
        assertTrue(conditioned.all { it.isFinite() && kotlin.math.abs(it) <= 0.97f })

        // Quiet trailing word region (1.2s..1.5s) must be significantly boosted (> 2x) toward normal speech level
        var rawTrailingSumSq = 0.0
        var condTrailingSumSq = 0.0
        val checkStart = (1.2 * sr).toInt()
        val checkEnd = (1.5 * sr).toInt()
        for (i in checkStart until checkEnd) {
            rawTrailingSumSq += (raw[i] - 0.004f) * (raw[i] - 0.004f)
            condTrailingSumSq += conditioned[i] * conditioned[i]
        }
        val rawRms = kotlin.math.sqrt(rawTrailingSumSq / (checkEnd - checkStart))
        val condRms = kotlin.math.sqrt(condTrailingSumSq / (checkEnd - checkStart))
        assertTrue("Expected quiet trailing words to be boosted >= 2.2x (raw=$rawRms, cond=$condRms)", condRms >= rawRms * 2.2)

        // Trailing zero-padded silence runway remains pure zero
        assertEquals(0.0f, conditioned.last(), 1e-6f)
    }

    @Test
    fun `reconcileTranscript preserves full detokenized text even if words timestamps omit last token`() {
        // Simulate Voz returning complete sentence in `text` while `words` timestamp alignment missed the final word
        val transcription = Transcription(
            text = "Optimize it so that, uh, the end never gets cut off.",
            words = listOf(
                Word("Optimize", 0.0, 0.4),
                Word("it", 0.4, 0.5),
                Word("so", 0.5, 0.6),
                Word("that,", 0.6, 0.8),
                Word("uh,", 0.8, 1.0),
                Word("the", 1.0, 1.1),
                Word("end", 1.1, 1.3),
                Word("never", 1.3, 1.5),
                Word("gets", 1.5, 1.7),
                Word("cut", 1.7, 1.9)
                // "off." omitted from timestamped words list
            ),
            duration = 2.0
        )
        val result = reconcileTranscript(
            transcription = transcription
        )
        assertEquals("Optimize it so that the end never gets cut off.", result)
    }

    @Test
    fun `UnigramSentencePieceTokenizer parses tokenizer json and segments words via Viterbi`() {
        val tempFile = java.io.File.createTempFile("punct_tokenizer_test", ".json")
        try {
            tempFile.writeText(
                """
                {
                  "version": "1.0",
                  "model": {
                    "type": "Unigram",
                    "unk_id": 3,
                    "vocab": [
                      ["<s>", 0.0],
                      ["<pad>", 0.0],
                      ["</s>", 0.0],
                      ["<unk>", 0.0],
                      ["\u2581hello", -4.2],
                      ["\u2581world", -4.5],
                      ["\u2581un", -3.1],
                      ["believ", -5.0],
                      ["able", -3.8],
                      ["\u2581u", -6.0],
                      ["n", -6.0]
                    ]
                  }
                }
                """.trimIndent()
            )

            val tokenizer = com.voicebubble.openflow.core.speech.UnigramSentencePieceTokenizer.fromTokenizerJson(tempFile)
            assertEquals(11, tokenizer.vocabSize)

            // Exact whole-word match -> single token ID
            val helloIds = tokenizer.encodeWord("hello")
            assertEquals(listOf(4L), helloIds.toList())

            // Multi-subword Viterbi segmentation ("\u2581un" + "believ" + "able" = -11.9 beats "\u2581u" + "n" + ...)
            val unbelievableIds = tokenizer.encodeWord("unbelievable")
            assertEquals(listOf(6L, 7L, 8L), unbelievableIds.toList())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `PunctuationRestorer applyPunctuationAndCasing fixes false mid-sentence periods and preserves German nouns`() {
        // 1. English false mid-sentence period removal + comma insertion + preserving "I" and custom vocab
        val enRawTokens = "I want to test this. Because the chunking split here. And I use openflow.".split(" ")
        val enParsed = enRawTokens.mapIndexed { idx, tok ->
            com.voicebubble.openflow.core.speech.parseWordToken(tok, idx, enRawTokens)
        }
        // Labels: 0=none, 1='.', 2=',', 3='?'
        // "this." had a false period -> model predicts 0 (none) so "Because" becomes "because"
        // "here." had a false period -> model predicts 2 (comma) so "here," and "And" becomes "and", while "I" stays "I"
        val enLabels = intArrayOf(
            0, // I
            0, // want
            0, // to
            0, // test
            0, // this (was "this.") -> false period removed!
            0, // Because -> lowercased to "because"
            0, // the
            0, // chunking
            0, // split
            2, // here (was "here.") -> converted to comma!
            0, // And -> lowercased to "and"
            0, // I -> stays "I"
            0, // use
            1  // openflow. -> period + custom vocab "OpenFlow"
        )
        val enRestored = com.voicebubble.openflow.core.speech.applyPunctuationAndCasing(
            words = enParsed,
            predictedLabels = enLabels,
            languageCode = "en",
            customVocabulary = listOf("OpenFlow")
        )
        assertEquals(
            "I want to test this because the chunking split here, and I use OpenFlow.",
            enRestored
        )

        // 2. German false mid-sentence period removal: lowercases verbs/conjunctions while keeping German nouns capitalized
        val deRawTokens = "Wir arbeiten an der. Optimierung von dem Programm. Weil es schneller läuft.".split(" ")
        val deParsed = deRawTokens.mapIndexed { idx, tok ->
            com.voicebubble.openflow.core.speech.parseWordToken(tok, idx, deRawTokens)
        }
        val deLabels = intArrayOf(
            0, // Wir
            0, // arbeiten
            0, // an
            0, // der (was "der.") -> false period removed!
            0, // Optimierung -> German noun (-ung after "der"), stays capitalized!
            0, // von
            0, // dem
            2, // Programm (was "Programm.") -> comma before subordinate clause!
            0, // Weil -> German conjunction, lowercased to "weil"!
            0, // es
            0, // schneller
            1  // läuft.
        )
        val deRestored = com.voicebubble.openflow.core.speech.applyPunctuationAndCasing(
            words = deParsed,
            predictedLabels = deLabels,
            languageCode = "de"
        )
        assertEquals(
            "Wir arbeiten an der Optimierung von dem Programm, weil es schneller läuft.",
            deRestored
        )
    }

    @Test
    fun `GemmaPromptPreset and ModelSettings resolve built-in and custom instructions safely`() {
        for (preset in com.voicebubble.openflow.settings.GemmaPromptPreset.entries) {
            assertEquals(preset, com.voicebubble.openflow.settings.GemmaPromptPreset.fromId(preset.id))
            assertEquals(preset, com.voicebubble.openflow.settings.GemmaPromptPreset.fromId(preset.id.uppercase()))
            assertTrue("Preset ${preset.id} must have non-blank default prompt", preset.defaultSystemPrompt.isNotBlank())
        }
        assertEquals(
            com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            com.voicebubble.openflow.settings.GemmaPromptPreset.fromId("non_existent_preset")
        )
        assertEquals(
            com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            com.voicebubble.openflow.settings.GemmaPromptPreset.fromId(null)
        )

        // Blank custom prompt falls back safely to CUSTOM.defaultSystemPrompt
        val blankCustom = ModelSettings(
            gemmaEnabled = true,
            gemmaPromptPreset = com.voicebubble.openflow.settings.GemmaPromptPreset.CUSTOM,
            gemmaCustomPrompt = "   "
        )
        assertEquals(
            com.voicebubble.openflow.settings.GemmaPromptPreset.CUSTOM.defaultSystemPrompt,
            blankCustom.resolvedGemmaTaskInstruction()
        )

        // Non-blank custom prompt is trimmed and returned directly
        val activeCustom = ModelSettings(
            gemmaEnabled = true,
            gemmaPromptPreset = com.voicebubble.openflow.settings.GemmaPromptPreset.CUSTOM,
            gemmaCustomPrompt = "  Rewrite as concise Markdown release notes.  "
        )
        assertEquals(
            "Rewrite as concise Markdown release notes.",
            activeCustom.resolvedGemmaTaskInstruction()
        )
    }

    @Test
    fun `GemmaTextProcessor buildSystemInstruction enforces anti-hallucination guardrails and custom flexibility`() {
        val builtInInstruction = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "",
            languageCode = "de",
            customVocabulary = listOf("OpenFlow", "LiteRT-LM")
        )
        assertTrue(builtInInstruction.contains("German [de]"))
        assertTrue(builtInInstruction.contains("Never translate into another language"))
        assertTrue(builtInInstruction.contains("OpenFlow, LiteRT-LM"))
        assertTrue(builtInInstruction.contains("<transcript>"))
        assertTrue(builtInInstruction.contains("Numbers, dates & units"))
        assertTrue(builtInInstruction.contains("percentages"))
        assertTrue(builtInInstruction.contains("currencies"))
        assertTrue(builtInInstruction.contains("version numbers"))
        assertTrue(builtInInstruction.contains("new paragraph"))

        val customInstruction = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CUSTOM,
            customPrompt = "Translate the transcript into English and make it polite.",
            languageCode = "de",
            customVocabulary = emptyList()
        )
        assertTrue(customInstruction.contains("Translate the transcript into English and make it polite."))
        assertTrue(customInstruction.contains("unless the transformation instruction explicitly asks to translate"))
        assertFalse(customInstruction.contains("Never translate into another language"))
    }

    @Test
    fun `GemmaTextProcessor sanitizeGemmaOutput strips thinking channels chatter fences and preserves structure`() {
        val rawInput = "um so we need to ship version two point six point eight today and uh test the npu"

        // 1. Strips Gemma 4 <|channel>thought...<channel|> reasoning blocks and conversational preambles
        val withThoughtAndChatter = """
            <|channel>thought
            The user wants me to clean up vocal fillers from their dictation.
            <channel|>Sure! Here is the cleaned transcript:

            We need to ship version 2.6.8 today and test the NPU.
        """.trimIndent()
        assertEquals(
            "We need to ship version 2.6.8 today and test the NPU.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = withThoughtAndChatter,
                fallbackInput = rawInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // 2. Unwraps echoed <transcript>...</transcript> tags and preserves multi-line bullet lists
        val bulletOutput = """
            <transcript>
            - Ship version 2.6.8 today
            - Test NPU acceleration on Snapdragon
            </transcript>
        """.trimIndent()
        assertEquals(
            "- Ship version 2.6.8 today\n- Test NPU acceleration on Snapdragon",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = bulletOutput,
                fallbackInput = rawInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.STRUCTURED_NOTES
            )
        )

        // 3. Unwraps accidental outer quotes and code fences for standard presets
        assertEquals(
            "We need to ship version 2.6.8 today.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "```text\n\"We need to ship version 2.6.8 today.\"\n```",
                fallbackInput = rawInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CONCISE_DIRECT
            )
        )

        // 4. Falls back safely to rawInput on empty or runaway hallucinated output
        assertEquals(
            rawInput,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "<|channel>thought\n<channel|>   ",
                fallbackInput = rawInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
        assertEquals(
            "Hello world.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "Hallucinated text. ".repeat(80),
                fallbackInput = "Hello world.",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.STRICT_CLEANUP
            )
        )
    }

    @Test
    fun `GemmaTextProcessor splitLongTranscript splits cleanly at sentence boundaries`() {
        val shortText = "First sentence. Second sentence."
        assertEquals(
            listOf(shortText),
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.splitLongTranscript(shortText, maxCharsPerSegment = 200)
        )

        val s1 = "This is the first complete spoken sentence in a long dictation."
        val s2 = "Here is the second spoken sentence with plenty of details."
        val s3 = "Finally, this is the third sentence at the end."
        val combined = "$s1 $s2 $s3"
        val split = com.voicebubble.openflow.core.speech.GemmaTextProcessor.splitLongTranscript(
            text = combined,
            maxCharsPerSegment = 130
        )
        assertEquals(2, split.size)
        assertEquals("$s1 $s2", split[0])
        assertEquals(s3, split[1])
    }

    @Test
    fun `GemmaTextProcessor classifyAppPackage and analyzeFieldContext detect app styles and cursor states`() {
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.EMAIL,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("com.google.android.gm")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.EMAIL,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("ch.protonmail.android")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.WORK_MESSAGING,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("com.Slack")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.PERSONAL_MESSAGING,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("com.whatsapp")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.CODE_AND_DEV,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("com.termux")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.NOTES_AND_DOCS,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("md.obsidian")
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.AppContextCategory.GENERAL,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.classifyAppPackage("com.voicebubble.openflow")
        )

        // Open clause on same line -> mid-sentence continuation
        val midClause = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "com.whatsapp",
            textBeforeCursor = "I spoke with Sarah and "
        )
        assertEquals(com.voicebubble.openflow.core.speech.AppContextCategory.PERSONAL_MESSAGING, midClause.appCategory)
        assertTrue(midClause.isMidSentenceContinuation)
        assertEquals(null, midClause.continuingNumberedListFrom)

        // Completed sentence on same line -> new sentence (keep capitalized)
        val afterPeriod = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "com.whatsapp",
            textBeforeCursor = "I spoke with Sarah. "
        )
        assertFalse(afterPeriod.isMidSentenceContinuation)

        // Fresh line below numbered list item -> continue numbering at #3
        val listNewLine = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "md.obsidian",
            textBeforeCursor = "1. Build release APK\n2. Run unit tests\n"
        )
        assertEquals(2, listNewLine.continuingNumberedListFrom)
        assertFalse(listNewLine.isMidSentenceContinuation)

        // Same line inside numbered list item -> mid-sentence continuation, NOT a new list item
        val listSameLine = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "md.obsidian",
            textBeforeCursor = "1. Test NPU latency "
        )
        assertEquals(null, listSameLine.continuingNumberedListFrom)
        assertTrue(listSameLine.isMidSentenceContinuation)

        // Editor already auto-inserted bare "3. " marker -> neither mid-sentence lowercase nor duplicate marker
        val autoInsertedMarker = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "md.obsidian",
            textBeforeCursor = "1. Build APK\n2. Run tests\n3. "
        )
        assertEquals(null, autoInsertedMarker.continuingNumberedListFrom)
        assertFalse(autoInsertedMarker.isMidSentenceContinuation)

        // Fresh line below bullet item -> continue bullet list
        val bulletNewLine = com.voicebubble.openflow.core.speech.GemmaTextProcessor.analyzeFieldContext(
            packageName = "notion.id",
            textBeforeCursor = "- Check NPU memory\n"
        )
        assertTrue(bulletNewLine.isContinuingBulletList)
        assertFalse(bulletNewLine.isMidSentenceContinuation)
    }

    @Test
    fun `GemmaTextProcessor preNormalizeSpokenConstructs converts spoken emails and layout cues without prose false positives`() {
        // Unambiguous spoken email addresses (EN + DE)
        assertEquals(
            "Send logs to john.doe@gmail.com right away.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Send logs to John dot Doe at Gmail dot com right away."
            )
        )
        assertEquals(
            "Schreib bitte an max.mustermann@firma.de.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Schreib bitte an Max punkt Mustermann at Firma punkt de."
            )
        )
        assertEquals(
            "Reach us via support@openflow.dev anytime.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Reach us via support at openflow dot dev anytime."
            )
        )

        // Guarded non-email prepositions ("at amazon dot com", "look at github dot com") MUST NOT become emails
        assertEquals(
            "I bought it at amazon dot com yesterday.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "I bought it at amazon dot com yesterday."
            )
        )
        assertEquals(
            "Look at github dot com for the source.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Look at github dot com for the source."
            )
        )

        // Spoken layout & dash commands
        assertEquals(
            "Thanks for the update.\n\nLet's ship today.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Thanks for the update. New paragraph. Let's ship today."
            )
        )
        assertEquals(
            "Best regards,\nAlex",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Best regards, new line, Alex"
            )
        )
        assertEquals(
            "Latency dropped — under 120 ms.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "Latency dropped, em dash, under 120 ms."
            )
        )

        // Guarded literal prose noun phrases MUST stay untouched
        assertEquals(
            "I wrote a new paragraph in the essay.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "I wrote a new paragraph in the essay."
            )
        )
        assertEquals(
            "We launched a new line of products.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "We launched a new line of products."
            )
        )
        assertEquals(
            "The next paragraph explains the API.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(
                "The next paragraph explains the API."
            )
        )
    }

    @Test
    fun `GemmaTextProcessor Wispr Flow context hints refusal guards and continuation casing work end to end`() {
        val emailFieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(
            appCategory = com.voicebubble.openflow.core.speech.AppContextCategory.EMAIL,
            continuingNumberedListFrom = 2
        )
        val enEmailPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            languageCode = "en",
            fieldContext = emailFieldContext
        )
        assertTrue(enEmailPrompt.contains("Course-correction & backtracks"))
        assertTrue(enEmailPrompt.contains("Smart list detection"))
        assertTrue(enEmailPrompt.contains("Email Composer"))
        assertTrue(enEmailPrompt.contains("start numbering at 3"))
        assertTrue(enEmailPrompt.contains("Example 1 Output: Hey Alex, let's meet Wednesday at 3:30 PM"))

        // Unprompted chatbot refusal falls back safely to raw transcript
        val questionInput = "Hey John, what time is our flight to Munich tomorrow?"
        assertEquals(
            questionInput,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "I'm sorry, I don't have access to your personal schedule or flight information.",
                fallbackInput = questionInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
        assertEquals(
            questionInput,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "As an AI assistant, I cannot check your flight time.",
                fallbackInput = questionInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // Legitimate user dictation mentioning "dont have access" is KEPT (not mistaken for AI refusal!)
        val legitAccessInput = "um hey team i dont have access to the staging server right now"
        assertEquals(
            "Hey team, I don't have access to the staging server right now.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "Hey team, I don't have access to the staging server right now.",
                fallbackInput = legitAccessInput,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // Echoed preceding-segment continuity tail is stripped cleanly
        assertEquals(
            "3. Update GitHub release notes",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "2. Verify 6 GB RAM headroom\n3. Update GitHub release notes",
                fallbackInput = "and third update github release notes",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                precedingSegmentTail = "1. Test NPU latency\n2. Verify 6 GB RAM headroom"
            )
        )

        // Mid-sentence cursor continuation casing
        assertEquals(
            "she said the build is green.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "She said the build is green.",
                languageCode = "en"
            )
        )
        assertEquals(
            "I'm heading out now.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "I'm heading out now.",
                languageCode = "en"
            )
        )
        assertEquals(
            "Wednesday at 3:30 PM works.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "Wednesday at 3:30 PM works.",
                languageCode = "en"
            )
        )
        assertEquals(
            "OpenFlow runs on the NPU.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "OpenFlow runs on the NPU.",
                languageCode = "en",
                customVocabulary = listOf("OpenFlow")
            )
        )
        assertEquals(
            "Optimierung der NPU läuft stabil.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "Optimierung der NPU läuft stabil.",
                languageCode = "de"
            )
        )
        assertEquals(
            "weil das Update schneller läuft.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyCursorContinuationCasing(
                text = "Weil das Update schneller läuft.",
                languageCode = "de"
            )
        )

        // Multi-segment list joining uses single newline between contiguous list items
        assertEquals(
            "1. Test NPU\n2. Check RAM\n3. Ship release",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.joinFormattedSegments(
                segments = listOf("1. Test NPU\n2. Check RAM", "3. Ship release"),
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
    }

    @Test
    fun `GemmaTextProcessor disableThinking option adjusts token budget and system prompt while defaulting to off`() {
        val sampleTranscript = "um hey alex let's meet wednesday at three thirty pm to check the eight gigabyte ram headroom"

        val fastNoThinkTokens = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sampleTranscript,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = true,
            maxContextTokens = 4_096
        )
        val defaultThinkingTokens4k = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sampleTranscript,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            maxContextTokens = 4_096
        )
        val defaultThinkingTokens2k = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sampleTranscript,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            maxContextTokens = 2_048
        )

        assertTrue("No-thinking budget should stay tight for speed", fastNoThinkTokens in 96..256)
        assertEquals(fastNoThinkTokens + 640, defaultThinkingTokens4k)
        assertEquals(fastNoThinkTokens + 384, defaultThinkingTokens2k)

        val defaultInstruction = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false
        )
        val disabledThinkingInstruction = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = true
        )
        assertTrue(defaultInstruction.contains("Keep internal reasoning brief"))
        assertFalse(disabledThinkingInstruction.contains("Keep internal reasoning brief"))

        // Low-memory 1,536-token KV window keeps output budget safely bounded inside compact context
        val lowMemThinkingTokens = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sampleTranscript.repeat(6),
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            maxContextTokens = 1_536
        )
        assertTrue("1.5K low-mem KV window must cap output tokens <= 576, got $lowMemThinkingTokens", lowMemThinkingTokens <= 576)
    }

    @Test
    fun `GemmaTextProcessor system prompt cache key and language-tagged user prompt preserve multilingual KV cache hits`() {
        val emailCtx = com.voicebubble.openflow.core.speech.FlowFieldContext(
            appCategory = com.voicebubble.openflow.core.speech.AppContextCategory.EMAIL,
            packageName = "com.google.android.gm",
            isMidSentenceContinuation = false
        )
        // Mid-sentence continuation is handled deterministically post-generation and must not invalidate KV cache
        val emailMidSentenceCtx = emailCtx.copy(isMidSentenceContinuation = true)

        val keyA = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemPromptCacheKey(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "ignored unless custom",
            customVocabulary = listOf(" OpenFlow ", "NPU", "OpenFlow"),
            fieldContext = emailCtx,
            disableThinking = false
        )
        val keyB = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemPromptCacheKey(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "different unused custom prompt",
            customVocabulary = listOf("OpenFlow", "NPU"),
            fieldContext = emailMidSentenceCtx,
            disableThinking = false
        )
        assertEquals("Cache keys must match across identical normalized settings", keyA, keyB)

        val keyNoThink = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemPromptCacheKey(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "",
            customVocabulary = listOf("OpenFlow", "NPU"),
            fieldContext = emailCtx,
            disableThinking = true
        )
        org.junit.Assert.assertNotEquals("Toggling disableThinking must produce a distinct cache key", keyA, keyNoThink)

        // Language-universal cached system prompt includes both EN and DE mechanics + lang attribute guidance
        val universalPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            languageCode = null,
            fieldContext = emailCtx
        )
        assertTrue(universalPrompt.contains("lang/language attribute on <transcript>"))
        assertTrue(universalPrompt.contains("<transcript lang=\"de\" language=\"German\">"))

        // User prompt carries resolved Ear language without mutating the cached system prompt
        val deUserPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildUserPrompt(
            transcript = "ähm hallo lukas",
            languageCode = "de"
        )
        assertEquals("<transcript lang=\"de\" language=\"German\">\nähm hallo lukas\n</transcript>", deUserPrompt)

        // Output sanitizer cleanly unwraps <transcript lang="..." language="..."> if echoed by the LLM
        val unwrapped = com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
            rawOutput = "<transcript lang=\"de\" language=\"German\">Hallo Lukas.</transcript>",
            fallbackInput = "ähm hallo lukas",
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        assertEquals("Hallo Lukas.", unwrapped)
    }

    @Test
    fun `MemoryPressureReport tiers Gemma KV window and prevents Voz-Gemma co-residency OOMs on low-RAM phones`() {
        // 12 GB flagship with 3.2 GB free -> HIGH_HEADROOM, prompt cache allowed, Voz + Gemma co-residency allowed
        val flagshipHighRam = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 3_200L,
            systemTotalMb = 11_500L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 48L,
            jvmMaxMb = 512L,
            jvmFreeHeadroomMb = 464L,
            nativeAllocatedMb = 1_800L
        )
        assertEquals(com.voicebubble.openflow.core.speech.GemmaMemoryMode.HIGH_HEADROOM, flagshipHighRam.gemmaMemoryMode)
        assertTrue(flagshipHighRam.canPrewarmGemmaPromptCache)
        assertTrue(flagshipHighRam.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = true, gemmaAlreadyLoaded = false))
        assertTrue(flagshipHighRam.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = false, gemmaAlreadyLoaded = true))

        // 8 GB phone with 1,650 MB free -> MODERATE_HEADROOM (2K KV window), prompt cache allowed when Gemma loaded,
        // but loading Gemma ON TOP of Voz (or reloading Voz on top of Gemma) is blocked to prevent LMKD OOM!
        val midRangeModerateRam = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 1_650L,
            systemTotalMb = 7_600L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 64L,
            jvmMaxMb = 256L,
            jvmFreeHeadroomMb = 192L,
            nativeAllocatedMb = 1_400L
        )
        assertEquals(com.voicebubble.openflow.core.speech.GemmaMemoryMode.MODERATE_HEADROOM, midRangeModerateRam.gemmaMemoryMode)
        assertTrue(midRangeModerateRam.canPrewarmGemmaPromptCache)
        assertFalse(midRangeModerateRam.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = true, gemmaAlreadyLoaded = false))
        assertFalse(midRangeModerateRam.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = false, gemmaAlreadyLoaded = true))
        assertTrue(midRangeModerateRam.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = true, gemmaAlreadyLoaded = true))

        // 6 GB phone with 980 MB free -> LOW_MEMORY (1.5K KV window), no idle prompt cache, no co-residency
        val lowRamPhone = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 980L,
            systemTotalMb = 5_700L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 80L,
            jvmMaxMb = 256L,
            jvmFreeHeadroomMb = 176L,
            nativeAllocatedMb = 1_200L
        )
        assertEquals(com.voicebubble.openflow.core.speech.GemmaMemoryMode.LOW_MEMORY, lowRamPhone.gemmaMemoryMode)
        assertFalse(lowRamPhone.canPrewarmGemmaPromptCache)
        assertFalse(lowRamPhone.canKeepVozAndGemmaCoResident(vozAlreadyLoaded = true, gemmaAlreadyLoaded = true))
    }

    @Test
    fun `conditionAudioWhenClearDisabled inPlace mode matches allocating mode bit-for-bit without extra array`() {
        val raw = FloatArray(16_000) { idx ->
            if (idx < 12_000) ((idx % 17) - 8) * 0.01f + 0.003f else 0f
        }
        val expected = com.voicebubble.openflow.core.speech.conditionAudioWhenClearDisabled(raw, inPlace = false)
        val inPlaceBuf = raw.copyOf()
        val returned = com.voicebubble.openflow.core.speech.conditionAudioWhenClearDisabled(inPlaceBuf, inPlace = true)

        assertTrue("inPlace=true must return the exact same FloatArray instance", returned === inPlaceBuf)
        org.junit.Assert.assertArrayEquals(expected, returned, 1e-6f)
    }

    @Test
    fun `CompactTokenIdTable rehashes cleanly and matches zero-allocation substring range queries`() {
        val table = com.voicebubble.openflow.core.speech.CompactTokenIdTable(initialCapacity = 16)
        for (i in 0 until 5_000) {
            table.putIfAbsent("▁piece_$i", i)
        }
        // Duplicate key keeps original ID
        table.putIfAbsent("▁piece_42", 99_999)
        assertEquals(5_000, table.size)
        assertEquals(42, table.get("▁piece_42"))

        val composite = "prefix:▁piece_4999:suffix"
        val start = "prefix:".length
        val end = start + "▁piece_4999".length
        assertEquals(4_999, table.getRange(composite, start, end))
        assertEquals(499, table.getRange(composite, start, end - 1))
        val missing = "prefix:▁piece_9999:suffix"
        assertEquals(-1, table.getRange(missing, start, start + "▁piece_9999".length))
    }

    @Test
    fun `LlmModelChoice catalog aligns with downloadable artifacts and optimizes prompts per model tier`() {
        assertEquals(6, com.voicebubble.openflow.settings.LlmModelChoice.entries.size)
        assertEquals(
            com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B,
            com.voicebubble.openflow.settings.LlmModelChoice.fromId("unknown_model")
        )
        assertEquals(
            com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B,
            com.voicebubble.openflow.settings.LlmModelChoice.fromId(null)
        )

        for (choice in com.voicebubble.openflow.settings.LlmModelChoice.entries) {
            assertEquals(choice, com.voicebubble.openflow.settings.LlmModelChoice.fromId(choice.id))
            assertEquals(choice, com.voicebubble.openflow.settings.LlmModelChoice.fromDesertAntModel(choice.desertAntModel))
            assertTrue("${choice.id} must map to an isLlmModel entry", choice.desertAntModel.isLlmModel)

            val spec = com.voicebubble.openflow.settings.ModelDownloadManager.MODEL_SPECS[choice.desertAntModel]
            assertTrue("Missing download spec for ${choice.id}", spec != null)
            val artifacts = spec!!.second
            assertEquals(1, artifacts.size)
            assertEquals(choice.modelFileName, artifacts.single().relativePath)
            assertTrue(choice.modelFileName.endsWith(".litertlm"))
        }

        // Per-model default CLEAN_AND_POLISH optimization checks
        val compactPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
        )
        assertTrue(compactPrompt.contains("under 15 words"))
        assertTrue(compactPrompt.contains("Hey Alex, can we meet Wednesday at 3:30 PM for two things:"))
        assertFalse("Compact 0.6B prompt should stay concise without heavy 2-example wall", compactPrompt.contains("Example 2 Input:"))

        val directInstructPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_2_5_1_5B
        )
        assertTrue(directInstructPrompt.contains("NOT a conversational chatbot"))
        assertFalse("Non-thinking Qwen 2.5 must never receive internal reasoning hints", directInstructPrompt.contains("internal reasoning"))

        // Qwen 2.5 1.5B must allocate zero thinking token headroom even when disableThinking=false
        val sample = "um let's ship version two point eight point zero today"
        val qwen25DefaultTokens = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sample,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = false,
            maxContextTokens = 4_096,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_2_5_1_5B
        )
        val qwen25NoThinkTokens = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computeMaxOutputTokens(
            inputText = sample,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = true,
            maxContextTokens = 4_096,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_2_5_1_5B
        )
        assertEquals("Non-thinking Qwen 2.5 must use tight direct token budget", qwen25NoThinkTokens, qwen25DefaultTokens)

        // Switching LLM model must invalidate cached system prompt keys
        val keyQwen06 = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemPromptCacheKey(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "",
            customVocabulary = emptyList(),
            fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(),
            disableThinking = false,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
        )
        val keyGemma4 = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemPromptCacheKey(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            customPrompt = "",
            customVocabulary = emptyList(),
            fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(),
            disableThinking = false,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
        )
        org.junit.Assert.assertNotEquals(keyQwen06, keyGemma4)
    }

    @Test
    fun `sanitizeGemmaOutput strips Qwen think blocks unclosed think tags and ChatML special tokens`() {
        val rawFallback = "um call lukas at three thirty pm"

        // Closed Qwen 3 <think>...</think> + <|im_end|>
        assertEquals(
            "Call Lukas at 3:30 PM.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "<think>\nRemove um and format time.\n</think>\n\nCall Lukas at 3:30 PM.<|im_end|>",
                fallbackInput = rawFallback,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // Truncated unclosed <think>... falls back cleanly to raw input instead of leaking reasoning
        assertEquals(
            rawFallback,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "<think>\nLet me analyze every word of the user's dictation carefully...",
                fallbackInput = rawFallback,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
    }

    @Test
    fun `MemoryPressureReport allows compact Qwen co-residency on mid-range phones while staging Gemma 4 safely`() {
        // 6 GB phone with 1,350 MB free while Voz is already loaded:
        // Qwen 3 0.6B Lite (450 MB) and Qwen 3 0.6B (650 MB) fit comfortably alongside Voz,
        // whereas 2.5 GB Gemma 4 E2B must stage sequentially.
        val midPhone = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 1_350L,
            systemTotalMb = 5_800L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 60L,
            jvmMaxMb = 256L,
            jvmFreeHeadroomMb = 196L,
            nativeAllocatedMb = 1_300L
        )
        assertTrue(
            midPhone.canKeepVozAndLlmCoResident(
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B_LITE,
                vozAlreadyLoaded = true,
                llmAlreadyLoaded = false
            )
        )
        assertTrue(
            midPhone.canKeepVozAndLlmCoResident(
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B,
                vozAlreadyLoaded = true,
                llmAlreadyLoaded = false
            )
        )
        assertFalse(
            midPhone.canKeepVozAndLlmCoResident(
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B,
                vozAlreadyLoaded = true,
                llmAlreadyLoaded = false
            )
        )
    }

    @Test
    fun `Pillar 1 zero-LLM fast path bypasses clean concise speech while catching every edit trigger`() {
        val cleanEn = "Can you review the pull request before lunch?"
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.canBypassLlmFastPath(
                text = cleanEn,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
        assertEquals(
            "can you review the OpenFlow pull request before lunch?",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyDeterministicFastPathCleanup(
                text = "Can you review the openflow pull request before lunch?",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                languageCode = "en",
                customVocabulary = listOf("OpenFlow"),
                fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(
                    isMidSentenceContinuation = true
                )
            )
        )

        val cleanDe = "Ich schicke dir die Unterlagen heute Abend."
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.canBypassLlmFastPath(
                text = cleanDe,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.STRICT_CLEANUP
            )
        )

        // Triggers that MUST route to the LLM (never bypass):
        val mustUseLlmSamples = listOf(
            "Um, can you review the pull request?",
            "Ähm, ich schicke dir die Unterlagen.",
            "I I think the build is green.",
            "Let's meet Tuesday, actually Wednesday.",
            "See you tomorrow at five pm.",
            "Wir treffen uns um drei Uhr.",
            "Please check the npu benchmark.",
            "Because of the traffic. We arrived late.",
            "can you review the pull request" // missing capitalization & terminal punctuation
        )
        for (dirty in mustUseLlmSamples) {
            assertFalse(
                "Expected LLM path for: $dirty",
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.canBypassLlmFastPath(
                    text = dirty,
                    preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
                )
            )
        }

        // Email salutation requires paragraph break after greeting ("Hi Sarah,\n\nThanks...")
        assertFalse(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.canBypassLlmFastPath(
                text = "Hi Sarah, thanks for the update.",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(
                    appCategory = com.voicebubble.openflow.core.speech.AppContextCategory.EMAIL
                )
            )
        )
    }

    @Test
    fun `Pillar 2 adaptive smart thinking skips reasoning on fillers and numbers but enables on repairs and lists`() {
        val defaults = ModelSettings()
        assertTrue("Default LFM3 model must disable thinking by default", defaults.gemmaDisableThinking)
        assertFalse("High-end Gemma 4 E2B keeps Adaptive Smart Thinking enabled by default", com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B.defaultDisableThinking)
        assertTrue("gemmaAdaptiveThinking must be enabled by default", defaults.gemmaAdaptiveThinking)

        // Everyday fillers + spoken numbers/times -> skip <think> (0 thought tokens!)
        assertFalse(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = "um hey Lukas let's meet tomorrow at three thirty pm and bring twenty euros",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
            )
        )

        // Mid-sentence self-correction -> dynamically enable <think>!
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = "send the invite to Thomas, wait no scratch that, send it to Hannah instead",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
            )
        )

        // Spoken list sequence -> dynamically enable <think>!
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = "we have three priorities first fix login second test payments third tag release",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
            )
        )

        // Structural presets enable thinking on thinking models, but stay false on direct Qwen 2.5
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = "Quick note about tomorrow.",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.STRUCTURED_NOTES,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_1_7B
            )
        )
        assertFalse(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = "send it to Thomas, wait no scratch that, Hannah",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.STRUCTURED_NOTES,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_2_5_1_5B
            )
        )
    }

    @Test
    fun `Pillar 3 streaming prefix overlap commits closed sentences and holds cross-chunk repairs`() {
        val p1 = "We finished testing the new audio pipeline."
        val c2Clean = "Everything looks ready for the release build."
        assertTrue(
            com.voicebubble.openflow.core.speech.StreamingDictationSession.canCommitStreamingPrefixForOverlap(
                prefixStitched = p1,
                lookaheadChunk = c2Clean,
                combinedStitched = "$p1 $c2Clean"
            )
        )

        // Cross-chunk English backtrack -> must hold prefix!
        val c2RepairEn = "Actually let's run one more regression test first."
        assertFalse(
            com.voicebubble.openflow.core.speech.StreamingDictationSession.canCommitStreamingPrefixForOverlap(
                prefixStitched = p1,
                lookaheadChunk = c2RepairEn,
                combinedStitched = "$p1 $c2RepairEn"
            )
        )

        // Cross-chunk German backtrack -> must hold prefix!
        val pDe = "Wir treffen uns am Dienstag."
        val c2RepairDe = "Oder besser am Mittwoch um zehn Uhr."
        assertFalse(
            com.voicebubble.openflow.core.speech.StreamingDictationSession.canCommitStreamingPrefixForOverlap(
                prefixStitched = pDe,
                lookaheadChunk = c2RepairDe,
                combinedStitched = "$pDe $c2RepairDe"
            )
        )

        // Unclosed prefix without terminal punctuation -> must hold prefix!
        assertFalse(
            com.voicebubble.openflow.core.speech.StreamingDictationSession.canCommitStreamingPrefixForOverlap(
                prefixStitched = "When we finish testing the new audio pipeline",
                lookaheadChunk = c2Clean,
                combinedStitched = "When we finish testing the new audio pipeline $c2Clean"
            )
        )

        assertEquals(
            "We finished testing the new audio pipeline. Everything looks ready for the release build.",
            com.voicebubble.openflow.core.speech.StreamingDictationSession.joinStreamingCleanedSegments(
                segments = listOf(p1, c2Clean),
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
    }

    @Test
    fun `Pillar 4 compact span-patch mode applies localized edits and falls back safely on bad anchors`() {
        val longProse = "Yesterday afternoon our engineering team reviewed the latency telemetry from the latest mobile build, " +
            "and um we noticed that prefill time dropped significantly across every single benchmark device while " +
            "battery consumption during twenty five minute dictation sessions stayed remarkably low on both flagship and mid-range phones."

        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldUseSpanPatchMode(
                segment = longProse,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext.NONE,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_1_7B
            )
        )
        assertFalse(
            "0.6B models should stay on direct rewrite rather than span-patch anchor formatting",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldUseSpanPatchMode(
                segment = longProse,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext.NONE,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
            )
        )

        // NONE fast-exit
        val noneRes = com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyVerifiedSpanEdits(
            rawOutput = "<think></think>\nNONE",
            sourceSegment = longProse
        )
        assertTrue(noneRes is com.voicebubble.openflow.core.speech.SpanPatchResult.NoChanges)

        // Verified multi-line span patch
        val patchOut = """
            and um we noticed => and we noticed
            twenty five minute => 25-minute
        """.trimIndent()
        val applied = com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyVerifiedSpanEdits(
            rawOutput = patchOut,
            sourceSegment = longProse
        )
        assertTrue(applied is com.voicebubble.openflow.core.speech.SpanPatchResult.Applied)
        val patchedText = (applied as com.voicebubble.openflow.core.speech.SpanPatchResult.Applied).text
        assertEquals(2, applied.editCount)
        assertTrue(patchedText.contains("and we noticed that prefill time"))
        assertTrue(patchedText.contains("during 25-minute dictation sessions"))

        // Hallucinated non-existent anchor -> safe FallbackRequired
        val badAnchor = com.voicebubble.openflow.core.speech.GemmaTextProcessor.applyVerifiedSpanEdits(
            rawOutput = "phrase that does not exist anywhere => replacement",
            sourceSegment = longProse
        )
        assertTrue(badAnchor is com.voicebubble.openflow.core.speech.SpanPatchResult.FallbackRequired)
    }

    @Test
    fun `Pillar 5 right-sized KV windows and streaming runaway repetition detector work accurately`() {
        val qwen06Windows = com.voicebubble.openflow.core.speech.GemmaTextProcessor.selectTokenWindowCandidates(
            memoryMode = com.voicebubble.openflow.core.speech.GemmaMemoryMode.HIGH_HEADROOM,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
        )
        assertEquals(listOf(1_536, 2_048, 4_096), qwen06Windows)

        val gemma4Windows = com.voicebubble.openflow.core.speech.GemmaTextProcessor.selectTokenWindowCandidates(
            memoryMode = com.voicebubble.openflow.core.speech.GemmaMemoryMode.HIGH_HEADROOM,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
        )
        assertEquals(listOf(2_048, 4_096), gemma4Windows)

        assertFalse(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.hasRunawayTailRepetition(
                "The release build finished compiling cleanly. All unit tests passed on the first run."
            )
        )
        val repeatedLoop = "Here is the summary: " + "We need to ship the update today. ".repeat(3)
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.hasRunawayTailRepetition(repeatedLoop)
        )
    }

    @Test
    fun `Pillar 6 sentence sieve preserves clean sentences verbatim and detects optional LoRA files`() {
        val mixedParagraph = "The database migration finished ahead of schedule. " +
            "All integration tests passed without errors. " +
            "Um we still need to update twenty five configuration keys before Friday. " +
            "The deployment window opens tomorrow morning."

        val spans = com.voicebubble.openflow.core.speech.GemmaTextProcessor.partitionSentenceSieve(
            text = mixedParagraph,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        org.junit.Assert.assertNotNull(spans)
        assertEquals(3, spans!!.size)
        assertFalse(spans[0].needsLlm)
        assertEquals(
            "The database migration finished ahead of schedule. All integration tests passed without errors.",
            spans[0].text
        )
        assertTrue(spans[1].needsLlm)
        assertEquals(
            "Um we still need to update twenty five configuration keys before Friday.",
            spans[1].text
        )
        assertFalse(spans[2].needsLlm)
        assertEquals("The deployment window opens tomorrow morning.", spans[2].text)

        // Repair-leading sentence must couple with its preceding clean sentence so backtracks aren't split
        val coupledRepair = "The meeting is on Monday. Actually move it to Tuesday afternoon. " +
            "Everything else on the roadmap stays unchanged. " +
            "Please share the slides with the design team."
        val repairSpans = com.voicebubble.openflow.core.speech.GemmaTextProcessor.partitionSentenceSieve(
            text = coupledRepair,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        org.junit.Assert.assertNotNull(repairSpans)
        assertTrue("First span must couple sentence 1 + 'Actually...' sentence 2 into needsLlm=true", repairSpans!![0].needsLlm)
        assertTrue(repairSpans[0].text.contains("The meeting is on Monday. Actually move it to Tuesday afternoon."))
        assertFalse(repairSpans[1].needsLlm)

        // Optional LoRA file detection & compact zero-example system prompt
        val tempDir = kotlin.io.path.createTempDirectory("lora_test").toFile()
        try {
            assertEquals(
                null,
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.resolveOptionalLoraFile(
                    tempDir,
                    com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
                )
            )
            val loraFile = java.io.File(tempDir, "qwen3_0_6b.lora").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertEquals(
                loraFile,
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.resolveOptionalLoraFile(
                    tempDir,
                    com.voicebubble.openflow.settings.LlmModelChoice.QWEN_3_0_6B
                )
            )
            val compactLoraPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildCompactLoraSystemInstruction(
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                customVocabulary = listOf("OpenFlow", "LiteRT-LM")
            )
            assertTrue(compactLoraPrompt.length < 220)
            assertTrue(compactLoraPrompt.contains("OpenFlow"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `screen stays awake while bubble is visible or transcribing or using LLM and releases when hidden or screen off`() {
        // 1. Visible idle bubble on screen keeps display awake
        assertTrue(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                isScreenInteractive = true,
                isBubbleVisible = true,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.READY,
                isPipelineActivelyProcessing = false
            )
        )

        // 2. Recording, Processing (Voz / LLM), or Success keeps display awake even if keyboard closes
        for (activeBubbleState in listOf(
            com.voicebubble.openflow.overlay.OverlayBubbleState.RECORDING,
            com.voicebubble.openflow.overlay.OverlayBubbleState.PROCESSING,
            com.voicebubble.openflow.overlay.OverlayBubbleState.SUCCESS
        )) {
            assertTrue(
                com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                    isScreenInteractive = true,
                    isBubbleVisible = false,
                    bubbleState = activeBubbleState,
                    pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.READY,
                    isPipelineActivelyProcessing = false
                )
            )
        }

        // 3. Active pipeline transcription or LLM cleanup/benchmark keeps display awake
        assertTrue(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                isScreenInteractive = true,
                isBubbleVisible = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.TRANSCRIBING,
                isPipelineActivelyProcessing = false
            )
        )
        assertTrue(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                isScreenInteractive = true,
                isBubbleVisible = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.LOADING,
                isPipelineActivelyProcessing = true
            )
        )

        // 4. Hidden idle bubble with idle pipeline allows normal screen timeout
        assertFalse(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                isScreenInteractive = true,
                isBubbleVisible = false,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.IDLE,
                pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.READY,
                isPipelineActivelyProcessing = false
            )
        )

        // 5. Manual hardware Power-button screen off always releases screen wake lock immediately
        assertFalse(
            com.voicebubble.openflow.overlay.FloatingBubbleService.computeShouldKeepScreenOn(
                isScreenInteractive = false,
                isBubbleVisible = true,
                bubbleState = com.voicebubble.openflow.overlay.OverlayBubbleState.PROCESSING,
                pipelinePhase = com.voicebubble.openflow.core.speech.PipelinePhase.TRANSCRIBING,
                isPipelineActivelyProcessing = true
            )
        )
    }

    @Test
    fun `LlmBenchmarkSample provides 5 standard examples, 2 complex examples, and 1 custom input option`() {
        val all = com.voicebubble.openflow.settings.LlmBenchmarkSample.entries
        val standardExamples = all.filter { !it.isComplex && !it.isCustom }
        val complexExamples = all.filter { it.isComplex && !it.isCustom }
        val customOptions = all.filter { it.isCustom }

        assertEquals(5, standardExamples.size)
        assertEquals(2, complexExamples.size)
        assertEquals(1, customOptions.size)
        all.forEach { sample ->
            assertTrue(sample.label.isNotBlank())
            assertTrue(sample.sampleText.isNotBlank())
        }
        complexExamples.forEach { complex ->
            assertTrue("Complex benchmark sample should be substantial", complex.sampleText.length >= 350)
        }
    }

    @Test
    fun `LLM benchmark metrics accurately compute uncached vs cached tokens per second and speedup`() {
        val uncached = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computePassMetrics(
            wallTotalMs = 800L,
            streamTtftMs = 300L,
            streamDecodeDurationMs = 500L,
            streamedTokenChunks = 25,
            tokensBeforeTurn = 0,
            tokensAfterTurn = 285,
            fallbackInputChars = 900,
            fallbackOutputChars = 100,
            nativeTtftSec = 0.30,
            nativePrefillTokens = 260,
            nativeDecodeTokens = 25,
            nativePrefillTokPerSec = 866.6,
            nativeDecodeTokPerSec = 50.0
        )
        assertEquals(800L, uncached.totalMs)
        assertEquals(300L, uncached.ttftMs)
        assertEquals(260, uncached.prefillTokens)
        assertEquals(25, uncached.decodeTokens)
        assertEquals(50.0, uncached.decodeTokensPerSec, 0.01)
        assertEquals(31.25, uncached.effectiveTokensPerSec, 0.01)

        val cached = com.voicebubble.openflow.core.speech.GemmaTextProcessor.computePassMetrics(
            wallTotalMs = 525L,
            streamTtftMs = 25L,
            streamDecodeDurationMs = 500L,
            streamedTokenChunks = 25,
            tokensBeforeTurn = 230,
            tokensAfterTurn = 285,
            fallbackInputChars = 120,
            fallbackOutputChars = 100
        )
        assertEquals(525L, cached.totalMs)
        assertEquals(25L, cached.ttftMs)
        assertEquals(25, cached.decodeTokens)
        assertTrue("Cached effective tok/s must exceed uncached effective tok/s", cached.effectiveTokensPerSec > uncached.effectiveTokensPerSec)

        val result = com.voicebubble.openflow.core.speech.LlmBenchmarkResult(
            modelTitle = "Qwen 3 0.6B",
            backendSummary = "GPU OpenCL/Vulkan",
            promptPresetTitle = "Default — Clean & Format",
            thinkingModeSummary = "Adaptive (Direct fast-path)",
            inputText = "um hey sarah running fifteen minutes late",
            cleanedOutput = "Hey Sarah, running 15 minutes late.",
            uncached = uncached,
            cached = cached,
            cachePrewarmMs = 190L,
            cachedSystemPromptTokens = 230
        )
        assertEquals(800.0 / 525.0, result.speedupFactor!!, 0.001)
    }

    @Test
    fun `ModelSettings auto-unload defaults to 5 minutes, sanitizes invalid values, and formats labels`() {
        val defaults = ModelSettings()
        assertEquals(ModelSettings.DEFAULT_MODEL_AUTO_UNLOAD_MINUTES, defaults.modelAutoUnloadMinutes)
        assertEquals(5, defaults.modelAutoUnloadMinutes)

        assertEquals(0, ModelSettings.sanitizeAutoUnloadMinutes(0))
        assertEquals(-1, ModelSettings.sanitizeAutoUnloadMinutes(-1))
        assertEquals(15, ModelSettings.sanitizeAutoUnloadMinutes(15))
        assertEquals(5, ModelSettings.sanitizeAutoUnloadMinutes(999))
        assertEquals(5, ModelSettings.sanitizeAutoUnloadMinutes(-42))

        assertEquals("Immediately", ModelSettings.autoUnloadChipLabel(0))
        assertEquals("5m", ModelSettings.autoUnloadChipLabel(5))
        assertEquals("Never", ModelSettings.autoUnloadChipLabel(-1))

        assertTrue(ModelSettings(modelAutoUnloadMinutes = 0).autoUnloadSummary.contains("immediately"))
        assertTrue(ModelSettings(modelAutoUnloadMinutes = 10).autoUnloadSummary.contains("10 min"))
        assertTrue(ModelSettings(modelAutoUnloadMinutes = -1).autoUnloadSummary.contains("tapped"))
    }

    @Test
    fun `LlmModelChoice descriptions show exact GB sizes with concise copy and zero speed or RAM claims`() {
        val gbPattern = Regex("""^\d+\.\d{2} GB$""")
        val forbiddenClaimPattern = Regex(
            """\b(tok/s|tokens?/s|ram|gb\+|mb\b|ultra-fast|fastest|sub-second)\b""",
            RegexOption.IGNORE_CASE
        )

        for (choice in com.voicebubble.openflow.settings.LlmModelChoice.entries) {
            assertTrue(
                "Expected X.XX GB for ${choice.id}, got '${choice.speedAndRamSummary}'",
                gbPattern.matches(choice.speedAndRamSummary)
            )
            assertEquals(choice.desertAntModel.sizeGbLabel, choice.sizeGbLabel)
            assertEquals(choice.sizeGbLabel, choice.speedAndRamSummary)
            assertTrue(
                "Description for ${choice.id} must be short (<= 38 chars), got '${choice.description}'",
                choice.description.length in 8..38
            )
            assertFalse(
                "Description for ${choice.id} must not make speed or RAM claims: '${choice.description}'",
                forbiddenClaimPattern.containsMatchIn(choice.description)
            )
            assertFalse(
                "Badge for ${choice.id} must not make speed or RAM claims: '${choice.badgeLabel}'",
                forbiddenClaimPattern.containsMatchIn(choice.badgeLabel)
            )
        }
    }

    @Test
    fun `Gemma 4 E2B upgraded prompt, inline list expansion, spoken colon normalization, and paragraph splitter work cleanly`() {
        // 1. Dedicated Gemma 4 E2B prompts across presets + telegraphic <35 word thinking cap
        for (preset in com.voicebubble.openflow.settings.GemmaPromptPreset.entries) {
            val sysPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
                preset = preset,
                disableThinking = false,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
            )
            assertTrue("Preset ${preset.id} must be non-blank for Gemma 4 E2B", sysPrompt.length > 200)
            assertTrue("Gemma 4 E2B prompt must include <35 word thinking cap", sysPrompt.contains("under 35 words"))
        }

        // 2. Spoken "colon" before "number one" normalizes cleanly
        val rawComplex = "Here is what we saw colon number one memory stayed low. Number two latency dropped. Number three tests passed."
        val preNormalized = com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(rawComplex)
        assertTrue(preNormalized.startsWith("Here is what we saw: number one"))

        // 3. "number one ... number two" triggers adaptive thinking and prevents span-patch flattening
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldEnableThinkingForSegment(
                segment = preNormalized,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
            )
        )
        assertFalse(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldUseSpanPatchMode(
                segment = preNormalized.repeat(3),
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B
            )
        )

        // 4. Inline single-line numbered lists expand onto clean vertical lines while ignoring decimals
        val inlineModelOutput = "Hi team,\n\nHere is what we saw in v2.11: 1. Memory stayed at 0 MB on 6 GB devices. 2. First token latency dropped by 45%. 3. All 5 benchmarks passed.\n\nPlease ship today."
        val expanded = com.voicebubble.openflow.core.speech.GemmaTextProcessor.expandInlineSequentialLists(inlineModelOutput)
        assertEquals(
            "Hi team,\n\nHere is what we saw in v2.11:\n1. Memory stayed at 0 MB on 6 GB devices.\n2. First token latency dropped by 45%.\n3. All 5 benchmarks passed.\n\nPlease ship today.",
            expanded
        )

        // Decimals & version numbers without sequential list items must remain untouched
        val decimalSentence = "Release 1. Build 2.11 uses 0.57 GB on disk."
        assertEquals(
            decimalSentence,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.expandInlineSequentialLists(decimalSentence)
        )

        // 5. splitLongTranscript preserves explicit paragraph breaks (\n\n)
        val multiPara = "Hi Marcus,\n\nFirst paragraph sentence one. First paragraph sentence two.\n\nBest regards."
        val splitParas = com.voicebubble.openflow.core.speech.GemmaTextProcessor.splitLongTranscript(
            text = multiPara,
            maxCharsPerSegment = 500
        )
        assertEquals(listOf(multiPara), splitParas)

        // 6. Prose mention "semicolon or colon" does not turn literal "colon" into ":"
        val proseColon = "Avoid putting a semicolon or colon number one in that title."
        assertEquals(
            proseColon,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preNormalizeSpokenConstructs(proseColon)
        )
    }

    @Test
    fun `All compiled Regex patterns across speech and overlay classes obey Android ICU bounded-lookbehind rules`() {
        val classesToAudit = listOf(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor::class.java,
            com.voicebubble.openflow.core.speech.SmartPipeline::class.java,
            com.voicebubble.openflow.core.speech.PunctuationRestorer::class.java,
            com.voicebubble.openflow.core.speech.StreamingDictationSession::class.java,
            com.voicebubble.openflow.overlay.FloatingBubbleService::class.java,
            com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService::class.java
        )

        fun extractLookbehindBodies(pattern: String): List<String> {
            val bodies = mutableListOf<String>()
            var i = 0
            while (i < pattern.length - 4) {
                if (pattern[i] == '\\') {
                    i += 2
                    continue
                }
                if (pattern.startsWith("(?<=", i) || pattern.startsWith("(?<!", i)) {
                    val startBody = i + 4
                    var depth = 1
                    var inCharClass = false
                    var j = startBody
                    while (j < pattern.length && depth > 0) {
                        val c = pattern[j]
                        if (c == '\\') {
                            j += 2
                            continue
                        }
                        if (c == '[' && !inCharClass) {
                            inCharClass = true
                        } else if (c == ']' && inCharClass) {
                            inCharClass = false
                        } else if (!inCharClass) {
                            if (c == '(') depth++
                            else if (c == ')') depth--
                        }
                        j++
                    }
                    if (depth == 0) {
                        bodies.add(pattern.substring(startBody, j - 1))
                    }
                }
                i++
            }
            return bodies
        }

        fun hasUnboundedQuantifier(lookbehindBody: String): Boolean {
            var inCharClass = false
            var i = 0
            while (i < lookbehindBody.length) {
                val c = lookbehindBody[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '[' && !inCharClass) {
                    inCharClass = true
                } else if (c == ']' && inCharClass) {
                    inCharClass = false
                } else if (!inCharClass) {
                    if (c == '+' || c == '*') return true
                    if (c == '{') {
                        val close = lookbehindBody.indexOf('}', i + 1)
                        if (close > i) {
                            val spec = lookbehindBody.substring(i + 1, close)
                            if (spec.endsWith(",")) return true
                        }
                    }
                }
                i++
            }
            return false
        }

        var auditedRegexCount = 0
        for (cls in classesToAudit) {
            val targets = buildList<Pair<Class<*>, Any?>> {
                add(cls to null)
                try {
                    val companionField = cls.getDeclaredField("Companion")
                    companionField.isAccessible = true
                    val companionInstance = companionField.get(null)
                    add(companionInstance.javaClass to companionInstance)
                } catch (_: NoSuchFieldException) {
                }
            }
            for ((targetClass, instance) in targets) {
                for (field in targetClass.declaredFields) {
                    if (field.type == Regex::class.java &&
                        (instance != null || java.lang.reflect.Modifier.isStatic(field.modifiers))
                    ) {
                        field.isAccessible = true
                        val regex = field.get(instance) as? Regex ?: continue
                        auditedRegexCount++
                        for (lb in extractLookbehindBodies(regex.pattern)) {
                            assertFalse(
                                "Android ICU4C rejects unbounded quantifier in lookbehind of ${cls.simpleName}.${field.name}: (?<...$lb)",
                                hasUnboundedQuantifier(lb)
                            )
                        }
                    }
                }
            }
        }
        assertTrue("Expected to audit >= 25 Regex fields, got $auditedRegexCount", auditedRegexCount >= 25)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `SmartPipeline background memory trim classifier and screen-off deep-sleep timeout work accurately`() {
        // Leaving OpenFlow UI for a chat app fires TRIM_MEMORY_UI_HIDDEN (20): must NOT unload loaded models!
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.DROP_KV_CACHE_ONLY,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            )
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.DROP_KV_CACHE_ONLY,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
            )
        )
        // Genuine low-memory / background LRU bands unload optional models cleanly
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.EVICT_OPTIONAL_MODELS,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            )
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.EVICT_OPTIONAL_MODELS,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            )
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.SEVERE_EVICT,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            )
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.Companion.TrimMemoryAction.SEVERE_EVICT,
            com.voicebubble.openflow.core.speech.SmartPipeline.classifyTrimMemoryAction(
                android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            )
        )

        // Screen-ON vs Screen-OFF idle unload timer resolution
        assertEquals(
            5 * 60_000L,
            com.voicebubble.openflow.core.speech.SmartPipeline.computeEffectiveIdleTimeoutMs(
                unloadMinutes = 5,
                isScreenInteractive = true
            )
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.SmartPipeline.SCREEN_OFF_DEEP_SLEEP_UNLOAD_MS,
            com.voicebubble.openflow.core.speech.SmartPipeline.computeEffectiveIdleTimeoutMs(
                unloadMinutes = 5,
                isScreenInteractive = false
            )
        )
        assertEquals(
            0L,
            com.voicebubble.openflow.core.speech.SmartPipeline.computeEffectiveIdleTimeoutMs(
                unloadMinutes = ModelSettings.AUTO_UNLOAD_IMMEDIATELY,
                isScreenInteractive = false
            )
        )
        assertEquals(
            null,
            com.voicebubble.openflow.core.speech.SmartPipeline.computeEffectiveIdleTimeoutMs(
                unloadMinutes = ModelSettings.AUTO_UNLOAD_NEVER,
                isScreenInteractive = false
            )
        )
    }

    @Test
    fun `Focused text-field splice helper handles empty hint fields, cursor insertion, smart spacing, and selection replacement`() {
        val splice = com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService::spliceTextForSetText

        // 1. Empty / placeholder-cleared field
        val emptyRes = splice("", -1, -1, "Hello world.")
        assertEquals("Hello world.", emptyRes.newText)
        assertEquals(12, emptyRes.newCursorPosition)

        // 2. Appending a new sentence at end of existing text without trailing space -> adds single space
        val appendEnd = splice("Hello world.", 12, 12, "This is OpenFlow.")
        assertEquals("Hello world. This is OpenFlow.", appendEnd.newText)
        assertEquals("Hello world. This is OpenFlow.".length, appendEnd.newCursorPosition)

        // 3. Appending when existing text already ends with a space -> no double space
        val appendAfterSpace = splice("Hello ", 6, 6, "there!")
        assertEquals("Hello there!", appendAfterSpace.newText)
        assertEquals("Hello there!".length, appendAfterSpace.newCursorPosition)

        // 4. Mid-sentence cursor splice ("That is a |car" at index 10) -> adds trailing space before "car"
        val midInsert = splice("That is a car", 10, 10, "really fast")
        assertEquals("That is a really fast car", midInsert.newText)
        assertEquals("That is a really fast".length, midInsert.newCursorPosition)

        // 5. Replacing a highlighted selection ("Buy apples today" -> replace "apples" [4..10])
        val replaceSel = splice("Buy apples today", 4, 10, "fresh oranges")
        assertEquals("Buy fresh oranges today", replaceSel.newText)
        assertEquals("Buy fresh oranges".length, replaceSel.newCursorPosition)

        // 6. Inserting inside parentheses or after newline does not inject unwanted leading space
        val afterParen = splice("Note (", 6, 6, "urgent")
        assertEquals("Note (urgent", afterParen.newText)
        val afterNewline = splice("Line 1\n", 7, 7, "Line 2")
        assertEquals("Line 1\nLine 2", afterNewline.newText)
    }

    @Test
    fun `Verified text injection detector catches silent Accessibility no-ops while confirming genuine insertions`() {
        val check = com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService::hasConfirmedTextInjection

        // 1. Empty field stays empty (silent no-op from container / wrapper) -> false
        assertFalse(
            check(
                false, "", -1, -1,
                false, "", -1, -1
            )
        )

        // 2. Empty field receives dictated text -> true
        assertTrue(
            check(
                false, "", 0, 0,
                false, "Hello from OpenFlow.", 20, 20
            )
        )

        // 3. Field showing placeholder hint ("Message") stays on placeholder hint (silent no-op) -> false
        assertFalse(
            check(
                true, "Message", -1, -1,
                true, "Message", -1, -1
            )
        )

        // 4. Field showing placeholder hint ("Message") has hint replaced even if user dictated "Message" -> true
        assertTrue(
            check(
                true, "Message", 0, 0,
                false, "Message", 7, 7
            )
        )

        // 5. Non-empty field unchanged with unmoved cursor (silent no-op) -> false
        assertFalse(
            check(
                false, "Draft: ", 7, 7,
                false, "Draft: ", 7, 7
            )
        )

        // 6. Masked field where caret advances forward -> true
        assertTrue(
            check(
                false, "", 0, 0,
                false, "", 12, 12
            )
        )

        // 7. Highlighted selection [4..10] replaced in-place -> true
        assertTrue(
            check(
                false, "Buy apples", 4, 10,
                false, "Buy apples", 10, 10
            )
        )

        // 8. Disguised placeholder ("Ask anything", wasShowingHint=false, 0..0) replaced even by identical words -> true
        assertTrue(
            check(
                false, "Ask anything", 0, 0,
                false, "Ask anything", 12, 12
            )
        )

        // 9. WebView / Flutter editor whose selection initializes from -1 to > 0 upon paste -> true
        assertTrue(
            check(
                false, "", -1, -1,
                false, "", 15, 15
            )
        )
    }

    @Test
    fun `Disguised placeholder sanitizer strips Compose, Material TextInputLayout, and custom hint text while preserving user text`() {
        val sanitize = com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService::sanitizeEditableNodeText

        // 1. Standard Android isShowingHintText == true -> stripped
        assertEquals(
            "",
            sanitize("Message", true, false, 0, 0, "Message", null, null, null, emptyList())
        )

        // 2. Password field -> stripped
        assertEquals(
            "",
            sanitize("••••••", false, true, 6, 6, null, null, null, null, emptyList())
        )

        // 3. Static/wrapper node with no active selection span (-1, -1) -> stripped
        assertEquals(
            "",
            sanitize("Write a comment", false, false, -1, -1, null, null, null, null, emptyList())
        )

        // 4. Disguised hintText match when isShowingHintText == false -> stripped
        assertEquals(
            "",
            sanitize("Type a message", false, false, 0, 0, "Type a message", null, null, null, emptyList())
        )

        // 5. Material TextInputLayout combined empty-field label + placeholder -> stripped
        assertEquals(
            "",
            sanitize("Email, Enter your work email", false, false, 0, 0, "Email", null, null, null, emptyList())
        )

        // 6. Jetpack Compose DecorationBox / BasicTextField merged child placeholder Text -> stripped
        assertEquals(
            "",
            sanitize("Ask anything", false, false, 0, 0, null, null, null, null, listOf("Ask anything"))
        )

        // 7. Ellipsis placeholder prompt at 0..0 without isShowingHintText -> stripped
        assertEquals(
            "",
            sanitize("Search messages...", false, false, 0, 0, null, null, null, null, emptyList())
        )

        // 8. Material TextInputLayout ", <hint>" suffix stripped from genuine non-empty user text
        assertEquals(
            "Hello Sarah",
            sanitize("Hello Sarah, Message", false, false, 11, 11, "Message", null, null, null, emptyList())
        )

        // 9. Genuine user-typed text with cursor > 0 is preserved verbatim
        assertEquals(
            "Hi Sarah, ",
            sanitize("Hi Sarah, ", false, false, 10, 10, "Message", null, null, null, listOf("Message"))
        )
    }

    @Test
    fun `Smart paste payload formats back-to-back dictations and mid-sentence insertions without touching placeholders or URLs`() {
        val payload = com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService::computeSmartPastePayload
        val textType = android.text.InputType.TYPE_CLASS_TEXT

        // 1. Empty or 0..0 placeholder-ambiguous field -> never adds leading or trailing spaces
        assertEquals("Hello world.", payload("", 0, 0, "Hello world.", textType))
        assertEquals("Hello world.", payload("Unmarked gray hint", 0, 0, "Hello world.", textType))

        // 2. Back-to-back dictation right after a period -> prepends single space
        assertEquals(
            " How is your day going?",
            payload("Hello world.", 12, 12, "How is your day going?", textType)
        )

        // 3. Mid-sentence insertion ("That is a |car" at 10..10) -> appends trailing space before "car"
        assertEquals(
            "really fast ",
            payload("That is a car", 10, 10, "really fast", textType)
        )

        // 4. Highlighted range replacement ("Buy [apples] today" at 4..10 and "[Buy] apples" at 0..3)
        assertEquals(
            "fresh oranges",
            payload("Buy apples today", 4, 10, "fresh oranges", textType)
        )
        assertEquals(
            "Get",
            payload("Buy apples", 0, 3, "Get", textType)
        )

        // 5. Email, Number, and spaceless URL fields suppress automatic word spaces
        val emailType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val uriType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        assertEquals("gmail.com", payload("alex@", 5, 5, "gmail.com", emailType))
        assertEquals("openflow", payload("https://github.com/", 19, 19, "openflow", uriType))
        assertEquals("com", payload("https://example.", 16, 16, "com", uriType))

        // 6. Browser omnibox multi-word search query (URI inputType containing spaces) still spaces words naturally
        assertEquals(" recipe", payload("best sourdough", 14, 14, "recipe", uriType))
    }

    @Test
    fun `NPU support aligns with model export capabilities and resolveBestModelFile prefers NPU bundle when present`() {
        for (choice in com.voicebubble.openflow.settings.LlmModelChoice.entries) {
            if (choice == com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B) {
                assertFalse(
                    "Standard CPU/GPU-only LFM3 1.7B must disable NPU JIT probing to prevent native compiler crashes",
                    choice.supportsNpuBackend
                )
            } else {
                assertTrue("${choice.id} must allow NPU backend probing", choice.supportsNpuBackend)
            }
        }

        val tempDir = kotlin.io.path.createTempDirectory("npu_model_test").toFile()
        try {
            val stdFile = java.io.File(tempDir, com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B.modelFileName).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            assertEquals(
                stdFile,
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.resolveBestModelFile(
                    modelDir = tempDir,
                    llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B,
                    wantNpu = true
                )
            )

            val npuUncompiled = java.io.File(tempDir, "gemma-4-E2B-it_NPU_Uncompiled.litertlm").apply {
                writeBytes(byteArrayOf(4, 5, 6, 7))
            }
            assertEquals(
                npuUncompiled,
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.resolveBestModelFile(
                    modelDir = tempDir,
                    llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B,
                    wantNpu = true
                )
            )
            assertEquals(
                stdFile,
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.resolveBestModelFile(
                    modelDir = tempDir,
                    llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B,
                    wantNpu = false
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `AudioRecordingController hasTrailingSpeechEnergy skips stop grace delay when trailing buffer is silent`() {
        val silentTail = FloatArray(16_000) { idx ->
            if (idx < 12_000) 0.15f else 0.001f // User paused for last 250ms before tapping Stop
        }
        assertFalse(
            com.voicebubble.openflow.core.audio.AudioRecordingController.hasTrailingSpeechEnergy(
                samples = silentTail,
                size = silentTail.size
            )
        )

        val activeSpeechAtStop = FloatArray(16_000) { 0.08f }
        assertTrue(
            com.voicebubble.openflow.core.audio.AudioRecordingController.hasTrailingSpeechEnergy(
                samples = activeSpeechAtStop,
                size = activeSpeechAtStop.size
            )
        )

        assertFalse(
            com.voicebubble.openflow.core.audio.AudioRecordingController.hasTrailingSpeechEnergy(
                samples = null,
                size = 0
            )
        )
    }

    @Test
    fun `Expanded zero-LLM fast path handles up to 28-word clean sentences`() {
        val twentyTwoWordsClean = "We finished testing the new hardware acceleration pipeline on the phone this afternoon, and the transcription latency feels noticeably faster now."
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.canBypassLlmFastPath(
                text = twentyTwoWordsClean,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
    }

    @Test
    fun `Liquid AI LFM3 1_7B default model has thinking disabled by default and prefill_256 compact prompt`() {
        val lfm = com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B
        val gemma = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B

        assertEquals("DEFAULT", lfm.badgeLabel)
        assertTrue(lfm.supportsThinking)
        assertTrue(lfm.defaultDisableThinking)
        assertEquals("HIGH-END", gemma.badgeLabel)
        assertFalse(gemma.defaultDisableThinking)

        for (category in com.voicebubble.openflow.core.speech.AppContextCategory.entries) {
            val lfmPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
                disableThinking = lfm.defaultDisableThinking,
                fieldContext = com.voicebubble.openflow.core.speech.FlowFieldContext(appCategory = category),
                llmChoice = lfm
            )
            assertTrue(
                "LFM3 prompt for $category must stay compact (< 960 chars) to hit prefill_256, got ${lfmPrompt.length}",
                lfmPrompt.length in 450..960
            )
            assertFalse(
                "LFM3 default prompt with thinking disabled must not append reasoning hints",
                lfmPrompt.contains("internal reasoning")
            )
        }

        // LFM3 orphan </think> prefix, <tool_call> blocks, and <|startoftext|> / <|pad|> tokens strip cleanly
        assertEquals(
            "Meet Wednesday at 3:30 PM.",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = "<|startoftext|>\n\n</think>\n\n<tool_call>{\"name\":\"noop\"}</tool_call>Meet Wednesday at 3:30 PM.<|im_end|><|pad|>",
                fallbackInput = "meet wednesday at three thirty pm",
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )
    }

    @Test
    fun `Pipeline spoken-text retention guards prevent sentence drops decimal splits and audio tail cutoffs`() {
        // 1. Decimal & version numbers ("2.8.0", "42.5") are never split mid-number by partitionSentenceSieve
        val multiSentenceWithVersions =
            "We shipped version 2.8.0 to production this morning. Everything passed at 99.5% reliability across devices. Um let us schedule the next sync for tuesday wait no thursday at three thirty pm."
        val sieveSpans = com.voicebubble.openflow.core.speech.GemmaTextProcessor.partitionSentenceSieve(
            text = multiSentenceWithVersions,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        assertTrue("Expected non-null sieve spans for clean+dirty mix with decimals", sieveSpans != null)
        assertEquals(2, sieveSpans!!.size)
        assertFalse(sieveSpans[0].needsLlm)
        assertTrue(sieveSpans[0].text.contains("2.8.0"))
        assertTrue(sieveSpans[0].text.contains("99.5%"))
        assertTrue(sieveSpans[1].needsLlm)

        // 2. Anti-truncation guard recovers full transcript if LLM prematurely drops trailing sentence(s)
        val fullSpoken =
            "Hey Sarah I just finished testing the updated speech recognition pipeline on my Android phone. Please review the pull request before we publish the release tonight."
        val truncatedByLlm =
            "Hey Sarah, I just finished testing the updated speech recognition pipeline on my Android phone."
        assertEquals(
            fullSpoken,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = truncatedByLlm,
                fallbackInput = fullSpoken,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // Valid dense number/currency/date conversion is NOT falsely flagged as truncated
        val spokenInvoice = com.voicebubble.openflow.settings.LlmBenchmarkSample.EXAMPLE_2_NUMBERS_DATES.sampleText
        val formattedInvoice = "The total invoice comes out to \$4,250.75, due on November 24, 2026, at 5:00 PM."
        assertEquals(
            formattedInvoice,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = formattedInvoice,
                fallbackInput = spokenInvoice,
                preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
            )
        )

        // 3. 3-tier stop flush grace: 165ms active speech, 95ms recent speech (<450ms) HAL flush, 0ms >=450ms silence
        val midWordStop = FloatArray(16_000) { 0.06f }
        assertEquals(165L, com.voicebubble.openflow.core.audio.AudioRecordingController.computeStopFlushGraceMs(midWordStop, midWordStop.size))
        val recentSpeech250msPause = FloatArray(16_000) { idx -> if (idx < 12_000) 0.08f else 0.001f }
        assertEquals(95L, com.voicebubble.openflow.core.audio.AudioRecordingController.computeStopFlushGraceMs(recentSpeech250msPause, recentSpeech250msPause.size))
        val longPause600ms = FloatArray(16_000) { idx -> if (idx < 6_400) 0.08f else 0.001f }
        assertEquals(0L, com.voicebubble.openflow.core.audio.AudioRecordingController.computeStopFlushGraceMs(longPause600ms, longPause600ms.size))

        // 4. Forced non-pause live cut retains 150ms micro-overlap & quiet 4-frame (80ms) tail speech is preserved
        val nonStopSpeech = FloatArray(16 * 16_000 + 3_200) { 0.05f }
        val cfg = com.voicebubble.openflow.settings.DictationChunkSettings()
        val forcedCut = com.voicebubble.openflow.core.speech.findStreamingCut(nonStopSpeech, nonStopSpeech.size, cfg)
        assertTrue(forcedCut != null && forcedCut.hasSpeech)
        assertEquals(
            com.voicebubble.openflow.core.speech.FORCED_CUT_OVERLAP_SAMPLES,
            forcedCut!!.index - forcedCut.resumeIndex
        )

        val quiet80msSyllableTail = FloatArray(1_920) { idx -> if (idx in 320 until 1_600) 0.0024f else 0.0004f }
        assertTrue(com.voicebubble.openflow.core.speech.hasAnyTailSpeech(quiet80msSyllableTail))
    }

    @Test
    fun `LFM3 loading stability guards skip NPU JIT lock ekv2048 window preserve native activations and recover from crash sentinels`() {
        val lfm = com.voicebubble.openflow.settings.LlmModelChoice.LFM_3_1_7B
        val gemma = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B

        // 1. Standard LFM3 never probes NPU JIT (neither in AUTO nor in explicit NPU mode), avoiding native compiler crashes
        for (accel in com.voicebubble.openflow.settings.HardwareAccelerator.entries) {
            assertFalse(
                "Standard LFM3 must skip NPU backend in $accel mode",
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldAllowNpuBackend(
                    llmChoice = lfm,
                    accelerator = accel,
                    hasHardwareNpu = true,
                    hasCustomNpuModelFile = false
                )
            )
        }
        assertTrue(
            "Custom side-loaded *_npu.litertlm LFM3 bundle may probe NPU",
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldAllowNpuBackend(
                llmChoice = lfm,
                accelerator = com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
                hasHardwareNpu = true,
                hasCustomNpuModelFile = true
            )
        )
        assertTrue(
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldAllowNpuBackend(
                llmChoice = gemma,
                accelerator = com.voicebubble.openflow.settings.HardwareAccelerator.AUTO,
                hasHardwareNpu = true,
                hasCustomNpuModelFile = false
            )
        )

        // 2. Static ekv2048 KV window is strictly locked across ALL memory modes (including LOW_MEMORY)
        for (memMode in com.voicebubble.openflow.core.speech.GemmaMemoryMode.entries) {
            assertEquals(
                "LFM3 must lock maxNumTokens=2048 in $memMode to match static ekv2048 & kv_cache_c buffers",
                listOf(2_048),
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.selectTokenWindowCandidates(
                    memoryMode = memMode,
                    llmChoice = lfm,
                    fileMaxContextTokens = 2_048
                )
            )
            assertEquals(
                listOf(2_048),
                com.voicebubble.openflow.core.speech.GemmaTextProcessor.selectTokenWindowCandidates(
                    memoryMode = memMode,
                    llmChoice = lfm,
                    fileMaxContextTokens = null
                )
            )
        }

        // 3. Native activation types & kv_cache_c channel filter safety
        assertEquals(
            null,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preferredActivationDataType(lfm, isCpuBackend = false)
        )
        assertEquals(
            com.google.ai.edge.litertlm.ActivationDataType.FLOAT16,
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.preferredActivationDataType(gemma, isCpuBackend = false)
        )
        assertFalse(com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldFilterChannelContentFromKvCache(lfm))
        assertTrue(com.voicebubble.openflow.core.speech.GemmaTextProcessor.shouldFilterChannelContentFromKvCache(gemma))

        // 4. Memory pressure swaps Voz out before loading 1.34 GB LFM3 on tighter mid-range RAM & skips split prewarming
        val highRamPhone = com.voicebubble.openflow.core.speech.MemoryPressureReport(
            systemAvailMb = 3_200L,
            systemTotalMb = 11_800L,
            systemLowMemoryFlag = false,
            jvmUsedMb = 64L,
            jvmMaxMb = 512L,
            jvmFreeHeadroomMb = 448L,
            nativeAllocatedMb = 900L
        )
        assertFalse("LFM3 single-bucket prefill_256 skips split prewarming", highRamPhone.canPrewarmLlmPromptCache(lfm))
        assertTrue(highRamPhone.canKeepVozAndLlmCoResident(lfm, vozAlreadyLoaded = true, llmAlreadyLoaded = false))

        val tightMidPhone = highRamPhone.copy(systemAvailMb = 1_600L, systemTotalMb = 5_800L)
        assertFalse(
            "Tight 1.6 GB free RAM must release Voz before loading LFM3 to prevent LMKD OOM kills",
            tightMidPhone.canKeepVozAndLlmCoResident(lfm, vozAlreadyLoaded = true, llmAlreadyLoaded = false)
        )

        // 5. Runtime cache stamp + crash-sentinel recovery
        val cacheDir = kotlin.io.path.createTempDirectory("lfm3_cache_guard_test").toFile()
        try {
            val staleShader = java.io.File(cacheDir, "stale_v2_14_0.program").apply { writeText("corrupt") }
            assertTrue(com.voicebubble.openflow.core.speech.GemmaTextProcessor.ensureCacheRuntimeStamp(cacheDir, 1_441_620_928L))
            assertFalse("Stale shader from older runtime must be purged", staleShader.exists())
            assertFalse(com.voicebubble.openflow.core.speech.GemmaTextProcessor.ensureCacheRuntimeStamp(cacheDir, 1_441_620_928L))

            // Attempt 1: normal clean start uses disk cache; caught Kotlin error clears lock without counting as native crash
            val plan1 = com.voicebubble.openflow.core.speech.GemmaTextProcessor.prepareBackendInitAttempt(cacheDir, "gpu", isCpuBackend = false)
            assertFalse(plan1.skipBackend)
            assertEquals(cacheDir.absolutePath, plan1.effectiveCacheDir)
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.clearBackendInitInflightLock(cacheDir, "gpu")

            // Simulate native crash #1 (inflight lock left behind + half-written shader file)
            val planBeforeCrash1 = com.voicebubble.openflow.core.speech.GemmaTextProcessor.prepareBackendInitAttempt(cacheDir, "gpu", isCpuBackend = false)
            assertEquals(cacheDir.absolutePath, planBeforeCrash1.effectiveCacheDir)
            val corruptShader = java.io.File(cacheDir, "half_compiled.clbin").apply { writeText("partial") }

            // Attempt after native crash #1: purges corrupt shader & retries once with ":nocache"
            val recoveryPlan = com.voicebubble.openflow.core.speech.GemmaTextProcessor.prepareBackendInitAttempt(cacheDir, "gpu", isCpuBackend = false)
            assertTrue(recoveryPlan.detectedPriorNativeCrash)
            assertEquals(1, recoveryPlan.totalNativeCrashes)
            assertFalse(recoveryPlan.skipBackend)
            assertEquals(":nocache", recoveryPlan.effectiveCacheDir)
            assertFalse("Half-compiled shader must be deleted after native crash", corruptShader.exists())

            // Simulate native crash #2 even with ":nocache" -> subsequent attempt skips broken GPU driver cleanly
            val skipPlan = com.voicebubble.openflow.core.speech.GemmaTextProcessor.prepareBackendInitAttempt(cacheDir, "gpu", isCpuBackend = false)
            assertTrue(skipPlan.detectedPriorNativeCrash)
            assertEquals(2, skipPlan.totalNativeCrashes)
            assertTrue("Backend must be skipped after 2 consecutive native init crashes", skipPlan.skipBackend)

            // Successful init resets crash state back to clean disk cache
            com.voicebubble.openflow.core.speech.GemmaTextProcessor.markBackendInitSuccess(cacheDir, "gpu")
            val cleanAgain = com.voicebubble.openflow.core.speech.GemmaTextProcessor.prepareBackendInitAttempt(cacheDir, "gpu", isCpuBackend = false)
            assertFalse(cleanAgain.skipBackend)
            assertEquals(0, cleanAgain.totalNativeCrashes)
            assertEquals(cacheDir.absolutePath, cleanAgain.effectiveCacheDir)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
