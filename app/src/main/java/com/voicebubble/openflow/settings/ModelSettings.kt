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
        displayName = "Qwen 3 0.6B Lite",
        roleSummary = "0.32 GB • 4-bit model for light cleanup",
        sizeMb = 329,
        expectedBytes = 344_671_744L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_3_0_6B(
        id = "qwen3_0_6b",
        shortLabel = "Qwen 3 0.6B",
        displayName = "Qwen 3 0.6B",
        roleSummary = "0.57 GB • Compact model for multilingual cleanup",
        sizeMb = 585,
        expectedBytes = 613_722_264L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_3_1_7B(
        id = "qwen3_1_7b",
        shortLabel = "Qwen 3 1.7B",
        displayName = "Qwen 3 1.7B",
        roleSummary = "0.91 GB • Mid-size model for multi-clause edits",
        sizeMb = 932,
        expectedBytes = 977_184_032L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    QWEN_2_5_1_5B(
        id = "qwen2_5_1_5b",
        shortLabel = "Qwen 2.5 1.5B",
        displayName = "Qwen 2.5 1.5B Instruct",
        roleSummary = "1.49 GB • Direct non-thinking instruct model",
        sizeMb = 1524,
        expectedBytes = 1_597_922_856L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    ),
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        shortLabel = "Gemma 4 E2B",
        displayName = "Gemma 4 E2B",
        roleSummary = "2.41 GB • Largest model for complex edits",
        sizeMb = 2468,
        expectedBytes = 2_588_147_712L,
        isRequired = false,
        isExperimental = true,
        isLlmModel = true
    );

    val sizeGbLabel: String
        get() = String.format(Locale.US, "%.2f GB", expectedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
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
    val preferredMaxContextTokens: Int,
    val thinkingTokenBudgetCap: Int,
    val samplerTopK: Int,
    val samplerTopP: Double,
    val samplerTemperature: Double
) {
    QWEN_3_0_6B_LITE(
        id = "qwen3_0_6b_lite",
        desertAntModel = DesertAntModel.QWEN_3_0_6B_LITE,
        modelFileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        title = "Qwen 3 0.6B Lite",
        badgeLabel = "LITE",
        speedAndRamSummary = "0.32 GB",
        description = "4-bit light cleanup",
        estimatedStandaloneRamMb = 480,
        estimatedIncrementalRamMb = 350,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.COMPACT_0_6B,
        preferredMaxContextTokens = 1_536,
        thinkingTokenBudgetCap = 48,
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
        speedAndRamSummary = "0.57 GB",
        description = "Compact multilingual cleanup",
        estimatedStandaloneRamMb = 650,
        estimatedIncrementalRamMb = 450,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.COMPACT_0_6B,
        preferredMaxContextTokens = 1_536,
        thinkingTokenBudgetCap = 56,
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
        speedAndRamSummary = "0.91 GB",
        description = "Mid-size multi-clause edits",
        estimatedStandaloneRamMb = 1_050,
        estimatedIncrementalRamMb = 650,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.BALANCED_1_7B,
        preferredMaxContextTokens = 2_048,
        thinkingTokenBudgetCap = 88,
        samplerTopK = 20,
        samplerTopP = 0.90,
        samplerTemperature = 0.08
    ),
    QWEN_2_5_1_5B(
        id = "qwen2_5_1_5b",
        desertAntModel = DesertAntModel.QWEN_2_5_1_5B,
        modelFileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        title = "Qwen 2.5 1.5B Instruct",
        badgeLabel = "INSTRUCT",
        speedAndRamSummary = "1.49 GB",
        description = "Direct non-thinking instruct",
        estimatedStandaloneRamMb = 1_450,
        estimatedIncrementalRamMb = 750,
        supportsThinking = false,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.DIRECT_INSTRUCT_1_5B,
        preferredMaxContextTokens = 1_536,
        thinkingTokenBudgetCap = 0,
        samplerTopK = 15,
        samplerTopP = 0.88,
        samplerTemperature = 0.08
    ),
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        desertAntModel = DesertAntModel.GEMMA_4_E2B,
        modelFileName = "gemma-4-E2B-it.litertlm",
        title = "Gemma 4 E2B",
        badgeLabel = "FLAGSHIP",
        speedAndRamSummary = "2.41 GB",
        description = "Flagship reasoning & structure",
        estimatedStandaloneRamMb = 1_900,
        estimatedIncrementalRamMb = 850,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.FLAGSHIP_GEMMA_4,
        preferredMaxContextTokens = 2_048,
        thinkingTokenBudgetCap = 256,
        samplerTopK = 20,
        samplerTopP = 0.90,
        samplerTemperature = 0.10
    );

    val sizeGbLabel: String
        get() = desertAntModel.sizeGbLabel

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
        defaultSystemPrompt = """Edit raw spoken dictation into clean, natural, ready-to-send text in the speaker's exact voice, tone, and language:
1. Self-corrections & backtracks: When the speaker retracts or revises words mid-thought ('wait', 'no wait', 'wait no', 'actually', 'actually sorry', 'sorry', 'I mean', 'scratch that', 'make that', 'nein warte', 'oder nein', 'ich meine', 'besser gesagt'), delete BOTH the abandoned phrase and the repair cue, keeping ONLY the final intended wording. Keep genuine contrasts ('not X, but Y').
2. Fillers, stutters & sentence flow: Strip vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), word stutters ('the the', 'I I'), false starts, and hesitation 'like' / 'you know' / 'sort of' (never remove literal uses like 'I like this' or 'looks like'). Repair false mid-sentence pause periods, capitalization, and punctuation; keep spoken questions as questions.
3. Vertical numbered & bulleted lists: Format spoken enumerations ('number one... number two... number three...', 'first... second... third...', 'one... two... three...', 'point one... point two...', 'item one... item two...', 'erstens... zweitens...', 'punkt eins... punkt zwei...') with a colon ':' on any lead-in clause (replacing spoken 'colon' / 'doppelpunkt') and put EVERY item on its OWN NEW LINE starting with '1. ', '2. ', '3. ' (stripping the spoken cue). Format 'bullet point' / 'dash item' cues as separate '- ' lines; keep casual inline triples ('milk, eggs, and bread') inline.
4. Numbers, dates & units: ALWAYS use digits (including 0–9) when paired with any unit, time, date, percent, money, version, or spec/model name — percentages ('98%', '25%'), currencies ('$4,250.75', '49,99 €'), clock times ('4:45 PM', '15:30'), dates ('October 30'), version numbers ('version 2.10', '2.8.0'), units ('0 MB', '6 GB', '1.9 GB', '380 ms', '42.5 tokens/s', '5 minutes'), and model names ('Qwen 3 0.6B', 'Gemma 4 E2B'). Spell out standalone prose counts one through nine ONLY when not paired with a unit, and use digits for standalone 10+.
5. Paragraphs, emails & tech casing: Apply explicit layout cues ('new paragraph' / 'neuer Absatz' -> blank line, 'new line' / 'neue Zeile' -> line break, 'em dash' -> ' — ') removing the cue, format spoken emails & domains ('elena dot kovacs at openflow dot dev' -> 'elena.kovacs@openflow.dev'), capitalize technical terms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'SQL', 'GitHub', 'OpenFlow', 'camelCase', 'snake_case'), and separate spoken greetings/sign-offs without inventing unspoken ones."""
    ),
    STRICT_CLEANUP(
        id = "strict_cleanup",
        title = "Light Cleanup (Verbatim)",
        shortLabel = "Light Cleanup",
        subtitle = "Verbatim cleanup: strips fillers, stutters & obvious backtracks, formats numbers/units & fixes punctuation with zero rephrasing",
        defaultSystemPrompt = "Perform light, verbatim dictation cleanup: remove vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm'), stutters, and obvious mid-phrase self-corrections (keeping only the intended correction), convert spoken decimals, percentages, currencies, dates, times, version numbers, measurements/units ('0 MB', '6 GB', '380 ms'), and 10+ counts into digits, capitalize standard acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'KV', 'API'), and repair broken punctuation, mid-sentence pause periods, and capitalization. Never rephrase, reorder, summarize, or alter the speaker's vocabulary."
    ),
    CONCISE_DIRECT(
        id = "concise_direct",
        title = "Crisp & Direct",
        shortLabel = "Crisp",
        subtitle = "Resolves backtracks and tightens rambling spoken thoughts into crisp, scannable sentences, lists & clean numbers",
        defaultSystemPrompt = "Edit the spoken dictation into crisp, concise, scannable text. Resolve all mid-sentence backtracks and self-corrections, strip vocal fillers, hedging, and redundant phrasing, place multi-item enumerations on separate '1. ' or '- ' lines after a ':', and format spoken numbers, decimals, percentages, currencies, dates, times, versions, and units accurately as digits while preserving every fact, name, and the speaker's original language."
    ),
    PROFESSIONAL_MESSAGE(
        id = "professional_message",
        title = "Email & Work Message",
        shortLabel = "Email / Work",
        subtitle = "Structures emails & work messages with clean greeting/sign-off spacing, paragraphs, action lists & accurate numbers",
        defaultSystemPrompt = "Format the spoken dictation into a natural, well-structured email or work message. Resolve all mid-sentence self-corrections and remove vocal hesitations, place spoken greetings (e.g., 'Hi Sarah,') and sign-offs (e.g., 'Best,\\nAlex') on separate lines with clean paragraph breaks, place multi-step action items on separate '1. ' or '- ' lines, and convert spoken numbers, dates, times, currencies, versions, and units into clean written digits in the speaker's original language without inventing placeholders or greetings that were not spoken."
    ),
    STRUCTURED_NOTES(
        id = "structured_notes",
        title = "Structured Lists & Notes",
        shortLabel = "Lists & Notes",
        subtitle = "Organizes spoken thoughts, meeting notes, or tasks into clean bulleted or numbered lists with backtrack cleanup",
        defaultSystemPrompt = "Transform the spoken dictation into a clean, scannable set of structured notes with each point on its own '- ' bullet line (or '1. ' numbered line when sequential). Resolve mid-sentence self-corrections, remove all vocal fillers and repetitions, format spoken numbers, dates, times, versions, currencies, and units cleanly as digits, group related action items logically, and preserve the speaker's original language."
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
- Self-corrections: When the speaker corrects themselves ('wait', 'no wait', 'actually', 'sorry', 'I mean', 'scratch that', 'make that', 'nein warte', 'ich meine'), keep ONLY the final intended phrase.
- Fillers & punctuation: Remove hesitation sounds ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('I I', 'the the'), and false mid-sentence periods. Fix capitalization and sentence punctuation. Keep spoken questions as questions.
- Lists: Format spoken sequences ('number one... number two...', 'first... second... third...', 'erstens... zweitens...') with each item on its own new line ('1. ', '2. ') after ':', and 'bullet point' cues as '- ' lines. Keep short inline series ('milk, eggs, and bread') on one line.
- Numbers & tech: Use digits whenever paired with units, times, dates, money, percents, versions, or model names ('0 MB', '6 GB', '5 minutes', '380 ms', '25%', '$1,500', '3:30 PM', '2.8.0', 'Qwen 3 0.6B') and for 10+; keep standalone one to nine as words in prose. Capitalize acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'KV', 'API', 'GitHub') and format spoken emails ('name@example.com')."""
            STRICT_CLEANUP -> "Clean the dictated text verbatim: remove fillers ('um', 'uh', 'äh', 'ähm', 'hm'), stutters, and mid-sentence self-corrections (keep only the correction), write 10+, decimals, times, money, versions, and units ('0 MB', '8 GB', '500 ms') as digits, capitalize acronyms ('NPU', 'GPU', 'RAM', 'API'), and fix punctuation and capitalization without changing the speaker's words."
            CONCISE_DIRECT -> "Rewrite the dictated speech into short, clear, direct sentences in the same language. Keep only the final wording after any self-correction, strip all fillers and repetition, put multiple enumerated items on separate '1. ' or '- ' lines, and write numbers, times, money, versions, and units cleanly as digits while keeping all facts."
            PROFESSIONAL_MESSAGE -> "Format the dictated speech as a clean email or work message in the same language. Remove fillers and self-corrections, put spoken greetings ('Hi Sarah,') and sign-offs ('Best,\\nAlex') on separate lines, put action items on separate '1. ' or '- ' lines, and write dates, times, money, versions, and units as digits. Do not invent greetings that were not spoken."
            STRUCTURED_NOTES -> "Turn the dictated speech into clean '- ' bullet notes (or '1. ' numbered lines for sequences), one item per line, in the same language. Remove fillers and self-corrections, and format numbers, times, dates, versions, and units as digits."
            CUSTOM -> defaultSystemPrompt
        }

        LlmPromptTier.DIRECT_INSTRUCT_1_5B -> when (this) {
            CLEAN_AND_POLISH -> """Act as a deterministic speech-to-text cleanup filter (NOT a conversational assistant). Output the speaker's dictated message cleaned and formatted in their own voice and language:
