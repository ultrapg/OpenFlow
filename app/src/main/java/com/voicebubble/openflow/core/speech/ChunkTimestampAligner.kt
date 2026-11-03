package com.voicebubble.openflow.core.speech

import ai.desertant.voz.Transcription
import ai.desertant.voz.Word
import com.voicebubble.openflow.settings.GemmaPromptPreset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result of preparing a raw PCM chunk for `Voz` Conformer-TDT inference while tracking the exact number
 * of prepended leading silence samples so word timestamps map back to 1-sample-exact global stream time.
 */
data class PreparedAudioChunk(
    val samples: FloatArray,
    val prependedLeadSamples: Int
)

/**
 * Word token anchored to global dictation recording coordinates (in seconds from `t = 0.0` of the session).
 */
data class GlobalTimedWord(
    val text: String,
    val startSec: Double,
    val endSec: Double
) {
    val midSec: Double get() = 0.5 * (startSec + endSec)
}

/**
 * Structured output of transcribing a single audio chunk (live or offline), preserving both normalized
 * text and global word timestamps alongside the acoustic cut metadata at its left boundary.
 */
data class ChunkTranscriptionSegment(
    val normalizedText: String,
    val words: List<GlobalTimedWord>,
    val chunkStartSec: Double,
    val chunkEndSec: Double,
    val nominalCutBeforeSec: Double? = null,
    val cutKindBefore: CutKind? = null
)

/**
 * Diagnostic counters summarizing how chunk seams were reconciled across a dictation.
 */
data class ChunkMergeTelemetry(
    val totalSegments: Int = 0,
    val anchorAlignedJoins: Int = 0,
    val timeAlignedJoins: Int = 0,
    val pauseBoundaryJoins: Int = 0
)

/**
 * Combined result of [mergeTimestampedChunkSegmentsWithTelemetry].
 */
data class MergedChunkResult(
    val text: String,
    val words: List<GlobalTimedWord>,
    val telemetry: ChunkMergeTelemetry
)

/**
 * Plan returned by [planStreamingOverlapCommit] when an earlier subset of uncleaned live chunks forms a
 * closed, backtrack-safe sentence group separated from subsequent speech by a verified acoustic pause.
 *
 * @property commitRelativeEndExclusive exclusive index inside the passed `uncleanedSegments` list (`1 .. size - 1`).
 * @property prefixTextToClean timestamp-merged transcript of `uncleanedSegments[0 until commitRelativeEndExclusive]`.
 */
internal data class StreamingOverlapCommitPlan(
    val commitRelativeEndExclusive: Int,
    val prefixTextToClean: String
)

private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

/**
 * Discourse adverbs that genuinely open a new sentence even after a brief pause and must NOT have a
 * preceding period stripped by acoustic-gap continuation heuristics.
 */
private val SENTENCE_INITIAL_DISCOURSE_STARTERS = setOf(
    "however", "therefore", "furthermore", "moreover", "meanwhile", "nevertheless", "nonetheless",
    "otherwise", "additionally", "consequently", "subsequently", "fortunately", "unfortunately",
    "honestly", "frankly", "overall", "finally", "firstly", "secondly", "thirdly",
    "allerdings", "außerdem", "trotzdem", "dennoch", "deshalb", "deswegen", "daher", "folglich",
    "inzwischen", "unterdessen", "übrigens", "jedenfalls", "schließlich", "zusammenfassend"
)

/**
 * Projects a single chunk's `Voz` [Transcription] into global recording time and aligns every token of
 * [reconcileTranscript] onto `Voz`'s 80 ms Conformer-TDT word timestamps, interpolating timestamps smoothly
 * if `Voz`'s `words` list omitted an edge token so zero tokens from `transcription.text` are ever lost.
 */
