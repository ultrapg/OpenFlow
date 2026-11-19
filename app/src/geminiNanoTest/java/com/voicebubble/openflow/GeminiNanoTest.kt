package com.voicebubble.openflow

import com.voicebubble.openflow.settings.ModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Android AICore Gemini Nano backend selection, variant mapping, token budgeting,
 * compact prompt tier, and LiteRT-LM weight independence.
 *
 * Excluded from compilation via `app/build.gradle.kts` (`enableGeminiNano = false`) while Gemini
 * Nano integration is paused for future work.
 */
class GeminiNanoTest {

    @Test
    fun `Gemini Nano defaults and prompt tier remain clean and compact`() {
        val defaultSettings = ModelSettings()
        assertEquals(
            "Default local LLM backend must be LiteRT-LM",
            com.voicebubble.openflow.settings.LocalLlmBackend.LITERT_LM,
            defaultSettings.localLlmBackend
        )
        assertEquals(
            "Default Gemini Nano variant must be FULL",
            com.voicebubble.openflow.settings.GeminiNanoVariant.FULL,
            defaultSettings.geminiNanoVariant
        )
        assertTrue("Gemini Nano auto-fallback must be enabled by default", defaultSettings.geminiNanoAutoFallback)

        val nanoPrompt = com.voicebubble.openflow.core.speech.GemmaTextProcessor.buildSystemInstruction(
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            disableThinking = true,
            llmChoice = com.voicebubble.openflow.settings.LlmModelChoice.GEMMA_4_E2B,
            promptTierOverride = com.voicebubble.openflow.settings.LlmPromptTier.GEMINI_NANO
        )
        assertTrue(nanoPrompt.contains("<transcript>"))
        assertFalse("Gemini Nano prompt must never receive internal reasoning hints", nanoPrompt.contains("internal reasoning"))
        assertTrue("Gemini Nano prompt must stay compact, got ${nanoPrompt.length} chars", nanoPrompt.length < 4_600)
    }

    @Test
    fun `Gemini Nano backend exposes FULL and FAST variants, AICore status selection, token budgeting, and model delete freedom`() {
        assertEquals(
            com.voicebubble.openflow.settings.LocalLlmBackend.GEMINI_NANO,
            com.voicebubble.openflow.settings.LocalLlmBackend.fromId("gemini_nano")
        )
        assertEquals(
            com.voicebubble.openflow.settings.LocalLlmBackend.LITERT_LM,
            com.voicebubble.openflow.settings.LocalLlmBackend.fromId("unknown_backend")
        )
        assertEquals(
            com.voicebubble.openflow.settings.GeminiNanoVariant.FULL,
            com.voicebubble.openflow.settings.GeminiNanoVariant.fromId("full")
        )
        assertEquals(
            com.voicebubble.openflow.settings.GeminiNanoVariant.FAST,
            com.voicebubble.openflow.settings.GeminiNanoVariant.fromId("fast")
        )
        assertEquals(
            com.voicebubble.openflow.settings.GeminiNanoVariant.FULL,
            com.voicebubble.openflow.settings.GeminiNanoVariant.fromId(null)
        )

        assertEquals(
            com.google.mlkit.genai.prompt.ModelPreference.FULL,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.toModelPreference(
                com.voicebubble.openflow.settings.GeminiNanoVariant.FULL
            )
        )
        assertEquals(
            com.google.mlkit.genai.prompt.ModelPreference.FAST,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.toModelPreference(
                com.voicebubble.openflow.settings.GeminiNanoVariant.FAST
            )
        )

        assertEquals(
            com.google.mlkit.genai.prompt.ModelReleaseStage.STABLE to com.google.mlkit.genai.common.FeatureStatus.AVAILABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.chooseBestStageStatus(
                stableStatus = com.google.mlkit.genai.common.FeatureStatus.AVAILABLE,
                previewStatus = com.google.mlkit.genai.common.FeatureStatus.AVAILABLE
            )
        )
        assertEquals(
            com.google.mlkit.genai.prompt.ModelReleaseStage.PREVIEW to com.google.mlkit.genai.common.FeatureStatus.AVAILABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.chooseBestStageStatus(
                stableStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE,
                previewStatus = com.google.mlkit.genai.common.FeatureStatus.AVAILABLE
            )
        )
        assertEquals(
            com.google.mlkit.genai.prompt.ModelReleaseStage.STABLE to com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.chooseBestStageStatus(
                stableStatus = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE,
                previewStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE
            )
        )
        assertEquals(
            com.google.mlkit.genai.prompt.ModelReleaseStage.PREVIEW to com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.chooseBestStageStatus(
                stableStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE,
                previewStatus = com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING
            )
        )
        assertEquals(
            com.google.mlkit.genai.prompt.ModelReleaseStage.STABLE to com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.chooseBestStageStatus(
                stableStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE,
                previewStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE
            )
        )

        assertEquals(
            com.voicebubble.openflow.core.speech.GeminiNanoAvailability.AVAILABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.mapFeatureStatus(com.google.mlkit.genai.common.FeatureStatus.AVAILABLE)
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.GeminiNanoAvailability.DOWNLOADABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.mapFeatureStatus(com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE)
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.GeminiNanoAvailability.DOWNLOADING,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.mapFeatureStatus(com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING)
        )
        assertEquals(
            com.voicebubble.openflow.core.speech.GeminiNanoAvailability.UNAVAILABLE,
            com.voicebubble.openflow.core.speech.GeminiNanoManager.mapFeatureStatus(com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE)
        )

        val defaultInputChars = com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor.computeMaxInputCharsPerPass(
            tokenLimit = null,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        val largeInputChars = com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor.computeMaxInputCharsPerPass(
            tokenLimit = 4_096,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH
        )
        assertTrue(defaultInputChars >= 380)
        assertTrue(largeInputChars > defaultInputChars)

        val noThinkOut = com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor.computeMaxOutputTokensForPass(
            inputText = "um hey can we move the design review meeting to tomorrow morning",
            tokenLimit = 2_048,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            thinkingEnabled = false
        )
        val thinkOut = com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor.computeMaxOutputTokensForPass(
            inputText = "um hey can we move the design review meeting to tomorrow morning",
            tokenLimit = 2_048,
            preset = com.voicebubble.openflow.settings.GemmaPromptPreset.CLEAN_AND_POLISH,
            thinkingEnabled = true
        )
        assertTrue(thinkOut > noThinkOut)
        assertTrue(thinkOut <= 1_536)

        val defaultSettings = ModelSettings()
        val nanoActive = ModelSettings(
            earEnabled = true,
            punctEnabled = false,
            gemmaEnabled = true,
            localLlmBackend = com.voicebubble.openflow.settings.LocalLlmBackend.GEMINI_NANO,
            geminiNanoVariant = com.voicebubble.openflow.settings.GeminiNanoVariant.FAST
        )
        assertTrue(nanoActive.isGeminiNanoEnabled)
        assertFalse(nanoActive.isLocalLiteRtLlmEnabled)
        assertFalse(nanoActive.isModelLocallyRequired(com.voicebubble.openflow.settings.DesertAntModel.GEMMA_4_E2B))
        assertTrue(nanoActive.canDeleteModel(com.voicebubble.openflow.settings.DesertAntModel.GEMMA_4_E2B))
        assertEquals(defaultSettings.estimatedPeakRamMb, nanoActive.estimatedPeakRamMb)
        assertEquals(defaultSettings.activeDiskFootprintMb, nanoActive.activeDiskFootprintMb)
    }
}
