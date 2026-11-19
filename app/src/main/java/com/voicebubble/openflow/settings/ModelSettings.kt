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
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        shortLabel = "Gemma 4 E2B",
        displayName = "Gemma 4 E2B",
        roleSummary = "2.41 GB • Default flagship reasoning & formatting",
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
 * Prompt optimization tier matched to each on-device LLM's parameter scale, context limit,
 * instruction-following behavior, and reasoning architecture.
 */
enum class LlmPromptTier {
    /** Compact, high-signal non-chatty prompt tuned for Android AICore system Gemini Nano (Full & Fast). */
    GEMINI_NANO,

    /** Full Wispr Flow multi-clause, nuance-preserving prompt + multilingual examples for flagship Gemma 4 E2B. */
    FLAGSHIP_GEMMA_4
}

/**
 * Execution backend used when [LlmProviderMode.LOCAL] is selected for AI Post-Processing:
 * - [LITERT_LM]: OpenFlow-managed downloadable LiteRT-LM model (`Gemma 4 E2B`, 2.41 GB).
 * - [GEMINI_NANO]: Android system AICore Gemini Nano (`Full` or `Fast`, 0 MB app storage).
 */
enum class LocalLlmBackend(val id: String, val title: String) {
    LITERT_LM("litert_lm", "Gemma 4 E2B (LiteRT-LM)"),
    GEMINI_NANO("gemini_nano", "Gemini Nano (AICore)");

    companion object {
        fun fromId(id: String?): LocalLlmBackend {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LITERT_LM
        }
    }
}

/**
 * Selectable on-device Gemini Nano model variants managed by Android AICore via ML Kit GenAI Prompt API:
 * - [FULL]: Full-capability Gemini Nano (`ModelPreference.FULL`) for highest nuance & accuracy.
 * - [FAST]: Latency-optimized Gemini Nano (`ModelPreference.FAST`) for quickest response times.
 */
enum class GeminiNanoVariant(
    val id: String,
    val title: String,
    val shortLabel: String,
    val badgeLabel: String,
    val description: String
) {
    FULL(
        id = "full",
        title = "Gemini Nano (Full)",
        shortLabel = "Nano Full",
        badgeLabel = "FULL",
        description = "Full-capability system Gemini Nano via Android AICore (0 MB app download)"
    ),
    FAST(
        id = "fast",
        title = "Gemini Nano Fast",
        shortLabel = "Nano Fast",
        badgeLabel = "FAST",
        description = "Low-latency speed-optimized Gemini Nano variant via Android AICore (0 MB app download)"
    );

    companion object {
        fun fromId(id: String?): GeminiNanoVariant {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FULL
        }
    }
}

