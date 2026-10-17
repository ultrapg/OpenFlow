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

        assertEquals("OpenFlow nutzt LiteRT und paraket.", cleaned)
    }

    @Test
    fun `ModelSettings toggle state matrix matches Specification Section 5_4`() {
        assertEquals(850, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(407, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(750, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(383, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(730, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(383, ModelSettings(earEnabled = true, clearEnabled = true, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(650, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(359, ModelSettings(earEnabled = true, clearEnabled = false, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(770, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(385, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(670, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = true).estimatedPeakRamMb)
        assertEquals(361, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = true).activeDiskFootprintMb)

        assertEquals(650, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(361, ModelSettings(earEnabled = false, clearEnabled = true, uhmEnabled = false).activeDiskFootprintMb)

        assertEquals(550, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = false).estimatedPeakRamMb)
        assertEquals(337, ModelSettings(earEnabled = false, clearEnabled = false, uhmEnabled = false).activeDiskFootprintMb)

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
}
