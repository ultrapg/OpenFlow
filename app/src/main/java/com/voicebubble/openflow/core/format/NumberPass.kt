package com.voicebubble.openflow.core.format

import java.util.Locale

/**
 * Deterministic Inverse Text Normalization: spoken numbers, decimals, percentages, currencies,
 * units, clock times, dates, ordinals, years and version numbers become their written forms.
 *
 * Conservative by design: ambiguous runs (`one two three`, `twenty twenty` without context) are
 * left untouched, and stand-alone `zero`..`nine` stay spelled out in prose unless they carry a
 * unit, currency, time, decimal or an identifier context (`version two`, `Gemma four`).
 */
internal class NumberPass(private val g: NumberGrammar) {
    private val isGerman = g.code == "de"

    private val yearContext = setOf("in", "since", "until", "during", "year", "from", "before", "after", "im", "seit", "bis", "jahr")
    private val timeContext = setOf("at", "by", "until", "till", "around", "from", "before", "after")
    private val scaleWords = setOf("thousand", "million", "billion")
    private val germanDateDeterminers = setOf("der", "die", "das", "am", "den", "dem", "zum", "zur", "im", "vom", "bis", "ab", "seit", "vor", "nach")

    var edits = 0
        private set

    fun apply(input: List<FormatChunk>): MutableList<FormatChunk> {
        val chunks = expandHyphenated(input)
        val out = ArrayList<FormatChunk>(chunks.size)
        var i = 0
        while (i < chunks.size) {
            val c = chunks[i]
            if (c.core.isEmpty()) { out.add(c); i++; continue }
            val replaced = try { tryReplace(chunks, i, out) } catch (_: Exception) { null }
            if (replaced == null) {
                out.add(c); i++
            } else {
                out.add(replaced.first)
                edits++
                i = replaced.second
            }
        }
        return out
    }

    /** `twenty-five` -> `twenty` `five` so the grammar sees separate words. */
    private fun expandHyphenated(chunks: List<FormatChunk>): List<FormatChunk> {
        if (isGerman || chunks.none { it.core.contains('-') }) return chunks
        val out = ArrayList<FormatChunk>(chunks.size + 4)
        for (c in chunks) {
            val core = c.core
            if (!core.contains('-') || core.startsWith("-") || core.endsWith("-")) { out.add(c); continue }
            val parts = core.split('-')
            val ok = parts.size in 2..4 && parts.all { p ->
                val lp = p.lowercase(Locale.ROOT)
                g.isNumberWord(lp) || g.parseOrdinal(listOf(FormatChunk("", "", lp, "")), 0) != null
            }
            if (!ok) { out.add(c); continue }
            parts.forEachIndexed { idx, p ->
                out.add(
                    FormatChunk(
                        sepBefore = if (idx == 0) c.sepBefore else " ",
                        lead = if (idx == 0) c.lead else "",
                        core = p,
                        trail = if (idx == parts.lastIndex) c.trail else ""
                    )
                )
            }
        }
        return out
    }

    private fun prevWord(out: List<FormatChunk>, current: FormatChunk): String {
        val p = out.lastOrNull() ?: return ""
        if (p.trail.isNotEmpty()) return ""
        if (current.hasNewlineBefore) return ""
        return p.lower
    }

    private fun merged(first: FormatChunk, lastIdx: Int, chunks: List<FormatChunk>, text: String): FormatChunk =
        FormatChunk(first.sepBefore, first.lead, text, chunks[lastIdx].trail)

