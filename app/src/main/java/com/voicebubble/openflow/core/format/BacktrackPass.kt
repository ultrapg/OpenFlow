package com.voicebubble.openflow.core.format

import java.util.Locale

/**
 * Resolves spoken self-corrections deterministically:
 *  - explicit deletion cues (`scratch that`, `streich das`) drop the preceding clause;
 *  - typed repairs (`Tuesday, wait no, Wednesday`, `at 3:30 I mean 4:15`, `8 GB make that 6 GB`)
 *    replace the earlier value only when both sides are the *same kind* of entity.
 * Anything else is left untouched and flagged through [unresolvedCue] so an LLM (if enabled) can
 * take over.
 */
internal class BacktrackPass(private val lang: String) {
    var edits = 0
        private set
    var unresolvedCue = false
        private set

    private class Entity(val type: String, val end: Int)

    private val dropCues: List<List<String>> = when (lang) {
        "de" -> listOf(listOf("streich", "das"), listOf("streiche", "das"), listOf("vergiss", "das"), listOf("lösch", "das"))
        "en" -> listOf(listOf("scratch", "that"), listOf("strike", "that"), listOf("delete", "that"))
        else -> emptyList()
    }
    private val unconditionalDrop = setOf("scratch that", "streich das")

    private val repairCues: List<List<String>> = when (lang) {
        "de" -> listOf(
            listOf("nein", "warte"), listOf("oder", "nein"), listOf("ich", "meine"), listOf("besser", "gesagt"),
            listOf("oder", "besser"), listOf("quatsch"), listOf("warte")
        )
        "en" -> listOf(
            listOf("wait", "no"), listOf("no", "wait"), listOf("i", "mean"), listOf("make", "that"),
            listOf("or", "rather"), listOf("sorry"), listOf("wait")
        )
        else -> emptyList()
    }
    private val skipBeforeY = setOf("no", "it's", "that's", "nein", "es", "ist")