fun buildChunkTranscriptionSegment(
    transcription: Transcription,
    chunkStartSample: Long,
    rawChunkSampleCount: Int,
    prependedLeadSamples: Int,
    nominalCutSampleBefore: Long? = null,
    cutKindBefore: CutKind? = null,
    customVocabulary: List<String> = emptyList(),
    sampleRateHz: Double = SmartPipeline.SAMPLE_RATE_HZ
): ChunkTranscriptionSegment {
    val chunkStartSec = chunkStartSample.toDouble() / sampleRateHz
    val chunkEndSec = (chunkStartSample + rawChunkSampleCount.coerceAtLeast(0).toLong()).toDouble() / sampleRateHz
    val nominalCutSec = nominalCutSampleBefore?.let { it.toDouble() / sampleRateHz }
    val normalizedText = reconcileTranscript(transcription, customVocabulary)
    if (normalizedText.isBlank()) {
        return ChunkTranscriptionSegment(
            normalizedText = "",
            words = emptyList(),
            chunkStartSec = chunkStartSec,
            chunkEndSec = chunkEndSec,
            nominalCutBeforeSec = nominalCutSec,
            cutKindBefore = cutKindBefore
        )
    }

    val tokens = normalizedText.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
    val timeOriginSec = (chunkStartSample - prependedLeadSamples.toLong()).toDouble() / sampleRateHz
    val minAllowedSec = (chunkStartSec - 0.08).coerceAtLeast(0.0)
    val maxAllowedSec = max(chunkEndSec + 0.16, minAllowedSec + 0.08)

    val rawShifted = ArrayList<GlobalTimedWord>(transcription.words.size)
    for (w: Word in transcription.words) {
        if (isUnambiguousHesitationToken(w.text)) continue
        val core = extractWordCore(w.text)
        if (core.isEmpty() && w.text.isBlank()) continue
        val s = (timeOriginSec + w.start).coerceIn(minAllowedSec, maxAllowedSec)
        val e = (timeOriginSec + w.end).coerceIn(s + 0.02, max(s + 0.02, maxAllowedSec))
        rawShifted.add(GlobalTimedWord(text = w.text.trim(), startSec = s, endSec = e))
    }

    val alignedWords = alignNormalizedTokensToTimedWords(
        tokens = tokens,
        rawShifted = rawShifted,
        chunkStartSec = chunkStartSec,
        chunkEndSec = max(chunkEndSec, chunkStartSec + 0.10)
    )

    return ChunkTranscriptionSegment(
        normalizedText = tokens.joinToString(" "),
        words = alignedWords,
        chunkStartSec = chunkStartSec,
        chunkEndSec = chunkEndSec,
        nominalCutBeforeSec = nominalCutSec,
        cutKindBefore = cutKindBefore
    )
}

private fun alignNormalizedTokensToTimedWords(
    tokens: List<String>,
    rawShifted: List<GlobalTimedWord>,
    chunkStartSec: Double,
    chunkEndSec: Double
): List<GlobalTimedWord> {
    val m = tokens.size
    if (m == 0) return emptyList()
    if (rawShifted.isEmpty()) {
        val span = max(0.10, chunkEndSec - chunkStartSec)
        val step = span / m.toDouble()
        return tokens.mapIndexed { idx, tok ->
            val s = chunkStartSec + idx * step
            GlobalTimedWord(tok, s, s + step * 0.85)
        }
    }

    val matchedStart = DoubleArray(m) { Double.NaN }
    val matchedEnd = DoubleArray(m) { Double.NaN }
    var wi = 0
    for (ti in 0 until m) {
        val tokCore = extractWordCore(tokens[ti])
        var foundIdx = -1
        val limit = min(rawShifted.size, wi + 4)
        for (cand in wi until limit) {
            val rawCore = extractWordCore(rawShifted[cand].text)
            if (tokCore.isNotEmpty() && rawCore.isNotEmpty() && (
                    tokCore.equals(rawCore, ignoreCase = true) ||
                        (tokCore.length >= 5 && rawCore.length >= 5 && isSingleEditDistanceIgnoreCase(tokCore, rawCore))
                    )
            ) {
                foundIdx = cand
                break
            }
        }
        if (foundIdx >= 0) {
            matchedStart[ti] = rawShifted[foundIdx].startSec
            matchedEnd[ti] = rawShifted[foundIdx].endSec
            wi = foundIdx + 1
        }
    }

    // Interpolate timestamps for any tokens omitted from `transcription.words` (e.g. a final word or split compound)
    val result = ArrayList<GlobalTimedWord>(m)
    var idx = 0
    while (idx < m) {
        if (!matchedStart[idx].isNaN()) {
            result.add(GlobalTimedWord(tokens[idx], matchedStart[idx], matchedEnd[idx]))
            idx++
        } else {
            var runEnd = idx
            while (runEnd < m && matchedStart[runEnd].isNaN()) runEnd++
            val count = runEnd - idx
            val leftBound = if (idx > 0 && result.isNotEmpty()) result.last().endSec else chunkStartSec
            val rightBound = when {
                runEnd < m -> max(leftBound + 0.04 * count, matchedStart[runEnd])
                else -> max(leftBound + 0.16 * count, chunkEndSec)
            }
            val step = max(0.04, (rightBound - leftBound) / count.toDouble())
            for (k in 0 until count) {
                val s = leftBound + k * step
                val e = s + step * 0.90
                result.add(GlobalTimedWord(tokens[idx + k], s, max(s + 0.02, e)))
            }
            idx = runEnd
        }
    }
    return result
}