/**
 * Selectable downloadable on-device LiteRT-LM models for AI post-processing & dictation cleanup.
 * Only [GEMMA_4_E2B] is shipped as a downloadable local LiteRT-LM model alongside Android system Gemini Nano.
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
    val samplerTemperature: Double,
    val defaultDisableThinking: Boolean = false,
    val hasDynamicKvCache: Boolean = false,
    val supportsPrefaceKvPrefill: Boolean = true,
    val needsSamplingWhileThinking: Boolean = false
) {
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        desertAntModel = DesertAntModel.GEMMA_4_E2B,
        modelFileName = "gemma-4-E2B-it.litertlm",
        title = "Gemma 4 E2B",
        badgeLabel = "DEFAULT",
        speedAndRamSummary = "2.41 GB",
        description = "Flagship reasoning & formatting",
        estimatedStandaloneRamMb = 1_900,
        estimatedIncrementalRamMb = 850,
        supportsThinking = true,
        supportsNpuBackend = true,
        promptTier = LlmPromptTier.FLAGSHIP_GEMMA_4,
        preferredMaxContextTokens = 2_048,
        thinkingTokenBudgetCap = 256,
        samplerTopK = 20,
        samplerTopP = 0.90,
        samplerTemperature = 0.10,
        defaultDisableThinking = false,
        hasDynamicKvCache = true
    );

    val sizeGbLabel: String
        get() = desertAntModel.sizeGbLabel

    val shortLabel: String
        get() = desertAntModel.shortLabel

    companion object {
        fun isSupportedId(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            return entries.any { it.id.equals(id, ignoreCase = true) }
        }

        fun fromId(id: String?): LlmModelChoice {
            if (id.isNullOrBlank()) return GEMMA_4_E2B
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMMA_4_E2B
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
    val defaultSystemPrompt: String,
    val cloudSystemPrompt: String
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
4. Numbers, dates & units: ALWAYS use digits (including 0–9) when paired with any unit, time, date, percent, money, version, or spec/model name — percentages ('98%', '25%'), currencies ('$4,250.75', '49,99 €'), clock times ('4:45 PM', '15:30'), dates ('October 30'), version numbers ('version 2.10', '2.8.0'), units ('0 MB', '6 GB', '1.9 GB', '380 ms', '42.5 tokens/s', '5 minutes'), and product/model names ('Pixel 10', 'GPT-4o'). Spell out standalone prose counts one through nine ONLY when not paired with a unit, and use digits for standalone 10+.
5. Paragraphs, emails & tech casing: Apply explicit layout cues ('new paragraph' / 'neuer Absatz' -> blank line, 'new line' / 'neue Zeile' -> line break, 'em dash' -> ' — ') removing the cue, format spoken emails & domains ('elena dot kovacs at openflow dot dev' -> 'elena.kovacs@openflow.dev'), capitalize technical terms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'KV', 'API', 'JSON', 'SQL', 'GitHub', 'OpenFlow', 'camelCase', 'snake_case'), and separate spoken greetings/sign-offs without inventing unspoken ones.""",
        cloudSystemPrompt = """Transform raw spoken dictation into clean, natural, ready-to-send written text preserving the speaker's exact voice, tone, meaning, and language:
1. Self-corrections & pivots: Resolve both short repairs and clause-level backtracks ('wait', 'no wait', 'wait no', 'actually', 'sorry', 'I mean', 'scratch that', 'make that', 'nein warte', 'oder nein', 'ich meine', 'besser gesagt') by deleting the retracted attempt and repair cue, keeping only what the speaker finally meant. Keep genuine contrasts ('not X, but Y').
2. Disfluencies & faithful flow: Remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters, false starts, and hesitation 'like' / 'you know' / 'sort of' (keep literal uses such as 'I like this'). Repair false pause periods, punctuation, capitalization, and unmistakable context-implied speech-recognition slips without altering names, slang, or intentional wording; keep questions as questions.
3. Paragraphs, lists & layout cues: On longer monologues, insert paragraph breaks naturally at clear topic shifts without over-splitting short notes. Apply explicit layout cues ('new paragraph' / 'neuer Absatz' -> blank line, 'new line' / 'neue Zeile' -> line break, 'em dash' -> ' — ') and drop the cue words. Format spoken enumerations ('first... second...', 'number one... number two...', 'one... two... three...', 'erstens... zweitens...', 'bullet point...') with ':' after any lead-in clause and each item on its own '1. ' or '- ' line; keep brief inline series ('milk, eggs, and bread') inline.
4. Numbers, entities & plain text: Format numbers paired with any unit (0+), standalone 10+, percentages, currencies, clock times, dates, version numbers, dictated emails ('name@domain.com'), URLs, technical acronyms, file paths, and code casing ('camelCase', 'snake_case') cleanly; spell out standalone prose counts one through nine. Separate spoken greetings and sign-offs onto their own lines without inventing unspoken ones, and emit plain text only (no unsolicited bold '**' or Markdown headings '#')."""
    ),
    STRICT_CLEANUP(
        id = "strict_cleanup",
        title = "Light Cleanup (Verbatim)",
        shortLabel = "Light Cleanup",
        subtitle = "Verbatim cleanup: strips fillers, stutters & obvious backtracks, formats numbers/units & fixes punctuation with zero rephrasing",
        defaultSystemPrompt = "Perform light, verbatim dictation cleanup: remove vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm'), stutters, and obvious mid-phrase self-corrections (keeping only the intended correction), convert spoken decimals, percentages, currencies, dates, times, version numbers, measurements/units ('0 MB', '6 GB', '380 ms'), and 10+ counts into digits, capitalize standard acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'KV', 'API'), and repair broken punctuation, mid-sentence pause periods, and capitalization. Never rephrase, reorder, summarize, or alter the speaker's vocabulary.",
        cloudSystemPrompt = "Perform light, faithful verbatim cleanup in the speaker's exact language and wording: remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters, and clear mid-sentence self-corrections (keeping only the intended repair), convert spoken decimals, percentages, currencies, clock times, dates, version numbers, numbers paired with units, and standalone 10+ into clean digits, format dictated emails/URLs and standard acronyms, apply explicit 'new line'/'new paragraph' breaks, and fix punctuation and capitalization without rephrasing, reordering, or summarizing."
    ),
    CONCISE_DIRECT(
        id = "concise_direct",
        title = "Crisp & Direct",
        shortLabel = "Crisp",
        subtitle = "Resolves backtracks and tightens rambling spoken thoughts into crisp, scannable sentences, lists & clean numbers",
        defaultSystemPrompt = "Edit the spoken dictation into crisp, concise, scannable text. Resolve all mid-sentence backtracks and self-corrections, strip vocal fillers, hedging, and redundant phrasing, place multi-item enumerations on separate '1. ' or '- ' lines after a ':', and format spoken numbers, decimals, percentages, currencies, dates, times, versions, and units accurately as digits while preserving every fact, name, and the speaker's original language.",
        cloudSystemPrompt = "Distill the spoken dictation into crisp, high-signal written text in the speaker's exact language: resolve all self-corrections, strip vocal fillers, throat-clearing, and hedging, tighten wordy repetition into direct sentences while preserving 100% of facts, names, constraints, and intent, break distinct topics into clean paragraphs, place multi-item enumerations on separate '1. ' or '- ' lines after ':', and format numbers, times, dates, currencies, percentages, versions, units, and emails cleanly."
    ),
    PROFESSIONAL_MESSAGE(
        id = "professional_message",
        title = "Email & Work Message",
        shortLabel = "Email / Work",
        subtitle = "Structures emails & work messages with clean greeting/sign-off spacing, paragraphs, action lists & accurate numbers",
        defaultSystemPrompt = "Format the spoken dictation into a natural, well-structured email or work message. Resolve all mid-sentence self-corrections and remove vocal hesitations, place spoken greetings (e.g., 'Hi Sarah,') and sign-offs (e.g., 'Best,\\nAlex') on separate lines with clean paragraph breaks, place multi-step action items on separate '1. ' or '- ' lines, and convert spoken numbers, dates, times, currencies, versions, and units into clean written digits in the speaker's original language without inventing placeholders or greetings that were not spoken.",
        cloudSystemPrompt = "Format the spoken dictation into a polished, natural email or workplace message in the speaker's exact language: resolve self-corrections and strip vocal fillers while keeping a genuine human voice (never add stiff corporate clichés). Place any spoken greeting (e.g., 'Hi Sarah,') on its own line followed by a blank line, structure distinct topics into paragraphs, put multi-step action items on separate '1. ' or '- ' lines after ':', place any spoken sign-off at the end on separate lines without inventing unspoken ones, and format numbers, dates, times, currencies, versions, units, and dictated emails cleanly."
    ),
    STRUCTURED_NOTES(
        id = "structured_notes",
        title = "Structured Lists & Notes",
        shortLabel = "Lists & Notes",
        subtitle = "Organizes spoken thoughts, meeting notes, or tasks into clean bulleted or numbered lists with backtrack cleanup",
        defaultSystemPrompt = "Transform the spoken dictation into a clean, scannable set of structured notes with each point on its own '- ' bullet line (or '1. ' numbered line when sequential). Resolve mid-sentence self-corrections, remove all vocal fillers and repetitions, format spoken numbers, dates, times, versions, currencies, and units cleanly as digits, group related action items logically, and preserve the speaker's original language.",
        cloudSystemPrompt = "Organize the spoken dictation into crisp, scannable structured notes in the speaker's exact language: place any brief spoken topic heading above a list ending with ':', put each distinct point or action item on its own '- ' bullet line (or '1. ' numbered line when sequential), resolve all self-corrections and remove fillers while retaining every concrete detail, name, and caveat, and format numbers, dates, times, versions, currencies, units, and emails accurately."
    ),
    CUSTOM(
        id = "custom",
        title = "Custom System Prompt",
        shortLabel = "Custom",
        subtitle = "Write your own custom system instruction for how the selected AI model transforms dictated speech",
        defaultSystemPrompt = "Resolve mid-sentence self-corrections, remove vocal fillers ('um', 'uh', 'like') and stutters, convert spoken numbers, dates, versions, and units into proper digits, and format the text cleanly while keeping the original meaning.",
        cloudSystemPrompt = "Apply the user's custom instruction cleanly to the dictated speech inside <transcript>...</transcript>: resolve obvious vocal fillers and mid-sentence self-corrections unless literal output is requested, format numbers, dates, and entities cleanly, and return only the transformed text without commentary."
    );

    /**
     * Returns the task instruction specifically optimized for [tier].
     */
    fun promptForTier(tier: LlmPromptTier): String = when (tier) {
        LlmPromptTier.GEMINI_NANO -> when (this) {
            CLEAN_AND_POLISH -> """Clean raw spoken dictation into natural, ready-to-send written text in the speaker's exact voice and language:
- Backtracks: When the speaker changes course ('wait', 'no wait', 'actually', 'sorry', 'I mean', 'scratch that', 'make that', 'nein warte', 'ich meine'), delete the retracted words and cue, keeping only the final intended words. Keep contrasts ('not X, but Y').
- Fillers & punctuation: Remove vocal fillers ('um', 'uh', 'er', 'ah', 'hmm', 'äh', 'ähm'), stutters, and hesitation 'like'/'you know' (keep literal 'I like'). Fix punctuation, false pause periods, and capitalization; keep questions as questions.
- Vertical lists: Format spoken enumerations ('number one... number two...', 'first... second...', 'erstens... zweitens...', 'bullet point...') with ':' after any lead-in clause and each item on its own new line ('1. ', '2. ' or '- '). Keep brief triples inline.
- Numbers, emails & tech: Use digits for 0+ paired with units, times, dates, percents, currencies, versions ('6 GB', '380 ms', '25%', '3:30 PM', 'v2.10'), and standalone 10+; spell out standalone one through nine. Format spoken emails ('name@domain.com'), acronyms ('NPU', 'GPU', 'RAM', 'LLM', 'API'), and spoken 'new line'/'new paragraph' breaks cleanly."""
            STRICT_CLEANUP -> "Clean spoken dictation verbatim in the speaker's exact language: remove vocal fillers ('um', 'uh', 'äh', 'ähm'), stutters, and clear mid-phrase self-corrections (keeping only the intended correction), format decimals, percentages, currencies, times, dates, versions, units, and 10+ as digits, capitalize acronyms ('NPU', 'GPU', 'RAM', 'API'), and fix punctuation and casing without rephrasing."
            CONCISE_DIRECT -> "Tighten spoken dictation into crisp, direct text in the speaker's original language: resolve self-corrections, remove fillers and hedging while keeping every fact, name, number, and constraint, put enumerations on vertical '1. ' or '- ' lines after ':', and format numbers, times, dates, currencies, and units cleanly."
            PROFESSIONAL_MESSAGE -> "Format spoken dictation as a clean email or work message in the speaker's language: resolve self-corrections and fillers, place any spoken greeting or sign-off on its own line (without inventing unspoken ones), structure paragraphs and vertical '1. '/' - ' action lists cleanly, and format numbers, dates, times, currencies, and spoken emails."
            STRUCTURED_NOTES -> "Turn spoken dictation into clean notes in the speaker's language: put each distinct point on its own '- ' bullet line (or '1. ' line when sequential) beneath any spoken heading ending with ':', resolve self-corrections, strip fillers, and format numbers, dates, times, units, and emails cleanly."
            CUSTOM -> defaultSystemPrompt
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
   - ALWAYS use digits (including 0–9) when paired with ANY unit, time, date, percent, currency, version, or model/spec name: percentages ('98%', '25%'), currencies ('\$4,250.75', '49,99 €'), clock times ('4:45 PM', '15:30'), dates ('October 30', 'November 24, 2026'), version numbers ('version 2.10', '2.8.0'), units ('0 MB', '6 GB', '1.9 GB', '1,500 MB', '380 ms', '42.5 tokens/s', '5 minutes', '30 seconds', '2,048 tokens'), and model names ('Gemma 4 E2B', 'Gemini Nano').
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

    /**
     * Returns the task instruction specifically optimized for [llmChoice]'s parameter scale,
     * prefill bucket efficiency, and instruction-following behavior.
     */
    fun promptForModel(llmChoice: LlmModelChoice): String = promptForTier(llmChoice.promptTier)

    /**
     * Returns the task instruction appropriate for [provider] and [localLlmBackend]: cloud-optimized
     * high-capacity instructions ([cloudSystemPrompt]) for [LlmProviderMode.OPENAI_API], compact
     * Gemini Nano instructions for [LocalLlmBackend.GEMINI_NANO], or LiteRT-LM instructions for
     * [LocalLlmBackend.LITERT_LM].
     */
    fun promptForProvider(
        provider: LlmProviderMode,
        llmChoice: LlmModelChoice = LlmModelChoice.GEMMA_4_E2B,
        localLlmBackend: LocalLlmBackend = LocalLlmBackend.LITERT_LM
    ): String = when {
        provider == LlmProviderMode.OPENAI_API -> cloudSystemPrompt
        localLlmBackend == LocalLlmBackend.GEMINI_NANO -> promptForTier(LlmPromptTier.GEMINI_NANO)
        else -> promptForModel(llmChoice)
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
 * Execution provider backend for AI Post-Processing.
 */
enum class LlmProviderMode(val id: String, val title: String) {
    LOCAL("local", "Local Offline Model"),
    OPENAI_API("openai_api", "OpenAI-Compatible API");

    companion object {
        fun fromId(id: String?): LlmProviderMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LOCAL
        }
    }
}

/**
 * Reasoning depth preference for thinking-capable LLMs.
 * - [DEFAULT]: Uses the remote server / model default without forcing a `reasoning_effort` override.
 * - [OFF]: Requests zero reasoning tokens (`reasoning_effort = "none"` when supported).
 * - [MINIMAL], [LOW], [MEDIUM], [HIGH], [XHIGH], [MAX]: Scales reasoning depth proportionally.
 * - [CUSTOM]: Sends an arbitrary user-entered `reasoning_effort` value for endpoints with custom levels.
 */
enum class LlmThinkingEffort(
    val id: String,
    val title: String,
    val description: String,
    val apiValue: String?,
    val localBudgetScale: Float
) {
    DEFAULT(
        id = "default",
        title = "Auto (Server Default)",
        description = "Omits reasoning_effort so your endpoint uses its default",
        apiValue = null,
        localBudgetScale = 1.0f
    ),
    OFF(
        id = "off",
        title = "Off (none)",
        description = "Requests disabled reasoning via reasoning_effort = \"none\"",
        apiValue = "none",
        localBudgetScale = 0.0f
    ),
    MINIMAL(
        id = "minimal",
        title = "Minimal",
        description = "Ultra-light reasoning pass for lowest latency",
        apiValue = "minimal",
        localBudgetScale = 0.35f
    ),
    LOW(
        id = "low",
        title = "Low",
        description = "Light reasoning check for quick dictation cleanup",
        apiValue = "low",
        localBudgetScale = 0.55f
    ),
    MEDIUM(
        id = "medium",
        title = "Medium",
        description = "Balanced reasoning for self-corrections and structure",
        apiValue = "medium",
        localBudgetScale = 1.0f
    ),
    HIGH(
        id = "high",
        title = "High",
        description = "Deep reasoning for complex edits and tricky formatting",
        apiValue = "high",
        localBudgetScale = 1.6f
    ),
    XHIGH(
        id = "xhigh",
        title = "Very High (xhigh)",
        description = "Extended reasoning depth (e.g. OpenAI GPT-5 / o-series endpoints)",
        apiValue = "xhigh",
        localBudgetScale = 1.85f
    ),
    MAX(
        id = "max",
        title = "Max",
        description = "Maximum thinking depth when supported (not recommended for everyday speed)",
        apiValue = "max",
        localBudgetScale = 2.0f
    ),
    CUSTOM(
        id = "custom",
        title = "Custom…",
        description = "Type an exact reasoning_effort string supported by your endpoint",
        apiValue = null,
        localBudgetScale = 1.5f
    );

    companion object {
        val API_OPTIONS: List<LlmThinkingEffort> = listOf(
            DEFAULT, OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX, CUSTOM
        )
        val LOCAL_OPTIONS: List<LlmThinkingEffort> = listOf(LOW, MEDIUM, HIGH, MAX)

        private val DISABLED_API_VALUES = setOf("none", "off", "disable", "disabled", "0", "false")

        fun fromId(id: String?): LlmThinkingEffort {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }

        fun fromLocalId(id: String?): LlmThinkingEffort {
            val parsed = fromId(id)
            return if (parsed in LOCAL_OPTIONS) parsed else MEDIUM
        }

        /**
         * Resolves the raw string value sent in JSON `"reasoning_effort"`, or `null` when omitted.
         */
        fun resolveApiValue(effort: LlmThinkingEffort, customValue: String = ""): String? {
            return when (effort) {
                CUSTOM -> customValue.trim().takeIf { it.isNotEmpty() }
                else -> effort.apiValue
            }
        }

        /**
         * `true` when the resolved effort explicitly asks the model not to think (`"none"`, `"off"`, etc.).
         */
        fun isDisabledApiValue(apiValue: String?): Boolean {
            return apiValue != null && apiValue.trim().lowercase(Locale.ROOT) in DISABLED_API_VALUES
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
    /** Deterministic non-AI formatting engine (numbers, lists, fillers, corrections). Works with or without an LLM. */
    val formattingEngineEnabled: Boolean = false,
    val localLlmBackend: LocalLlmBackend = LocalLlmBackend.LITERT_LM,
    val geminiNanoVariant: GeminiNanoVariant = GeminiNanoVariant.FULL,
    val geminiNanoAutoFallback: Boolean = true,
    val selectedLlmModel: LlmModelChoice = LlmModelChoice.GEMMA_4_E2B,
    val gemmaDisableThinking: Boolean = selectedLlmModel.defaultDisableThinking,
    val gemmaAdaptiveThinking: Boolean = true,
    val localThinkingEffort: LlmThinkingEffort = LlmThinkingEffort.MEDIUM,
    val localLlmChunkingEnabled: Boolean = true,
    val gemmaPromptPreset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH,
    val gemmaCustomPrompt: String = "",
    val llmProvider: LlmProviderMode = LlmProviderMode.LOCAL,
    val openAiBaseUrl: String = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_BASE_URL,
    val openAiApiKey: String = "",
    val openAiModel: String = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_MODEL,
    val openAiTimeoutSeconds: Int = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_TIMEOUT_SECONDS,
    val openAiThinkingEffort: LlmThinkingEffort = LlmThinkingEffort.DEFAULT,
    val openAiCustomReasoningEffort: String = "",
    val openAiChunkingEnabled: Boolean = false,
    val remoteVozEnabled: Boolean = false,
    val remoteVozBaseUrl: String = "",
    val remoteVozApiKey: String = "",
    val remoteVozModel: String = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_VOZ_MODEL,
    val remoteVozTimeoutSeconds: Int = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_AUDIO_TIMEOUT_SECONDS,
    val remoteEarEnabled: Boolean = false,
    val remoteEarUseSameEndpoint: Boolean = true,
    val remoteEarBaseUrl: String = "",
    val remoteEarApiKey: String = "",
    val remoteEarModel: String = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_EAR_MODEL,
    val remoteEarTimeoutSeconds: Int = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_AUDIO_TIMEOUT_SECONDS,
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList(),
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO,
    val modelAutoUnloadMinutes: Int = DEFAULT_MODEL_AUTO_UNLOAD_MINUTES
) {
    /**
     * `Voz` is the core STT engine (on-device unless [isRemoteVozActive] is true) and cannot be disabled.
     */
    val vozEnabled: Boolean = true

    /** `true` when [remoteVozBaseUrl] and [remoteVozModel] form a valid remote STT configuration. */
    val isRemoteVozConfigured: Boolean
        get() = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.isValidHttpUrl(remoteVozBaseUrl) &&
            remoteVozModel.isNotBlank()

    /** `true` when speech-to-text (`Voz`) runs on a configured remote OpenAI-compatible transcription endpoint. */
    val isRemoteVozActive: Boolean
        get() = remoteVozEnabled && isRemoteVozConfigured

    /** Effective base URL used for Remote `Ear` (reuses [remoteVozBaseUrl] when [remoteEarUseSameEndpoint] is true). */
    val effectiveRemoteEarBaseUrl: String
        get() = if (remoteEarUseSameEndpoint && remoteVozEnabled) remoteVozBaseUrl else remoteEarBaseUrl

    /** Effective API key used for Remote `Ear` (reuses [remoteVozApiKey] when [remoteEarUseSameEndpoint] is true). */
    val effectiveRemoteEarApiKey: String
        get() = if (remoteEarUseSameEndpoint && remoteVozEnabled) remoteVozApiKey else remoteEarApiKey

    /** Effective request timeout in seconds for Remote `Ear`. */
    val effectiveRemoteEarTimeoutSeconds: Int
        get() = if (remoteEarUseSameEndpoint && remoteVozEnabled) remoteVozTimeoutSeconds else remoteEarTimeoutSeconds

    /** `true` when Remote `Ear` has a valid effective endpoint URL and non-blank model ID. */
    val isRemoteEarConfigured: Boolean
        get() = com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.isValidHttpUrl(effectiveRemoteEarBaseUrl) &&
            remoteEarModel.isNotBlank()

    /** `true` when language ID (`Ear`) is enabled and configured to call a remote transcription endpoint. */
    val isRemoteEarActive: Boolean
        get() = earEnabled && remoteEarEnabled && isRemoteEarConfigured

    /** Concise status badge text for the collapsible Remote Voz & Ear section in Audio Models settings. */
    val remoteAudioSummaryLabel: String
        get() = when {
            isRemoteVozActive && isRemoteEarActive ->
                if (remoteEarUseSameEndpoint && remoteVozEnabled) "Voz + Ear · Shared API" else "Voz + Ear · 2 Endpoints"
            isRemoteVozActive -> "Voz · API"
            isRemoteEarActive -> "Ear · API"
            remoteVozEnabled || (earEnabled && remoteEarEnabled) -> "Setup needed"
            else -> "Local"
        }

    /** `true` when AI post-processing is enabled and using an on-device LiteRT-LM model (`Gemma 4 E2B`). */
    val isLocalLiteRtLlmEnabled: Boolean
        get() = gemmaEnabled &&
            llmProvider == LlmProviderMode.LOCAL &&
            localLlmBackend == LocalLlmBackend.LITERT_LM

    /** `true` when AI post-processing is enabled and using Android AICore system Gemini Nano. */
    val isGeminiNanoEnabled: Boolean
        get() = gemmaEnabled &&
            llmProvider == LlmProviderMode.LOCAL &&
            localLlmBackend == LocalLlmBackend.GEMINI_NANO

    /** `true` when AI post-processing is enabled in local mode (`Gemma 4 E2B` or `Gemini Nano`). */
    val isLocalLlmEnabled: Boolean
        get() = isLocalLiteRtLlmEnabled || isGeminiNanoEnabled

    /** `true` when AI post-processing is enabled and configured to call a remote/LAN OpenAI-compatible API. */
    val isOpenAiApiEnabled: Boolean
        get() = gemmaEnabled &&
            llmProvider == LlmProviderMode.OPENAI_API &&
            openAiBaseUrl.isNotBlank() &&
            openAiModel.isNotBlank()

    /** `true` when either a local AI model (`Gemma 4 E2B` / `Gemini Nano`) or an OpenAI-compatible API handles post-processing. */
    val isAiPostProcessingActive: Boolean
        get() = isLocalLlmEnabled || isOpenAiApiEnabled

    /**
     * Effective `reasoning_effort` string sent in OpenAI-compatible API requests, or `null` when
     * omitted (`DEFAULT`, or `CUSTOM` while blank).
     */
    val effectiveOpenAiReasoningEffortValue: String?
        get() = LlmThinkingEffort.resolveApiValue(openAiThinkingEffort, openAiCustomReasoningEffort)

    /** `true` when at least one remote/cloud OpenAI-compatible API stage (`Voz`, `Ear`, or LLM) is active. */
    val hasAnyRemoteApiActive: Boolean
        get() = isRemoteVozActive || isRemoteEarActive || isOpenAiApiEnabled

    /** `true` when local `Punct` is enabled and not bypassed by an active AI post-processor. */
    val isPunctEffectivelyActive: Boolean
        get() = punctEnabled && !isAiPostProcessingActive

    /** Concise status badge text for the collapsed AI Post-Processing card header. */
    val aiSectionSummaryLabel: String
        get() = when {
            isOpenAiApiEnabled -> "API · ${openAiModel.trim()}"
            isGeminiNanoEnabled -> "Local · ${geminiNanoVariant.shortLabel}"
            isLocalLiteRtLlmEnabled -> "Local · ${selectedLlmModel.title}"
            gemmaEnabled && llmProvider == LlmProviderMode.OPENAI_API -> "API · Setup needed"
            formattingEngineEnabled -> "Instant Engine"
            else -> "Off"
        }

    /**
     * Resolves the active provider/model task instruction based on [llmProvider], [localLlmBackend],
     * [selectedLlmModel], [gemmaPromptPreset], and [gemmaCustomPrompt].
     */
    fun resolvedGemmaTaskInstruction(): String {
        return if (gemmaPromptPreset == GemmaPromptPreset.CUSTOM) {
            gemmaCustomPrompt.trim().ifEmpty {
                GemmaPromptPreset.CUSTOM.promptForProvider(llmProvider, selectedLlmModel, localLlmBackend)
            }
        } else {
            gemmaPromptPreset.promptForProvider(llmProvider, selectedLlmModel, localLlmBackend)
        }
    }

    /**
     * Active disk footprint in MB based on currently enabled OpenFlow-downloaded models
     * (`0 MB` extra for cloud APIs and system AICore Gemini Nano).
     */
    val activeDiskFootprintMb: Int
        get() = (if (isRemoteVozActive) 0 else DesertAntModel.VOZ.sizeMb) +
            (if (earEnabled && !isRemoteEarActive) DesertAntModel.EAR.sizeMb else 0) +
            (if (punctEnabled) DesertAntModel.PUNCT.sizeMb else 0) +
            (if (isLocalLiteRtLlmEnabled) selectedLlmModel.desertAntModel.sizeMb else 0)

    /**
     * Estimated peak app RAM in MB based on models that can ever co-reside in RAM:
     * - Local `Voz` (1,187 MB weights + runtime buffers): 1,400 MB (0 MB when [isRemoteVozActive])
     * - Local `Ear` (10 MB weights + runtime buffers): +50 MB (0 MB when [isRemoteEarActive])
     * - Text cleanup stage (strictly mutually exclusive in RAM — `Punct` is never loaded alongside an active LLM):
     *   - Active Local `Gemma 4 E2B` (+850 MB), OR
     *   - `Gemini Nano` / OpenAI API (+0 MB app RAM — AICore runs in system service), OR
     *   - `Punct` (+120 MB) when AI post-processing is disabled.
     */
    val estimatedPeakRamMb: Int
        get() = (if (isRemoteVozActive) 0 else 1400) +
            (if (earEnabled && !isRemoteEarActive) 50 else 0) +
            when {
                isLocalLiteRtLlmEnabled -> selectedLlmModel.estimatedIncrementalRamMb
                isGeminiNanoEnabled || isOpenAiApiEnabled -> 0
                punctEnabled -> 120
                else -> 0
            }

    fun isModelEnabled(model: DesertAntModel): Boolean = when (model) {
        DesertAntModel.VOZ -> !isRemoteVozActive
        DesertAntModel.EAR -> earEnabled && !isRemoteEarActive
        DesertAntModel.PUNCT -> punctEnabled
        DesertAntModel.GEMMA_4_E2B -> isLocalLiteRtLlmEnabled && model == selectedLlmModel.desertAntModel
    }

    /**
     * `true` when [model] is currently required locally (`Voz` when Remote Voz API is not active).
     * When Remote Voz API is connected (`isRemoteVozActive == true`), local `Voz` is no longer
     * required and can be deleted cleanly to reclaim 1.18 GB.
     */
    fun isModelLocallyRequired(model: DesertAntModel): Boolean =
        model == DesertAntModel.VOZ && !isRemoteVozActive

    /**
     * `true` when any cached files for [model] can safely be deleted without breaking the active
     * configuration (including local `Voz` when Remote Voz API is active, bypassed `Punct` when AI
     * post-processing handles formatting, or any disabled/inactive model).
     */
    fun canDeleteModel(model: DesertAntModel): Boolean = when {
        isModelLocallyRequired(model) -> false
        model == DesertAntModel.PUNCT -> !isModelEnabled(model) || isAiPostProcessingActive
        else -> !isModelEnabled(model)
    }

    val autoUnloadSummary: String
        get() = autoUnloadSummary(modelAutoUnloadMinutes)

    fun enabledModels(): List<DesertAntModel> = buildList {
        if (!isRemoteVozActive) add(DesertAntModel.VOZ)
        if (earEnabled && !isRemoteEarActive) add(DesertAntModel.EAR)
        if (punctEnabled) add(DesertAntModel.PUNCT)
        if (isLocalLiteRtLlmEnabled) add(selectedLlmModel.desertAntModel)
    }

    /**
     * Local models required by the user's selected mode flags during startup/onboarding, even
     * before remote API URLs and keys are filled in, so API-mode and system Gemini Nano selections
     * require zero downloadable local LLM weights.
     */
    fun plannedLocalModels(): List<DesertAntModel> = buildList {
        if (!remoteVozEnabled) add(DesertAntModel.VOZ)
        if (earEnabled && !remoteEarEnabled) add(DesertAntModel.EAR)
        if (punctEnabled) add(DesertAntModel.PUNCT)
        if (gemmaEnabled && llmProvider == LlmProviderMode.LOCAL && localLlmBackend == LocalLlmBackend.LITERT_LM) {
            add(selectedLlmModel.desertAntModel)
        }
    }

    /**
     * Estimated peak local RAM (MB) matching [plannedLocalModels] before API credentials are entered.
     */
    fun plannedPeakRamMb(): Int =
        (if (!remoteVozEnabled) 1400 else 0) +
            (if (earEnabled && !remoteEarEnabled) 50 else 0) +
            when {
                gemmaEnabled && llmProvider == LlmProviderMode.LOCAL && localLlmBackend == LocalLlmBackend.LITERT_LM ->
                    selectedLlmModel.estimatedIncrementalRamMb
                punctEnabled && !gemmaEnabled -> 120
                else -> 0
            }

    companion object {
        const val PREFS_NAME = "openflow_model_settings"
        const val KEY_EAR_ENABLED = "ear_enabled"
        const val KEY_PUNCT_ENABLED = "punct_enabled"
        const val KEY_GEMMA_ENABLED = "gemma_enabled"
        const val KEY_FORMATTING_ENGINE_ENABLED = "formatting_engine_enabled_v2"
        const val KEY_LOCAL_LLM_BACKEND = "local_llm_backend"
        const val KEY_GEMINI_NANO_VARIANT = "gemini_nano_variant"
        const val KEY_GEMINI_NANO_AUTO_FALLBACK = "gemini_nano_auto_fallback"
        const val KEY_SELECTED_LLM_MODEL = "selected_llm_model"
        const val KEY_GEMMA_DISABLE_THINKING = "gemma_disable_thinking"
        const val KEY_GEMMA_ADAPTIVE_THINKING = "gemma_adaptive_thinking"
        const val KEY_LOCAL_THINKING_EFFORT = "local_thinking_effort"
        const val KEY_LOCAL_LLM_CHUNKING_ENABLED = "local_llm_chunking_enabled"
        const val KEY_GEMMA_PROMPT_PRESET = "gemma_prompt_preset"
        const val KEY_GEMMA_CUSTOM_PROMPT = "gemma_custom_prompt"
        const val KEY_LLM_PROVIDER = "llm_provider"
        const val KEY_OPENAI_BASE_URL = "openai_base_url"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_OPENAI_TIMEOUT_SECONDS = "openai_timeout_seconds"
        const val KEY_OPENAI_THINKING_EFFORT = "openai_thinking_effort"
        const val KEY_OPENAI_CUSTOM_REASONING_EFFORT = "openai_custom_reasoning_effort"
        const val KEY_OPENAI_CHUNKING_ENABLED = "openai_chunking_enabled"
        const val KEY_REMOTE_VOZ_ENABLED = "remote_voz_enabled"
        const val KEY_REMOTE_VOZ_BASE_URL = "remote_voz_base_url"
        const val KEY_REMOTE_VOZ_API_KEY = "remote_voz_api_key"
        const val KEY_REMOTE_VOZ_MODEL = "remote_voz_model"
        const val KEY_REMOTE_VOZ_TIMEOUT_SECONDS = "remote_voz_timeout_seconds"
        const val KEY_REMOTE_EAR_ENABLED = "remote_ear_enabled"
        const val KEY_REMOTE_EAR_USE_SAME_ENDPOINT = "remote_ear_use_same_endpoint"
        const val KEY_REMOTE_EAR_BASE_URL = "remote_ear_base_url"
        const val KEY_REMOTE_EAR_API_KEY = "remote_ear_api_key"
        const val KEY_REMOTE_EAR_MODEL = "remote_ear_model"
        const val KEY_REMOTE_EAR_TIMEOUT_SECONDS = "remote_ear_timeout_seconds"
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
            val llmProvider = LlmProviderMode.fromId(
                prefs.getString(KEY_LLM_PROVIDER, LlmProviderMode.LOCAL.id)
            )
            val openAiBaseUrl = (prefs.getString(
                KEY_OPENAI_BASE_URL,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_BASE_URL
            ) ?: com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_BASE_URL).trim()
            val openAiApiKey = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
            val openAiModel = (prefs.getString(
                KEY_OPENAI_MODEL,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_MODEL
            ) ?: com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_MODEL).trim()
            val openAiTimeoutSeconds = prefs.getInt(
                KEY_OPENAI_TIMEOUT_SECONDS,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_TIMEOUT_SECONDS
            ).coerceIn(5, 90)
            val openAiThinkingEffort = LlmThinkingEffort.fromId(
                prefs.getString(KEY_OPENAI_THINKING_EFFORT, LlmThinkingEffort.DEFAULT.id)
            )
            val openAiCustomReasoningEffort = (prefs.getString(KEY_OPENAI_CUSTOM_REASONING_EFFORT, "") ?: "").trim()
            val openAiChunkingEnabled = prefs.getBoolean(KEY_OPENAI_CHUNKING_ENABLED, false)
            val localThinkingEffort = LlmThinkingEffort.fromLocalId(
                prefs.getString(KEY_LOCAL_THINKING_EFFORT, LlmThinkingEffort.MEDIUM.id)
            )
            val localLlmChunkingEnabled = prefs.getBoolean(KEY_LOCAL_LLM_CHUNKING_ENABLED, true)
            val remoteVozEnabled = prefs.getBoolean(KEY_REMOTE_VOZ_ENABLED, false)
            val remoteVozBaseUrl = (prefs.getString(KEY_REMOTE_VOZ_BASE_URL, "") ?: "").trim()
            val remoteVozApiKey = prefs.getString(KEY_REMOTE_VOZ_API_KEY, "") ?: ""
            val remoteVozModel = (prefs.getString(
                KEY_REMOTE_VOZ_MODEL,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_VOZ_MODEL
            ) ?: com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_VOZ_MODEL).trim()
            val remoteVozTimeoutSeconds = prefs.getInt(
                KEY_REMOTE_VOZ_TIMEOUT_SECONDS,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_AUDIO_TIMEOUT_SECONDS
            ).coerceIn(5, 120)
            val remoteEarEnabled = prefs.getBoolean(KEY_REMOTE_EAR_ENABLED, false)
            val remoteEarUseSameEndpoint = prefs.getBoolean(KEY_REMOTE_EAR_USE_SAME_ENDPOINT, true)
            val remoteEarBaseUrl = (prefs.getString(KEY_REMOTE_EAR_BASE_URL, "") ?: "").trim()
            val remoteEarApiKey = prefs.getString(KEY_REMOTE_EAR_API_KEY, "") ?: ""
            val remoteEarModel = (prefs.getString(
                KEY_REMOTE_EAR_MODEL,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_EAR_MODEL
            ) ?: com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_REMOTE_EAR_MODEL).trim()
            val remoteEarTimeoutSeconds = prefs.getInt(
                KEY_REMOTE_EAR_TIMEOUT_SECONDS,
                com.voicebubble.openflow.core.speech.OpenAiCompatibleClient.DEFAULT_AUDIO_TIMEOUT_SECONDS
            ).coerceIn(5, 120)
            val rawGemmaEnabled = prefs.getBoolean(KEY_GEMMA_ENABLED, false)
            val localLlmBackend = LocalLlmBackend.fromId(
                prefs.getString(KEY_LOCAL_LLM_BACKEND, LocalLlmBackend.LITERT_LM.id)
            )
            val geminiNanoVariant = GeminiNanoVariant.fromId(
                prefs.getString(KEY_GEMINI_NANO_VARIANT, GeminiNanoVariant.FULL.id)
            )
            val geminiNanoAutoFallback = prefs.getBoolean(KEY_GEMINI_NANO_AUTO_FALLBACK, true)
            val rawSelectedId = prefs.getString(KEY_SELECTED_LLM_MODEL, null)
            val hasValidExplicitModel = LlmModelChoice.isSupportedId(rawSelectedId)
            val migratedFromRemovedModel = !rawSelectedId.isNullOrBlank() && !hasValidExplicitModel
            val selectedLlm = if (hasValidExplicitModel) {
                LlmModelChoice.fromId(rawSelectedId)
            } else {
                LlmModelChoice.GEMMA_4_E2B
            }
            val resolvedDisableThinking = if (hasValidExplicitModel) {
                prefs.getBoolean(KEY_GEMMA_DISABLE_THINKING, selectedLlm.defaultDisableThinking)
            } else {
                selectedLlm.defaultDisableThinking
            }
            val gemmaEnabled = if (migratedFromRemovedModel &&
                rawGemmaEnabled &&
                llmProvider == LlmProviderMode.LOCAL &&
                localLlmBackend == LocalLlmBackend.LITERT_LM
            ) {
                DesertAntModelStorage.isModelDownloaded(context, selectedLlm.desertAntModel)
            } else {
                rawGemmaEnabled
            }
            if (migratedFromRemovedModel) {
                prefs.edit()
                    .putString(KEY_SELECTED_LLM_MODEL, selectedLlm.id)
                    .putBoolean(KEY_GEMMA_DISABLE_THINKING, resolvedDisableThinking)
                    .putBoolean(KEY_GEMMA_ENABLED, gemmaEnabled)
                    .apply()
            }

            val autoUnloadMinutes = sanitizeAutoUnloadMinutes(
                prefs.getInt(KEY_MODEL_AUTO_UNLOAD_MINUTES, DEFAULT_MODEL_AUTO_UNLOAD_MINUTES)
            )

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                punctEnabled = prefs.getBoolean(KEY_PUNCT_ENABLED, false),
                gemmaEnabled = gemmaEnabled,
                formattingEngineEnabled = prefs.getBoolean(KEY_FORMATTING_ENGINE_ENABLED, false),
                localLlmBackend = localLlmBackend,
                geminiNanoVariant = geminiNanoVariant,
                geminiNanoAutoFallback = geminiNanoAutoFallback,
                selectedLlmModel = selectedLlm,
                gemmaDisableThinking = resolvedDisableThinking,
                gemmaAdaptiveThinking = prefs.getBoolean(KEY_GEMMA_ADAPTIVE_THINKING, true),
                localThinkingEffort = localThinkingEffort,
                localLlmChunkingEnabled = localLlmChunkingEnabled,
                gemmaPromptPreset = preset,
                gemmaCustomPrompt = customPrompt,
                llmProvider = llmProvider,
                openAiBaseUrl = openAiBaseUrl,
                openAiApiKey = openAiApiKey,
                openAiModel = openAiModel,
                openAiTimeoutSeconds = openAiTimeoutSeconds,
                openAiThinkingEffort = openAiThinkingEffort,
                openAiCustomReasoningEffort = openAiCustomReasoningEffort,
                openAiChunkingEnabled = openAiChunkingEnabled,
                remoteVozEnabled = remoteVozEnabled,
                remoteVozBaseUrl = remoteVozBaseUrl,
                remoteVozApiKey = remoteVozApiKey,
                remoteVozModel = remoteVozModel,
                remoteVozTimeoutSeconds = remoteVozTimeoutSeconds,
                remoteEarEnabled = remoteEarEnabled,
                remoteEarUseSameEndpoint = remoteEarUseSameEndpoint,
                remoteEarBaseUrl = remoteEarBaseUrl,
                remoteEarApiKey = remoteEarApiKey,
                remoteEarModel = remoteEarModel,
                remoteEarTimeoutSeconds = remoteEarTimeoutSeconds,
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
                .putBoolean(KEY_FORMATTING_ENGINE_ENABLED, settings.formattingEngineEnabled)
                .putString(KEY_LOCAL_LLM_BACKEND, settings.localLlmBackend.id)
                .putString(KEY_GEMINI_NANO_VARIANT, settings.geminiNanoVariant.id)
                .putBoolean(KEY_GEMINI_NANO_AUTO_FALLBACK, settings.geminiNanoAutoFallback)
                .putString(KEY_SELECTED_LLM_MODEL, settings.selectedLlmModel.id)
                .putBoolean(KEY_GEMMA_DISABLE_THINKING, settings.gemmaDisableThinking)
                .putBoolean(KEY_GEMMA_ADAPTIVE_THINKING, settings.gemmaAdaptiveThinking)
                .putString(KEY_LOCAL_THINKING_EFFORT, settings.localThinkingEffort.id)
                .putBoolean(KEY_LOCAL_LLM_CHUNKING_ENABLED, settings.localLlmChunkingEnabled)
                .putString(KEY_GEMMA_PROMPT_PRESET, settings.gemmaPromptPreset.id)
                .putString(KEY_GEMMA_CUSTOM_PROMPT, settings.gemmaCustomPrompt)
                .putString(KEY_LLM_PROVIDER, settings.llmProvider.id)
                .putString(KEY_OPENAI_BASE_URL, settings.openAiBaseUrl.trim())
                .putString(KEY_OPENAI_API_KEY, settings.openAiApiKey.trim())
                .putString(KEY_OPENAI_MODEL, settings.openAiModel.trim())
                .putInt(KEY_OPENAI_TIMEOUT_SECONDS, settings.openAiTimeoutSeconds.coerceIn(5, 90))
                .putString(KEY_OPENAI_THINKING_EFFORT, settings.openAiThinkingEffort.id)
                .putString(KEY_OPENAI_CUSTOM_REASONING_EFFORT, settings.openAiCustomReasoningEffort.trim())
                .putBoolean(KEY_OPENAI_CHUNKING_ENABLED, settings.openAiChunkingEnabled)
                .putBoolean(KEY_REMOTE_VOZ_ENABLED, settings.remoteVozEnabled)
                .putString(KEY_REMOTE_VOZ_BASE_URL, settings.remoteVozBaseUrl.trim())
                .putString(KEY_REMOTE_VOZ_API_KEY, settings.remoteVozApiKey.trim())
                .putString(KEY_REMOTE_VOZ_MODEL, settings.remoteVozModel.trim())
                .putInt(KEY_REMOTE_VOZ_TIMEOUT_SECONDS, settings.remoteVozTimeoutSeconds.coerceIn(5, 120))
                .putBoolean(KEY_REMOTE_EAR_ENABLED, settings.remoteEarEnabled)
                .putBoolean(KEY_REMOTE_EAR_USE_SAME_ENDPOINT, settings.remoteEarUseSameEndpoint)
                .putString(KEY_REMOTE_EAR_BASE_URL, settings.remoteEarBaseUrl.trim())
                .putString(KEY_REMOTE_EAR_API_KEY, settings.remoteEarApiKey.trim())
                .putString(KEY_REMOTE_EAR_MODEL, settings.remoteEarModel.trim())
                .putInt(KEY_REMOTE_EAR_TIMEOUT_SECONDS, settings.remoteEarTimeoutSeconds.coerceIn(5, 120))
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
        DesertAntModel.GEMMA_4_E2B to "v1.0.0"
    )

    // In-memory cache of (targetDir.absolutePath -> (lastModifiedSum, sizeSum)) for verified model directories
    // so repeated per-chunk / per-UI checks avoid redundant disk stat() syscalls when nothing on disk changed.
    private val directoryCompleteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    @Volatile
    private var bundledExtractionChecked = false

    @Volatile
    private var legacyModelsCleanedUp = false

    internal val REMOVED_LEGACY_MODEL_IDS: List<String> = listOf(
        "clear",
        "uhm",
        "qwen3_0_6b_lite",
        "qwen3_0_6b",
        "qwen3_1_7b",
        "lfm3_1_7b",
        "qwen2_5_1_5b",
        "minicpm5_2b",
        "smollm3_3b",
        "granite4_micro",
        "ministral3_3b",
        "gemma4_e4b",
        "granite4_1_3b",
        "qwen3_4b_instruct_2507",
        "granite4_2_3b",
        "qwen3_4b"
    )

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
     * Automatically removes any leftover deprecated model directories and compiled LiteRT-LM caches
     * from previous releases to reclaim device storage.
     */
    fun cleanupLegacyRemovedModels(context: Context) {
        if (legacyModelsCleanedUp) return
        legacyModelsCleanedUp = true
        val cacheDir = context.applicationContext.cacheDir
        cleanupLegacyRemovedModelsInDirectory(File(cacheDir, "desert-ant-models"))
        cleanupLegacyRemovedModelsInDirectory(File(cacheDir, "litert_llm_cache"))
    }

    internal fun cleanupLegacyRemovedModelsInDirectory(modelsBase: File): Int {
        var removedCount = 0
        try {
            if (modelsBase.exists()) {
                for (legacyId in REMOVED_LEGACY_MODEL_IDS) {
                    val dir = File(modelsBase, legacyId)
                    if (dir.exists() && dir.deleteRecursively()) {
                        removedCount++
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return removedCount
    }

    /**
     * If this APK is the Offline-Bundled build containing pre-packaged model files under
     * `assets/desert-ant-models/`, extracts any missing model files and `.dal-meta/manifest`
     * into `context.cacheDir/desert-ant-models/<id>/<revision>` automatically.
     */
    fun ensureBundledModelsExtracted(context: Context) {
        if (bundledExtractionChecked && legacyModelsCleanedUp) return
        ensureBundledModelsExtractedSynchronized(context)
    }

    @Synchronized
    private fun ensureBundledModelsExtractedSynchronized(context: Context) {
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
     * Total bytes currently occupied on disk by [model] under `desert-ant-models/<id>` (including
     * partial `.part` downloads) plus any compiled `litert_llm_cache/<llm>` artifacts for LLMs.
     */
    fun modelCacheDirectoryBytes(context: Context, model: DesertAntModel): Long {
        val modelRoot = modelCacheRoot(context, model)
        var totalBytes = computeDirectoryBytes(modelRoot)
        if (model.isLlmModel) {
            val llmChoice = LlmModelChoice.fromDesertAntModel(model)
            if (llmChoice != null) {
                val compilerCacheDir = File(context.applicationContext.cacheDir, "litert_llm_cache/${llmChoice.id}")
                totalBytes += computeDirectoryBytes(compilerCacheDir)
            }
        }
        return totalBytes
    }

    internal fun computeDirectoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Reliably deletes [targetDir] and (if provided) [extraCacheDir], clearing cached directory-check
     * state and recovering from read-only files if needed. Returns `true` only once [targetDir]
     * no longer exists on disk.
     */
    internal fun deleteModelDirectoryTree(targetDir: File, extraCacheDir: File? = null): Boolean {
        val targetPrefix = targetDir.absolutePath
        directoryCompleteCache.keys.removeAll { it == targetPrefix || it.startsWith("$targetPrefix/") || it.startsWith("$targetPrefix\\") }

        fun forceDelete(dir: File): Boolean {
            if (!dir.exists()) return true
            return try {
                if (!dir.deleteRecursively() && dir.exists()) {
                    dir.walkBottomUp().forEach { entry ->
                        runCatching {
                            entry.setWritable(true)
                            entry.delete()
                        }
                    }
                }
                !dir.exists()
            } catch (_: Throwable) {
                !dir.exists()
            }
        }

        val primaryDeleted = forceDelete(targetDir)
        if (extraCacheDir != null) {
            forceDelete(extraCacheDir)
        }
        return primaryDeleted && !targetDir.exists()
    }

    /**
     * Deletes cached files on disk for [model] (plus compiled LiteRT-LM cache for LLMs).
     * Local `Voz` is blocked unless [allowRequired] is `true` (e.g. when Remote Voz API is active).
     */
    fun deleteModelCache(
        context: Context,
        model: DesertAntModel,
        allowRequired: Boolean = false
    ): Boolean {
        if (model.isRequired && !allowRequired) return false
        val root = modelCacheRoot(context, model)
        val extraCompilerCache = if (model.isLlmModel) {
            LlmModelChoice.fromDesertAntModel(model)?.let {
                File(context.applicationContext.cacheDir, "litert_llm_cache/${it.id}")
            }
        } else {
            null
        }
        return deleteModelDirectoryTree(root, extraCompilerCache)
    }

    /**
     * Deletes all downloaded or partially cached models that are safe to remove under [settings]
     * (`canDeleteModel`, including local `Voz` when Remote Voz API is active, bypassed `Punct` when
     * AI post-processing is active, and any disabled or partial caches).
     */
    fun deleteAllInactiveModels(context: Context, settings: ModelSettings): Int {
        var deletedCount = 0
        for (model in DesertAntModel.entries) {
            val onDisk = isModelDownloaded(context, model) || modelCacheDirectoryBytes(context, model) > 0L
            if (settings.canDeleteModel(model) && onDisk) {
                if (deleteModelCache(context, model, allowRequired = true)) {
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
        sampleText = "um okay team quick architecture recap new paragraph we tested three local models on eight gigabytes of ram wait no make that six gigabytes of ram and uh here is what we saw colon number one ministral three three b finished in about four hundred twenty milliseconds at thirty eight point five tokens per second number two gemma four e two b used around two point one gigabytes actually sorry one point nine gigabytes with ninety eight percent formatting accuracy number three idle memory dropped to zero megabytes after five minutes new paragraph please ping elena dot kovacs at openflow dot dev if throughput dips below twenty five percent",
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