- Backtracks & self-repairs: If the speaker retracts words mid-sentence ('actually', 'wait', 'no wait', 'sorry', 'I mean', 'make that', 'scratch that', 'nein warte', 'ich meine'), delete both the retracted words and the repair cue, keeping only the corrected wording. Preserve real contrasts ('not X, but Y').
- Disfluencies & sentence flow: Strip vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), word repeats ('the the'), and filler 'like'/'you know' when used purely as hesitation (keep literal uses like 'I like this'). Fix false pause periods and capitalization. Preserve questions as questions — never answer them.
- Automatic vertical lists: Turn spoken enumerations ('number one... number two...', 'first... second... third...', 'one... two... three...', 'erstens... zweitens...') into vertical '1. ', '2. ' lines (each on its own new line after ':'), and 'bullet point'/'dash' cues into '- ' lines. Keep casual 3-item inline phrases inline.
- Numbers, dates & tech: Use digits for any number paired with a unit, time, date, percent, currency, version, or model name ('0 MB', '8 GB', '500 ms', '5 minutes', '25%', '$1,500', '3:30 PM', '2.8.0', 'Qwen 3 0.6B') and for standalone 10+; spell out standalone counts one through nine in prose. Capitalize acronyms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'GitHub'), format dictated emails ('name@example.com'), and apply spoken 'new line'/'new paragraph' breaks cleanly."""
            STRICT_CLEANUP -> "Act strictly as a verbatim transcript cleaner (never reply as an assistant): remove vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm'), stutters, and mid-sentence self-repairs (keeping only the intended repair), convert 10+, decimals, percentages, currencies, dates, times, versions, and units into digits, capitalize acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'API'), and fix punctuation and capitalization without rephrasing."
            else -> defaultSystemPrompt
        }

        LlmPromptTier.BALANCED_1_7B -> when (this) {
            CLEAN_AND_POLISH -> """Edit raw spoken dictation into clean, natural written text that reads smoothly while keeping the speaker's exact voice, tone, intent, and language:
- Mid-sentence backtracks: When the speaker changes course ('actually', 'wait', 'no wait', 'sorry', 'I mean', 'scratch that', 'make that', 'oder nein', 'nein warte', 'ich meine'), remove both the abandoned phrase and the repair cue, keeping only the final intended words. Keep true contrasts ('not X, but Y').
- Fillers & punctuation: Strip vocal hesitations ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('the the', 'I I'), false starts, and hesitation 'like'/'you know' (never remove literal 'like' in 'I like this' or 'looks like'). Repair mid-sentence pause periods and capitalization.
- Vertical list layout: Format spoken sequences ('number one... number two...', 'first... second... third...', 'one... two... three...', 'erstens... zweitens...') with ':' after any lead-in clause and EVERY item on its own new line ('1. ', '2. '), and 'bullet point'/'dash item' cues as '- ' lines. Keep short casual series ('milk, eggs, and bread') inline.
- Numbers, times & units: Always use digits when paired with units, times, dates, percents, currencies, versions, or model names ('0 MB', '6 GB', '1.9 GB', '380 ms', '5 minutes', '25%', '$1,500', '49,99 €', '3:30 PM', '14:30', '2.8.0', 'Gemma 4 E2B') and for standalone 10+; spell out standalone counts one to nine in ordinary prose.
- Tech terms, emails & breaks: Capitalize acronyms & identifiers ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'SQL', 'GitHub', 'camelCase', 'snake_case'), format spoken emails/URLs ('name@example.com'), and turn explicit spoken 'new line'/'new paragraph' cues into line breaks without adding unrequested formality."""
            else -> defaultSystemPrompt
        }

        LlmPromptTier.FLAGSHIP_GEMMA_4 -> when (this) {
            CLEAN_AND_POLISH -> """Edit raw spoken dictation into clean, natural, ready-to-send text in the speaker's exact voice, tone, and language:
1. Course-correction & backtracks:
   - Delete BOTH retracted phrases and spoken repair cues ('wait', 'no wait', 'wait no', 'actually', 'actually sorry', 'sorry', 'I mean', 'scratch that', 'make that', 'nein warte', 'oder nein', 'ich meine', 'besser gesagt'), keeping ONLY the final intended wording (e.g., 'eight gigabytes wait no make that six gigabytes' -> '6 GB'; 'two point one gigabytes actually sorry one point nine gigabytes' -> '1.9 GB').
   - Keep genuine contrasts ('not Monday morning', 'not X, but Y').
2. Fillers, stutters & sentence flow:
   - Remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), word stutters ('the the', 'I I'), false starts, and filler 'like' / 'you know' / 'sort of' ONLY when used as hesitation (keep literal 'I like this' or 'looks like').
   - Fix false mid-sentence pause periods, run-on clauses, capitalization, and punctuation; keep spoken questions as questions ('?').
3. Smart list detection (vertical numbered & bulleted lists):
   - Detect spoken sequences ('number one... number two... number three...', 'first... second... third...', 'one... two... three...', 'point one... point two...', 'item one... item two...', 'erstens... zweitens...', 'punkt eins... punkt zwei...').
   - End any lead-in clause with ':' (replacing spoken 'colon' / 'doppelpunkt'), strip the spoken cue ('number one', 'first'), and place EVERY item on its OWN NEW LINE starting with '1. ', '2. ', '3. ' — NEVER write '1. ... 2. ... 3. ...' inline on one paragraph line.
   - Format 'bullet point' / 'dash item' cues as separate '- ' lines; keep casual inline triples ('milk, eggs, and bread') inline.