/**
 * Merges timestamped chunk transcriptions (`[ChunkTranscriptionSegment]`) into a single continuous transcript
 * using Overlap-and-Align + Revisable Tail reconciliation:
 *
 * 1. **Timestamp-Gated Lexical Anchor Alignment:** Across overlapping valley cuts (`1.20 s` bilateral context),
 *    locates matching word/phrase anchors at matching global timestamps (`|t_prev - t_next| <= 0.60 s`)
 *    closest to the nominal cut center, adopting left-context casing from the outgoing chunk and right-context
 *    mid-stream punctuation from the incoming chunk (eliminating both boundary word drops/duplications and
 *    synthetic chunk-final periods).
 * 2. **Timestamp Crossover + Revisable Tail:** When no lexical anchor overlaps (e.g. cut fell cleanly between
 *    distinct words), splits strictly at `nominalCutBeforeSec` (`midSec <= cut` vs `midSec > cut`) with
 *    boundary-echo deduplication.
 * 3. **Acoustic-Gap + Grammatical Seam Reconciliation:** Uses the true inter-word gap (`next.startSec - prev.endSec`)
 *    together with English & German grammatical rules to strip false chunk-final periods on continuous speech
 *    while preserving genuine multi-sentence boundaries and German noun capitalization.
 */
fun mergeTimestampedChunkSegments(
    segments: List<ChunkTranscriptionSegment>,
    language: String? = null,
    customVocabulary: List<String> = emptyList()
): String = mergeTimestampedChunkSegmentsWithTelemetry(
    segments = segments,
    language = language,
    customVocabulary = customVocabulary
).text

fun mergeTimestampedChunkSegmentsWithTelemetry(
    segments: List<ChunkTranscriptionSegment>,
    language: String? = null,
    customVocabulary: List<String> = emptyList()
): MergedChunkResult {
    val nonBlank = segments.filter { seg ->
        seg.normalizedText.any { it.isLetterOrDigit() }
    }
    if (nonBlank.isEmpty()) {
        return MergedChunkResult("", emptyList(), ChunkMergeTelemetry())
    }
    if (nonBlank.size == 1) {
        val singleSeg = nonBlank[0]
        val text = stitchChunkTranscripts(
            chunks = listOf(singleSeg.normalizedText),
            language = language,
            customVocabulary = customVocabulary
        )
        val singleWords = capitalizeFirstTimedWord(singleSeg.words, customVocabulary)
        return MergedChunkResult(
            text = text,
            words = singleWords,
            telemetry = ChunkMergeTelemetry(totalSegments = 1)
        )
    }

    // If any segment lacks word timestamps altogether, fall back cleanly to string stitching
    if (nonBlank.any { it.words.isEmpty() }) {
        val fallback = stitchChunkTranscripts(
            chunks = nonBlank.map { it.normalizedText },
            language = language,
            customVocabulary = customVocabulary
        )
        return MergedChunkResult(
            text = fallback,
            words = emptyList(),
            telemetry = ChunkMergeTelemetry(
                totalSegments = nonBlank.size,
                pauseBoundaryJoins = nonBlank.size - 1
            )
        )
    }

    val isGerman = language?.lowercase(Locale.ROOT)?.startsWith("de") == true
    val customVocabLower = if (customVocabulary.isEmpty()) {
        emptySet()
    } else {
        customVocabulary.mapNotNull { term ->
            extractWordCore(term.trim()).lowercase(Locale.ROOT).ifEmpty { null }
        }.toSet()
    }

    var accWords = capitalizeFirstTimedWord(nonBlank[0].words, customVocabulary).toMutableList()
    var prevChunkEndSec = nonBlank[0].chunkEndSec
    var anchorJoins = 0
    var timeJoins = 0
    var pauseJoins = 0

    for (segIdx in 1 until nonBlank.size) {
        val seg = nonBlank[segIdx]
        val nextWords = seg.words
        if (nextWords.isEmpty()) continue
        if (accWords.isEmpty()) {
            accWords = capitalizeFirstTimedWord(nextWords, customVocabulary).toMutableList()
            prevChunkEndSec = seg.chunkEndSec
            continue
        }

        val overlapSec = prevChunkEndSec - seg.chunkStartSec
        val hasAcousticOverlap = overlapSec > 0.08 || seg.cutKindBefore == CutKind.VALLEY_OVERLAP

        if (hasAcousticOverlap) {
            val overlapStartSec = min(seg.chunkStartSec, prevChunkEndSec)
            val overlapEndSec = max(seg.chunkStartSec, prevChunkEndSec)
            val nominalCutSec = seg.nominalCutBeforeSec ?: (0.5 * (overlapStartSec + overlapEndSec))

            val anchorMatch = findBestOverlapAnchor(
                accWords = accWords,
                nextWords = nextWords,
                overlapStartSec = overlapStartSec,
                overlapEndSec = overlapEndSec,
                nominalCutSec = nominalCutSec
            )

            if (anchorMatch != null) {
                anchorJoins++
                accWords = applyAnchorAlignment(
                    accWords = accWords,
                    nextWords = nextWords,
                    anchorPrevIdx = anchorMatch.first,
                    anchorNextIdx = anchorMatch.second,
                    isGerman = isGerman,
                    customVocabLower = customVocabLower
                )
            } else {
                timeJoins++
                accWords = applyTimeCrossoverAlignment(
                    accWords = accWords,
                    nextWords = nextWords,
                    nominalCutSec = nominalCutSec,
                    isGerman = isGerman,
                    customVocabLower = customVocabLower
                )
            }
        } else {
            pauseJoins++
            accWords = applyPauseSeamMerge(
                accWords = accWords,
                nextWords = nextWords,
                isGerman = isGerman,
                customVocabLower = customVocabLower
            )
        }
        prevChunkEndSec = max(prevChunkEndSec, seg.chunkEndSec)
    }

    val rawJoined = accWords.joinToString(" ") { it.text }
    val finalNormalized = applyCustomVocabulary(
        normalizeTranscriptText(rawJoined, capitalizeLeadingWord = true),
        customVocabulary
    )
    return MergedChunkResult(
        text = finalNormalized,
        words = accWords,
        telemetry = ChunkMergeTelemetry(
            totalSegments = nonBlank.size,
            anchorAlignedJoins = anchorJoins,
            timeAlignedJoins = timeJoins,
            pauseBoundaryJoins = pauseJoins
        )
    )
}