    private fun tryReplace(chunks: List<FormatChunk>, i: Int, out: List<FormatChunk>): Pair<FormatChunk, Int>? {
        val c = chunks[i]
        val w = c.lower
        val prev = prevWord(out, c)

        // ---- Dates -------------------------------------------------------------------------
        if (!isGerman) englishDate(chunks, i)?.let { return it }
        if (isGerman) germanDate(chunks, i)?.let { return it }

        // ---- Spoken year in context (EN): "in twenty twenty six" ----------------------------
        if (!isGerman && prev in yearContext) {
            EnglishGrammar.parseSpokenYear(chunks, i)?.let { (year, end) ->
                if (year in 1900..2099 && chunks.joinable(i, end)) {
                    return merged(c, end - 1, chunks, year.toString()) to end
                }
            }
        }

        // ---- Ordinals >= 10 ("the twenty fourth") ------------------------------------------
        g.parseOrdinal(chunks, i)?.let { (v, end) ->
            if (v >= 10 && chunks.joinable(i, end) && ordinalAllowedStandalone(chunks, i, prev, w)) {
                val text = if (isGerman) "$v." else v.toString() + englishOrdinalSuffix(v)
                return merged(c, end - 1, chunks, text) to end
            }
        }

        // ---- Cardinals ---------------------------------------------------------------------
        if (!g.isNumberWord(w)) return null
        val np = g.parseCardinal(chunks, i) ?: return null
        if (!chunks.joinable(i, np.end)) return null
        var end = np.end

        // adjacency: another number word right after => times, otherwise ambiguous, leave alone
        val nextW = chunks.lowerAt(end)
        val adjacentNumber = (g.isNumberWord(nextW) || nextW == "oh") && end < chunks.size && chunks.joinable(end - 1, end + 1)
        if (!isGerman && np.fraction == null) {
            englishTime(chunks, i, np, prev)?.let { return it }
        }
        val scaleAfterDecimal = np.fraction != null && nextW in scaleWords
        if (adjacentNumber && g.isNumberWord(nextW) && !scaleAfterDecimal) {
            var j = end
            while (g.isNumberWord(chunks.lowerAt(j)) && chunks.joinable(j - 1, j + 1)) j++
            // consume the whole ambiguous run unchanged by signalling "no replacement" for the first
            // chunk; the following chunks would re-trigger, so emit a verbatim marker instead.
            return verbatimRun(chunks, i, j)
        }

        if (isGerman && np.fraction == null) {
            germanClock(chunks, i, np)?.let { return it }
        }

        val identifier = prev in g.identifierContextWords
        val yearFlag = np.value in 1000..2100 && np.fraction == null && prev in yearContext
        var fractionDigits = np.fraction
        // version chains: "version two point one seven" / "two point five point one"
        if (!isGerman && (identifier || fractionDigits != null)) {
            while (chunks.lowerAt(end) == "point" && chunks.joinable(end - 1, end + 2) &&
                g.digitWord(chunks.lowerAt(end + 1)) != null
            ) {
                val sb = StringBuilder()
                var k = end + 1
                while (g.digitWord(chunks.lowerAt(k)) != null && chunks.joinable(k - 1, k + 1)) {
                    sb.append(g.digitWord(chunks.lowerAt(k))); k++
                }
                fractionDigits = (fractionDigits ?: "") + "." + sb
                end = k
            }
        }
        val versionLike = fractionDigits?.contains('.') == true || (identifier && (prev == "version" || prev == "v"))
        val grouping = np.value >= 1000 && !yearFlag && !identifier && !versionLike

        fun numberText(): String {
            val intPart = formatWithGroups(np.value, g.groupSeparator, grouping)
            val dec = if (versionLike) '.' else g.decimalSeparator
            return if (fractionDigits != null) intPart + dec + fractionDigits else intPart
        }

        val after = chunks.lowerAt(end)
        val canAttach = end < chunks.size && chunks.joinable(end - 1, end + 1)

        // scale word after decimal ("two point five million [dollars]")
        if (!isGerman && np.fraction != null && canAttach && after in scaleWords) {
            val scaleEnd = end + 1
            val cur = currencySymbolEn(chunks.lowerAt(scaleEnd))
            if (cur != null && chunks.joinable(scaleEnd - 1, scaleEnd + 1)) {
                return merged(c, scaleEnd, chunks, "$cur${numberText()} $after") to (scaleEnd + 1)
            }
            return merged(c, scaleEnd - 1, chunks, "${numberText()} $after") to scaleEnd
        }

        if (canAttach && after in g.percentWords) {
            return merged(c, end, chunks, numberText() + "%") to (end + 1)
        }

        // currency
        if (canAttach) {
            currency(chunks, i, c, np, end, ::numberText)?.let { return it }
        }

        if (canAttach && !isGerman && after in setOf("am", "pm") ) {
            return merged(c, end, chunks, numberText() + " " + after.uppercase(Locale.ROOT)) to (end + 1)
        }
        if (canAttach && (after == "a.m" || after == "p.m")) {
            return merged(c, end, chunks, numberText() + " " + after.replace(".", "").uppercase(Locale.ROOT)) to (end + 1)
        }

        if (canAttach && (after == "degrees" || after == "grad") &&
            chunks.lowerAt(end + 1).let { it == "celsius" || it == "fahrenheit" } && chunks.joinable(end, end + 2)
        ) {
            val sym = if (chunks.lowerAt(end + 1) == "celsius") "°C" else "°F"
            return merged(c, end + 1, chunks, numberText() + sym) to (end + 2)
        }
        if (canAttach) {
            g.unitAbbreviations[after]?.let { abbr ->
                return merged(c, end, chunks, numberText() + " " + abbr) to (end + 1)
            }
        }

        val measure = canAttach && after in g.measureWords
        val forceDigits = measure || identifier || versionLike || fractionDigits != null || np.value >= 10
        if (!forceDigits) return null
        return merged(c, end - 1, chunks, numberText()) to end
    }