4. Numbers, dates & units:
   - ALWAYS use digits (including 0–9) when paired with ANY unit, time, date, percent, currency, version, or model/spec name: percentages ('98%', '25%'), currencies ('$4,250.75', '49,99 €'), clock times ('4:45 PM', '15:30'), dates ('October 30', 'November 24, 2026'), version numbers ('version 2.10', '2.8.0'), units ('0 MB', '6 GB', '1.9 GB', '1,500 MB', '380 ms', '42.5 tokens/s', '5 minutes', '30 seconds', '2,048 tokens'), and model names ('Qwen 3 0.6B', 'Gemma 4 E2B').
   - Spell out standalone prose counts one through nine ONLY when not paired with a unit ('three local models', 'four things'), and use digits for standalone 10+.
5. Paragraphs, emails & technical terms:
   - Apply explicit layout cues ('new paragraph' / 'neuer Absatz' -> blank line, 'new line' / 'neue Zeile' -> line break, 'em dash' -> ' — ') and delete the cue words.
   - Format spoken emails & domains ('elena dot kovacs at openflow dot dev' -> 'elena.kovacs@openflow.dev', 'acme dash corp dot com' -> 'acme-corp.com').
   - Capitalize acronyms, products & code identifiers ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'SQL', 'GitHub', 'OpenFlow', 'LiteRT-LM', 'Voz', 'camelCase', 'snake_case') and separate spoken greetings/sign-offs cleanly without inventing unspoken ones."""
            STRICT_CLEANUP -> """Perform strict verbatim dictation cleanup in the speaker's exact language and wording:
1. Remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters ('the the'), and mid-sentence self-corrections ('wait no', 'scratch that', 'actually make that'), keeping only the intended repair.
2. Convert spoken decimals, percentages, currencies, clock times, dates, version numbers, 10+ counts, and ALL numbers paired with units or model names ('0 MB', '6 GB', '380 ms', '5 minutes', 'Gemma 4 E2B') into clean digits.
3. Format spoken emails ('name@domain.com'), capitalize standard acronyms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'GitHub'), apply explicit 'new line' / 'new paragraph' cues, and fix punctuation and capitalization without rephrasing or reordering words."""
            CONCISE_DIRECT -> """Edit spoken dictation into crisp, high-signal, scannable text in the speaker's exact language:
1. Resolve all mid-sentence self-corrections and strip fillers, hedging, throat-clearing, and wordy repetition while keeping 100% of facts, names, constraints, and numbers.
2. Put multi-item enumerations ('number one... number two...', 'first... second...') onto separate vertical '1. ', '2. ' or '- ' lines after a ':' — never cram multiple numbered items onto one line.
3. Write all units, specs, percentages, currencies, times, dates, version numbers, model names ('0 MB', '6 GB', '380 ms', '98%', '4:45 PM', 'v2.10'), and 10+ counts as clean digits, format spoken emails, and capitalize technical acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'KV', 'API')."""
            PROFESSIONAL_MESSAGE -> """Format spoken dictation into a polished, natural email or work message in the speaker's exact language:
1. Resolve all mid-sentence self-corrections and strip vocal fillers while keeping a warm, authentic human voice (zero stiff corporate clichés).
2. Place spoken greetings ('Hi Sarah,') on their own opening line followed by a blank line, organize distinct topics into clear paragraphs, put multi-step action items on separate vertical '1. ' or '- ' lines after ':', and place spoken sign-offs ('Thanks,\nAlex') at the end without inventing unspoken greetings or sign-offs.
3. Format all numbers with units, times, dates, percentages, currencies, version numbers, spoken emails ('name@company.com'), and technical acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'API') cleanly."""
            STRUCTURED_NOTES -> """Transform spoken dictation into clean, scannable structured notes in the speaker's exact language:
1. Place EVERY distinct point or action item on its own vertical '- ' bullet line (or '1. ', '2. ', '3. ' numbered lines when sequential), keeping any brief spoken topic header above the list ending with ':'.
2. Resolve all mid-sentence self-corrections, remove fillers and stutters, and tighten phrasing into crisp note items without losing any detail.
3. Format all numbers with units ('0 MB', '6 GB', '380 ms', '5 minutes'), percentages, currencies, dates, times, version numbers, model names, spoken emails, and technical acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'KV', 'API') accurately."""
            CUSTOM -> defaultSystemPrompt
        }
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
    val gemmaAdaptiveThinking: Boolean = true,
    val gemmaPromptPreset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH,
    val gemmaCustomPrompt: String = "",
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList(),
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO,
    val modelAutoUnloadMinutes: Int = DEFAULT_MODEL_AUTO_UNLOAD_MINUTES
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

    val autoUnloadSummary: String
        get() = autoUnloadSummary(modelAutoUnloadMinutes)

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
        const val KEY_GEMMA_ADAPTIVE_THINKING = "gemma_adaptive_thinking"
        const val KEY_GEMMA_PROMPT_PRESET = "gemma_prompt_preset"
        const val KEY_GEMMA_CUSTOM_PROMPT = "gemma_custom_prompt"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_CUSTOM_VOCABULARY = "custom_vocabulary"
        const val KEY_HARDWARE_ACCELERATOR = "hardware_accelerator"
        const val KEY_MODEL_AUTO_UNLOAD_MINUTES = "model_auto_unload_minutes"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        const val DEFAULT_MODEL_AUTO_UNLOAD_MINUTES = 5
        const val AUTO_UNLOAD_IMMEDIATELY = 0
        const val AUTO_UNLOAD_NEVER = -1

        /**
         * Selectable idle auto-unload presets in minutes (`0` = unload immediately after dictation,
         * `-1` = keep loaded until Android OS requests memory).
         */
        val AUTO_UNLOAD_OPTIONS_MINUTES: List<Int> = listOf(0, 1, 2, 5, 10, 15, 30, -1)

        fun sanitizeAutoUnloadMinutes(raw: Int): Int {
            return when {
                raw == AUTO_UNLOAD_NEVER -> AUTO_UNLOAD_NEVER
                raw in 0..120 -> raw
                else -> DEFAULT_MODEL_AUTO_UNLOAD_MINUTES
            }
        }

        fun autoUnloadChipLabel(minutes: Int): String = when (minutes) {
            AUTO_UNLOAD_IMMEDIATELY -> "Immediately"
            AUTO_UNLOAD_NEVER -> "Never"
            else -> "${minutes}m"
        }

        fun autoUnloadSummary(minutes: Int): String = when (minutes) {
            AUTO_UNLOAD_IMMEDIATELY ->
                "Unloads all models to 0 MB immediately after each dictation — maximum battery & RAM savings; reloads automatically while you speak when the bubble is tapped."
            AUTO_UNLOAD_NEVER ->
                "Keeps loaded models warm in RAM once the bubble is tapped (evicts only under Android low-memory pressure)."
            1 ->
                "Unloads all models (Voz, Ear, Punct & LLM) to 0 MB after 1 minute of inactivity and reloads only when the bubble is tapped."
            else ->
                "Unloads all models (Voz, Ear, Punct & LLM) to 0 MB after $minutes minutes of inactivity and reloads only when the bubble is tapped."
        }

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

            val autoUnloadMinutes = sanitizeAutoUnloadMinutes(
                prefs.getInt(KEY_MODEL_AUTO_UNLOAD_MINUTES, DEFAULT_MODEL_AUTO_UNLOAD_MINUTES)
            )

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                punctEnabled = prefs.getBoolean(KEY_PUNCT_ENABLED, false),
                gemmaEnabled = gemmaEnabled,
                selectedLlmModel = selectedLlm,
                gemmaDisableThinking = prefs.getBoolean(KEY_GEMMA_DISABLE_THINKING, false),
                gemmaAdaptiveThinking = prefs.getBoolean(KEY_GEMMA_ADAPTIVE_THINKING, true),
                gemmaPromptPreset = preset,
                gemmaCustomPrompt = customPrompt,
                preferredLanguage = sanitizedLang,
                customVocabulary = vocabList,
                hardwareAccelerator = accel,
                modelAutoUnloadMinutes = autoUnloadMinutes
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
            val validAutoUnload = sanitizeAutoUnloadMinutes(settings.modelAutoUnloadMinutes)

            getPreferences(context).edit()
                .putBoolean(KEY_EAR_ENABLED, settings.earEnabled)
                .putBoolean(KEY_PUNCT_ENABLED, settings.punctEnabled)
                .putBoolean(KEY_GEMMA_ENABLED, settings.gemmaEnabled)
                .putString(KEY_SELECTED_LLM_MODEL, settings.selectedLlmModel.id)
                .putBoolean(KEY_GEMMA_DISABLE_THINKING, settings.gemmaDisableThinking)
                .putBoolean(KEY_GEMMA_ADAPTIVE_THINKING, settings.gemmaAdaptiveThinking)
                .putString(KEY_GEMMA_PROMPT_PRESET, settings.gemmaPromptPreset.id)
                .putString(KEY_GEMMA_CUSTOM_PROMPT, settings.gemmaCustomPrompt)
                .putString(KEY_PREFERRED_LANGUAGE, validLang)
                .putString(KEY_CUSTOM_VOCABULARY, jsonArray.toString())
                .putString(KEY_HARDWARE_ACCELERATOR, settings.hardwareAccelerator.id)
                .putInt(KEY_MODEL_AUTO_UNLOAD_MINUTES, validAutoUnload)
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

/**
 * Built-in dictation benchmark samples (5 standard examples + 2 complex multi-edit stress tests)
 * plus a user-editable custom input option for testing on-device LLM speed (cached vs uncached system prompt).
 */
enum class LlmBenchmarkSample(
    val id: String,
    val label: String,
    val sampleText: String,
    val isComplex: Boolean = false,
    val isCustom: Boolean = false
) {
    EXAMPLE_1_CASUAL_FILLERS(
        id = "example_1_casual",
        label = "Example 1: Casual Message & Fillers",
        sampleText = "um hey sarah i was just calling to say uh i am running about fifteen minutes late see you around three thirty"
    ),
    EXAMPLE_2_NUMBERS_DATES(
        id = "example_2_numbers",
        label = "Example 2: Numbers, Currency & Dates",
        sampleText = "the total invoice comes out to four thousand two hundred fifty dollars and seventy five cents due on november twenty fourth twenty twenty six at five pm"
    ),
    EXAMPLE_3_SELF_CORRECTION(
        id = "example_3_correction",
        label = "Example 3: Spoken Self-Correction",
        sampleText = "let us schedule the design review for tuesday at ten am actually scratch that make it thursday at two thirty pm instead"
    ),
    EXAMPLE_4_EMAIL_FORMATTING(
        id = "example_4_email",
        label = "Example 4: Work Email & Spoken Address",
        sampleText = "hi alex new paragraph please send the updated q three deck to marcus dot weber at acme dash corp dot com before noon tomorrow new paragraph thanks"
    ),
    EXAMPLE_5_ACTION_LIST(
        id = "example_5_list",
        label = "Example 5: Spoken Numbered List",
        sampleText = "three quick reminders for today number one merge the android release branch number two verify the benchmark numbers number three publish version two point ten"
    ),
    COMPLEX_1_MULTI_EDIT_SPECS(
        id = "complex_1_specs",
        label = "Complex 1: Multi-Correction, Specs, List & Email",
        sampleText = "um okay team quick architecture recap new paragraph we tested three local models on eight gigabytes of ram wait no make that six gigabytes of ram and uh here is what we saw colon number one qwen three zero point six b finished in about three hundred eighty milliseconds at forty two point five tokens per second number two gemma four e two b used around two point one gigabytes actually sorry one point nine gigabytes with ninety eight percent formatting accuracy number three idle memory dropped to zero megabytes after five minutes new paragraph please ping elena dot kovacs at openflow dot dev if throughput dips below twenty five percent",
        isComplex = true
    ),
    COMPLEX_2_MEETING_RECAP(
        id = "complex_2_meeting",
        label = "Complex 2: Dense Technical Dictation & Course-Corrections",
        sampleText = "so uh for the quarterly engineering update i want to cover four things first latency dropped from one thousand four hundred twenty milliseconds down to six hundred ten milliseconds when the system prompt kv cache is warm second uh on devices with less than twelve hundred megabytes free wait scratch that fifteen hundred megabytes free we dynamically cap the context window at two thousand forty eight tokens instead of four thousand ninety six tokens third please remind david and priya that the deadline is friday october thirtieth at four forty five pm sharp not monday morning and fourth if the screen timeout is set to fifteen seconds or thirty seconds the floating dictation bubble now keeps the display awake during both voz transcription and llm cleanup",
        isComplex = true
    ),
    CUSTOM_INPUT(
        id = "custom_input",
        label = "Custom Text Input...",
        sampleText = "um type or dictate your own test text here and tap benchmark to compare cached versus uncached speed",
        isCustom = true
    );
}