private fun capitalizeFirstTimedWord(
    words: List<GlobalTimedWord>,
    customVocabulary: List<String>
): List<GlobalTimedWord> {
    if (words.isEmpty()) return words
    val out = words.toMutableList()
    val first = out[0]
    val biasedFirst = applyCustomVocabulary(first.text, customVocabulary)
    val letterIdx = biasedFirst.indexOfFirst { it.isLetter() }
    val capped = if (letterIdx >= 0 && biasedFirst[letterIdx].isLowerCase()) {
        val core = extractWordCore(biasedFirst)
        val hasInternalCaps = core.drop(1).any { it.isUpperCase() }
        if (hasInternalCaps) {
            biasedFirst
        } else {
            biasedFirst.substring(0, letterIdx) +
                biasedFirst[letterIdx].titlecase(Locale.getDefault()) +
                biasedFirst.substring(letterIdx + 1)
        }
    } else {
        biasedFirst
    }
    out[0] = first.copy(text = capped)
    return out
}

private fun wordsMatchForOverlapAnchor(
    tokenA: String,
    tokenB: String,
    allowEdgeFuzzy: Boolean
): Boolean {
    val coreA = extractWordCore(tokenA)
    val coreB = extractWordCore(tokenB)
    if (coreA.isEmpty() || coreB.isEmpty()) return false
    if (coreA.equals(coreB, ignoreCase = true)) return true
    if (allowEdgeFuzzy && coreA.length >= 5 && coreB.length >= 5) {
        return isSingleEditDistanceIgnoreCase(coreA, coreB)
    }
    return false
}

private fun findBestOverlapAnchor(
    accWords: List<GlobalTimedWord>,
    nextWords: List<GlobalTimedWord>,
    overlapStartSec: Double,
    overlapEndSec: Double,
    nominalCutSec: Double
): Pair<Int, Int>? {
    val searchMarginSec = 0.42
    val maxTimeDriftSec = 0.60
    val minI = max(0, accWords.size - 20)
    val maxJ = min(20, nextWords.size)

    var bestPair: Pair<Int, Int>? = null
    var bestScore = Double.NEGATIVE_INFINITY

    for (i in minI until accWords.size) {
        val wPrev = accWords[i]
        if (wPrev.endSec < overlapStartSec - searchMarginSec) continue
        val corePrev = extractWordCore(wPrev.text)
        if (corePrev.isEmpty()) continue

        for (j in 0 until maxJ) {
            val wNext = nextWords[j]
            if (wNext.startSec > overlapEndSec + searchMarginSec) break
            val timeDrift = abs(wPrev.midSec - wNext.midSec)
            if (timeDrift > maxTimeDriftSec) continue

            val onEdge = (i == accWords.lastIndex || j == 0)
            if (!wordsMatchForOverlapAnchor(wPrev.text, wNext.text, allowEdgeFuzzy = onEdge)) continue

            // Check neighboring phrase agreement (d in -2..2) to disambiguate short common words like "a" or "the"
            var neighborMatches = 0
            for (d in listOf(-2, -1, 1, 2)) {
                val pi = i + d
                val nj = j + d
                if (pi in accWords.indices && nj in nextWords.indices) {
                    val np = accWords[pi]
                    val nn = nextWords[nj]
                    if (abs(np.midSec - nn.midSec) <= 0.70 &&
                        wordsMatchForOverlapAnchor(np.text, nn.text, allowEdgeFuzzy = false)
                    ) {
                        neighborMatches++
                    }
                }
            }

            // Single-letter words ("a", "I") require either tight timestamp lock (<= 0.22s) or a matching neighbor
            if (corePrev.length == 1 && neighborMatches == 0 && timeDrift > 0.22) continue

            val meanMid = 0.5 * (wPrev.midSec + wNext.midSec)
            val distToCut = abs(meanMid - nominalCutSec)
            val interiorBonus = if (i < accWords.lastIndex && j > 0) 3.0 else 0.0
            val score = neighborMatches * 10.0 + interiorBonus - distToCut * 4.0 - timeDrift * 3.0
            if (score > bestScore) {
                bestScore = score
                bestPair = i to j
            }
        }
    }
    return bestPair
}