    private fun verbatimRun(chunks: List<FormatChunk>, from: Int, to: Int): Pair<FormatChunk, Int> {
        // Re-emit the run as a single synthetic chunk with identical rendering so later chunks are
        // not re-parsed individually. Rendering is preserved exactly.
        val sb = StringBuilder()
        for (k in from until to) {
            val c = chunks[k]
            sb.append(if (k == from) "" else c.sepBefore).append(c.lead).append(c.core).append(c.trail)
        }
        val first = chunks[from]
        return FormatChunk(first.sepBefore, "", sb.toString(), "") to to
    }

    private fun ordinalAllowedStandalone(chunks: List<FormatChunk>, i: Int, prev: String, w: String): Boolean {
        if (isGerman) {
            val c = chunks[i]
            if (c.core.first().isUpperCase() && i > 0 && !endsSentence(chunks[i - 1].trail)) return false
            return prev in germanDateDeterminers
        }
        return true
    }

    private fun englishOrdinalSuffix(v: Long): String {
        val mod100 = v % 100
        if (mod100 in 11..13) return "th"
        return when (v % 10) { 1L -> "st"; 2L -> "nd"; 3L -> "rd"; else -> "th" }
    }

    private fun currencySymbolEn(w: String): String? = when (w) {
        "dollars", "dollar" -> "$"
        "euros", "euro" -> "€"
        else -> null
    }

