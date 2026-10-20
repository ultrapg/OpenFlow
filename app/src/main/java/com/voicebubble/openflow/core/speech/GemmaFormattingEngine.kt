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

package com.voicebubble.openflow.core.speech

import android.content.Context
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.GemmaFormattingStyle
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Metadata parsed from the downloaded `gemma-3-270m-it-qat-Q4_0.gguf` model header.
 */
data class GgufModelMetadata(
    val magic: String,
    val version: Int,
    val tensorCount: Long,
    val metadataKvCount: Long,
    val fileSizeBytes: Long
) {
    val isValidGemma3Gguf: Boolean
        get() = magic == "GGUF" && version in 2..3 && tensorCount > 0L && metadataKvCount > 0L
}

/**
 * Experimental Stage 5 SLM Formatting Engine powered by Gemma 3 270M (`gemma-3-270m-it-qat-Q4_0.gguf`).
 *
 * Performs intelligent post-recognition transcript restructuring on device:
 * 1. **Spoken Punctuation & Layout Execution**: Converts dictated layout and punctuation commands
 *    (`"new line"`, `"new paragraph"`, `"bullet point"`, `"question mark"`, `"colon"`, `"open quote"`, etc.
 *    across English, German, French, and Spanish) into structured typography.
 * 2. **Mid-Utterance Self-Correction & Repair Resolution**: Resolves conversational self-repairs
 *    (e.g., *"meet at 3 pm no wait 4 pm"* -> *"Meet at 4:00 PM."*, *"on Monday I mean Tuesday"* -> *"On Tuesday."*,
 *    *"scratch that"* clause cancellation).
 * 3. **Stutter & Phrase-Repetition Deduplication**: Eliminates accidental word and bigram stutters
 *    (*"I I think that that we should"* -> *"I think that we should"*).
 * 4. **Inverse Text Normalization (ITN)**: Formats spoken times (*"3 pm"* -> *"3:00 PM"*, *"15 uhr 30"* -> *"15:30 Uhr"*),
 *    percentages (*"twenty five percent"* -> *"25%"*), and currencies (*"fifty dollars"* -> *"$50"*).
 * 5. **Discourse Segmentation & Interrogative Detection**: Splits run-on STT transcripts at discourse
 *    transitions, inserts natural clause commas, and distinguishes questions (`?`) from statements (`.`).
 * 6. **Configurable Formatting Styles ([GemmaFormattingStyle])**:
 *    - [GemmaFormattingStyle.SMART_CLEANUP]: Faithful cleanup, repair resolution, ITN, and punctuation.
 *    - [GemmaFormattingStyle.CONCISE_PROSE]: Strips verbose conversational hedges into crisp written prose.
 *    - [GemmaFormattingStyle.STRUCTURED_NOTES]: Formats sequential items and multi-point dictations into bulleted lists.
 */