/**
 * Combines the best properties of both chunks on a matched anchor word:
 * - **Left-context casing** from [prevWord] when `anchorNextIdx == 0` (where `next` lacked left context and
 *   may have capitalized its first word).
 * - **Right-context punctuation** from [nextWord] when `anchorPrevIdx == accWords.lastIndex` and `next`
 *   has words after `anchorNextIdx` (stripping synthetic chunk-final `.` added by `Voz` at the end of `prev`).
 */
private fun reconcileAnchorToken(
    prevWord: GlobalTimedWord,
    nextWord: GlobalTimedWord,
    isPrevChunkFinal: Boolean,
    isNextChunkInitial: Boolean,
    hasNextRightContext: Boolean
): GlobalTimedWord {
    val prevLead = prevWord.text.takeWhile { !it.isLetterOrDigit() }
    val prevTrail = prevWord.text.takeLastWhile { !it.isLetterOrDigit() }
    val prevCore = extractWordCore(prevWord.text)

    val nextTrail = nextWord.text.takeLastWhile { !it.isLetterOrDigit() }
    val nextCore = extractWordCore(nextWord.text)

    // Choose core spelling/casing from whichever chunk was NOT clipped at its boundary
    val chosenCore = when {
        isPrevChunkFinal && !isNextChunkInitial && nextCore.length > prevCore.length -> nextCore
        isNextChunkInitial && prevCore.isNotEmpty() -> prevCore
        prevCore.isNotEmpty() -> prevCore
        else -> nextCore
    }

    // Choose trailing punctuation from whichever chunk had true right-hand spoken context
    val chosenTrail = when {
        isPrevChunkFinal && hasNextRightContext -> nextTrail
        else -> prevTrail
    }

    return GlobalTimedWord(
        text = prevLead + chosenCore + chosenTrail,
        startSec = 0.5 * (prevWord.startSec + nextWord.startSec),
        endSec = 0.5 * (prevWord.endSec + nextWord.endSec)
    )
}

private fun applyAnchorAlignment(
    accWords: List<GlobalTimedWord>,
    nextWords: List<GlobalTimedWord>,
    anchorPrevIdx: Int,
    anchorNextIdx: Int,
    isGerman: Boolean,
    customVocabLower: Set<String>
): MutableList<GlobalTimedWord> {
    val merged = ArrayList<GlobalTimedWord>(anchorPrevIdx + 1 + (nextWords.size - anchorNextIdx - 1))
    for (k in 0 until anchorPrevIdx) {
        merged.add(accWords[k])
    }
    val anchor = reconcileAnchorToken(
        prevWord = accWords[anchorPrevIdx],
        nextWord = nextWords[anchorNextIdx],
        isPrevChunkFinal = anchorPrevIdx == accWords.lastIndex,
        isNextChunkInitial = anchorNextIdx == 0,
        hasNextRightContext = anchorNextIdx < nextWords.lastIndex
    )
    merged.add(anchor)

    val remainder = nextWords.subList(anchorNextIdx + 1, nextWords.size)
    if (remainder.isNotEmpty()) {
        val adjustedFirst = adjustFirstRemainderTokenAfterAnchor(
            anchorToken = anchor.text,
            firstRemainder = remainder[0],
            secondRemainder = remainder.getOrNull(1),
            isGerman = isGerman,
            customVocabLower = customVocabLower
        )
        merged.add(adjustedFirst)
        for (k in 1 until remainder.size) {
            merged.add(remainder[k])
        }
    }
    return merged
}