    private fun currency(
        chunks: List<FormatChunk>, i: Int, c: FormatChunk, np: NumParse, end: Int, numberText: () -> String
    ): Pair<FormatChunk, Int>? {
        val after = chunks.lowerAt(end)
        if (!isGerman) {
            val sym = currencySymbolEn(after) ?: return null
            var last = end
            var text = sym + numberText()
            if (np.fraction != null && np.fraction.length == 1) text += "0"
            if (chunks.lowerAt(end + 1) == "and" && chunks.joinable(end, end + 2)) {
                val cents = EnglishGrammar.parseCardinal(chunks, end + 2)
                if (cents != null && cents.fraction == null && cents.value in 1..99 &&
                    chunks.lowerAt(cents.end).let { it == "cents" || it == "cent" } &&
                    chunks.joinable(end, cents.end + 1)
                ) {
                    text = sym + formatWithGroups(np.value, ',', np.value >= 1000) + "." + cents.value.toString().padStart(2, '0')
                    last = cents.end
                }
            }
            return merged(c, last, chunks, text) to (last + 1)
        }
        if (after != "euro" && after != "euros") return null
        var last = end
        var text = numberText() + " €"
        val hasUnd = chunks.lowerAt(end + 1) == "und"
        val centsStart = if (hasUnd) end + 2 else end + 1
        if (chunks[end].trail.isEmpty() && centsStart < chunks.size && chunks.joinable(end, centsStart + 1)) {
            val cents = GermanGrammar.parseCardinal(chunks, centsStart)
            if (cents != null && cents.fraction == null && cents.value in 0..99 && chunks.joinable(end, cents.end)) {
                val explicitCent = chunks.lowerAt(cents.end).let { it == "cent" || it == "cents" } &&
                    chunks.joinable(cents.end - 1, cents.end + 1)
                val endsClause = cents.end >= chunks.size || chunks[cents.end - 1].trail.isNotEmpty()
                if (explicitCent || (!hasUnd && endsClause)) {
                    text = formatWithGroups(np.value, '.', np.value >= 1000) + "," +
                        cents.value.toString().padStart(2, '0') + " €"
                    last = if (explicitCent) cents.end else cents.end - 1
                }
            }
        }
        return merged(c, last, chunks, text) to (last + 1)
    }

    // ---- English dates -------------------------------------------------------------------------

    private fun englishDate(chunks: List<FormatChunk>, i: Int): Pair<FormatChunk, Int>? {
        val c = chunks[i]
        val month = EnglishGrammar.months[c.lower] ?: return null
        if (c.lower == "may" && !c.core.first().isUpperCase()) return null
        if (i + 1 >= chunks.size) return null
        if (c.trail.isNotEmpty()) return null
        var day: Long? = null
        var dayEnd = i + 1
        EnglishGrammar.parseOrdinal(chunks, i + 1)?.let { (v, e) -> if (v in 1..31) { day = v; dayEnd = e } }
        if (day == null) {
            val np = EnglishGrammar.parseCardinal(chunks, i + 1)
            if (np != null && np.fraction == null && np.value in 1..31 && chunks.lowerAt(np.end) != "point") {
                day = np.value; dayEnd = np.end
            }
        }
        val d = day ?: return null
        if (!chunks.joinable(i, dayEnd)) return null
        var lastIdx = dayEnd - 1
        var text = "$month $d"
        if (chunks[lastIdx].trail.isEmpty() && dayEnd < chunks.size && chunks.joinable(lastIdx, dayEnd + 1)) {
            val sy = EnglishGrammar.parseSpokenYear(chunks, dayEnd)
            if (sy != null && chunks.joinable(dayEnd, sy.second)) {
                text += ", ${sy.first}"; lastIdx = sy.second - 1
            } else {
                val np = EnglishGrammar.parseCardinal(chunks, dayEnd)
                if (np != null && np.fraction == null && np.value in 1000..2100 && chunks.joinable(dayEnd, np.end)) {
                    text += ", ${np.value}"; lastIdx = np.end - 1
                } else if (chunks[dayEnd].core.matches(Regex("(?:19|20)\\d{2}")) && chunks[dayEnd].lead.isEmpty()) {
                    text += ", ${chunks[dayEnd].core}"; lastIdx = dayEnd
                }
            }
        }
        return merged(c, lastIdx, chunks, text) to (lastIdx + 1)
    }

    // ---- German dates and clock ----------------------------------------------------------------

