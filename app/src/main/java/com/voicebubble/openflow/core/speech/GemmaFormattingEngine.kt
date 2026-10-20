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
 * Designed for both short voice notes and long multi-minute dictations:
 * 1. **Cross-Chunk Continuity Stitching ([stitchChunkTranscripts])**: Heals artificial mid-sentence periods
 *    and capitalization across 20s audio chunk boundaries before formatting.
 * 2. **Sliding Discourse-Window Processing ([splitIntoDiscourseWindows])**: Processes arbitrarily long
 *    dictations in bounded `<= 1,600`-character windows aligned at natural clause/sentence boundaries,
 *    guaranteeing linear $O(N)$ execution time with zero regex backtracking.
 * 3. **Configurable SLM Pipeline ([GemmaFormattingConfig])**:
 *    - **6 Formatting Styles ([GemmaFormattingStyle])**: `SMART_CLEANUP`, `CONCISE_PROSE`, `STRUCTURED_NOTES`,
 *      `PROFESSIONAL_EMAIL`, `CASUAL_CHAT`, and `TECHNICAL_CODE`.
 *    - **Mid-Sentence Self-Correction & Multi-Word Phrase Repair** (`selfCorrectionEnabled`).
 *    - **Word & Phrase Stutter Deduplication** (`stutterRemovalEnabled`).
 *    - **Spoken Punctuation & Layout Commands** (`spokenCommandsEnabled`).
 *    - **Inverse Text Normalization (ITN)** for times, hundreds/tens/ones numbers, percentages, and currencies (`smartNumbersEnabled`).
 *    - **Discourse Segmentation & Question Detection** (`smartPunctuationEnabled`).
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
         * Keeps every regex pass strictly bounded and cache-friendly regardless of dictation length.
         */
        const val MAX_DISCOURSE_WINDOW_CHARS = 1_600
        const val MIN_DISCOURSE_WINDOW_CHARS = 900

        private val MULTIPLE_SPACES_REGEX = Regex("[ \\t]{2,}")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.;:?!])")
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
            "no wait", "wait no", "oh wait", "i mean", "or rather", "actually no", "scratch that", "never mind",
            "new line", "new paragraph", "bullet point", "question mark", "exclamation mark", "comma", "colon", "semicolon",
            "und ", "oder ", "aber ", "sondern ", "weil ", "obwohl ", "dass ", "während ", "wohingegen ",
            "nein warte", "warte nein", "ich meine", "streich das", "neue zeile", "neuer absatz", "stichpunkt", "fragezeichen"
        )

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

        // Bounded O(N) clause cancellation ("scratch that", "never mind that", "streich das"):
        // Cancels at most 1..10 words preceding the trigger since the last sentence/clause boundary,
        // preventing long unpunctuated paragraphs from being wiped out.
        private val CLAUSE_CANCEL_REGEX = Regex(
            "(?i)(?:(?<=[.!?\\n,;])\\s+|\\b(?:and|but|so|then|und|aber|dann)\\s+|^)(?:[\\p{L}0-9$€%':-]+\\s+){1,10}?\\b(?:scratch\\s+that|never\\s+mind\\s+that|delete\\s+that|streich\\s+das|vergiss\\s+das)\\b[,.]?\\s*"
        )

        private const val REPAIR_TRIGGER_PATTERN =
            "(?:no\\s+wait|wait\\s+no|oh\\s+wait|actually\\s+no|or\\s+rather|i\\s+mean|nein\\s+warte|warte\\s+nein|ach\\s+nein|ich\\s+meine|oder\\s+besser\\s+gesagt|oder\\s+vielmehr)"

        // 1) Prepositional / multi-word phrase repair:
        //    "to the marketing team no wait to the engineering team" -> "to the engineering team"
        //    "at 3 pm no wait at 4:30 pm" -> "at 4:30 pm"
        private val PREP_PHRASE_REPAIR_REGEX = Regex(
            "(?i)\\b(at|on|in|to|for|from|with|by|into|about|um|am|im|nach|zu|zum|zur|für|mit|von|bei)\\s+((?:the\\s+|a\\s+|an\\s+|next\\s+|this\\s+|der\\s+|die\\s+|das\\s+|dem\\s+|den\\s+|einem\\s+|einer\\s+)?[\\w:$€%.-]+(?:\\s+(?:am|pm|uhr|o'clock|morning|afternoon|evening|night|team|department|project|report|version|branch|server|meeting|call|days?|weeks?|months?|years?))?)\\s*,?\\s*\\b$REPAIR_TRIGGER_PATTERN\\b\\s*,?\\s*(?:\\1\\s+)?((?:the\\s+|a\\s+|an\\s+|next\\s+|this\\s+|der\\s+|die\\s+|das\\s+|dem\\s+|den\\s+|einem\\s+|einer\\s+)?[\\w:$€%.-]+(?:\\s+(?:am|pm|uhr|o'clock|morning|afternoon|evening|night|team|department|project|report|version|branch|server|meeting|call|days?|weeks?|months?|years?))?)"
        )

        // 2) Modifier + head-noun repair: "next Monday morning I mean next Tuesday afternoon"
        private val TWO_WORD_PHRASE_REPAIR_REGEX = Regex(
            "(?i)\\b([A-Za-zÄÖÜäöüß0-9$€%]+\\s+[A-Za-zÄÖÜäöüß0-9$€%]+)\\s*,?\\s*\\b$REPAIR_TRIGGER_PATTERN\\b\\s*,?\\s*([A-Za-zÄÖÜäöüß0-9$€%]+\\s+[A-Za-zÄÖÜäöüß0-9$€%]+)"
        )

        // 3) Time expression repair without preposition: "3 pm no wait 4 pm" / "14 uhr nein warte 15 uhr"
        private val TIME_REPAIR_REGEX = Regex(
            "(?i)\\b(\\d{1,2}(?:[:\\s]\\d{2})?\\s*(?:am|pm|uhr|o'clock))\\s*,?\\s*\\b$REPAIR_TRIGGER_PATTERN\\b\\s*,?\\s*(\\d{1,2}(?:[:\\s]\\d{2})?\\s*(?:am|pm|uhr|o'clock))"
        )

        // 4) Single-token / number / day / proper-name repair: "Tuesday I mean Wednesday", "$50 no wait $60"
        private val SINGLE_TOKEN_REPAIR_REGEX = Regex(
            "(?i)([\\p{L}0-9$€%]+)\\s*,?\\s*\\b$REPAIR_TRIGGER_PATTERN\\b\\s*,?\\s*([\\p{L}0-9$€%]+)"
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

        // Spoken word-number lookup for ITN (hundreds, tens, ones)
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

        // Discourse sentence-splitting triggers when a run-on transcript exceeds a single clause
        private val DISCOURSE_SENTENCE_BREAK_REGEX = Regex(
            "(?i)(?<=\\b[\\p{L}0-9%$€]{2,20})\\s+(?=(?:however|furthermore|moreover|meanwhile|therefore|consequently|nevertheless|additionally|on\\s+the\\s+other\\s+hand|moving\\s+on\\s+to|on\\s+another\\s+note|in\\s+conclusion|to\\s+summarize|allerdings|außerdem|darüber\\s+hinaus|infolgedessen|nichtsdestotrotz|zudem|kommen\\s+wir\\s+zu|ein\\s+weiterer\\s+punkt|zusammenfassend|abschließend)\\b)"
        )

        // Topic-shift sentence prefixes that trigger an automatic paragraph break in long dictations
        private val TOPIC_SHIFT_SENTENCE_PREFIXES = listOf(
            "moving on", "on another note", "regarding ", "turning to ", "next topic",
            "furthermore,", "moreover,", "in addition,", "meanwhile,", "on the other hand",
            "finally,", "in conclusion", "to summarize", "overall,",
            "kommen wir zu", "ein weiterer punkt", "bezüglich ", "was das betrifft",
            "darüber hinaus", "außerdem,", "abschließend", "zusammenfassend"
        )

        // Conversational hedges removed in CONCISE_PROSE mode
        private val CONCISE_HEDGES: List<Regex> = listOf(
            Regex("(?i)(?:^|(?<=[.!?\\n]\\s))(?:so\\s+)?(?:i\\s+just\\s+wanted\\s+to\\s+(?:say|let\\s+you\\s+know|mention|reach\\s+out\\s+and\\s+say)\\s+that\\s+)"),
            Regex("(?i)(?:^|(?<=[.!?\\n]\\s))(?:basically\\s+what\\s+i(?:'m|\\s+am)\\s+trying\\s+to\\s+say\\s+is\\s+(?:that\\s+)?)"),
            Regex("(?i)\\b(?:to\\s+be\\s+completely\\s+honest|to\\s+be\\s+honest\\s+with\\s+you|if\\s+i\\s+am\\s+being\\s+honest)\\s*,?\\s*"),
            Regex("(?i)\\b(?:sort\\s+of\\s+like|kind\\s+of\\s+like|you\\s+know\\s+what\\s+i\\s+mean)\\s*,?\\s*"),
            Regex("(?i)(?:^|(?<=[.!?\\n]\\s))(?:ich\\s+wollte\\s+(?:eigentlich\\s+)?nur\\s+(?:kurz\\s+)?(?:sagen|bescheid\\s+geben)\\s*,?\\s*dass\\s+)"),
            Regex("(?i)\\b(?:im\\s+grunde\\s+genommen|ehrlich\\s+gesagt|um\\s+ehrlich\\s+zu\\s+sein|sozusagen)\\s*,?\\s*")
        )

        // Contraction expansions for PROFESSIONAL_EMAIL mode
        private val PROFESSIONAL_CONTRACTIONS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)\\bwon't\\b") to "will not",
            Regex("(?i)\\bcan't\\b") to "cannot",
            Regex("(?i)\\bdon't\\b") to "do not",
            Regex("(?i)\\bdoesn't\\b") to "does not",
            Regex("(?i)\\bdidn't\\b") to "did not",
            Regex("(?i)\\bisn't\\b") to "is not",
            Regex("(?i)\\baren't\\b") to "are not",
            Regex("(?i)\\bwasn't\\b") to "was not",
            Regex("(?i)\\bweren't\\b") to "were not",
            Regex("(?i)\\bhaven't\\b") to "have not",
            Regex("(?i)\\bhasn't\\b") to "has not",
            Regex("(?i)\\bhadn't\\b") to "had not",
            Regex("(?i)\\bwouldn't\\b") to "would not",
            Regex("(?i)\\bcouldn't\\b") to "could not",
            Regex("(?i)\\bshouldn't\\b") to "should not",
            Regex("(?i)\\bi'm\\b") to "I am",
            Regex("(?i)\\bi've\\b") to "I have",
            Regex("(?i)\\bi'll\\b") to "I will",
            Regex("(?i)\\bwe're\\b") to "we are",
            Regex("(?i)\\bwe've\\b") to "we have",
            Regex("(?i)\\bwe'll\\b") to "we will",
            Regex("(?i)\\bthey're\\b") to "they are",
            Regex("(?i)\\byou're\\b") to "you are",
            Regex("(?i)\\bit's\\b") to "it is"
        )

        // Email greeting & sign-off patterns for PROFESSIONAL_EMAIL mode
        private val EMAIL_GREETING_REGEX = Regex(
            "(?i)^\\s*((?:hi|hello|dear|good\\s+morning|good\\s+afternoon|good\\s+evening|hallo|liebe|lieber)\\s+(?!i\\b|we\\b)[A-Za-zÄÖÜäöüß]+)\\s*[,!]?\\s+(?=[\\p{L}])"
        )
        private val EMAIL_SIGNOFF_REGEX = Regex(
            "(?i)([.!?])?\\s+\\b((?:best\\s+regards|kind\\s+regards|warm\\s+regards|many\\s+thanks|sincerely|best\\s+wishes|mit\\s+freundlichen\\s+grüßen|viele\\s+grüße|beste\\s+grüße|herzliche\\s+grüße))\\b\\s*[,]?\\s*"
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
         * Stitches transcripts produced across multiple 20s audio chunks in long dictations.
         *
         * When `Voz` transcribes each 20s chunk independently, it frequently appends a period `.`
         * at the end of Chunk `i` and capitalizes the start of Chunk `i+1` even when the speaker
         * paused briefly mid-sentence or uttered a cross-chunk self-repair.
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
                    // Strip the artificial period at the end of chunk i-1
                    builder.setLength(currentStr.length - 1)
                    builder.append(' ')
                    // Lowercase the first word of nextChunk unless it is "I" / "I'm"
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
         * long dictations without regex backtracking.
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
                    if (ch == '\n' || ((ch == '.' || ch == '!' || ch == '?') && (idx + 1 == length || trimmed[idx + 1].isWhitespace()))) {
                        splitAt = idx + 1
                        break
                    }
                }

                // 2. Next prefer splitting at a comma/semicolon or before a discourse transition word
                if (splitAt == -1) {
                    for (idx in (searchEnd - 1) downTo searchStart) {
                        val ch = trimmed[idx]
                        if ((ch == ',' || ch == ';') && idx + 1 < length && trimmed[idx + 1].isWhitespace()) {
                            splitAt = idx + 1
                            break
                        }
                    }
                }

                // 3. Fallback: split at the last whitespace before searchEnd, avoiding splitting mid-repair trigger
                if (splitAt == -1) {
                    for (idx in (searchEnd - 1) downTo searchStart) {
                        if (trimmed[idx].isWhitespace()) {
                            val lookbehind = trimmed.substring((idx - 16).coerceAtLeast(offset), idx).lowercase(Locale.ROOT)
                            if (!lookbehind.endsWith("no") && !lookbehind.endsWith("wait") && !lookbehind.endsWith("i") && !lookbehind.endsWith("nein")) {
                                splitAt = idx + 1
                                break
                            }
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
            style: GemmaFormattingStyle = GemmaFormattingStyle.SMART_CLEANUP,
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

            var combined = formattedWindows.filter { it.isNotBlank() }.joinToString(" ")

            // Document-level post-processing:
            // 1. Professional Email greeting & sign-off paragraphing
            if (config.style == GemmaFormattingStyle.PROFESSIONAL_EMAIL) {
                combined = applyProfessionalEmailLayout(combined)
            }

            // 2. Long-Dictation Auto-Paragraphing across the assembled document
            if (config.autoParagraphEnabled && config.style != GemmaFormattingStyle.STRUCTURED_NOTES) {
                combined = applyAutoParagraphing(combined, config.paragraphDensity)
            }

            // 3. Casual Chat trailing period relaxation for single-sentence messages
            if (config.style == GemmaFormattingStyle.CASUAL_CHAT) {
                combined = applyCasualChatPolish(combined)
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

            // Stage 2: Technical / Developer spoken code syntax (if TECHNICAL_CODE mode)
            if (config.style == GemmaFormattingStyle.TECHNICAL_CODE) {
                text = applyTechnicalCodeFormatting(text)
            }

            // Stage 3: Inverse Text Normalization (hundreds/tens/ones numbers, times, percentages, currencies)
            if (config.smartNumbersEnabled) {
                text = normalizeNumbersTimesAndUnits(text, lang)
            }

            // Stage 4: Mid-utterance self-corrections and multi-word phrase repairs
            if (config.selfCorrectionEnabled) {
                text = resolveSelfCorrections(text)
            }

            // Stage 5: Involuntary word/phrase stutter deduplication
            if (config.stutterRemovalEnabled) {
                text = deduplicateStutters(text)
            }

            // Stage 6: Style-specific prose transformations
            when (config.style) {
                GemmaFormattingStyle.CONCISE_PROSE -> {
                    text = applyConciseProseTightening(text)
                }
                GemmaFormattingStyle.PROFESSIONAL_EMAIL -> {
                    text = applyProfessionalContractions(text, lang)
                }
                else -> {}
            }

            // Stage 7: Discourse segmentation & clause punctuation per paragraph/line
            text = segmentAndPunctuateParagraphs(
                input = text,
                lang = lang,
                style = config.style,
                smartPunctuationEnabled = config.smartPunctuationEnabled
            )

            // Stage 8: Enforce English "I" pronoun casing and custom vocabulary casing
            if (lang == "en") {
                text = enforceEnglishPronounCasing(text)
            }
            if (customVocabulary.isNotEmpty()) {
                text = applyCustomVocabulary(text, customVocabulary)
            }

            return finalizeTypography(text)
        }

        private fun resolveSelfCorrections(input: String): String {
            var text = input

            // 1. Bounded clause cancellation ("scratch that", "never mind that", "streich das")
            text = CLAUSE_CANCEL_REGEX.replace(text, "")

            // 2. Prepositional & multi-word phrase repairs ("to the marketing team no wait to the engineering team")
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

            // 4. Two-word modifier+noun phrase repairs ("next Monday I mean next Tuesday")
            iterations = 0
            do {
                previous = text
                text = TWO_WORD_PHRASE_REPAIR_REGEX.replace(text) { match ->
                    match.groupValues[2]
                }
                iterations++
            } while (text != previous && iterations < 3)

            // 5. Single-token repairs ("Tuesday I mean Wednesday" -> "Wednesday", "$50 no wait $60" -> "$60")
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

        private fun applyProfessionalContractions(input: String, lang: String): String {
            if (lang != "en") return input
            var text = input
            for ((pattern, expansion) in PROFESSIONAL_CONTRACTIONS) {
                text = pattern.replace(text, expansion)
            }
            return text
        }

        private fun applyProfessionalEmailLayout(input: String): String {
            var text = EMAIL_GREETING_REGEX.replace(input) { match ->
                val greeting = capitalizeFirstLetter(match.groupValues[1].trim())
                "$greeting,\n\n"
            }
            text = EMAIL_SIGNOFF_REGEX.replace(text) { match ->
                val punct = match.groupValues[1].ifEmpty { "." }
                val signoff = capitalizeFirstLetter(match.groupValues[2].trim())
                "$punct\n\n$signoff,\n"
            }
            return text.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) capitalizeFirstLetter(trimmed) else ""
            }
        }

        private fun applyCasualChatPolish(input: String): String {
            val trimmed = input.trim()
            // In casual chat mode, if the entire dictation is a single short sentence ending with '.',
            // strip the trailing period for natural messaging style while preserving '?' and '!'
            if (!trimmed.contains('\n') &&
                trimmed.endsWith(".") &&
                trimmed.count { it == '.' || it == '!' || it == '?' } == 1 &&
                trimmed.length <= 140
            ) {
                return trimmed.dropLast(1)
            }
            return trimmed
        }

        private fun applyTechnicalCodeFormatting(input: String): String {
            var text = input

            // 1. Spoken inline code and operators first so identifier boundaries are unambiguous
            text = Regex("(?i)\\binline\\s+code\\s+([A-Za-z0-9_.()-]+)").replace(text, "`$1`")
            text = Regex("(?i)\\s+\\bfat\\s+arrow\\b\\s+").replace(text, " => ")
            text = Regex("(?i)\\s+\\bthin\\s+arrow\\b\\s+").replace(text, " -> ")
            text = Regex("(?i)\\s+\\btriple\\s+equals\\b\\s+").replace(text, " === ")
            text = Regex("(?i)\\s+\\bdouble\\s+equals\\b\\s+").replace(text, " == ")
            text = Regex("(?i)\\s+\\bnot\\s+equals\\b\\s+").replace(text, " != ")
            text = Regex("(?i)\\s*\\bdouble\\s+colon\\b\\s*").replace(text, "::")

            // 2. Spoken identifier casing commands: "camel case user profile id" -> "userProfileId"
            val casingRegex = Regex(
                "(?i)\\b(camel\\s+case|pascal\\s+case|snake\\s+case|screaming\\s+snake\\s+case|constant\\s+case|kebab\\s+case)\\s+([A-Za-z0-9]+(?:\\s+[A-Za-z0-9]+){1,4}?)(?=[.,;?!\\n]|\\s+(?:==|===|!=|=>|->)|\\s+(?:is|equals|to|in|for|and|or|with|returns|from|of)\\b|$)"
            )
            text = casingRegex.replace(text) { match ->
                val mode = match.groupValues[1].lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                val words = match.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                when (mode) {
                    "camel case" -> words.mapIndexed { idx, w ->
                        if (idx == 0) w.lowercase(Locale.ROOT)
                        else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                    }.joinToString("")
                    "pascal case" -> words.joinToString("") { w ->
                        w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                    }
                    "snake case" -> words.joinToString("_") { it.lowercase(Locale.ROOT) }
                    "screaming snake case", "constant case" -> words.joinToString("_") { it.uppercase(Locale.ROOT) }
                    "kebab case" -> words.joinToString("-") { it.lowercase(Locale.ROOT) }
                    else -> match.value
                }
            }

            return text
        }

        private fun normalizeNumbersTimesAndUnits(input: String, lang: String): String {
            var text = input

            // 1. Spoken hundreds ("three hundred fifty" / "three hundred and fifty" -> "350")
            val hundredsRegex = Regex(
                "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun)\\s+(?:hundred|hundert)(?:\\s+(?:and|und))?(?:\\s+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig)(?:[\\s-]+(one|two|three|four|five|six|seven|eight|nine|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun))?|\\s+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|fünfzehn))?\\b"
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

            // 2. Spoken two-word numbers (e.g. "twenty five" -> "25") before units/currencies
            val compoundNumberUnitRegex = Regex(
                "(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[\\s-]+(one|two|three|four|five|six|seven|eight|nine)\\s+(percent|per\\s+cent|dollars?|euros?|pounds?|minutes?|hours?|days?|weeks?|months?|years?|items?|units?|users?|points?)\\b"
            )
            text = compoundNumberUnitRegex.replace(text) { match ->
                val tens = SPOKEN_TENS[match.groupValues[1].lowercase(Locale.ROOT)] ?: return@replace match.value
                val ones = SPOKEN_ONES[match.groupValues[2].lowercase(Locale.ROOT)] ?: return@replace match.value
                val unit = match.groupValues[3]
                "${tens + ones} $unit"
            }

            // 3. Spoken single-word numbers before percent/dollars/euros/prozent
            val singleWordNumberUnitRegex = Regex(
                "(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|fünfzehn|zwanzig|dreißig|vierzig|fünfzig|sechzig|siebzig|achtzig|neunzig|hundert)\\s+(percent|per\\s+cent|prozent|dollars?|euros?)\\b"
            )
            text = singleWordNumberUnitRegex.replace(text) { match ->
                val word = match.groupValues[1].lowercase(Locale.ROOT)
                val num = SPOKEN_ONES[word] ?: SPOKEN_TENS[word] ?: return@replace match.value
                val unit = match.groupValues[2]
                "$num $unit"
            }

            // 4. Format numeric percentages: "25 percent" / "25 prozent" -> "25%"
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:percent|per\\s+cent|prozent)\\b").replace(text, "$1%")

            // 5. Format numeric currencies: "50 dollars" -> "$50", "50 euros" -> "€50" (or "50 €" in German)
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s+dollars?\\b").replace(text, "\\$$1")
            text = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s+euros?\\b").replace(text) { match ->
                val amount = match.groupValues[1]
                if (lang == "de") "$amount €" else "€$amount"
            }

            // 6. Format English clock times: "3 pm" -> "3:00 PM", "3 30 pm" -> "3:30 PM", "3:30 pm" -> "3:30 PM"
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

            // 7. Format German clock times: "15 uhr 30" -> "15:30 Uhr", "15 uhr" -> "15:00 Uhr"
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
            style: GemmaFormattingStyle,
            smartPunctuationEnabled: Boolean
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
                    val formattedBody = formatSingleParagraphBody(body, lang, smartPunctuationEnabled)
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
                // Insert natural comma before contrast/causal conjunctions in longer clauses
                text = insertNaturalClauseCommas(text, lang)
            }

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
                    smartPunctuationEnabled && isLikelyQuestion(core, lang) -> '?'
                    else -> existingMark ?: '.'
                }
                "$capitalized$terminal"
            }
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
            val sentenceSplitRegex = Regex("(?<=[.!?])\\s+(?=[A-ZÄÖÜ\"])")

            val resultBlocks = ArrayList<String>(blocks.size * 2)
            for (block in blocks) {
                val trimmedBlock = block.trim()
                if (trimmedBlock.isEmpty() || trimmedBlock.contains("\n•") || trimmedBlock.startsWith("•")) {
                    resultBlocks.add(trimmedBlock)
                    continue
                }

                val sentences = sentenceSplitRegex.split(trimmedBlock)
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
            result = Regex(
                "(?i)(^|(?<=[.!?]\\s))(however|furthermore|moreover|meanwhile|therefore|nevertheless|additionally|unfortunately|fortunately|in\\s+addition|in\\s+conclusion|allerdings|außerdem|trotzdem|deshalb|leider|glücklicherweise|zusammenfassend|abschließend)\\s+(?=[\\p{L}])"
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