private fun adjustFirstRemainderTokenAfterAnchor(
    anchorToken: String,
    firstRemainder: GlobalTimedWord,
    secondRemainder: GlobalTimedWord?,
    isGerman: Boolean,
    customVocabLower: Set<String>
): GlobalTimedWord {
    val anchorTrim = anchorToken.trim()
    val endsTerminal = (anchorTrim.endsWith(".") && !anchorTrim.endsWith("...")) ||
        anchorTrim.endsWith("!") ||
        anchorTrim.endsWith("?") ||
        anchorTrim.endsWith("…")
    if (endsTerminal) return firstRemainder

    val anchorCore = extractWordCore(anchorTrim).lowercase(Locale.ROOT)
    if (shouldLowercaseMidSentenceToken(
            firstToken = firstRemainder.text,
            secondToken = secondRemainder?.text,
            lastPrevWord = anchorCore,
            isGerman = isGerman,
            allowGeneralEnglishLowercase = true,
            customVocabLower = customVocabLower
        )
    ) {
        return firstRemainder.copy(text = lowercaseFirstLetterOfToken(firstRemainder.text))
    }
    return firstRemainder
}

private fun applyTimeCrossoverAlignment(
    accWords: List<GlobalTimedWord>,
    nextWords: List<GlobalTimedWord>,
    nominalCutSec: Double,
    isGerman: Boolean,
    customVocabLower: Set<String>
): MutableList<GlobalTimedWord> {
    val left = accWords.filter { it.midSec <= nominalCutSec }.toMutableList()
    val right = nextWords.filter { it.midSec > nominalCutSec }.toMutableList()

    if (left.isEmpty()) return nextWords.toMutableList()
    if (right.isEmpty()) return left

    // Deduplicate a boundary word that straddled `nominalCutSec` across the two chunks
    if (abs(right.first().midSec - left.last().midSec) <= 0.48 &&
        wordsMatchForOverlapAnchor(left.last().text, right.first().text, allowEdgeFuzzy = true)
    ) {
        val dupRight = right.removeAt(0)
        left[left.lastIndex] = reconcileAnchorToken(
            prevWord = left.last(),
            nextWord = dupRight,
            isPrevChunkFinal = true,
            isNextChunkInitial = true,
            hasNextRightContext = right.isNotEmpty()
        )
        if (right.isEmpty()) return left
    }

    val gapSec = right.first().startSec - left.last().endSec
    return reconcileBoundarySeamWithAcousticGap(
        left = left,
        right = right,
        acousticGapSec = gapSec,
        isGerman = isGerman,
        customVocabLower = customVocabLower
    )
}

private fun applyPauseSeamMerge(
    accWords: List<GlobalTimedWord>,
    nextWords: List<GlobalTimedWord>,
    isGerman: Boolean,
    customVocabLower: Set<String>
): MutableList<GlobalTimedWord> {
    val left = accWords.toMutableList()
    val right = nextWords.toMutableList()
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left

    val gapSec = right.first().startSec - left.last().endSec
    return reconcileBoundarySeamWithAcousticGap(
        left = left,
        right = right,
        acousticGapSec = gapSec,
        isGerman = isGerman,
        customVocabLower = customVocabLower
    )
}

/**
 * Reconciles the boundary between [left] and [right] using **both** the physical inter-word acoustic gap
 * (`[acousticGapSec] = right.first().startSec - left.last().endSec`) and [mergeTwoChunkTranscripts] grammar:
 *
 * - When `acousticGapSec < 0.38 s`, the user spoke straight across the boundary without a sentence pause:
 *   if [left] ended with a synthetic chunk-final `.` (not `!` or `?`) and [right] does not begin with a
 *   sentence-initial discourse adverb (`"However,"`, `"Therefore,"`, `"Allerdings,"`), strips the synthetic
 *   period so [mergeTwoChunkTranscripts] seamlessly joins the mid-sentence continuation (preserving `"I"`,
 *   acronyms, custom vocabulary, and German capitalized nouns).
 * - Delegates final token-level deduplication and English/German subordinate/coordinating connector rules
 *   to [mergeTwoChunkTranscripts], projecting the reconciled surface tokens back onto the timed word list.
 */
