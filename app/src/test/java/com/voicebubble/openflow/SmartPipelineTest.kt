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
        assertEquals(1450, defaultSettings.estimatedPeakRamMb)
        assertEquals(1197, defaultSettings.activeDiskFootprintMb)
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
}