    private fun germanDate(chunks: List<FormatChunk>, i: Int): Pair<FormatChunk, Int>? {
        val c = chunks[i]
        if (c.trail.isNotEmpty() || c.core.isEmpty()) return null
        val ord = GermanGrammar.parseOrdinal(chunks, i) ?: return null
        val (day, e) = ord
        if (day !in 1..31) return null
        val monthChunk = chunks.getOrNull(e) ?: return null
        val month = GermanGrammar.months[monthChunk.lower] ?: return null
        if (!chunks.joinable(i, e + 1)) return null
        var lastIdx = e
        var text = "$day. $month"
        if (monthChunk.trail.isEmpty() && e + 1 < chunks.size && chunks.joinable(e, e + 2)) {
            val a = GermanGrammar.parseCardinal(chunks, e + 1)
            if (a != null && a.fraction == null) {
                if (a.value in 1000..2100) { text += " ${a.value}"; lastIdx = a.end - 1 }
                else if (a.value in 19..20 && chunks.joinable(e + 1, a.end + 1)) {
                    val b = GermanGrammar.parseCardinal(chunks, a.end)
                    if (b != null && b.fraction == null && b.value in 10..99) {
                        text += " ${a.value * 100 + b.value}"; lastIdx = b.end - 1
                    }
                }
            }
        }
        return merged(c, lastIdx, chunks, text) to (lastIdx + 1)
    }

    private fun germanClock(chunks: List<FormatChunk>, i: Int, np: NumParse): Pair<FormatChunk, Int>? {
        if (np.value !in 0..24) return null
        val end = np.end
        if (chunks.lowerAt(end) != "uhr" || !chunks.joinable(end - 1, end + 1)) return null
        val c = chunks[i]
        var last = end
        var text = "${np.value} Uhr"
        if (chunks[end].trail.isEmpty() && end + 1 < chunks.size && chunks.joinable(end, end + 2)) {
            val m = GermanGrammar.parseCardinal(chunks, end + 1)
            if (m != null && m.fraction == null && m.value in 1..59 && chunks.joinable(end, m.end)) {
                text = "${np.value}:${m.value.toString().padStart(2, '0')} Uhr"
                last = m.end - 1
            }
        }
        return merged(c, last, chunks, text) to (last + 1)
    }

    // ---- English clock times -------------------------------------------------------------------

    private fun ampmAt(chunks: List<FormatChunk>, i: Int): Pair<String, Int>? {
        val l = chunks.lowerAt(i)
        return when (l) {
            "am", "a.m" -> "AM" to (i + 1)
            "pm", "p.m" -> "PM" to (i + 1)
            else -> null
        }
    }

    private fun englishTime(chunks: List<FormatChunk>, i: Int, np: NumParse, prev: String): Pair<FormatChunk, Int>? {
        val h = np.value
        val end = np.end
        if (h !in 1..23) return null
        if (!chunks.joinable(i, end)) return null
        var minute: Long? = null
        var minEnd = end
        if (chunks.lowerAt(end) == "oh" && chunks.joinable(end - 1, end + 2)) {
            val u = EnglishGrammar.parseCardinal(chunks, end + 1)
            if (u != null && u.fraction == null && u.value in 1..9 && u.end == end + 2) {
                minute = u.value; minEnd = u.end
            }
        } else if (EnglishGrammar.isNumberWord(chunks.lowerAt(end)) && chunks.joinable(end - 1, end + 1)) {
            val mp = EnglishGrammar.parseCardinal(chunks, end)
            if (mp != null && mp.fraction == null && mp.value in 10..59 && chunks.joinable(end, mp.end)) {
                minute = mp.value; minEnd = mp.end
            }
        }
        if (minute == null) return null
        val marker = if (chunks[minEnd - 1].trail.isEmpty() && minEnd < chunks.size) ampmAt(chunks, minEnd) else null
        if (marker != null && h in 1..12 && chunks.joinable(minEnd - 1, minEnd + 1)) {
            val text = "$h:${minute.toString().padStart(2, '0')} ${marker.first}"
            return merged(chunks[i], minEnd, chunks, text) to (minEnd + 1)
        }
        if (marker == null && prev in timeContext) {
            val text = "$h:${minute.toString().padStart(2, '0')}"
            return merged(chunks[i], minEnd - 1, chunks, text) to minEnd
        }
        return null
    }
}