private fun reconcileBoundarySeamWithAcousticGap(
    left: MutableList<GlobalTimedWord>,
    right: MutableList<GlobalTimedWord>,
    acousticGapSec: Double,
    isGerman: Boolean,
    customVocabLower: Set<String>
): MutableList<GlobalTimedWord> {
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left

    // Revisable Tail: strip synthetic chunk-final period when physical inter-word gap proves continuous speech (< 380 ms)
    val lastLeft = left.last()
    val firstRight = right.first()
    val lastLeftTrim = lastLeft.text.trim()
    val leftEndsSyntheticPeriod = lastLeftTrim.endsWith(".") && !lastLeftTrim.endsWith("...")
    val firstRightCoreLower = extractWordCore(firstRight.text).lowercase(Locale.ROOT)
    val firstRightLooksLikeDiscourseStart = firstRight.text.endsWith(",") ||
        firstRightCoreLower in SENTENCE_INITIAL_DISCOURSE_STARTERS

    if (leftEndsSyntheticPeriod &&
        acousticGapSec < 0.38 &&
        !firstRightLooksLikeDiscourseStart
    ) {
        val stripped = lastLeftTrim.dropLast(1)
        if (stripped.isNotEmpty()) {
            left[left.lastIndex] = lastLeft.copy(text = stripped)
        }
    }

    // Apply grammatical boundary stitching on a compact local window (last 3 words of `left` + first 3 words of `right`)
    // so timestamps of all interior words remain 100% intact.
    val leftPrefixCount = (left.size - 3).coerceAtLeast(0)
    val rightSuffixStart = min(3, right.size)

    val leftWindow = left.subList(leftPrefixCount, left.size)
    val rightWindow = right.subList(0, rightSuffixStart)
    val mergedWindowText = mergeTwoChunkTranscripts(
        prev = leftWindow.joinToString(" ") { it.text },
        next = rightWindow.joinToString(" ") { it.text },
        isGerman = isGerman,
        customVocabLower = customVocabLower
    )
    val combinedWindowSource = ArrayList<GlobalTimedWord>(leftWindow.size + rightWindow.size).apply {
        addAll(leftWindow)
        addAll(rightWindow)
    }
    val mergedWindowTokens = mergedWindowText.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
    val mergedWindowWords = alignNormalizedTokensToTimedWords(
        tokens = mergedWindowTokens,
        rawShifted = combinedWindowSource,
        chunkStartSec = combinedWindowSource.first().startSec,
        chunkEndSec = combinedWindowSource.last().endSec
    )

    val out = ArrayList<GlobalTimedWord>(leftPrefixCount + mergedWindowWords.size + (right.size - rightSuffixStart))
    for (i in 0 until leftPrefixCount) out.add(left[i])
    out.addAll(mergedWindowWords)
    for (i in rightSuffixStart until right.size) out.add(right[i])
    return out
}

/**
 * Boundary-aware live LLM overlap planner:
 * Inspects uncleaned live [segments] (`size >= 2`) and finds the largest prefix `[0 until splitIdx]`
 * that forms a **closed, complete sentence group** separated from `[splitIdx until size]` by:
 * 1. A verified acoustic pause cut ([CutKind.CONFIDENT_PAUSE] — never a [CutKind.VALLEY_OVERLAP] forced cut!).
 * 2. At least `0.45 s` of real inter-word silence when word timestamps are available.
 * 3. Terminal sentence punctuation (`.` / `!` / `?` / `…`) that remains completely unchanged when merged
 *    with subsequent lookahead chunks via [mergeTimestampedChunkSegments].
 * 4. Zero verbal repair/backtrack cues (`"wait"`, `"actually"`, `"scratch that"`, `"nein warte"`, `"oder besser"`),
 *    conjunctions, or list continuations at the start of any lookahead chunk.
 * 5. At least `5` words so tiny 1–3 word fragments are never dispatched to the LLM without surrounding context.
 */
