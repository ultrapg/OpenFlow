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
import com.voicebubble.openflow.settings.GemmaFormattingConfig
import com.voicebubble.openflow.settings.GemmaFormattingStyle
import com.voicebubble.openflow.settings.GemmaParagraphDensity
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
 * Focuses strictly on deterministic, non-destructive **text formatting** (never rewrites user vocabulary or tone):
 * 1. **Cross-Chunk Continuity Stitching ([stitchChunkTranscripts])**: Heals artificial mid-sentence periods
 *    and capitalization across 20s audio chunk boundaries in long dictations.
 * 2. **Sliding Discourse-Window Processing ([splitIntoDiscourseWindows])**: Processes arbitrarily long
 *    dictations in bounded `<= 1,600`-character windows aligned at sentence/clause boundaries.
 * 3. **Pure Formatting Pipeline ([GemmaFormattingConfig])**:
 *    - **4 Layout Styles ([GemmaFormattingStyle])**: `STANDARD`, `BULLET_LIST`, `NUMBERED_LIST`, and `COMPACT_CHAT`.
 *    - **Sentence Capitalization & Punctuation** (`smartPunctuationEnabled`): Sentence-start casing, English `"I"`
 *      pronoun casing, question mark (`?`) detection, transition sentence splitting, and decimal/abbreviation-safe punctuation.
 *    - **Spoken Punctuation & Layout Commands** (`spokenCommandsEnabled`): `"comma"`, `"period"` / `"full stop"`,
 *      `"question mark"`, `"exclamation mark"`, `"colon"`, `"semicolon"`, `"new line"`, `"new paragraph"`, `"bullet point"`,
 *      `"open/close quote"`, and `"open/close parenthesis"`.
 *    - **Number, Time & Currency Formatting (ITN)** (`smartNumbersEnabled`): Thousands, hundreds, compound numbers,
 *      decimals, clock times (`"three thirty pm"` -> `"3:30 PM"`), percentages (`"25%"`), and currencies (`"$50"`, `"€50"`).
 *    - **Accidental Duplicate Word Cleanup** (`stutterRemovalEnabled`): Collapses immediate duplicate function words
 *      (`"the the"` -> `"the"`, `"I I"` -> `"I"`).
 *    - **Long-Dictation Auto-Paragraphing** (`autoParagraphEnabled` + [GemmaParagraphDensity]).
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

        /**
         * Maximum characters per discourse window when formatting long multi-minute dictations.
         * Keeps every formatting pass strictly bounded regardless of dictation length.
         */
        const val MAX_DISCOURSE_WINDOW_CHARS = 1_600
        const val MIN_DISCOURSE_WINDOW_CHARS = 900

        private val MULTIPLE_SPACES_REGEX = Regex("[ \\t]{2,}")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("[ \\t]+([,.;:?!])")
        private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")

        // Dangling words at the end of a 20s audio chunk where a trailing period from Voz is artificial
        private val DANGLING_CHUNK_END_WORDS = setOf(
            "the", "a", "an", "to", "in", "on", "at", "for", "with", "of", "from", "by", "about", "into",
            "and", "or", "but", "nor", "so", "yet", "because", "although", "since", "unless", "while",
            "that", "which", "who", "whom", "whose", "whether", "if", "when", "where",
            "is", "are", "was", "were", "have", "has", "had", "will", "would", "can", "could", "should",
            // German
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
            "und", "oder", "aber", "sondern", "denn", "weil", "obwohl", "dass", "ob", "wenn", "als",
            "in", "im", "an", "am", "auf", "für", "mit", "zu", "zum", "zur", "von", "vom", "bei", "nach"
        )

        // Continuation starters at the beginning of the next 20s audio chunk that indicate mid-sentence continuity
        private val CONTINUATION_CHUNK_START_PREFIXES = listOf(
            "and ", "or ", "but ", "because ", "which ", "that ", "who ", "while ", "whereas ", "although ",
            "new line", "new paragraph", "bullet point", "question mark", "exclamation mark", "comma", "colon", "semicolon", "period", "full stop",
            "und ", "oder ", "aber ", "sondern ", "weil ", "obwohl ", "dass ", "während ", "wohingegen ",
            "neue zeile", "neuer absatz", "stichpunkt", "fragezeichen", "ausrufezeichen", "komma", "doppelpunkt", "punkt"
        )

        // Spoken layout commands (handles optional trailing/leading punctuation inserted by Voz around pauses)
        private val SPOKEN_LAYOUT_COMMANDS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)(?:[ \\t]*\\b(?:new\\s+paragraph|next\\s+paragraph|neuer\\s+absatz|nächster\\s+absatz|nouveau\\s+paragraphe|nuevo\\s+p[aá]rrafo)\\b[.,;!?]*[ \\t]*)+") to "\n\n",
            Regex("(?i)(?:[ \\t]*\\b(?:new\\s+line|next\\s+line|line\\s+break|neue\\s+zeile|nächste\\s+zeile|zeilenumbruch|nouvelle\\s+ligne|nueva\\s+l[ií]nea)\\b[.,;!?]*[ \\t]*)+") to "\n",
            Regex("(?i)(?:\\n[ \\t]*)?[.,;!?]*[ \\t]*\\b(?:bullet\\s+point|next\\s+bullet|new\\s+bullet|stichpunkt|neuer\\s+stichpunkt|aufzählungspunkt)\\b[.,;:!?]*[ \\t]*") to "\n• "
        )

        // Spoken punctuation commands (strips stray period/comma that Voz may have placed right before the command)
        private val SPOKEN_PUNCTUATION_COMMANDS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:question\\s+mark|fragezeichen|point\\s+d'interrogation|signo\\s+de\\s+interrogaci[oó]n)\\b[.,;!?]*") to "?",
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:exclamation\\s+(?:mark|point)|ausrufezeichen|point\\s+d'exclamation|signo\\s+de\\s+exclamaci[oó]n)\\b[.,;!?]*") to "!",
            // "period" / "full stop" / "punkt" guarded against noun-phrase usage ("a short period", "period of", "ein guter Punkt")
            Regex("(?i)[.,;!?]*[ \\t]+(?<!\\b(?:a|an|the|this|that|short|long|brief|time|grace|waiting|trial|billing|notice|retention|transition|historical|initial|extended|fixed|certain|entire|whole|same|each|every|per|during|over|for|ein|einen|einem|der|den|dem|dieser|diesen|diesem|nächster|nächsten|wichtiger|wichtigen|guter|guten|erster|ersten|zweiter|zweiten|letzter|letzten|am|zum|im)\\s)\\b(?:full\\s+stop|period|punkt)\\b(?!\\s+(?:of|in|for|between|where|when)\\b)[.,;!?]*(?=[ \\t\\n]|$)") to ".",
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:comma|komma)\\b[.,;!?]*") to ",",
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:semicolon|semikolon|strichpunkt)\\b[.,;:!?]*") to ";",
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:colon|doppelpunkt)\\b[.,;:!?]*") to ":",
            Regex("(?i)[.,;!?]*[ \\t]+\\b(?:em\\s+dash|gedankenstrich)\\b[.,;!?]*[ \\t]*") to " — ",
            Regex("(?i)\\b(?:open\\s+quote|begin\\s+quote|zitat\\s+anfang)\\b[.,;!?]*[ \\t]*") to "\"",
            Regex("(?i)[ \\t]*\\b(?:close\\s+quote|end\\s+quote|unquote|zitat\\s+ende)\\b[.,;!?]*") to "\"",
            Regex("(?i)\\b(?:open\\s+parenthesis|open\\s+bracket|klammer\\s+auf)\\b[.,;!?]*[ \\t]*") to "(",
            Regex("(?i)[ \\t]*\\b(?:close\\s+parenthesis|close\\s+bracket|klammer\\s+zu)\\b[.,;!?]*") to ")"
        )

        // Function words where immediate repetition is an involuntary speech stutter ("the the", "I I")
        private val STUTTER_WORDS = setOf(
            "i", "we", "you", "they", "he", "she", "it",
            "the", "a", "an", "to", "in", "on", "at", "for", "of", "with", "from", "by", "about",
            "and", "but", "or", "so", "if", "when", "while", "because", "that", "this", "these", "those",
            "is", "are", "was", "were", "be", "been", "am", "have", "has", "had",
            "can", "could", "will", "would", "shall", "should", "may", "might", "must", "do", "does", "did",
            "my", "your", "our", "their", "his", "her", "its", "not",
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

        private val SINGLE_WORD_STUTTER_REGEX = Regex("(?i)\\b([\\p{L}]+)[ \\t]+\\1\\b")
        private val FUNCTION_BIGRAM_STUTTER_REGEX = Regex("(?i)\\b([\\p{L}]+)[ \\t]+([\\p{L}]+)[ \\t]+\\1[ \\t]+\\2\\b")

        // Spoken word-number lookup for ITN (thousands, hundreds, tens, ones)
        private val SPOKEN_ONES = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
            "null" to 0, "eins" to 1, "ein" to 1, "eine" to 1, "zwei" to 2, "drei" to 3, "vier" to 4,
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

        // Unambiguous discourse sentence-splitting transitions in unpunctuated run-on STT output
        // Requires at least 3 preceding words and guards "however" against adverbial use ("however much", "however you")
        private val DISCOURSE_SENTENCE_BREAK_REGEX = Regex(
            "(?i)(?<=\\b[\\p{L}0-9%$€£]+[ \\t]+[\\p{L}0-9%$€£]+[ \\t]+[\\p{L}0-9%$€£]{2,20})[ \\t]+(?=(?:furthermore|moreover|meanwhile|therefore|consequently|nevertheless|additionally|on\\s+the\\s+other\\s+hand|moving\\s+on\\s+to|on\\s+another\\s+note|in\\s+conclusion|to\\s+summarize|however(?!\\s+(?:much|many|long|hard|fast|often|bad|good|well|little|you|we|they|i|he|she|it)\\b)|allerdings|außerdem|darüber\\s+hinaus|infolgedessen|nichtsdestotrotz|zudem|kommen\\s+wir\\s+zu|ein\\s+weiterer\\s+punkt|zusammenfassend|abschließend)\\b)"
        )

        // Topic-shift sentence prefixes that trigger an automatic paragraph break in long dictations
        private val TOPIC_SHIFT_SENTENCE_PREFIXES = listOf(
            "moving on", "on another note", "regarding ", "turning to ", "next topic",
            "furthermore,", "moreover,", "in addition,", "meanwhile,", "on the other hand",
            "finally,", "in conclusion", "to summarize", "overall,",
            "kommen wir zu", "ein weiterer punkt", "bezüglich ", "was das betrifft",
            "darüber hinaus", "außerdem,", "abschließend", "zusammenfassend"
        )

        // Sequential enumeration markers for BULLET_LIST and NUMBERED_LIST styles
        private val LIST_ENUM_MARKER_REGEX = Regex(
            "(?i)(?:^|[ \\t]+|[,.;][ \\t]+)(?:(?:and|und)[ \\t]+)?\\b(?:first\\s+of\\s+all|firstly|first\\s+item|first|secondly|second\\s+item|second|thirdly|third\\s+item|third|fourthly|fourth|fifth|finally|lastly|erstens|zweitens|drittens|viertens|fünftens|schließlich|abschließend)\\b(?:[ \\t]*,[ \\t]*|[ \\t]+is\\b[ \\t]*|[ \\t]*:[ \\t]*|[ \\t]+)"
        )

        // Decimal-safe and abbreviation-safe sentence splitter:
        // Splits after '.', '!', '?' ONLY when not preceded by a common abbreviation and not between digits (e.g., 3.5, $4.50)
        private val SENTENCE_SPLIT_REGEX = Regex(
            "(?<!\\b(?:Mr|Mrs|Ms|Dr|Prof|St|Jr|Sr|vs|etc|ca|bzw|inkl|zzgl|Nr|Abs|z\\.B|u\\.a|d\\.h|e\\.g|i\\.e))(?<=[!?]|(?<!\\d)\\.(?!\\d))[ \\t]+"
        )

        private val ENGLISH_DIRECT_QUESTION_PREFIXES = setOf(
            "can you", "could you", "would you", "will you", "do you", "did you", "have you", "are you",
            "is there", "are there", "is it", "is this", "is that", "are we", "should we", "shall we", "can we", "could we",
            "do we", "did we", "do they", "did they", "have they", "has he", "has she", "has it",
            "isn't it", "aren't you", "don't you", "didn't you", "won't you", "wouldn't you", "couldn't we", "shouldn't we",
            "what is", "what are", "what was", "what were", "what do", "what does", "what did", "what can", "what could", "what will", "what would", "what should", "what time", "what happened",
            "when is", "when are", "when was", "when were", "when do", "when does", "when did", "when can", "when will", "when would", "when should",
            "where is", "where are", "where was", "where were", "where do", "where does", "where did", "where can", "where will", "where should",
            "why is", "why are", "why was", "why were", "why do", "why does", "why did", "why can't", "why don't", "why didn't", "why isn't", "why would", "why should",
            "how is", "how are", "how was", "how were", "how do", "how does", "how did", "how can", "how could", "how will", "how would", "how should", "how much", "how many", "how long",
            "who is", "who are", "who was", "who were", "who do", "who does", "who did", "who can", "who will", "who would"
        )

        private val GERMAN_DIRECT_QUESTION_PREFIXES = setOf(
            "kannst du", "könntest du", "würdest du", "willst du", "hast du", "bist du", "machst du", "weißt du",
            "können sie", "könnten sie", "würden sie", "haben sie", "sind sie", "wissen sie",
            "gibt es", "ist das", "ist es", "sind wir", "sollen wir", "können wir", "wollen wir", "haben wir",
            "was ist", "was sind", "was war", "was machen", "was soll", "was kann",
            "wann ist", "wann sind", "wann war", "wann fängt", "wann beginnt", "wann können", "wann sollen",
            "wo ist", "wo sind", "wo war", "wo waren", "wo kann", "wo können", "wo soll",
            "warum ist", "warum sind", "warum hat", "warum haben", "wieso ist", "weshalb ist",
            "wie ist", "wie sind", "wie war", "wie geht", "wie viel", "wie viele", "wie lange", "wie kann", "wie können", "wie soll",
            "wer ist", "wer sind", "wer war", "wer hat", "wer kann"
        )

        private val QUESTION_TAG_SUFFIX_REGEX = Regex(
            "(?i),[ \\t]*(?:right|isn't\\s+it|aren't\\s+we|don't\\s+you|didn't\\s+you|won't\\s+you|oder|nicht\\s+wahr|stimmt's)$"
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
         * Stitches transcripts produced across multiple 20s audio chunks in long dictations.
         *
         * When `Voz` transcribes each 20s chunk independently, it frequently appends a period `.`
         * at the end of Chunk `i` and capitalizes the start of Chunk `i+1` even when the speaker
         * paused briefly mid-sentence.
         */
        fun stitchChunkTranscripts(chunks: List<String>): String {
            val nonBlank = chunks.map { it.trim() }.filter { it.isNotEmpty() }
            if (nonBlank.isEmpty()) return ""
            if (nonBlank.size == 1) return nonBlank[0]

            val builder = StringBuilder(nonBlank.sumOf { it.length + 2 })
            builder.append(nonBlank[0])

            for (i in 1 until nonBlank.size) {
                val nextChunk = nonBlank[i]
                val currentStr = builder.toString().trimEnd()
                val endsWithPeriod = currentStr.endsWith(".")
                val lastWordBeforePunct = currentStr
                    .trimEnd('.', ',', ';', ':', '!', '?')
                    .substringAfterLast(' ')
                    .lowercase(Locale.ROOT)
                val nextLower = nextChunk.lowercase(Locale.ROOT)

                val isDanglingEnd = lastWordBeforePunct in DANGLING_CHUNK_END_WORDS
                val isContinuationStart = CONTINUATION_CHUNK_START_PREFIXES.any { prefix ->
                    nextLower == prefix.trim() || nextLower.startsWith(prefix)
                }

                if (endsWithPeriod && (isDanglingEnd || isContinuationStart)) {
                    builder.setLength(currentStr.length - 1)
                    builder.append(' ')
                    val firstSpace = nextChunk.indexOf(' ')
                    val firstWord = if (firstSpace > 0) nextChunk.substring(0, firstSpace) else nextChunk
                    val shouldLowercase = firstWord.length > 1 &&
                        firstWord[0].isUpperCase() &&
                        firstWord.drop(1).all { it.isLowerCase() } &&
                        !firstWord.equals("I", ignoreCase = false) &&
                        !firstWord.startsWith("I'")
                    if (shouldLowercase && isContinuationStart) {
                        builder.append(firstWord[0].lowercaseChar()).append(nextChunk.substring(1))
                    } else {
                        builder.append(nextChunk)
                    }
                } else {
                    builder.append(' ').append(nextChunk)
                }
            }
            return builder.toString()
        }

        /**
         * Splits long transcripts into bounded discourse windows (`<= MAX_DISCOURSE_WINDOW_CHARS`)
         * at sentence or clause boundaries so formatting scales linearly $O(N)$ to arbitrarily
         * long dictations.
         */
        fun splitIntoDiscourseWindows(
            text: String,
            maxWindowChars: Int = MAX_DISCOURSE_WINDOW_CHARS,
            minWindowChars: Int = MIN_DISCOURSE_WINDOW_CHARS
        ): List<String> {
            val trimmed = text.trim()
            if (trimmed.length <= maxWindowChars) return listOf(trimmed)

            val windows = ArrayList<String>((trimmed.length / maxWindowChars) + 2)
            var offset = 0
            val length = trimmed.length

            while (offset < length) {
                val remaining = length - offset
                if (remaining <= maxWindowChars) {
                    val tail = trimmed.substring(offset).trim()
                    if (tail.isNotEmpty()) windows.add(tail)
                    break
                }

                val searchStart = offset + minWindowChars.coerceAtMost(maxWindowChars - 1)
                val searchEnd = (offset + maxWindowChars).coerceAtMost(length)

                // 1. Prefer splitting right after terminal punctuation ('.', '!', '?', '\n')
                var splitAt = -1
                for (idx in (searchEnd - 1) downTo searchStart) {
                    val ch = trimmed[idx]
                    val isDecimalDot = ch == '.' && idx > 0 && idx + 1 < length && trimmed[idx - 1].isDigit() && trimmed[idx + 1].isDigit()
                    if (ch == '\n' || (!isDecimalDot && (ch == '.' || ch == '!' || ch == '?') && (idx + 1 == length || trimmed[idx + 1].isWhitespace()))) {
                        splitAt = idx + 1
                        break
                    }
                }

                // 2. Next prefer splitting at a comma/semicolon
                if (splitAt == -1) {
                    for (idx in (searchEnd - 1) downTo searchStart) {
                        val ch = trimmed[idx]
                        if ((ch == ',' || ch == ';') && idx + 1 < length && trimmed[idx + 1].isWhitespace()) {
                            splitAt = idx + 1
                            break
                        }
                    }
                }

                // 3. Fallback: split at the last whitespace before searchEnd
                if (splitAt == -1) {
                    for (idx in (searchEnd - 1) downTo searchStart) {
                        if (trimmed[idx].isWhitespace()) {
                            splitAt = idx + 1
                            break
                        }
                    }
                }

                if (splitAt <= offset) {
                    splitAt = searchEnd
                }

                val slice = trimmed.substring(offset, splitAt).trim()
                if (slice.isNotEmpty()) {
                    windows.add(slice)
                }
                offset = splitAt
            }

            return windows
        }

        /**
         * Backwards-compatible entrypoint using [GemmaFormattingStyle] with default [GemmaFormattingConfig] flags.
         */
        fun formatTranscript(
            rawText: String,
            language: String = "en",
            style: GemmaFormattingStyle = GemmaFormattingStyle.STANDARD,
            customVocabulary: List<String> = emptyList()
        ): String {
            return formatTranscript(
                rawText = rawText,
                language = language,
                config = GemmaFormattingConfig(style = style),
                customVocabulary = customVocabulary
            )
        }

        /**
         * Full configurable entrypoint for formatting short or long transcripts using [GemmaFormattingConfig].
         */
        fun formatTranscript(
            rawText: String,
            language: String = "en",
            config: GemmaFormattingConfig,
            customVocabulary: List<String> = emptyList()
        ): String {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) return ""

            val lang = language.lowercase(Locale.ROOT).take(2).ifEmpty { "en" }

            // Process long transcripts in bounded discourse windows to guarantee O(N) performance
            val windows = splitIntoDiscourseWindows(trimmedInput)
            val formattedWindows = windows.map { windowText ->
                formatSingleWindow(
                    windowText = windowText,
                    lang = lang,
                    config = config,
                    customVocabulary = customVocabulary
                )
            }

            val joinSeparator = when (config.style) {
                GemmaFormattingStyle.BULLET_LIST, GemmaFormattingStyle.NUMBERED_LIST -> "\n"
                else -> " "
            }
            var combined = formattedWindows.filter { it.isNotBlank() }.joinToString(joinSeparator)

            // Apply document-level layout style:
            when (config.style) {
                GemmaFormattingStyle.BULLET_LIST -> {
                    combined = formatAsList(combined, numbered = false)
                }
                GemmaFormattingStyle.NUMBERED_LIST -> {
                    combined = formatAsList(combined, numbered = true)
                }
                GemmaFormattingStyle.STANDARD -> {
                    if (config.autoParagraphEnabled) {
                        combined = applyAutoParagraphing(combined, config.paragraphDensity)
                    }
                }
                GemmaFormattingStyle.COMPACT_CHAT -> {
                    combined = applyCompactChatLayout(combined)
                }
            }

            combined = finalizeTypography(combined)
            return combined.ifBlank { trimmedInput }
        }

        private fun formatSingleWindow(
            windowText: String,
            lang: String,
            config: GemmaFormattingConfig,
            customVocabulary: List<String>
        ): String {
            var text = windowText

            // Stage 1: Spoken punctuation & layout commands
            if (config.spokenCommandsEnabled) {
                for ((pattern, replacement) in SPOKEN_PUNCTUATION_COMMANDS) {
                    text = pattern.replace(text, replacement)
                }
                for ((pattern, replacement) in SPOKEN_LAYOUT_COMMANDS) {
                    text = pattern.replace(text, replacement)
                }
            }

            // Stage 2: Number, Time & Currency Formatting (ITN)
            if (config.smartNumbersEnabled) {
                text = normalizeNumbersTimesAndUnits(text, lang)
            }

            // Stage 3: Accidental duplicate function-word cleanup ("the the" -> "the")
            if (config.stutterRemovalEnabled) {
                text = deduplicateStutters(text)
            }

            // Stage 4: Sentence segmentation, capitalization & punctuation per line
            text = segmentAndPunctuateLines(
                input = text,
                lang = lang,
                style = config.style,
                smartPunctuationEnabled = config.smartPunctuationEnabled
            )

            // Stage 5: Enforce English "I" pronoun casing and custom vocabulary casing
            if (lang == "en") {
                text = enforceEnglishPronounCasing(text)
            }
            if (customVocabulary.isNotEmpty()) {
                text = applyCustomVocabulary(text, customVocabulary)
            }

            return finalizeTypography(text)
        }

        private fun deduplicateStutters(input: String): String {
            var text = input
            var prev: String
            var rounds = 0
            do {
                prev = text
                // Collapse repeated 2-word function phrases ("in the in the", "I think I think", "on the on the")
                text = FUNCTION_BIGRAM_STUTTER_REGEX.replace(text) { match ->
                    val w1 = match.groupValues[1]
                    val w2 = match.groupValues[2]
                    if (w1.lowercase(Locale.ROOT) in STUTTER_WORDS || w2.lowercase(Locale.ROOT) in STUTTER_WORDS) {
                        "$w1 $w2"
                    } else {
                        match.value
                    }
                }
                // Collapse immediate duplicate function words ("the the", "I I", "to to", "and and")
                text = SINGLE_WORD_STUTTER_REGEX.replace(text) { match ->
                    val word = match.groupValues[1]
                    if (word.lowercase(Locale.ROOT) in STUTTER_WORDS) {
                        word
                    } else {
                        match.value
                    }
                }
                rounds++
            } while (text != prev && rounds < 4)
            return text
        }

        private fun normalizeNumbersTimesAndUnits(input: String, lang: String): String {
            var text = input

            // 1. Spoken thousands ("two thousand twenty six" -> "2026", "one thousand five hundred" -> "1500")
            val thousandsRegex = Regex(
                "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)[ \\t]+(?:thousand|tausend)(?:[ \\t]+(?:and|und))?(?:[ \\t]+(one|two|three|four|five|six|seven|eight|nine|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)[ \\t]+(?:hundred|hundert))?(?:[ \\t]+(?:and|und))?(?:[ \\t]+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig)(?:[ \\t-]+(one|two|three|four|five|six|seven|eight|nine|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun))?|[ \\t]+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn))?\\b"
            )
            text = thousandsRegex.replace(text) { match ->
                val th = SPOKEN_ONES[match.groupValues[1].lowercase(Locale.ROOT)] ?: return@replace match.value
                val h = if (match.groupValues[2].isNotEmpty()) SPOKEN_ONES[match.groupValues[2].lowercase(Locale.ROOT)] ?: 0 else 0
                val tens = if (match.groupValues[3].isNotEmpty()) SPOKEN_TENS[match.groupValues[3].lowercase(Locale.ROOT)] ?: 0 else 0
                val ones = when {
                    match.groupValues[4].isNotEmpty() -> SPOKEN_ONES[match.groupValues[4].lowercase(Locale.ROOT)] ?: 0
                    match.groupValues[5].isNotEmpty() -> SPOKEN_ONES[match.groupValues[5].lowercase(Locale.ROOT)] ?: 0
                    else -> 0
                }
                (th * 1000 + h * 100 + tens + ones).toString()
            }

            // 2. Spoken hundreds ("three hundred fifty" / "three hundred and fifty" -> "350")
            val hundredsRegex = Regex(
                "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)[ \\t]+(?:hundred|hundert)(?:[ \\t]+(?:and|und))?(?:[ \\t]+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig)(?:[ \\t-]+(one|two|three|four|five|six|seven|eight|nine|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun))?|[ \\t]+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn))?\\b"
            )
            text = hundredsRegex.replace(text) { match ->
                val h = SPOKEN_ONES[match.groupValues[1].lowercase(Locale.ROOT)] ?: return@replace match.value
                val tensWord = match.groupValues[2].lowercase(Locale.ROOT)
                val onesAfterTensWord = match.groupValues[3].lowercase(Locale.ROOT)
                val directOnesWord = match.groupValues[4].lowercase(Locale.ROOT)
                val tens = if (tensWord.isNotEmpty()) SPOKEN_TENS[tensWord] ?: 0 else 0
                val ones = when {
                    onesAfterTensWord.isNotEmpty() -> SPOKEN_ONES[onesAfterTensWord] ?: 0
                    directOnesWord.isNotEmpty() -> SPOKEN_ONES[directOnesWord] ?: 0
                    else -> 0
                }
                (h * 100 + tens + ones).toString()
            }

            // 3. Spoken compound numbers 21..99 anywhere in text ("twenty five" -> "25", "forty-two" -> "42")
            val compoundTensOnesRegex = Regex(
                "(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[ \\t-]+(one|two|three|four|five|six|seven|eight|nine)\\b"
            )
            text = compoundTensOnesRegex.replace(text) { match ->
                val tens = SPOKEN_TENS[match.groupValues[1].lowercase(Locale.ROOT)] ?: return@replace match.value
                val ones = SPOKEN_ONES[match.groupValues[2].lowercase(Locale.ROOT)] ?: return@replace match.value
                (tens + ones).toString()
            }

            // 4. Unambiguous spoken numbers >= 10 ("ten".."nineteen", "twenty".."ninety") anywhere in text
            val teensAndTensRegex = Regex(
                "(?i)\\b(ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig)\\b"
            )
            text = teensAndTensRegex.replace(text) { match ->
                val word = match.groupValues[1].lowercase(Locale.ROOT)
                val value = SPOKEN_ONES[word] ?: SPOKEN_TENS[word] ?: return@replace match.value
                value.toString()
            }

            // 5. Spoken single digits 0..9 when followed by a unit, currency, percent, time marker, or another number ("three thirty pm" -> "3 30 pm", "five percent" -> "5 percent")
            val singleDigitBeforeUnitOrTimeRegex = Regex(
                "(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|null|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun)(?=[ \\t]+(?:\\d{1,2}\\b|a\\.?m\\.?\\b|p\\.?m\\.?\\b|uhr\\b|o'clock\\b|percent\\b|per\\s+cent\\b|prozent\\b|dollars?\\b|euros?\\b|pounds?\\b|cents?\\b|seconds?\\b|minutes?\\b|hours?\\b|days?\\b|weeks?\\b|months?\\b|years?\\b|sekunden?\\b|minuten?\\b|stunden?\\b|tagen?\\b|wochen?\\b|monaten?\\b|jahren?\\b|gb\\b|mb\\b|kb\\b|tb\\b|km\\b|kg\\b|cm\\b|mm\\b|ms\\b|point\\b|komma\\b))"
            )
            text = singleDigitBeforeUnitOrTimeRegex.replace(text) { match ->
                val word = match.groupValues[1].lowercase(Locale.ROOT)
                (SPOKEN_ONES[word] ?: return@replace match.value).toString()
            }

            // 6. Spoken single digits 0..9 after a label word ("version two" -> "version 2", "page five" -> "page 5", "step three" -> "step 3", "at three" -> "at 3")
            val singleDigitAfterLabelRegex = Regex(
                "(?i)(?<=\\b(?:version|step|item|page|chapter|section|part|option|phase|stage|level|tier|room|floor|channel|port|line|number|nr|nummer|schritt|seite|kapitel|teil|stufe|raum|zeile)[ \\t])(zero|one|two|three|four|five|six|seven|eight|nine|null|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun)\\b"
            )
            text = singleDigitAfterLabelRegex.replace(text) { match ->
                val word = match.groupValues[1].lowercase(Locale.ROOT)
                (SPOKEN_ONES[word] ?: return@replace match.value).toString()
            }

            // 7. Spoken decimals ("3 point five" / "3.5" -> "3.5", "3 komma fünf" -> "3,5")
            val spokenDecimalRegex = Regex(
                "(?i)\\b(\\d+)[ \\t]+(point|komma)[ \\t]+(\\d+|zero|one|two|three|four|five|six|seven|eight|nine|null|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun)\\b"
            )
            text = spokenDecimalRegex.replace(text) { match ->
                val left = match.groupValues[1]
                val sep = if (match.groupValues[2].lowercase(Locale.ROOT) == "komma") "," else "."
                val rightWord = match.groupValues[3].lowercase(Locale.ROOT)
                val right = SPOKEN_ONES[rightWord]?.toString() ?: rightWord
                "$left$sep$right"
            }

            // 8. Format numeric percentages: "25 percent" / "25 prozent" -> "25%"
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)[ \\t]*(?:percent|per\\s+cent|prozent)\\b").replace(text, "$1%")

            // 9. Format numeric currencies:
            //    "20 dollars and 50 cents" -> "$20.50"
            //    "50 dollars" -> "$50", "50 euros" -> "€50" (or "50 €" in German), "50 pounds" -> "£50"
            val dollarsAndCentsRegex = Regex("(?i)\\b(\\d+)[ \\t]+dollars?[ \\t]+(?:and[ \\t]+)?(\\d{1,2})[ \\t]+cents?\\b")
            text = dollarsAndCentsRegex.replace(text) { match ->
                val dollars = match.groupValues[1]
                val cents = match.groupValues[2].padStart(2, '0')
                "$$dollars.$cents"
            }
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)[ \\t]+dollars?\\b").replace(text, "\\$$1")
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)[ \\t]+pounds?\\b").replace(text, "£$1")
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)[ \\t]+euros?\\b").replace(text) { match ->
                val amount = match.groupValues[1]
                if (lang == "de") "$amount €" else "€$amount"
            }

            // 10. Format English clock times: "3 pm" -> "3:00 PM", "3 30 pm" -> "3:30 PM", "3:30 pm" -> "3:30 PM"
            val englishTimeWithMinRegex = Regex("(?i)\\b(1[0-2]|0?[1-9])[: \\t]([0-5]\\d)[ \\t]*(a\\.?m\\.?|p\\.?m\\.?)\\b")
            text = englishTimeWithMinRegex.replace(text) { match ->
                val hour = match.groupValues[1].toInt()
                val min = match.groupValues[2]
                val amPm = if (match.groupValues[3].lowercase(Locale.ROOT).startsWith("a")) "AM" else "PM"
                "$hour:$min $amPm"
            }
            val englishTimeHourOnlyRegex = Regex("(?i)\\b(1[0-2]|0?[1-9])[ \\t]+(a\\.?m\\.?|p\\.?m\\.?)\\b")
            text = englishTimeHourOnlyRegex.replace(text) { match ->
                val hour = match.groupValues[1].toInt()
                val amPm = if (match.groupValues[2].lowercase(Locale.ROOT).startsWith("a")) "AM" else "PM"
                "$hour:00 $amPm"
            }

            // 11. Format German clock times: "15 uhr 30" -> "15:30 Uhr", "15 uhr" -> "15:00 Uhr"
            val germanTimeWithMinRegex = Regex("(?i)\\b([01]?\\d|2[0-3])[ \\t]+uhr[ \\t]+([0-5]\\d)\\b")
            text = germanTimeWithMinRegex.replace(text) { match ->
                "${match.groupValues[1]}:${match.groupValues[2]} Uhr"
            }
            val germanTimeHourOnlyRegex = Regex("(?i)\\b([01]?\\d|2[0-3])[ \\t]+uhr\\b")
            text = germanTimeHourOnlyRegex.replace(text) { match ->
                "${match.groupValues[1]}:00 Uhr"
            }

            return text
        }

        private fun segmentAndPunctuateLines(
            input: String,
            lang: String,
            style: GemmaFormattingStyle,
            smartPunctuationEnabled: Boolean
        ): String {
            var working = input
            if ((style == GemmaFormattingStyle.BULLET_LIST || style == GemmaFormattingStyle.NUMBERED_LIST) &&
                hasSequentialEnumeration(working)
            ) {
                working = splitSequentialEnumerationIntoLines(working)
            }

            val lines = working.split("\n")
            val formattedLines = lines.map { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    ""
                } else {
                    val hasBullet = trimmed.startsWith("•")
                    val body = if (hasBullet) trimmed.removePrefix("•").trim() else trimmed
                    val formattedBody = formatSingleParagraphBody(body, lang, smartPunctuationEnabled)
                    if (hasBullet) "• $formattedBody" else formattedBody
                }
            }
            return formattedLines.joinToString("\n")
        }

        private fun hasSequentialEnumeration(text: String): Boolean {
            return LIST_ENUM_MARKER_REGEX.findAll(text).count() >= 2
        }

        private fun splitSequentialEnumerationIntoLines(text: String): String {
            val parts = LIST_ENUM_MARKER_REGEX.split(text)
                .map { it.trim().trimEnd(',', ';', '.') }
                .filter { it.isNotEmpty() }
            if (parts.size < 2) return text
            return parts.joinToString("\n")
        }

        private fun formatSingleParagraphBody(
            body: String,
            lang: String,
            smartPunctuationEnabled: Boolean
        ): String {
            if (body.isEmpty()) return ""

            var text = body
            if (smartPunctuationEnabled) {
                // Break run-on discourse transitions into distinct sentences
                text = DISCOURSE_SENTENCE_BREAK_REGEX.replace(text, ". ")
                // Insert natural comma after transition words and before independent contrast clauses
                text = insertNaturalClauseCommas(text, lang)
            }

            // Decimal-safe and abbreviation-safe sentence segmentation
            val sentences = SENTENCE_SPLIT_REGEX.split(text)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (sentences.isEmpty()) return capitalizeFirstLetter(body)

            return sentences.joinToString(" ") { rawSentence ->
                val lastChar = rawSentence.last()
                val hasTerminal = lastChar in charArrayOf('.', '!', '?', ':', ';')
                val core = if (hasTerminal) rawSentence.dropLast(1).trim() else rawSentence.trim()
                val existingMark = if (hasTerminal) lastChar else null
                val capitalized = capitalizeFirstLetter(core)
                val terminal = when {
                    existingMark != null && existingMark != '.' -> existingMark
                    smartPunctuationEnabled && isLikelyQuestion(core, lang) -> '?'
                    else -> existingMark ?: '.'
                }
                "$capitalized$terminal"
            }
        }

        /**
         * Formats the text as either a bulleted list (`• Item`) or numbered list (`1. Item`, `2. Item`),
         * splitting on line breaks and sentence boundaries so multi-sentence dictations always produce a clean list.
         */
        private fun formatAsList(input: String, numbered: Boolean): String {
            val rawLines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.isEmpty()) return ""

            val items = ArrayList<String>()
            for (line in rawLines) {
                val cleanLine = line
                    .removePrefix("•")
                    .replace(Regex("^\\d+\\.[ \\t]+"), "")
                    .trim()
                if (cleanLine.isEmpty()) continue

                // If the user didn't already split onto multiple lines, split multi-sentence lines into list items
                if (rawLines.size == 1) {
                    val sentences = SENTENCE_SPLIT_REGEX.split(cleanLine)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    items.addAll(sentences)
                } else {
                    items.add(cleanLine)
                }
            }

            if (items.isEmpty()) return input
            return items.mapIndexed { index, item ->
                val capitalized = capitalizeFirstLetter(item)
                val prefix = if (numbered) "${index + 1}. " else "• "
                "$prefix$capitalized"
            }.joinToString("\n")
        }

        private fun applyCompactChatLayout(input: String): String {
            // Collapse paragraph breaks into single spaces for compact chat messaging
            val singleBlock = input.replace(Regex("\\s*\\n+\\s*"), " ").trim()
            // If it is a single sentence ending with '.', omit the trailing period while preserving '?' and '!'
            val sentenceCount = SENTENCE_SPLIT_REGEX.split(singleBlock).count { it.isNotBlank() }
            if (sentenceCount == 1 && singleBlock.endsWith(".") && singleBlock.length <= 160) {
                return singleBlock.dropLast(1)
            }
            return singleBlock
        }

        /**
         * Segments long multi-sentence paragraphs into readable, structured paragraphs based on
         * topic-shift discourse markers and [density] (`COMPACT` = 3, `BALANCED` = 4, `LONG_FORM` = 6).
         */
        private fun applyAutoParagraphing(
            input: String,
            density: GemmaParagraphDensity
        ): String {
            val targetSentences = density.targetSentences
            val blocks = input.split("\n\n")

            val resultBlocks = ArrayList<String>(blocks.size * 2)
            for (block in blocks) {
                val trimmedBlock = block.trim()
                if (trimmedBlock.isEmpty() || trimmedBlock.contains("\n•") || trimmedBlock.startsWith("•")) {
                    resultBlocks.add(trimmedBlock)
                    continue
                }

                val sentences = SENTENCE_SPLIT_REGEX.split(trimmedBlock)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (sentences.size <= targetSentences) {
                    resultBlocks.add(trimmedBlock)
                    continue
                }

                val currentParagraph = ArrayList<String>(targetSentences + 1)
                for (sentence in sentences) {
                    val lower = sentence.lowercase(Locale.ROOT)
                    val isTopicShift = TOPIC_SHIFT_SENTENCE_PREFIXES.any { lower.startsWith(it) }
                    val shouldBreakBefore = (isTopicShift && currentParagraph.size >= 2) ||
                        currentParagraph.size >= targetSentences

                    if (shouldBreakBefore && currentParagraph.isNotEmpty()) {
                        resultBlocks.add(currentParagraph.joinToString(" "))
                        currentParagraph.clear()
                    }
                    currentParagraph.add(sentence)
                }
                if (currentParagraph.isNotEmpty()) {
                    resultBlocks.add(currentParagraph.joinToString(" "))
                }
            }

            return resultBlocks.filter { it.isNotEmpty() }.joinToString("\n\n")
        }

        private fun insertNaturalClauseCommas(text: String, lang: String): String {
            var result = text
            // Comma after sentence-initial discourse transitions ("However, ", "Furthermore, ", "Therefore, ")
            result = Regex(
                "(?i)(^|(?<=[.!?][ \\t]))(however(?!\\s+(?:much|many|long|hard|fast|often|bad|good|well|little|you|we|they|i|he|she|it)\\b)|furthermore|moreover|meanwhile|therefore|nevertheless|additionally|unfortunately|fortunately|in\\s+addition|in\\s+conclusion|allerdings|außerdem|trotzdem|deshalb|leider|glücklicherweise|zusammenfassend|abschließend)[ \\t]+(?=[\\p{L}])"
            ).replace(result, "$1$2, ")

            // Comma before independent contrast clauses ("..., but we...", "..., obwohl wir..."),
            // guarding against "nothing but", "not only ... but also", etc.
            if (lang == "de") {
                result = Regex(
                    "(?i)(?<=\\b[\\p{L}0-9]+[ \\t]+[\\p{L}0-9]+[ \\t]+(?!(?:nichts|alles|niemand|nur)\\b)[\\p{L}0-9]{2,20})[ \\t]+(aber|sondern|obwohl|wohingegen)[ \\t]+"
                ).replace(result, ", $1 ")
            } else {
                result = Regex(
                    "(?i)(?<=\\b[\\p{L}0-9]+[ \\t]+[\\p{L}0-9]+[ \\t]+(?!(?:nothing|everything|anything|anyone|anybody|nobody|none|all|only)\\b)[\\p{L}0-9]{2,20})[ \\t]+(but|although|whereas|even\\s+though)[ \\t]+(?=(?:i|we|you|they|he|she|it|the|this|that|there|my|our|your|their|his|her)\\b)"
                ).replace(result, ", $1 ")
            }
            return result
        }

        private fun isLikelyQuestion(clause: String, lang: String): Boolean {
            val normalized = clause.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return false
            if (QUESTION_TAG_SUFFIX_REGEX.containsMatchIn(normalized)) return true
            val prefixes = if (lang == "de") GERMAN_DIRECT_QUESTION_PREFIXES else ENGLISH_DIRECT_QUESTION_PREFIXES
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
     * Formats [rawTranscript] using the full [GemmaFormattingConfig].
     */
    fun format(
        rawTranscript: String,
        language: String = "en",
        config: GemmaFormattingConfig = GemmaFormattingConfig(),
        customVocabulary: List<String> = emptyList()
    ): String {
        check(!closed.get()) { "GemmaFormattingEngine has already been closed" }
        if (rawTranscript.isBlank()) return ""

        return formatTranscript(
            rawText = rawTranscript,
            language = language,
            config = config,
            customVocabulary = customVocabulary
        )
    }

    /**
     * Backwards-compatible overload accepting [GemmaFormattingStyle].
     */
    fun format(
        rawTranscript: String,
        language: String = "en",
        style: GemmaFormattingStyle,
        customVocabulary: List<String> = emptyList()
    ): String {
        return format(
            rawTranscript = rawTranscript,
            language = language,
            config = GemmaFormattingConfig(style = style),
            customVocabulary = customVocabulary
        )
    }

    override fun close() {
        closed.set(true)
        cachedMetadata = null
    }
}
