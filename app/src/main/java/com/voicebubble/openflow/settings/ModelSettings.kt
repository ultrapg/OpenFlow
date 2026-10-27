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

package com.voicebubble.openflow.settings

import ai.desertant.ear.Ear
import ai.desertant.voz.Voz
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * The 25 European languages supported by Desert Ant Labs `Voz` (Parakeet TDT 0.6B v3).
 */
private val VOZ_SUPPORTED_LANGUAGES: Set<String> = linkedSetOf(
    "bg", "cs", "da", "de", "el",
    "en", "es", "et", "fi", "fr",
    "hr", "hu", "it", "lt", "lv",
    "mt", "nl", "pl", "pt", "ro",
    "ru", "sk", "sl", "sv", "uk"
)

/**
 * Exposes `Voz.supportedLanguages` on `Voz.Companion` to match the Desert Ant SDK specification.
 */
val Voz.Companion.supportedLanguages: Set<String>
    get() = VOZ_SUPPORTED_LANGUAGES

enum class DesertAntModel(
    val id: String,
    val shortLabel: String,
    val displayName: String,
    val roleSummary: String,
    val sizeMb: Int,
    val expectedBytes: Long,
    val isRequired: Boolean,
    val isExperimental: Boolean = false,
    val isLlmModel: Boolean = false
) {
    VOZ(
        id = "voz",
        shortLabel = "Voz",
        displayName = "Voz — Speech Recognition",
        roleSummary = "Required • Core transcription across 25 European languages",
        sizeMb = 1187,
        expectedBytes = 1_244_258_225L,
        isRequired = true
    ),
    EAR(
        id = "ear",
        shortLabel = "Ear",
        displayName = "Ear — Auto Language Detection",
        roleSummary = "Automatically detects spoken language before transcription",
        sizeMb = 10,
        expectedBytes = 10_738_698L,
        isRequired = false
    ),
    PUNCT(
        id = "punct",
        shortLabel = "Punct",
        displayName = "Punct — Text & Punctuation Cleanup",
        roleSummary = "XLM-RoBERTa token tagger that fixes mid-sentence periods, commas & casing on the NPU (0% hallucination)",
        sizeMb = 282,
        expectedBytes = 295_740_930L,
        isRequired = false
    ),
    QWEN_3_0_6B_LITE(
        id = "qwen3_0_6b_lite",
        shortLabel = "Qwen 3 0.6B Lite",
        displayName = "Qwen 3 0.6B Lite — Ultra-Fast AI",
        roleSummary = "Experimental • Ultra-light 4-bit GPU/CPU LLM (329 MB) for instant filler removal & cleanup",
        sizeMb = 329,
        expectedBytes = 344_671_744L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_3_0_6B(
        id = "qwen3_0_6b",
        shortLabel = "Qwen 3 0.6B",
        displayName = "Qwen 3 0.6B — Fast & Light AI",
        roleSummary = "Experimental • Recommended NPU/GPU/CPU LLM (585 MB) for fast multilingual cleanup, numbers & lists",
        sizeMb = 585,
        expectedBytes = 613_722_264L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_3_1_7B(
        id = "qwen3_1_7b",
        shortLabel = "Qwen 3 1.7B",
        displayName = "Qwen 3 1.7B — Balanced AI",
        roleSummary = "Experimental • Fast 4-bit GPU LLM (932 MB) balancing sub-second speed with smart backtrack editing",
        sizeMb = 932,
        expectedBytes = 977_184_032L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_2_5_1_5B(
        id = "qwen2_5_1_5b",
        shortLabel = "Qwen 2.5 1.5B",
        displayName = "Qwen 2.5 1.5B — Direct Instruct AI",
        roleSummary = "Experimental • Direct non-thinking INT8 LLM (1,524 MB) for instant zero-thought GPU/CPU cleanup",
        sizeMb = 1524,
        expectedBytes = 1_597_922_856L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        shortLabel = "Gemma 4 E2B",
        displayName = "Gemma 4 E2B — Flagship Max Quality",
        roleSummary = "Experimental • Flagship Google Gemma 4 E2B LLM (2,468 MB) on NPU for nuanced Wispr Flow reformatting",
        sizeMb = 2468,
        expectedBytes = 2_588_147_712L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    );
}

/**
 * Prompt optimization tier matched to each on-device LLM's parameter scale, instruction-tuning
 * behavior, and reasoning architecture.
 */
enum class LlmPromptTier {
    /** Ultra-crisp, positive bullet rules + 1 compact example (~190 tokens) for 0.6B models so attention stays sharp and prefill hits fast 256-token buckets. */
    COMPACT_0_6B,

    /** Deterministic non-conversational text-filter framing for direct instruct models with no `<think>` scratchpad (prevents answering spoken questions). */
    DIRECT_INSTRUCT_1_5B,

    /** High-signal multi-rule prompt + 2 grounded examples + bounded reasoning budget for 1.7B models. */
    BALANCED_1_7B,

    /** Full Wispr Flow multi-clause, nuance-preserving prompt + multilingual examples for flagship Gemma 4 E2B. */
    FLAGSHIP_GEMMA_4
}

/**
 * Selectable on-device LiteRT-LM models for AI post-processing & dictation cleanup, ordered from
 * smallest/fastest to largest/most capable. Only ONE selected model is ever enabled or loaded into RAM.
 */
enum class LlmModelChoice(
    val id: String,
    val desertAntModel: DesertAntModel,
    val modelFileName: String,
    val title: String,
    val badgeLabel: String,
    val speedAndRamSummary: String,
    val description: String,
    val estimatedStandaloneRamMb: Int,
    val estimatedIncrementalRamMb: Int,
    val supportsThinking: Boolean,
    val supportsNpuBackend: Boolean,
    val promptTier: LlmPromptTier,
    val samplerTopK: Int,
    val samplerTopP: Double,
    val samplerTemperature: Double
) {
    QWEN_3_0_6B_LITE(
        id = "qwen3_0_6b_lite",
        desertAntModel = DesertAntModel.QWEN_3_0_6B_LITE,
        modelFileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        title = "Qwen 3 0.6B Lite",
        badgeLabel = "TINY & FASTEST",
        speedAndRamSummary = "329 MB download • ~350 MB RAM • Ultra-fast GPU / CPU",
        description = "Smallest 4-bit model (~70+ tok/s on GPU). Best for instant filler removal, numbers & light cleanup on any phone (4 GB+ RAM).",
        estimatedStandaloneRamMb = 480,
        estimatedIncrementalRamMb = 350,
        supportsThinking = true,
        supportsNpuBackend = false,
        promptTier = LlmPromptTier.COMPACT_0_6B,
        samplerTopK = 10,
        samplerTopP = 0.85,
        samplerTemperature = 0.05
    ),
    QWEN_3_0_6B(
        id = "qwen3_0_6b",
        desertAntModel = DesertAntModel.QWEN_3_0_6B,
        modelFileName = "Qwen3-0.6B.litertlm",
        title = "Qwen 3 0.6B",
        badgeLabel = "RECOMMENDED",
        speedAndRamSummary = "585 MB download • ~450 MB RAM • NPU / GPU / CPU",
        description = "Best all-round light model. Runs natively on NPU, GPU, or CPU with crisp multilingual cleanup, numbers & list formatting (6 GB+ RAM).",
        estimatedStandaloneRamMb = 650,
        estimatedIncrementalRamMb = 450,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.COMPACT_0_6B,
        samplerTopK = 12,
        samplerTopP = 0.88,
        samplerTemperature = 0.06
    ),
    QWEN_3_1_7B(
        id = "qwen3_1_7b",
        desertAntModel = DesertAntModel.QWEN_3_1_7B,
        modelFileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        title = "Qwen 3 1.7B",
        badgeLabel = "BALANCED",
        speedAndRamSummary = "932 MB download • ~650 MB RAM • Fast 4-bit GPU",
        description = "Sweet spot of sub-second GPU speed (~41 tok/s) and stronger multi-clause backtrack & list understanding (6 GB+ RAM).",
        estimatedStandaloneRamMb = 1_050,
        estimatedIncrementalRamMb = 650,
        supportsThinking = true,
        supportsNpuBackend = false,
        promptTier = LlmPromptTier.BALANCED_1_7B,
        samplerTopK = 20,
        samplerTopP = 0.90,
        samplerTemperature = 0.08
    ),
    QWEN_2_5_1_5B(
        id = "qwen2_5_1_5b",
        desertAntModel = DesertAntModel.QWEN_2_5_1_5B,
        modelFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        title = "Qwen 2.5 1.5B Instruct",
        badgeLabel = "ZERO-THINK",
        speedAndRamSummary = "1,524 MB download • ~750 MB RAM • Direct GPU / CPU",
        description = "Direct instruction-tuned INT8 model with zero thinking step (~31–34 tok/s on GPU & CPU). Instant first-token output (6 GB+ RAM).",
        estimatedStandaloneRamMb = 1_450,
        estimatedIncrementalRamMb = 750,
        supportsThinking = false,
        supportsNpuBackend = false,
        promptTier = LlmPromptTier.DIRECT_INSTRUCT_1_5B,
        samplerTopK = 15,
        samplerTopP = 0.88,
        samplerTemperature = 0.08
    ),
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        desertAntModel = DesertAntModel.GEMMA_4_E2B,
        modelFileName = "gemma-4-E2B-it.litertlm",
        title = "Gemma 4 E2B",
        badgeLabel = "MAX QUALITY",
        speedAndRamSummary = "2,468 MB download • ~850 MB RAM • NPU Flagship",
        description = "Largest flagship Google Gemma 4 model on NPU/GPU. Highest accuracy on complex mid-sentence backtracks, nuance & custom prompts (8 GB+ RAM).",
        estimatedStandaloneRamMb = 1_900,
        estimatedIncrementalRamMb = 850,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.FLAGSHIP_GEMMA_4,
        samplerTopK = 20,
        samplerTopP = 0.90,
        samplerTemperature = 0.10
    );

    val shortLabel: String
        get() = desertAntModel.shortLabel

    companion object {
        fun fromId(id: String?): LlmModelChoice {
            if (id.isNullOrBlank()) return QWEN_3_0_6B
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: QWEN_3_0_6B
        }

        fun fromDesertAntModel(model: DesertAntModel): LlmModelChoice? {
            return entries.firstOrNull { it.desertAntModel == model }
        }
    }
}

/**
 * Selectable system prompt presets (plus [CUSTOM]) for on-device LLM post-processing.
 *
 * Each preset dynamically adapts its prompt structure via [promptForModel] to match the active
 * LLM's capacity, instruction-following profile, and prefill token sweet spot.
 */
enum class GemmaPromptPreset(
    val id: String,
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val defaultSystemPrompt: String
) {
    CLEAN_AND_POLISH(
        id = "clean_polish",
        title = "Wispr Flow Auto-Edit (Default)",
        shortLabel = "Auto-Edit",
        subtitle = "Resolves mid-sentence backtracks, strips fillers, auto-formats spoken lists, numbers, dates, emails & app-aware layout",
        defaultSystemPrompt = """Transform raw spoken dictation into clean, natural, ready-to-send written text — exactly as the speaker intended it to read — while preserving their authentic voice, tone, word choice, and language:
- Course-correction & backtracks: When the speaker changes their mind mid-thought ('actually', 'wait', 'no wait', 'sorry', 'I mean', 'scratch that', 'make that', 'oder nein', 'nein warte', 'ich meine', 'besser gesagt'), discard BOTH the retracted phrase and the repair cue, keeping only the final intended words. Keep genuine logical contrasts ('not X, but Y').
- Disfluencies & pauses: Remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('the the', 'I I'), abandoned false starts, and discourse fillers ('like', 'you know', 'sort of' ONLY when used as hesitation — never remove literal verbs or comparisons like 'I like this' or 'looks like'). Repair false mid-sentence pause periods and broken capitalization.
- Smart list detection: Automatically format clear spoken sequences ('first... second... third...', 'one... two... three...', '1... 2... 3...', 'erstens... zweitens...') as vertical numbered lists ('1. ', '2. ') with a colon (':') on any lead-in clause, and spoken bullet cues ('bullet point', 'dash item') as '- ' bullet lists. Keep short casual inline series ('milk, eggs, and bread') inline.
- Numbers, dates & units: Spell out standalone counts one through nine in ordinary prose (and idioms like 'one of the', 'a couple of', 'ein paar'), and convert 10+ plus all decimals ('3.5' / '3,5'), percentages ('25%'), currencies ('$1,500', '49,99 €'), dates, clock times ('3:30 PM', '14:30'), version numbers ('2.8.0'), ranges ('10–15'), and measurement/tech units ('8 GB', '500 ms', '16 kHz', '24 °C') into clean digits matching the transcript language.
- Tech terms, emails & structure: Capitalize acronyms and code identifiers accurately ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'API', 'JSON', 'SQL', 'GitHub', 'camelCase', 'snake_case'), format dictated email addresses and URLs ('name@example.com'), apply explicit spoken cues ('new line' / 'neue Zeile', 'new paragraph' / 'neuer Absatz') as real breaks (removing the cue), separate spoken greetings/sign-offs cleanly, and never inject corporate buzzwords or unrequested formality."""
    ),
    STRICT_CLEANUP(
        id = "strict_cleanup",
        title = "Light Cleanup (Verbatim)",
        shortLabel = "Light Cleanup",
        subtitle = "Verbatim cleanup: strips fillers, stutters & obvious backtracks, formats numbers/units & fixes punctuation with zero rephrasing",
        defaultSystemPrompt = "Perform light, verbatim dictation cleanup: remove vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm'), stutters, and obvious mid-phrase self-corrections (keeping only the intended correction), convert spoken decimals, percentages, currencies, dates, times, version numbers, measurements, and 10+ counts into digits, capitalize standard acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'API'), and repair broken punctuation, mid-sentence pause periods, and capitalization. Never rephrase, reorder, summarize, or alter the speaker's vocabulary."
    ),
    CONCISE_DIRECT(
        id = "concise_direct",
        title = "Crisp & Direct",
        shortLabel = "Crisp",
        subtitle = "Resolves backtracks and tightens rambling spoken thoughts into crisp, scannable sentences, lists & clean numbers",
        defaultSystemPrompt = "Edit the spoken dictation into crisp, concise, scannable text. Resolve all mid-sentence backtracks and self-corrections, strip vocal fillers, hedging, and redundant phrasing, format multi-item enumerations as clean lists, and format spoken numbers, decimals, percentages, currencies, dates, times, versions, and units accurately while preserving every fact, name, and the speaker's original language."
    ),
    PROFESSIONAL_MESSAGE(
        id = "professional_message",
        title = "Email & Work Message",
        shortLabel = "Email / Work",
        subtitle = "Structures emails & work messages with clean greeting/sign-off spacing, paragraphs, action lists & accurate numbers",
        defaultSystemPrompt = "Format the spoken dictation into a natural, well-structured email or work message. Resolve all mid-sentence self-corrections and remove vocal hesitations, place spoken greetings (e.g., 'Hi Sarah,') and sign-offs (e.g., 'Best,\\nAlex') on separate lines with clean paragraph breaks, format multi-step action items as numbered or bulleted lists, and convert spoken numbers, dates, times, currencies, versions, and units into clean written digits in the speaker's original language without inventing placeholders or greetings that were not spoken."
    ),
    STRUCTURED_NOTES(
        id = "structured_notes",
        title = "Structured Lists & Notes",
        shortLabel = "Lists & Notes",
        subtitle = "Organizes spoken thoughts, meeting notes, or tasks into clean bulleted or numbered lists with backtrack cleanup",
        defaultSystemPrompt = "Transform the spoken dictation into a clean, scannable set of structured notes using '- ' bullet points (or '1. ' numbered steps when sequential). Resolve mid-sentence self-corrections, remove all vocal fillers and repetitions, format spoken numbers, dates, times, versions, currencies, and units cleanly as digits, group related action items logically, and preserve the speaker's original language."
    ),
    CUSTOM(
        id = "custom",
        title = "Custom System Prompt",
        shortLabel = "Custom",
        subtitle = "Write your own custom system instruction for how the selected AI model transforms dictated speech",
        defaultSystemPrompt = "Resolve mid-sentence self-corrections, remove vocal fillers ('um', 'uh', 'like') and stutters, convert spoken numbers, dates, versions, and units into proper digits, and format the text cleanly while keeping the original meaning."
    );

    /**
     * Returns the task instruction specifically optimized for [llmChoice]'s parameter scale,
     * prefill bucket efficiency, and instruction-following behavior.
     */
    fun promptForModel(llmChoice: LlmModelChoice): String = when (llmChoice.promptTier) {
        LlmPromptTier.COMPACT_0_6B -> when (this) {
            CLEAN_AND_POLISH -> """Clean and format the dictated speech while keeping the speaker's exact meaning, tone, and language:
- Self-corrections: When the speaker corrects themselves ('wait', 'no wait', 'actually', 'I mean', 'make that', 'nein warte', 'ich meine'), keep ONLY the final intended phrase.
- Fillers & punctuation: Remove hesitation sounds ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('I I', 'the the'), and false mid-sentence periods. Fix capitalization and sentence punctuation. Keep spoken questions as questions.
- Lists: Format spoken sequences ('first... second... third...', 'erstens... zweitens...') as '1. ', '2. ' numbered lines after a ':', and 'bullet point' cues as '- ' lines. Keep short inline series ('milk, eggs, and bread') on one line.
- Numbers & tech: Write 10+, decimals ('3.5'), percentages ('25%'), money ('$1,500', '49,99 €'), times ('3:30 PM', '14:30'), versions ('2.8.0'), and units ('8 GB', '500 ms') as digits; keep one to nine as words in prose. Capitalize acronyms ('NPU', 'GPU', 'RAM', 'API', 'GitHub') and format spoken emails ('name@example.com')."""
            STRICT_CLEANUP -> "Clean the dictated text verbatim: remove fillers ('um', 'uh', 'äh', 'ähm', 'hm'), stutters, and mid-sentence self-corrections (keep only the correction), write 10+, decimals, times, money, versions, and units ('8 GB', '500 ms') as digits, capitalize acronyms ('NPU', 'GPU', 'RAM', 'API'), and fix punctuation and capitalization without changing the speaker's words."
            CONCISE_DIRECT -> "Rewrite the dictated speech into short, clear, direct sentences in the same language. Keep only the final wording after any self-correction, strip all fillers and repetition, use '1. ' or '- ' lists for multiple items, and write numbers, times, money, versions, and units cleanly as digits while keeping all facts."
            PROFESSIONAL_MESSAGE -> "Format the dictated speech as a clean email or work message in the same language. Remove fillers and self-corrections, put spoken greetings ('Hi Sarah,') and sign-offs ('Best,\\nAlex') on separate lines, format action items as '1. ' or '- ' lists, and write dates, times, money, versions, and units as digits. Do not invent greetings that were not spoken."
            STRUCTURED_NOTES -> "Turn the dictated speech into clean '- ' bullet notes (or '1. ' numbered steps for sequences) in the same language. Remove fillers and self-corrections, and format numbers, times, dates, versions, and units as digits."
            CUSTOM -> defaultSystemPrompt
        }

        LlmPromptTier.DIRECT_INSTRUCT_1_5B -> when (this) {
            CLEAN_AND_POLISH -> """Act as a deterministic speech-to-text cleanup filter (NOT a conversational assistant). Output the speaker's dictated message cleaned and formatted in their own voice and language:
- Backtracks & self-repairs: If the speaker retracts words mid-sentence ('actually', 'wait', 'no wait', 'sorry', 'I mean', 'make that', 'scratch that', 'nein warte', 'ich meine'), delete both the retracted words and the repair cue, keeping only the corrected wording. Preserve real contrasts ('not X, but Y').
- Disfluencies & sentence flow: Strip vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), word repeats ('the the'), and filler 'like'/'you know' when used purely as hesitation (keep literal uses like 'I like this'). Fix false pause periods and capitalization. Preserve questions as questions — never answer them.
- Automatic lists: Turn explicit spoken enumerations ('first... second... third...', 'one... two... three...', 'erstens... zweitens...') into vertical '1. ', '2. ' lists with a ':' on the lead-in phrase, and 'bullet point'/'dash' cues into '- ' bullets. Keep casual 3-item inline phrases inline.
- Numbers, dates & tech: Spell out standalone counts one through nine in prose, and use digits for 10+, decimals ('3.5'), percentages ('25%'), currencies ('$1,500', '49,99 €'), dates, times ('3:30 PM', '14:30'), versions ('2.8.0'), ranges ('10–15'), and units ('8 GB', '500 ms', '16 kHz'). Capitalize acronyms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'API', 'JSON', 'GitHub'), format dictated emails ('name@example.com'), and apply spoken 'new line'/'new paragraph' breaks cleanly."""
            STRICT_CLEANUP -> "Act strictly as a verbatim transcript cleaner (never reply as an assistant): remove vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm'), stutters, and mid-sentence self-repairs (keeping only the intended repair), convert 10+, decimals, percentages, currencies, dates, times, versions, and units into digits, capitalize acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'API'), and fix punctuation and capitalization without rephrasing."
            else -> defaultSystemPrompt
        }

        LlmPromptTier.BALANCED_1_7B -> when (this) {
            CLEAN_AND_POLISH -> """Edit raw spoken dictation into clean, natural written text that reads smoothly while keeping the speaker's exact voice, tone, intent, and language:
- Mid-sentence backtracks: When the speaker changes course ('actually', 'wait', 'no wait', 'sorry', 'I mean', 'scratch that', 'make that', 'oder nein', 'nein warte', 'ich meine'), remove both the abandoned phrase and the repair cue, keeping only the final intended words. Keep true contrasts ('not X, but Y').
- Fillers & punctuation: Strip vocal hesitations ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('the the', 'I I'), false starts, and hesitation 'like'/'you know' (never remove literal 'like' in 'I like this' or 'looks like'). Repair mid-sentence pause periods and capitalization.
- Smart list layout: Format clear spoken sequences ('first... second... third...', 'one... two... three...', 'erstens... zweitens...') as vertical '1. ', '2. ' lists with a colon ':' after any lead-in clause, and 'bullet point'/'dash item' cues as '- ' lists. Keep short casual series ('milk, eggs, and bread') inline.
- Numbers, times & units: Spell out standalone counts one to nine in ordinary prose (and idioms like 'one of the', 'a couple of'), and format 10+, decimals ('3.5' / '3,5'), percentages ('25%'), currencies ('$1,500', '49,99 €'), dates, clock times ('3:30 PM', '14:30'), versions ('2.8.0'), ranges ('10–15'), and units ('8 GB', '500 ms', '16 kHz', '24 °C') as clean digits.
- Tech terms, emails & breaks: Capitalize acronyms & identifiers ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'API', 'JSON', 'SQL', 'GitHub', 'camelCase', 'snake_case'), format spoken emails/URLs ('name@example.com'), and turn explicit spoken 'new line'/'new paragraph' cues into line breaks without adding unrequested formality."""
            else -> defaultSystemPrompt
        }

        LlmPromptTier.FLAGSHIP_GEMMA_4 -> defaultSystemPrompt
    }

    companion object {
        fun fromId(id: String?): GemmaPromptPreset {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLEAN_AND_POLISH
        }
    }
}

/**
 * Hardware acceleration preference for LiteRT on-device audio inference.
 */
enum class HardwareAccelerator(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    AUTO(
        id = "auto",
        displayName = "Auto (NPU / GPU / CPU)",
        shortLabel = "Auto",
        description = "Automatically selects Qualcomm Hexagon NPU, MediaTek NeuroPilot, Tensor TPU, Exynos NPU, or OpenCL GPU with XNNPACK fallback"
    ),
    NPU(
        id = "npu",
        displayName = "NPU / AI Accelerator",
        shortLabel = "NPU",
        description = "Prioritizes Qualcomm Hexagon HTP/QNN, MediaTek NeuroPilot APU, Google Tensor TPU, Exynos ENN, Kirin HiAI & NNAPI"
    ),
    GPU(
        id = "gpu",
        displayName = "GPU (OpenCL / GLES)",
        shortLabel = "GPU",
        description = "Accelerates neural audio models on Adreno / Mali / Immortalis GPU via LiteRT OpenCL/GLES with CPU fallback"
    ),
    CPU(
        id = "cpu",
        displayName = "CPU (XNNPACK)",
        shortLabel = "CPU",
        description = "Multi-threaded ARM NEON / XNNPACK execution; lowest memory footprint and maximum stability"
    );

    companion object {
        fun fromId(id: String?): HardwareAccelerator {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Immutable snapshot of user-configurable Desert Ant model settings persisted in SharedPreferences.
 */
data class ModelSettings(
    val earEnabled: Boolean = true,
    val punctEnabled: Boolean = false,
    val gemmaEnabled: Boolean = false,
    val selectedLlmModel: LlmModelChoice = LlmModelChoice.QWEN_3_0_6B,
    val gemmaDisableThinking: Boolean = false,
    val gemmaPromptPreset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH,
    val gemmaCustomPrompt: String = "",
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList(),
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO
) {
    /**
     * `Voz` is the core STT engine and cannot be disabled.
     */
    val vozEnabled: Boolean = true

    /**
     * Resolves the active model-optimized task instruction based on [selectedLlmModel],
     * [gemmaPromptPreset], and [gemmaCustomPrompt].
     */
    fun resolvedGemmaTaskInstruction(): String {
        return if (gemmaPromptPreset == GemmaPromptPreset.CUSTOM) {
            gemmaCustomPrompt.trim().ifEmpty { GemmaPromptPreset.CUSTOM.promptForModel(selectedLlmModel) }
        } else {
            gemmaPromptPreset.promptForModel(selectedLlmModel)
        }
    }

    /**
     * Active disk footprint in MB based on currently enabled models (at most one selected LLM).
     */
    val activeDiskFootprintMb: Int
        get() = DesertAntModel.VOZ.sizeMb +
            (if (earEnabled) DesertAntModel.EAR.sizeMb else 0) +
            (if (punctEnabled) DesertAntModel.PUNCT.sizeMb else 0) +
            (if (gemmaEnabled) selectedLlmModel.desertAntModel.sizeMb else 0)

    /**
     * Estimated peak RAM in MB based on models that can ever co-reside in RAM:
     * - `Voz` (1,187 MB weights + runtime buffers): 1,400 MB
     * - `Ear` (10 MB weights + runtime buffers): +50 MB
     * - Text cleanup stage (strictly mutually exclusive in RAM — `Punct` is never loaded alongside an active LLM):
     *   - Active LLM (`Qwen 3 0.6B Lite` +350 MB, `Qwen 3 0.6B` +450 MB, `Qwen 3 1.7B` +650 MB,
     *     `Qwen 2.5 1.5B` +750 MB, `Gemma 4 E2B` +850 MB), OR
     *   - `Punct` (+120 MB) when AI post-processing is disabled.
     */
    val estimatedPeakRamMb: Int
        get() = 1400 +
            (if (earEnabled) 50 else 0) +
            when {
                gemmaEnabled -> selectedLlmModel.estimatedIncrementalRamMb
                punctEnabled -> 120
                else -> 0
            }

    fun isModelEnabled(model: DesertAntModel): Boolean = when (model) {
        DesertAntModel.VOZ -> true
        DesertAntModel.EAR -> earEnabled
        DesertAntModel.PUNCT -> punctEnabled
        DesertAntModel.QWEN_3_0_6B_LITE,
        DesertAntModel.QWEN_3_0_6B,
        DesertAntModel.QWEN_3_1_7B,
        DesertAntModel.QWEN_2_5_1_5B,
        DesertAntModel.GEMMA_4_E2B -> gemmaEnabled && model == selectedLlmModel.desertAntModel
    }

    fun enabledModels(): List<DesertAntModel> = buildList {
        add(DesertAntModel.VOZ)
        if (earEnabled) add(DesertAntModel.EAR)
        if (punctEnabled) add(DesertAntModel.PUNCT)
        if (gemmaEnabled) add(selectedLlmModel.desertAntModel)
    }

    companion object {
        const val PREFS_NAME = "openflow_model_settings"
        const val KEY_EAR_ENABLED = "ear_enabled"
        const val KEY_PUNCT_ENABLED = "punct_enabled"
        const val KEY_GEMMA_ENABLED = "gemma_enabled"
        const val KEY_SELECTED_LLM_MODEL = "selected_llm_model"
        const val KEY_GEMMA_DISABLE_THINKING = "gemma_disable_thinking"
        const val KEY_GEMMA_PROMPT_PRESET = "gemma_prompt_preset"
        const val KEY_GEMMA_CUSTOM_PROMPT = "gemma_custom_prompt"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_CUSTOM_VOCABULARY = "custom_vocabulary"
        const val KEY_HARDWARE_ACCELERATOR = "hardware_accelerator"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        fun defaultPreferredLanguage(): String {
            val systemLang = Locale.getDefault().language.lowercase(Locale.ROOT)
            return if (systemLang in Voz.supportedLanguages) systemLang else "en"
        }

        fun getPreferences(context: Context): SharedPreferences {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun load(context: Context): ModelSettings {
            val prefs = getPreferences(context)
            val defaultLang = defaultPreferredLanguage()
            val rawLang = prefs.getString(KEY_PREFERRED_LANGUAGE, defaultLang) ?: defaultLang
            val sanitizedLang = if (rawLang in Voz.supportedLanguages) rawLang else defaultLang

            val vocabJson = prefs.getString(KEY_CUSTOM_VOCABULARY, null)
            val vocabList = parseVocabularyJson(vocabJson)
            val accel = HardwareAccelerator.fromId(
                prefs.getString(KEY_HARDWARE_ACCELERATOR, HardwareAccelerator.AUTO.id)
            )
            val preset = GemmaPromptPreset.fromId(
                prefs.getString(KEY_GEMMA_PROMPT_PRESET, GemmaPromptPreset.CLEAN_AND_POLISH.id)
            )
            val customPrompt = prefs.getString(KEY_GEMMA_CUSTOM_PROMPT, "") ?: ""
            val gemmaEnabled = prefs.getBoolean(KEY_GEMMA_ENABLED, false)
            val selectedLlm = if (prefs.contains(KEY_SELECTED_LLM_MODEL)) {
                LlmModelChoice.fromId(prefs.getString(KEY_SELECTED_LLM_MODEL, null))
            } else if (gemmaEnabled || DesertAntModelStorage.isModelDownloaded(context, DesertAntModel.GEMMA_4_E2B)) {
                LlmModelChoice.GEMMA_4_E2B
            } else {
                LlmModelChoice.QWEN_3_0_6B
            }

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                punctEnabled = prefs.getBoolean(KEY_PUNCT_ENABLED, false),
                gemmaEnabled = gemmaEnabled,
                selectedLlmModel = selectedLlm,
                gemmaDisableThinking = prefs.getBoolean(KEY_GEMMA_DISABLE_THINKING, false),
                gemmaPromptPreset = preset,
                gemmaCustomPrompt = customPrompt,
                preferredLanguage = sanitizedLang,
                customVocabulary = vocabList,
                hardwareAccelerator = accel
            )
        }

        fun save(context: Context, settings: ModelSettings) {
            val sanitizedVocab = settings.customVocabulary
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val jsonArray = JSONArray()
            sanitizedVocab.forEach { jsonArray.put(it) }

            val validLang = if (settings.preferredLanguage in Voz.supportedLanguages) {
                settings.preferredLanguage
            } else {
                defaultPreferredLanguage()
            }

            getPreferences(context).edit()
                .putBoolean(KEY_EAR_ENABLED, settings.earEnabled)
                .putBoolean(KEY_PUNCT_ENABLED, settings.punctEnabled)
                .putBoolean(KEY_GEMMA_ENABLED, settings.gemmaEnabled)
                .putString(KEY_SELECTED_LLM_MODEL, settings.selectedLlmModel.id)
                .putBoolean(KEY_GEMMA_DISABLE_THINKING, settings.gemmaDisableThinking)
                .putString(KEY_GEMMA_PROMPT_PRESET, settings.gemmaPromptPreset.id)
                .putString(KEY_GEMMA_CUSTOM_PROMPT, settings.gemmaCustomPrompt)
                .putString(KEY_PREFERRED_LANGUAGE, validLang)
                .putString(KEY_CUSTOM_VOCABULARY, jsonArray.toString())
                .putString(KEY_HARDWARE_ACCELERATOR, settings.hardwareAccelerator.id)
                .apply()
        }

        fun setOnboardingCompleted(context: Context, completed: Boolean) {
            getPreferences(context).edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
                .apply()
        }

        fun isOnboardingCompleted(context: Context): Boolean {
            return getPreferences(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
        }

        private fun parseVocabularyJson(rawJson: String?): List<String> {
            if (rawJson.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(rawJson)
                buildList {
                    for (i in 0 until array.length()) {
                        val term = array.optString(i, "").trim()
                        if (term.isNotEmpty()) {
                            add(term)
                        }
                    }
                }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun languageDisplayName(isoCode: String): String {
            val locale = Locale(isoCode)
            val display = locale.getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            return if (display.isNotBlank() && !display.equals(isoCode, ignoreCase = true)) {
                "$display ($isoCode)"
            } else {
                isoCode.uppercase(Locale.ROOT)
            }
        }
    }
}

/**
 * Helper object for checking on-disk model cache status in O(1) time without spawning native
 * JNI handles, managing verified artifact stamps, and deleting inactive model files on disk.
 */
object DesertAntModelStorage {

    private const val VERIFIED_STAMP_FILE = ".openflow_verified"

    private val MODEL_REVISIONS = mapOf(
        DesertAntModel.VOZ to "v0.4.0",
        DesertAntModel.EAR to "v0.2.0",
        DesertAntModel.PUNCT to "v1.0.0",
        DesertAntModel.QWEN_3_0_6B_LITE to "v1.0.0",
        DesertAntModel.QWEN_3_0_6B to "v1.0.0",
        DesertAntModel.QWEN_3_1_7B to "v1.0.0",
        DesertAntModel.QWEN_2_5_1_5B to "v1.0.0",
        DesertAntModel.GEMMA_4_E2B to "v1.0.0"
    )

    // In-memory cache of (targetDir.absolutePath -> (lastModifiedSum, sizeSum)) for verified model directories
    // so repeated per-chunk / per-UI checks avoid redundant disk stat() syscalls when nothing on disk changed.
    private val directoryCompleteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    @Volatile
    private var bundledExtractionChecked = false

    @Volatile
    private var legacyModelsCleanedUp = false

    fun modelRevision(model: DesertAntModel): String {
        return MODEL_REVISIONS.getValue(model)
    }

    fun modelCacheRoot(context: Context, model: DesertAntModel): File {
        return File(context.applicationContext.cacheDir, "desert-ant-models/${model.id}")
    }

    fun modelRevisionDir(context: Context, model: DesertAntModel): File {
        return File(modelCacheRoot(context, model), modelRevision(model))
    }

    /**
     * Automatically removes any leftover `clear` or `uhm` model directories from previous releases
     * to reclaim up to 70 MB of device storage.
     */
    fun cleanupLegacyRemovedModels(context: Context) {
        if (legacyModelsCleanedUp) return
        legacyModelsCleanedUp = true
        try {
            val modelsBase = File(context.applicationContext.cacheDir, "desert-ant-models")
            if (modelsBase.exists()) {
                for (legacyId in listOf("clear", "uhm")) {
                    val dir = File(modelsBase, legacyId)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * If this APK is the Offline-Bundled build containing pre-packaged model files under
     * `assets/desert-ant-models/`, extracts any missing model files and `.dal-meta/manifest`
     * into `context.cacheDir/desert-ant-models/<id>/<revision>` automatically.
     */
    @Synchronized
    fun ensureBundledModelsExtracted(context: Context) {
        cleanupLegacyRemovedModels(context)
        if (bundledExtractionChecked) return
        val appContext = context.applicationContext
        val assetManager = appContext.assets
        try {
            val bundledModels = assetManager.list("desert-ant-models") ?: emptyArray()
            if (bundledModels.isEmpty()) {
                bundledExtractionChecked = true
                return
            }
            for ((model, rev) in MODEL_REVISIONS) {
                if (model.id !in bundledModels) continue
                val assetBase = "desert-ant-models/${model.id}/$rev"
                val assetFiles = assetManager.list(assetBase) ?: emptyArray()
                if (assetFiles.isEmpty()) continue

                val targetDir = File(modelCacheRoot(appContext, model), rev)
                val targetManifest = File(File(targetDir, ".dal-meta"), "manifest")
                if (targetManifest.exists() && targetManifest.length() > 0L) {
                    continue
                }

                targetDir.mkdirs()
                var hasDalMeta = false
                for (entry in assetFiles) {
                    if (entry == "dal_meta") {
                        hasDalMeta = true
                    } else {
                        val outFile = File(targetDir, entry)
                        if (!outFile.exists() || outFile.length() == 0L) {
                            val tempFile = File(targetDir, "$entry.part")
                            assetManager.open("$assetBase/$entry").use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 256 * 1024)
                                }
                            }
                            if (outFile.exists()) outFile.delete()
                            tempFile.renameTo(outFile)
                        }
                    }
                }
                if (hasDalMeta) {
                    val metaDir = File(targetDir, ".dal-meta")
                    metaDir.mkdirs()
                    assetManager.open("$assetBase/dal_meta/manifest").use { input ->
                        targetManifest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val specArtifacts = ModelDownloadManager.MODEL_SPECS[model]?.second
                if (specArtifacts != null) {
                    writeModelManifestAndStamp(targetDir, specArtifacts)
                }
            }
            bundledExtractionChecked = true
        } catch (_: Throwable) {
            bundledExtractionChecked = true
        }
    }

    /**
     * Fast, non-blocking O(1) check that verifies whether all required model artifacts exist
     * on disk with their exact expected byte sizes and no incomplete `.part` files.
     *
     * Critically, this does NOT instantiate native `Voz`/`Ear` JNI objects, avoiding
     * `ModelStore`'s 1.2 GB pure-Swift `SoftwareSHA256` memory spike on UI/background threads.
     */
    fun isModelDownloaded(context: Context, model: DesertAntModel): Boolean {
        val appContext = context.applicationContext
        ensureBundledModelsExtracted(appContext)
        return try {
            val (revision, artifacts) = ModelDownloadManager.MODEL_SPECS[model] ?: return false
            val targetDir = File(modelCacheRoot(appContext, model), revision)
            isModelDirectoryComplete(targetDir, artifacts)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether all required model artifacts exist on disk AND have valid SHA-256 verification
     * stamps in `.openflow_verified`.
     */
    fun isModelVerified(context: Context, model: DesertAntModel): Boolean {
        val appContext = context.applicationContext
        ensureBundledModelsExtracted(appContext)
        return try {
            val (revision, artifacts) = ModelDownloadManager.MODEL_SPECS[model] ?: return false
            val targetDir = File(modelCacheRoot(appContext, model), revision)
            areAllArtifactsVerified(targetDir, artifacts)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Pure directory completeness check testable without Android Context.
     */
    fun isModelDirectoryComplete(targetDir: File, artifacts: List<ModelFileArtifact>): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory || artifacts.isEmpty()) {
            directoryCompleteCache.remove(targetDir.absolutePath)
            return false
        }
        val manifestFile = File(File(targetDir, ".dal-meta"), "manifest")
        val dirKey = targetDir.absolutePath
        if (manifestFile.exists() && manifestFile.length() > 0L) {
            val cached = directoryCompleteCache[dirKey]
            if (cached != null) {
                val dirLastMod = targetDir.lastModified()
                if (dirLastMod > 0L && dirLastMod == cached.first && artifacts.size.toLong() == cached.second) {
                    return true
                }
            }
        }
        for (artifact in artifacts) {
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")
            if (partFile.exists()) {
                directoryCompleteCache.remove(dirKey)
                return false
            }
            if (!finalFile.exists() || !finalFile.isFile || finalFile.length() != artifact.sizeBytes) {
                directoryCompleteCache.remove(dirKey)
                return false
            }
        }
        if (!manifestFile.exists()) {
            try {
                writeModelManifest(targetDir, artifacts)
            } catch (_: Throwable) {
            }
        }
        val finalDirMod = targetDir.lastModified()
        if (finalDirMod > 0L) {
            directoryCompleteCache[dirKey] = finalDirMod to artifacts.size.toLong()
        }
        return true
    }

    /**
     * Checks whether all [artifacts] in [targetDir] exist and have valid `.openflow_verified` stamps.
     */
    fun areAllArtifactsVerified(targetDir: File, artifacts: List<ModelFileArtifact>): Boolean {
        if (!isModelDirectoryComplete(targetDir, artifacts)) return false
        return artifacts.all { isArtifactAlreadyVerified(targetDir, it) }
    }

    /**
     * Checks if a single [artifact] in [targetDir] has already been SHA-256 verified and recorded
     * in `.openflow_verified` with matching file size and lastModified timestamp.
     */
    fun isArtifactAlreadyVerified(targetDir: File, artifact: ModelFileArtifact): Boolean {
        val finalFile = File(targetDir, artifact.relativePath)
        val partFile = File(targetDir, "${artifact.relativePath}.part")
        if (partFile.exists()) return false
        if (!finalFile.exists() || !finalFile.isFile || finalFile.length() != artifact.sizeBytes) {
            return false
        }
        val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
        if (!stampFile.exists()) return false
        return try {
            val expectedLine = artifactStampLine(finalFile, artifact)
            stampFile.readLines().any { it.trim() == expectedLine }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Appends or updates the verified stamp entry for [artifact] in [targetDir].
     */
    @Synchronized
    fun markArtifactVerified(targetDir: File, artifact: ModelFileArtifact) {
        val finalFile = File(targetDir, artifact.relativePath)
        if (!finalFile.exists() || finalFile.length() != artifact.sizeBytes) return
        try {
            val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
            val prefix = "${artifact.relativePath}\t"
            val existing = if (stampFile.exists()) {
                stampFile.readLines().filter { it.isNotBlank() && !it.startsWith(prefix) }
            } else {
                emptyList()
            }
            val updated = existing + artifactStampLine(finalFile, artifact)
            stampFile.writeText(updated.joinToString("\n") + "\n")
            directoryCompleteCache.remove(targetDir.absolutePath)
        } catch (_: Throwable) {
        }
    }

    /**
     * Writes `.dal-meta/manifest` for [artifacts] in [targetDir] without touching `.openflow_verified`.
     */
    @Synchronized
    fun writeModelManifest(targetDir: File, artifacts: List<ModelFileArtifact>) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val metaDir = File(targetDir, ".dal-meta")
        metaDir.mkdirs()
        val manifestFile = File(metaDir, "manifest")
        val rLines = artifacts.joinToString("\n") { "R\t${it.relativePath}" }
        val fLines = artifacts.joinToString("\n") { "F\t${it.relativePath}\t${it.sizeBytes}\t${it.sha256}" }
        manifestFile.writeText("DAL1\n$rLines\n$fLines\n")
    }

    /**
     * Writes `.dal-meta/manifest` and `.openflow_verified` for all [artifacts] in [targetDir]
     * after their SHA-256 hashes have been verified.
     */
    @Synchronized
    fun writeModelManifestAndStamp(targetDir: File, artifacts: List<ModelFileArtifact>) {
        writeModelManifest(targetDir, artifacts)
        val stampLines = artifacts.mapNotNull { artifact ->
            val f = File(targetDir, artifact.relativePath)
            if (f.exists() && f.length() == artifact.sizeBytes) {
                artifactStampLine(f, artifact)
            } else {
                null
            }
        }
        if (stampLines.size == artifacts.size) {
            File(targetDir, VERIFIED_STAMP_FILE).writeText(stampLines.joinToString("\n") + "\n")
        }
        directoryCompleteCache.remove(targetDir.absolutePath)
    }

    private fun artifactStampLine(file: File, artifact: ModelFileArtifact): String {
        return "${artifact.relativePath}\t${file.length()}\t${file.lastModified()}\t${artifact.sha256.lowercase(Locale.ROOT)}"
    }

    fun areAllEnabledModelsDownloaded(context: Context, settings: ModelSettings = ModelSettings.load(context)): Boolean {
        return settings.enabledModels().all { isModelDownloaded(context, it) }
    }

    fun countDownloadedModels(context: Context): Int {
        return DesertAntModel.entries.count { isModelDownloaded(context, it) }
    }

    /**
     * Downloads [model] on [Dispatchers.IO] while emitting progress in `0.0f..1.0f`.
     */
    suspend fun downloadModel(
        context: Context,
        model: DesertAntModel,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (isModelDownloaded(appContext, model)) {
            onProgress(1.0f)
            return@withContext
        }
        ModelDownloadManager.downloadSingleModelDirect(appContext, model, onProgress)
    }

    /**
     * Deletes cached files on disk for a non-required model (`Ear`).
     * `Voz` is locked and cannot be deleted.
     */
    fun deleteModelCache(context: Context, model: DesertAntModel): Boolean {
        if (model.isRequired) return false
        val revDir = modelRevisionDir(context, model)
        directoryCompleteCache.remove(revDir.absolutePath)
        val root = modelCacheRoot(context, model)
        return if (root.exists()) {
            root.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Deletes all downloaded optional models that are currently toggled OFF in [settings].
     */
    fun deleteAllInactiveModels(context: Context, settings: ModelSettings): Int {
        var deletedCount = 0
        for (model in DesertAntModel.entries) {
            if (!model.isRequired && !settings.isModelEnabled(model) && isModelDownloaded(context, model)) {
                if (deleteModelCache(context, model)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