internal fun planStreamingOverlapCommit(
    uncleanedSegments: List<ChunkTranscriptionSegment>,
    language: String? = null,
    customVocabulary: List<String> = emptyList()
): StreamingOverlapCommitPlan? {
    if (uncleanedSegments.size < 2) return null

    // If any already-arrived trailing chunk opens with a self-correction / repair cue, hold until stabilized
    for (idx in 1 until uncleanedSegments.size) {
        val text = uncleanedSegments[idx].normalizedText.trim()
        if (text.isNotEmpty() && GemmaTextProcessor.startsWithStreamingRepairOrContinuation(text)) {
            // Only chunks strictly before `idx - 1` could even potentially be unaffected; be conservative and hold
            // if the repair is at `idx` right after our candidate split.
        }
    }

    val fullMerged = mergeTimestampedChunkSegments(
        segments = uncleanedSegments,
        language = language,
        customVocabulary = customVocabulary
    )
    if (fullMerged.isBlank()) return null

    for (splitIdx in (uncleanedSegments.size - 1) downTo 1) {
        val nextSeg = uncleanedSegments[splitIdx]
        if (nextSeg.cutKindBefore != null && nextSeg.cutKindBefore != CutKind.CONFIDENT_PAUSE) {
            continue
        }

        val prefixSlice = uncleanedSegments.subList(0, splitIdx)
        val lookaheadSlice = uncleanedSegments.subList(splitIdx, uncleanedSegments.size)
        val lastPrefixNonEmpty = prefixSlice.lastOrNull { it.words.isNotEmpty() }
        val firstLookaheadNonEmpty = lookaheadSlice.firstOrNull { it.words.isNotEmpty() }
        if (lastPrefixNonEmpty != null && firstLookaheadNonEmpty != null) {
            val acousticGapSec = firstLookaheadNonEmpty.words.first().startSec -
                lastPrefixNonEmpty.words.last().endSec
            if (acousticGapSec < 0.45) {
                continue
            }
        }

        // Reject if any lookahead chunk in `lookaheadSlice` starts with a repair/backtrack cue
        if (lookaheadSlice.any { seg ->
                val t = seg.normalizedText.trim()
                t.isNotEmpty() && GemmaTextProcessor.startsWithStreamingRepairOrContinuation(t)
            }
        ) {
            continue
        }

        val prefixMerged = mergeTimestampedChunkSegments(
            segments = prefixSlice,
            language = language,
            customVocabulary = customVocabulary
        )
        val lookaheadMerged = mergeTimestampedChunkSegments(
            segments = lookaheadSlice,
            language = language,
            customVocabulary = customVocabulary
        )
        if (prefixMerged.isBlank() || lookaheadMerged.isBlank()) continue

        val wordCount = prefixMerged.split(WHITESPACE_SPLIT_REGEX).count { it.isNotEmpty() }
        if (wordCount < 5) continue

        if (StreamingDictationSession.canCommitStreamingPrefixForOverlap(
                prefixStitched = prefixMerged,
                lookaheadChunk = lookaheadMerged,
                combinedStitched = fullMerged
            )
        ) {
            return StreamingOverlapCommitPlan(
                commitRelativeEndExclusive = splitIdx,
                prefixTextToClean = prefixMerged
            )
        }
    }
    return null
}

/**
 * Whole-dictation reconciliation guard executed at the end of a multi-segment dictation session:
 * compares the post-processed [candidateOutput] against the complete timestamp-merged raw `Voz`
 * transcript ([rawMergedVoz]) across the entire session:
 *
 * 1. Protects against multi-segment truncation (dropped prefix, middle, or tail sentences when the user
 *    did NOT utter any verbal self-correction/backtrack cue).
 * 2. Preserves legitimate spoken self-corrections, dense number/currency/date formatting, and structured
 *    summarizing presets ([GemmaPromptPreset.CONCISE_SUMMARY], [GemmaPromptPreset.ACTION_ITEMS], etc.).
 */
fun reconcileWholeDictationWithRawVoz(
    rawMergedVoz: String,
    candidateOutput: String,
    preset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH
): String {
    val rawTrimmed = rawMergedVoz.trim()
    val candTrimmed = candidateOutput.trim()
    if (rawTrimmed.isEmpty()) return candTrimmed
    if (candTrimmed.isEmpty()) {
        return GemmaTextProcessor.preNormalizeSpokenConstructs(rawTrimmed, preset).ifBlank { rawTrimmed }
    }
    if (preset != GemmaPromptPreset.CLEAN_AND_POLISH && preset != GemmaPromptPreset.STRICT_CLEANUP) {
        return candTrimmed
    }

    // Check existing tail & global lexical-anchor retention (exempts spoken backtracks & number formatting)
    if (GemmaTextProcessor.isSuspiciouslyTruncatedOutput(
            output = candTrimmed,
            fallbackInput = rawTrimmed,
            preset = preset
        )
    ) {
        return GemmaTextProcessor.preNormalizeSpokenConstructs(rawTrimmed, preset).ifBlank { rawTrimmed }
    }

    // Additionally verify that an early prefix segment was not lost when joining multi-chunk segments
    // (unless the speaker uttered a repair/backtrack cue that intentionally deleted the opening sentence).
    if (!GemmaTextProcessor.containsSpokenBacktrackCue(rawTrimmed)) {
        val rawTokens = rawTrimmed.split(WHITESPACE_SPLIT_REGEX)
            .map { extractWordCore(it).lowercase(Locale.ROOT) }
            .filter { it.length >= 4 && it.all { ch -> ch.isLetter() } }
        if (rawTokens.size >= 12) {
            val headAnchors = rawTokens.take(4)
            val candLower = candTrimmed.lowercase(Locale.ROOT)
            val headPreserved = headAnchors.any { anchor ->
                candLower.contains(anchor) || candLower.contains(anchor.substring(0, 4))
            }
            if (!headPreserved) {
                return GemmaTextProcessor.preNormalizeSpokenConstructs(rawTrimmed, preset).ifBlank { rawTrimmed }
            }
        }
    }

    return candTrimmed
}