    private val days: Set<String> = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"
    )
    private val months: Set<String> = EnglishGrammar.months.keys + GermanGrammar.months.keys
    private val units: Map<String, String> = EnglishGrammar.unitAbbreviations.values.associateWith { abbr ->
        when (abbr) {
            "GB", "MB", "KB", "TB" -> "BYTES"
            "kHz", "MHz", "GHz", "Hz" -> "FREQ"
            "km", "cm", "mm" -> "LENGTH"
            else -> abbr
        }
    }
    private val numberRegex = Regex("^\\d[\\d.,]*$")
    private val timeRegex = Regex("^\\d{1,2}:\\d{2}$")
    private val currencyRegex = Regex("^[$€£]\\d[\\d.,]*$")
    private val ampm = setOf("am", "pm", "uhr")

    fun apply(text: String): String {
        if (lang != "en" && lang != "de") return text
        var chunks = FormatChunk.split(text)
        var guard = 0
        // 1) deletion cues
        while (guard++ < 12) {
            val dropped = applyDrop(chunks) ?: break
            chunks = dropped
            edits++
        }
        // 2) typed repairs
        guard = 0
        var i = 0
        while (i < chunks.size && guard++ < 64) {
            val cueLen = repairCueLenAt(chunks, i)
            if (cueLen == 0) { i++; continue }
            val resolved = tryRepair(chunks, i, cueLen)
            if (resolved != null) {
                chunks = resolved.first
                i = resolved.second
                edits++
            } else {
                unresolvedCue = unresolvedCue || cueLooksLikeCorrection(chunks, i, cueLen)
                i += cueLen
            }
        }
        return FormatChunk.join(chunks)
    }

    private fun cueLooksLikeCorrection(chunks: List<FormatChunk>, i: Int, len: Int): Boolean {
        // Only a *punctuated* cue ("wait no," / ", I mean,") is likely a self-correction; flagging
        // everything would send ordinary prose such as "I mean it" to the LLM.
        val last = chunks[i + len - 1]
        return last.trail.isNotEmpty() || (i > 0 && chunks[i - 1].trail.isNotEmpty() && len >= 2)
    }

    private fun matchAt(chunks: List<FormatChunk>, i: Int, cue: List<String>): Boolean {
        if (i + cue.size > chunks.size) return false
        for (k in cue.indices) {
            val c = chunks[i + k]
            if (c.core.isEmpty() || c.lower != cue[k]) return false
            if (k > 0 && (c.lead.isNotEmpty() || c.hasNewlineBefore)) return false
            if (k < cue.size - 1 && c.trail.isNotEmpty()) return false
        }
        return true
    }

    private fun repairCueLenAt(chunks: List<FormatChunk>, i: Int): Int {
        for (cue in repairCues.sortedByDescending { it.size }) if (matchAt(chunks, i, cue)) return cue.size
        return 0
    }

    // ---- deletion cues -------------------------------------------------------------------------

    private fun applyDrop(chunks: List<FormatChunk>): MutableList<FormatChunk>? {
        for (i in chunks.indices) {
            for (cue in dropCues) {
                if (!matchAt(chunks, i, cue)) continue
                val last = chunks[i + cue.size - 1]
                val key = cue.joinToString(" ")
                val punctuated = last.trail.isNotEmpty() || i + cue.size >= chunks.size ||
                    (i > 0 && chunks[i - 1].trail.isNotEmpty())
                if (key !in unconditionalDrop && !punctuated) continue
                // clause start = after the previous sentence end / newline
                var start = i
                while (start > 0 && !endsSentence(chunks[start - 1].trail) && !chunks[start].hasNewlineBefore) start--
                val out = ArrayList<FormatChunk>(chunks.size)
                for (k in 0 until start) out.add(chunks[k])
                val next = i + cue.size
                val tail = ArrayList<FormatChunk>()
                for (k in next until chunks.size) tail.add(chunks[k])
                if (tail.isNotEmpty()) {
                    val first = tail[0]
                    if (start == 0) first.sepBefore = ""
                    if (start == 0 || endsSentence(out.lastOrNull()?.trail ?: "")) capitalizeChunk(first)
                } else if (out.isNotEmpty()) {
                    // everything after the cue was dropped as well; keep the sentence properly closed
                }
                out.addAll(tail)
                return out
            }
        }
        return null
    }

    // ---- typed repairs -------------------------------------------------------------------------

    private fun tryRepair(chunks: List<FormatChunk>, cueStart: Int, cueLen: Int): Pair<MutableList<FormatChunk>, Int>? {
        if (cueStart == 0) return null
        val cueEnd = cueStart + cueLen
        if (cueEnd >= chunks.size) return null
        val x = entityEndingAt(chunks, cueStart) ?: return null
        var yStart = cueEnd
        while (yStart < chunks.size && chunks[yStart].lower in skipBeforeY && chunks[yStart].core.isNotEmpty()) yStart++
        // "it is" two-word skip is covered by single-word skips above
        val y = entityAt(chunks, yStart, requireCapitalForProper = true) ?: return null
        if (y.type != x.type) return null
        val xStart = findEntityStart(chunks, cueStart, x)
        // Proper nouns must not be sentence initial on the X side
        if (x.type == "PROPER" && (xStart == 0 || endsSentence(chunks[xStart - 1].trail))) return null

        val out = ArrayList<FormatChunk>(chunks.size)
        for (k in 0 until xStart) out.add(chunks[k])
        val yChunks = ArrayList<FormatChunk>()
        for (k in yStart until y.end) yChunks.add(chunks[k].copyChunk())
        yChunks[0].sepBefore = chunks[xStart].sepBefore
        yChunks[0].lead = chunks[xStart].lead
        out.addAll(yChunks)
        val resumeAt = out.size
        for (k in y.end until chunks.size) out.add(chunks[k])
        return out to resumeAt
    }

    private fun findEntityStart(chunks: List<FormatChunk>, endExclusive: Int, e: Entity): Int {
        for (start in maxOf(0, endExclusive - 3)..endExclusive - 1) {
            val cand = entityAt(chunks, start, requireCapitalForProper = false)
            if (cand != null && cand.end == endExclusive && cand.type == e.type) return start
        }
        return endExclusive - 1
    }

    private fun entityEndingAt(chunks: List<FormatChunk>, endExclusive: Int): Entity? {
        for (start in maxOf(0, endExclusive - 3)..endExclusive - 1) {
            val cand = entityAt(chunks, start, requireCapitalForProper = false) ?: continue
            if (cand.end != endExclusive) continue
            // inner chunks of the entity must not carry punctuation
            var ok = true
            for (k in start until endExclusive - 1) if (chunks[k].trail.isNotEmpty()) ok = false
            if (!chunks[endExclusive - 1].trail.all { it == ',' || it == ' ' }) ok = false
            if (ok) return cand
        }
        return null
    }

    private fun entityAt(chunks: List<FormatChunk>, i: Int, requireCapitalForProper: Boolean): Entity? {
        val c = chunks.getOrNull(i) ?: return null
        if (c.core.isEmpty()) return null
        val w = c.lower
        if (i > 0 && c.lead.isNotEmpty() && c.lead.any { it == '"' || it == '(' }) return null
        val next = chunks.getOrNull(i + 1)
        val nextOk = next != null && next.core.isNotEmpty() && next.lead.isEmpty() && !next.hasNewlineBefore && c.trail.isEmpty()

        if (w in months) {
            if (nextOk && next!!.core.matches(Regex("^\\d{1,2}$"))) return Entity("DATE", i + 2)
            return if (c.core.first().isUpperCase() || lang == "de") Entity("MONTH", i + 1) else null
        }
        if (w in days) return Entity("DAY", i + 1)
        if (currencyRegex.matches(c.core)) return Entity("CUR", i + 1)
        if (c.core.endsWith("%") && numberRegex.matches(c.core.dropLast(1))) return Entity("PCT", i + 1)
        if (timeRegex.matches(c.core)) {
            return if (nextOk && next!!.lower in ampm) Entity("TIME", i + 2) else Entity("TIME", i + 1)
        }
        if (numberRegex.matches(c.core)) {
            if (nextOk) {
                val nl = next!!.lower
                if (nl in ampm) return Entity("TIME", i + 2)
                units[next.core]?.let { return Entity("QTY:$it", i + 2) }
                val measure = EnglishGrammar.measureWords + GermanGrammar.measureWords
                if (nl in measure) return Entity("QTY:$nl", i + 2)
            }
            return Entity("NUM", i + 1)
        }
        val first = c.core.first()
        if (first.isUpperCase() && c.core.length >= 2 && c.core.drop(1).all { it.isLetter() } &&
            (!requireCapitalForProper || true)
        ) {
            if (w in setOf("i", "the", "a", "an", "and", "but", "or", "so", "then", "it", "we", "he", "she", "they", "you")) return null
            return Entity("PROPER", i + 1)
        }
        return null
    }
}