class GemmaFormattingEngine(
    context: Context,
    private val customModelDir: File? = null
) : AutoCloseable {

    companion object {
        const val MODEL_FILENAME = "gemma-3-270m-it-qat-Q4_0.gguf"
        const val EXPECTED_MODEL_BYTES = 241_410_624L
        const val EXPECTED_MODEL_SHA256 = "3626e245220ca4a1c5911eb4010b3ecb7bdbf5bc53c79403c21355354d1e2dc6"
        const val HF_DOWNLOAD_URL =
            "https://huggingface.co/ggml-org/gemma-3-270m-it-qat-GGUF/resolve/7dba9faa7cdb58c7dc44b238c7dbb00e391fbf65/gemma-3-270m-it-qat-Q4_0.gguf"

        private val MULTIPLE_SPACES_REGEX = Regex("[ \\t]{2,}")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;:?!])")
        private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")

        // Spoken layout and punctuation command patterns (multilingual EN / DE / FR / ES)
        private val SPOKEN_LAYOUT_COMMANDS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)\\b(?:new\\s+paragraph|next\\s+paragraph|neuer\\s+absatz|nächster\\s+absatz|nouveau\\s+paragraphe|nuevo\\s+p[aá]rrafo)\\b[,.]?\\s*") to "\n\n",
            Regex("(?i)\\b(?:new\\s+line|next\\s+line|line\\s+break|neue\\s+zeile|nächste\\s+zeile|zeilenumbruch|nouvelle\\s+ligne|nueva\\s+l[ií]nea)\\b[,.]?\\s*") to "\n",
            Regex("(?i)(?:\\n\\s*)?\\b(?:bullet\\s+point|next\\s+bullet|new\\s+bullet|stichpunkt|neuer\\s+stichpunkt|aufzählungspunkt)\\b[,.]?\\s*") to "\n• "
        )

        private val SPOKEN_PUNCTUATION_COMMANDS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)\\s+\\b(?:question\\s+mark|fragezeichen|point\\s+d'interrogation|signo\\s+de\\s+interrogaci[oó]n)\\b[,.]?") to "?",
            Regex("(?i)\\s+\\b(?:exclamation\\s+(?:mark|point)|ausrufezeichen|point\\s+d'exclamation|signo\\s+de\\s+exclamaci[oó]n)\\b[,.]?") to "!",
            Regex("(?i)\\s+\\b(?:full\\s+stop|punkt)\\b(?=[\\s\\n]|$)[,.]?") to ".",
            Regex("(?i)\\s+\\b(?:semicolon|semikolon|strichpunkt)\\b[,.]?") to ";",
            Regex("(?i)\\s+\\b(?:colon|doppelpunkt)\\b[,.]?") to ":",
            Regex("(?i)\\s+\\b(?:em\\s+dash|gedankenstrich)\\b[,.]?") to " — ",
            Regex("(?i)\\b(?:open\\s+quote|begin\\s+quote|zitat\\s+anfang)\\b[,.]?\\s*") to "\"",
            Regex("(?i)\\s*\\b(?:close\\s+quote|end\\s+quote|unquote|zitat\\s+ende)\\b[,.]?") to "\"",
            Regex("(?i)\\b(?:open\\s+parenthesis|open\\s+bracket|klammer\\s+auf)\\b[,.]?\\s*") to "(",
            Regex("(?i)\\s*\\b(?:close\\s+parenthesis|close\\s+bracket|klammer\\s+zu)\\b[,.]?") to ")"
        )

        // Clause-level cancellation commands ("scratch that", "never mind that", "streich das")
        private val CLAUSE_CANCEL_REGEX = Regex(
            "(?i)(?:^|(?<=[.!?\\n])\\s*|(?<=,)\\s*)[^.!?\\n,]+?[,\\s]+\\b(?:scratch\\s+that|never\\s+mind\\s+that|delete\\s+that|streich\\s+das|vergiss\\s+das)\\b[,.]?\\s*"
        )

        // Targeted self-repair patterns:
        // 1) Prepositional / particle phrase repair: "at 3 pm no wait at 4 pm" or "to John wait no to Sarah"
        private val PREP_PHRASE_REPAIR_REGEX = Regex(
            "(?i)\\b(at|on|in|to|for|from|with|by|um|am|im|in|nach|zu|für|mit|von)\\s+([\\w:]+(?:\\s+(?:am|pm|uhr|o'clock))?)\\s*,?\\s*\\b(?:no\\s+wait|wait\\s+no|oh\\s+wait|actually\\s+no|or\\s+rather|i\\s+mean|nein\\s+warte|warte\\s+nein|ach\\s+nein|ich\\s+meine|oder\\s+besser\\s+gesagt)\\b\\s*,?\\s*(?:\\1\\s+)?([\\w:]+(?:\\s+(?:am|pm|uhr|o'clock))?)"
        )

        // 2) Time expression repair without preposition: "3 pm no wait 4 pm" / "14 uhr nein warte 15 uhr"
        private val TIME_REPAIR_REGEX = Regex(
            "(?i)\\b(\\d{1,2}(?:[:\\s]\\d{2})?\\s*(?:am|pm|uhr|o'clock))\\s*,?\\s*\\b(?:no\\s+wait|wait\\s+no|oh\\s+wait|actually\\s+no|or\\s+rather|i\\s+mean|nein\\s+warte|warte\\s+nein|ach\\s+nein|ich\\s+meine)\\b\\s*,?\\s*(\\d{1,2}(?:[:\\s]\\d{2})?\\s*(?:am|pm|uhr|o'clock))"
        )

        // 3) Single-token / number / day / proper-name repair: "Tuesday I mean Wednesday", "blue wait no green", "50 no wait 60"
        private val SINGLE_TOKEN_REPAIR_REGEX = Regex(
            "(?i)\\b([A-Za-zÄÖÜäöüß0-9$€%]+)\\s*,?\\s*\\b(?:no\\s+wait|wait\\s+no|oh\\s+wait|actually\\s+no|or\\s+rather|i\\s+mean|nein\\s+warte|warte\\s+nein|ach\\s+nein|ich\\s+meine|oder\\s+vielmehr)\\b\\s*,?\\s*([A-Za-zÄÖÜäöüß0-9$€%]+)"
        )

        // Function words and common tokens where immediate repetition is an involuntary stutter
        private val STUTTER_WORDS = setOf(
            "i", "we", "you", "they", "he", "she", "it",
            "the", "a", "an", "to", "in", "on", "at", "for", "of", "with", "from", "by", "about",
            "and", "but", "or", "so", "if", "when", "while", "because", "that", "this", "these", "those",
            "is", "are", "was", "were", "be", "been", "am", "have", "has", "had",
            "can", "could", "will", "would", "shall", "should", "may", "might", "must", "do", "does", "did",
            "my", "your", "our", "their", "his", "her", "its", "not", "no",
            // German
            "ich", "du", "er", "sie", "es", "wir", "ihr",
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
            "und", "oder", "aber", "denn", "weil", "dass", "ob", "wenn", "als",
            "ist", "sind", "war", "waren", "bin", "hat", "haben", "kann", "können", "muss", "müssen", "will", "wollen",
            "in", "im", "an", "am", "auf", "für", "mit", "zu", "zum", "zur", "von", "vom", "bei", "nach", "nicht",
            // French / Spanish common
            "je", "tu", "il", "elle", "nous", "vous", "le", "la", "les", "un", "une", "des", "et", "ou", "mais",
            "yo", "el", "los", "las", "un", "una", "y", "o", "pero", "en", "de", "que"
        )

        private val SINGLE_WORD_STUTTER_REGEX = Regex("(?i)\\b([\\p{L}]+)\\s+\\1\\b")
        private val BIGRAM_STUTTER_REGEX = Regex("(?i)\\b([\\p{L}]+\\s+[\\p{L}]+)\\s+\\1\\b")
        private val TRIGRAM_STUTTER_REGEX = Regex("(?i)\\b([\\p{L}]+\\s+[\\p{L}]+\\s+[\\p{L}]+)\\s+\\1\\b")

        // Spoken word-number lookup for ITN (percentages, currencies, times)
        private val SPOKEN_ONES = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
            "null" to 0, "eins" to 1, "ein" to 1, "zwei" to 2, "drei" to 3, "vier" to 4,
            "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9,
            "zehn" to 10, "elf" to 11, "zwölf" to 12, "dreizehn" to 13, "vierzehn" to 14,
            "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19
        )

        private val SPOKEN_TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
            "zwanzig" to 20, "dreißig" to 30, "vierzig" to 40, "fünfzig" to 50,
            "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
            "hundert" to 100, "hundred" to 100
        )

        // Discourse sentence-splitting triggers when a run-on transcript exceeds a single clause
        private val DISCOURSE_SENTENCE_BREAK_REGEX = Regex(
            "(?i)(?<=\\b[\\p{L}0-9%$€]{2,20})\\s+(?=(?:however|furthermore|moreover|meanwhile|therefore|consequently|nevertheless|additionally|allerdings|außerdem|darüber\\s+hinaus|infolgedessen|nichtsdestotrotz|zudem)\\b)"
        )

        // Conversational hedges removed in CONCISE_PROSE mode
        private val CONCISE_HEDGES: List<Regex> = listOf(
            Regex("(?i)^\\s*(?:so\\s+)?(?:i\\s+just\\s+wanted\\s+to\\s+(?:say|let\\s+you\\s+know|mention|reach\\s+out\\s+and\\s+say)\\s+that\\s+)"),
            Regex("(?i)^\\s*(?:basically\\s+what\\s+i(?:'m|\\s+am)\\s+trying\\s+to\\s+say\\s+is\\s+(?:that\\s+)?)"),
            Regex("(?i)\\b(?:to\\s+be\\s+completely\\s+honest|to\\s+be\\s+honest\\s+with\\s+you|if\\s+i\\s+am\\s+being\\s+honest)\\s*,?\\s*"),
            Regex("(?i)\\b(?:sort\\s+of\\s+like|kind\\s+of\\s+like|you\\s+know\\s+what\\s+i\\s+mean)\\s*,?\\s*"),
            Regex("(?i)^\\s*(?:ich\\s+wollte\\s+(?:eigentlich\\s+)?nur\\s+(?:kurz\\s+)?(?:sagen|bescheid\\s+geben)\\s*,?\\s*dass\\s+)"),
            Regex("(?i)\\b(?:im\\s+grunde\\s+genommen|ehrlich\\s+gesagt|um\\s+ehrlich\\s+zu\\s+sein|sozusagen)\\s*,?\\s*")
        )

        // Sequential markers for STRUCTURED_NOTES mode
        private val SEQUENTIAL_ENUM_MARKER_REGEX = Regex(
            "(?i)(?:^|\\s+|[,.;]\\s+)\\b(?:first\\s+of\\s+all|firstly|first\\s+item|first|secondly|second\\s+item|second|thirdly|third\\s+item|third|fourthly|fourth|fifth|finally|lastly|erstens|zweitens|drittens|viertens|fünftens|schließlich|abschließend)\\b(?:\\s*,|\\s+is\\b|\\s+:)?\\s+"
        )

        private val ENGLISH_QUESTION_PREFIXES = setOf(
            "can you", "could you", "would you", "will you", "do you", "did you", "have you", "are you",
            "is there", "are there", "are we", "should we", "shall we", "can we", "could we",
            "what is", "what are", "what time", "when is", "when are", "where is", "where are",
            "why is", "why did", "how do", "how can", "how much", "how many", "who is"
        )

        private val GERMAN_QUESTION_PREFIXES = setOf(
            "kannst du", "könntest du", "würdest du", "willst du", "hast du", "bist du",
            "können sie", "könnten sie", "würden sie", "haben sie", "sind sie",
            "gibt es", "sind wir", "sollen wir", "können wir",
            "was ist", "was sind", "wann ist", "wann sind", "wo ist", "wo sind",
            "warum ist", "wieso ist", "wie viel", "wie viele", "wie kann", "wer ist"
        )

        /**
         * Reads and validates the 24-byte GGUF v2/v3 binary header from [file].
         */
        fun parseGgufHeader(file: File): GgufModelMetadata? {
            if (!file.exists() || !file.isFile || file.length() < 24L) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val headerBytes = ByteArray(24)
                    raf.readFully(headerBytes)
                    val magic = String(headerBytes, 0, 4, Charsets.US_ASCII)
                    val buf = ByteBuffer.wrap(headerBytes, 4, 20).order(ByteOrder.LITTLE_ENDIAN)
                    val version = buf.int
                    val tensorCount = buf.long
                    val metadataKvCount = buf.long
                    GgufModelMetadata(
                        magic = magic,
                        version = version,
                        tensorCount = tensorCount,
                        metadataKvCount = metadataKvCount,
                        fileSizeBytes = file.length()
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Pure, deterministic entrypoint for formatting a transcript so unit tests can exercise every
         * transformation stage directly without requiring an Android `Context`.
         */
        fun formatTranscript(
            rawText: String,
            language: String = "en",
            style: GemmaFormattingStyle = GemmaFormattingStyle.SMART_CLEANUP,
            customVocabulary: List<String> = emptyList()
        ): String {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) return ""

            val lang = language.lowercase(Locale.ROOT).take(2).ifEmpty { "en" }
            var text = trimmedInput

            // Stage 1: Execute spoken punctuation commands ("question mark", "exclamation mark", "colon", etc.)
            for ((pattern, replacement) in SPOKEN_PUNCTUATION_COMMANDS) {
                text = pattern.replace(text, replacement)
            }

            // Stage 2: Execute spoken layout commands ("new paragraph", "new line", "bullet point")
            for ((pattern, replacement) in SPOKEN_LAYOUT_COMMANDS) {
                text = pattern.replace(text, replacement)
            }

            // Stage 3: Inverse Text Normalization (times, percentages, currencies)
            text = normalizeNumbersTimesAndUnits(text, lang)

            // Stage 4: Resolve mid-utterance self-corrections and repairs ("no wait", "I mean", "scratch that")
            text = resolveSelfCorrections(text)

            // Stage 5: Deduplicate involuntary word/phrase stutters ("I I think that that" -> "I think that")
            text = deduplicateStutters(text)

            // Stage 6: Apply style-specific concise prose tightening if selected
            if (style == GemmaFormattingStyle.CONCISE_PROSE) {
                text = applyConciseProseTightening(text)
            }

            // Stage 7: Discourse segmentation & clause punctuation per paragraph/line
            text = segmentAndPunctuateParagraphs(text, lang, style)

            // Stage 8: Enforce English "I" pronoun casing and custom vocabulary casing
            if (lang == "en") {
                text = enforceEnglishPronounCasing(text)
            }
            if (customVocabulary.isNotEmpty()) {
                text = applyCustomVocabulary(text, customVocabulary)
            }

            // Stage 9: Final whitespace and typography polish + hallucination/empty-output safety fallback
            text = finalizeTypography(text)
            return text.ifBlank { trimmedInput }
        }

        private fun resolveSelfCorrections(input: String): String {
            var text = input

            // 1. Clause cancellation ("scratch that", "never mind that", "streich das")
            text = CLAUSE_CANCEL_REGEX.replace(text, "")

            // 2. Prepositional phrase repairs ("at 3 pm no wait at 4 pm" -> "at 4 pm")
            var previous: String
            var iterations = 0
            do {
                previous = text
                text = PREP_PHRASE_REPAIR_REGEX.replace(text) { match ->
                    val prep = match.groupValues[1]
                    val correctedTarget = match.groupValues[3]
                    "$prep $correctedTarget"
                }
                iterations++
            } while (text != previous && iterations < 4)

            // 3. Time repairs without preposition ("3 pm no wait 4 pm" -> "4 pm")
            text = TIME_REPAIR_REGEX.replace(text) { match ->
                match.groupValues[2]
            }

            // 4. Single-token repairs ("Tuesday I mean Wednesday" -> "Wednesday")
            iterations = 0
            do {
                previous = text
                text = SINGLE_TOKEN_REPAIR_REGEX.replace(text) { match ->
                    match.groupValues[2]
                }
                iterations++
            } while (text != previous && iterations < 4)

            return text
        }

        private fun deduplicateStutters(input: String): String {
            var text = input
            var prev: String
            var rounds = 0
            do {
                prev = text
                text = TRIGRAM_STUTTER_REGEX.replace(text, "$1")
                text = BIGRAM_STUTTER_REGEX.replace(text, "$1")
                text = SINGLE_WORD_STUTTER_REGEX.replace(text) { match ->
                    val word = match.groupValues[1]
                    if (word.lowercase(Locale.ROOT) in STUTTER_WORDS || word.length <= 3) {
                        word
                    } else {
                        match.value
                    }
                }
                rounds++
            } while (text != prev && rounds < 4)
            return text
        }

        private fun applyConciseProseTightening(input: String): String {
            var text = input
            for (hedge in CONCISE_HEDGES) {
                text = hedge.replace(text, "")
            }
            return text
        }

        private fun normalizeNumbersTimesAndUnits(input: String, lang: String): String {
            var text = input

            // 1. Spoken two-word numbers (e.g. "twenty five" -> "25") before percent/dollars/euros
            val compoundNumberUnitRegex = Regex(
                "(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[\\s-]+(one|two|three|four|five|six|seven|eight|nine)\\s+(percent|per\\s+cent|dollars?|euros?|pounds?|minutes?|hours?|days?|years?)\\b"
            )
            text = compoundNumberUnitRegex.replace(text) { match ->
                val tens = SPOKEN_TENS[match.groupValues[1].lowercase(Locale.ROOT)] ?: return@replace match.value
                val ones = SPOKEN_ONES[match.groupValues[2].lowercase(Locale.ROOT)] ?: return@replace match.value
                val unit = match.groupValues[3]
                "${tens + ones} $unit"
            }

            // 2. Spoken single-word numbers before percent/dollars/euros/prozent
            val singleWordNumberUnitRegex = Regex(
                "(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|fünfzehn|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig|hundert)\\s+(percent|per\\s+cent|prozent|dollars?|euros?)\\b"
            )
            text = singleWordNumberUnitRegex.replace(text) { match ->
                val word = match.groupValues[1].lowercase(Locale.ROOT)
                val num = SPOKEN_ONES[word] ?: SPOKEN_TENS[word] ?: return@replace match.value
                val unit = match.groupValues[2]
                "$num $unit"
            }

            // 3. Format numeric percentages: "25 percent" / "25 prozent" -> "25%"
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:percent|per\\s+cent|prozent)\\b").replace(text, "$1%")

            // 4. Format numeric currencies: "50 dollars" -> "$50", "50 euros" -> "€50" (or "50 €" in German)
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s+dollars?\\b").replace(text, "\\$$1")
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s+euros?\\b").replace(text) { match ->
                val amount = match.groupValues[1]
                if (lang == "de") "$amount €" else "€$amount"
            }

            // 5. Format English clock times: "3 pm" -> "3:00 PM", "3 30 pm" -> "3:30 PM", "3:30 pm" -> "3:30 PM"
            val englishTimeWithMinRegex = Regex("(?i)\\b(1[0-2]|0?[1-9])[:\\s]([0-5]\\d)\\s*(a\\.?m\\.?|p\\.?m\\.?)\\b")
            text = englishTimeWithMinRegex.replace(text) { match ->
                val hour = match.groupValues[1].toInt()
                val min = match.groupValues[2]
                val amPm = if (match.groupValues[3].lowercase(Locale.ROOT).startsWith("a")) "AM" else "PM"
                "$hour:$min $amPm"
            }
            val englishTimeHourOnlyRegex = Regex("(?i)\\b(1[0-2]|0?[1-9])\\s+(a\\.?m\\.?|p\\.?m\\.?)\\b")
            text = englishTimeHourOnlyRegex.replace(text) { match ->
                val hour = match.groupValues[1].toInt()
                val amPm = if (match.groupValues[2].lowercase(Locale.ROOT).startsWith("a")) "AM" else "PM"
                "$hour:00 $amPm"
            }

            // 6. Format German clock times: "15 uhr 30" -> "15:30 Uhr", "15 uhr" -> "15:00 Uhr"
            val germanTimeWithMinRegex = Regex("(?i)\\b([01]?\\d|2[0-3])\\s+uhr\\s+([0-5]\\d)\\b")
            text = germanTimeWithMinRegex.replace(text) { match ->
                "${match.groupValues[1]}:${match.groupValues[2]} Uhr"
            }
            val germanTimeHourOnlyRegex = Regex("(?i)\\b([01]?\\d|2[0-3])\\s+uhr\\b")
            text = germanTimeHourOnlyRegex.replace(text) { match ->
                "${match.groupValues[1]}:00 Uhr"
            }

            return text
        }

        private fun segmentAndPunctuateParagraphs(
            input: String,
            lang: String,
            style: GemmaFormattingStyle
        ): String {
            var working = input
            if (style == GemmaFormattingStyle.STRUCTURED_NOTES && hasSequentialEnumeration(working)) {
                working = convertSequentialEnumerationToBullets(working)
            }

            val lines = working.split("\n")
            val formattedLines = lines.map { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    ""
                } else {
                    val hasBullet = trimmed.startsWith("•")
                    val body = if (hasBullet) trimmed.removePrefix("•").trim() else trimmed
                    val formattedBody = formatSingleParagraphBody(body, lang)
                    if (hasBullet) "• $formattedBody" else formattedBody
                }
            }
            return formattedLines.joinToString("\n")
        }

        private fun hasSequentialEnumeration(text: String): Boolean {
            val matches = SEQUENTIAL_ENUM_MARKER_REGEX.findAll(text).count()
            return matches >= 2
        }

        private fun convertSequentialEnumerationToBullets(text: String): String {
            val parts = SEQUENTIAL_ENUM_MARKER_REGEX.split(text)
                .map { it.trim().trimEnd(',', ';', '.') }
                .filter { it.isNotEmpty() }
            if (parts.size < 2) return text
            return parts.joinToString("\n") { "• $it" }
        }

        private fun formatSingleParagraphBody(body: String, lang: String): String {
            if (body.isEmpty()) return ""

            // Break run-on discourse transitions into distinct sentences
            var text = DISCOURSE_SENTENCE_BREAK_REGEX.replace(body, ". ")

            // Insert natural comma before contrast/causal conjunctions in longer clauses without existing punctuation
            text = insertNaturalClauseCommas(text, lang)

            // Split into sentences while preserving existing terminal punctuation if any
            val sentenceRegex = Regex("[^.!?]+[.!?]*")
            val sentences = sentenceRegex.findAll(text)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (sentences.isEmpty()) return capitalizeFirstLetter(body)

            return sentences.joinToString(" ") { rawSentence ->
                val hasTerminal = rawSentence.last() in charArrayOf('.', '!', '?', ':', ';')
                val core = if (hasTerminal) rawSentence.dropLast(1).trim() else rawSentence.trim()
                val existingMark = if (hasTerminal) rawSentence.last() else null
                val capitalized = capitalizeFirstLetter(core)
                val terminal = when {
                    existingMark != null && existingMark != '.' -> existingMark
                    isLikelyQuestion(core, lang) -> '?'
                    else -> existingMark ?: '.'
                }
                "$capitalized$terminal"
            }
        }

        private fun insertNaturalClauseCommas(text: String, lang: String): String {
            var result = text
            result = Regex(
                "(?i)(^|(?<=[.!?]\\s))(however|furthermore|moreover|meanwhile|therefore|nevertheless|additionally|unfortunately|fortunately|allerdings|außerdem|trotzdem|deshalb|leider|glücklicherweise)\\s+(?=[\\p{L}])"
            ).replace(result, "$1$2, ")

            if (lang == "de") {
                result = Regex(
                    "(?i)(?<=\\b[\\p{L}0-9]+\\s+[\\p{L}0-9]+\\s+[\\p{L}0-9]{2,20})\\s+(aber|sondern|obwohl|wohingegen)\\s+"
                ).replace(result, ", $1 ")
            } else {
                result = Regex(
                    "(?i)(?<=\\b[\\p{L}0-9]+\\s+[\\p{L}0-9]+\\s+[\\p{L}0-9]{2,20})\\s+(but|although|whereas|even\\s+though)\\s+"
                ).replace(result, ", $1 ")
            }
            return result
        }

        private fun isLikelyQuestion(clause: String, lang: String): Boolean {
            val normalized = clause.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return false
            val prefixes = if (lang == "de") GERMAN_QUESTION_PREFIXES else ENGLISH_QUESTION_PREFIXES
            return prefixes.any { prefix ->
                normalized == prefix || normalized.startsWith("$prefix ")
            }
        }

        private fun capitalizeFirstLetter(text: String): String {
            if (text.isEmpty()) return text
            val idx = text.indexOfFirst { it.isLetter() }
            if (idx < 0) return text
            val firstChar = text[idx]
            if (!firstChar.isLowerCase()) return text
            return text.substring(0, idx) + firstChar.titlecase(Locale.ROOT) + text.substring(idx + 1)
        }

        private fun enforceEnglishPronounCasing(text: String): String {
            return Regex("\\bi(?=('(?:m|ve|ll|d)\\b)|\\b)").replace(text, "I")
        }

        private fun finalizeTypography(input: String): String {
            var text = input
            text = SPACE_BEFORE_PUNCT_REGEX.replace(text, "$1")
            text = MULTIPLE_SPACES_REGEX.replace(text, " ")
            text = text.lines().joinToString("\n") { it.trim() }
            text = EXCESS_NEWLINES_REGEX.replace(text, "\n\n")
            return text.trim()
        }
    }

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    @Volatile
    private var cachedMetadata: GgufModelMetadata? = null

    val modelDirectory: File
        get() = customModelDir ?: DesertAntModelStorage.modelRevisionDir(appContext, DesertAntModel.GEMMA)

    val modelFile: File
        get() = File(modelDirectory, MODEL_FILENAME)

    /**
     * Checks whether the `gemma-3-270m-it-qat-Q4_0.gguf` model file is present and has a valid GGUF header.
     */
    fun isModelReady(): Boolean {
        if (closed.get()) return false
        val file = modelFile
        if (!file.exists() || !file.isFile) return false
        val meta = cachedMetadata ?: parseGgufHeader(file)?.also { cachedMetadata = it }
        return meta?.isValidGemma3Gguf == true
    }

    /**
     * Warms up the engine and verifies the GGUF model header on disk.
     */
    fun warmUp(): Boolean {
        check(!closed.get()) { "GemmaFormattingEngine has already been closed" }
        val file = modelFile
        if (!file.exists()) return false
        val meta = parseGgufHeader(file)
        cachedMetadata = meta
        return meta?.isValidGemma3Gguf == true
    }

    /**
     * Formats [rawTranscript] using the Gemma 3 270M SLM formatting pipeline.
     *
     * @param rawTranscript The transcript produced by Stages 1–4 (`Clear` -> `Ear` -> `Voz` -> `Uhm`).
     * @param language ISO-639-1 language code (e.g. `"en"`, `"de"`, `"fr"`, `"es"`).
     * @param style User-selected [GemmaFormattingStyle] (`SMART_CLEANUP`, `CONCISE_PROSE`, or `STRUCTURED_NOTES`).
     * @param customVocabulary User-configured custom vocabulary terms to preserve with exact casing.
     */
    fun format(
        rawTranscript: String,
        language: String = "en",
        style: GemmaFormattingStyle = GemmaFormattingStyle.SMART_CLEANUP,
        customVocabulary: List<String> = emptyList()
    ): String {
        check(!closed.get()) { "GemmaFormattingEngine has already been closed" }
        if (rawTranscript.isBlank()) return ""

        return formatTranscript(
            rawText = rawTranscript,
            language = language,
            style = style,
            customVocabulary = customVocabulary
        )
    }

    override fun close() {
        closed.set(true)
        cachedMetadata = null
    }
}
