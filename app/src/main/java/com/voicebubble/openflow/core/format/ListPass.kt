package com.voicebubble.openflow.core.format

import java.util.Locale

/**
 * Turns dictated enumerations (`first ... second ... third ...`, `number one ... number two ...`,
 * `erstens ... zweitens ...`, `bullet point ... bullet point ...`) into vertical `1.` / `-` lists.
 *
 * A list is only built when at least two consecutive markers starting at 1 are found and every
 * item has content, so phrases like "I came in second" are never touched.
 */
internal class ListPass(private val lang: String) {
    var edits = 0
        private set

    private class Marker(val n: Int, val start: Int, val end: Int)

    private val enOrdinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "firstly" to 1, "secondly" to 2, "thirdly" to 3, "fourthly" to 4, "fifthly" to 5
    )
    private val deOrdinals = mapOf(
        "erstens" to 1, "zweitens" to 2, "drittens" to 3, "viertens" to 4, "fünftens" to 5, "sechstens" to 6
    )
    private val enNumbers = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7, "8" to 8
    )
    private val deNumbers = mapOf(
        "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7, "8" to 8
    )
    private val enNumberLead = setOf("number", "item", "step")
    private val deNumberLead = setOf("punkt", "nummer", "schritt")

    private val firstBlockers = setOf("at", "the", "my", "your", "his", "her", "their", "our", "in", "for", "a", "of", "this", "that")
    private val laterBlockers = setOf("a", "the", "one", "per", "every", "each", "last", "next", "this", "that", "another", "half", "in", "for", "of")
    private val trailingConnectors = setOf("and", "und", "then", "dann", "also", "plus")

    fun apply(text: String): String {
        if (lang != "en" && lang != "de") return text
        val chunks = FormatChunk.split(text)
        if (chunks.size < 4) return text
        return orderedList(chunks) ?: bulletList(chunks) ?: text
    }

    private fun numberOf(w: String): Int? = if (lang == "de") deNumbers[w] else enNumbers[w]

    private fun markerAt(chunks: List<FormatChunk>, i: Int): Marker? {
        val c = chunks[i]
        if (c.core.isEmpty()) return null
        val w = c.lower
        val ordinals = if (lang == "de") deOrdinals else enOrdinals
        ordinals[w]?.let { n ->
            var end = i + 1
            if (lang == "en" && n == 1 && chunks.lowerAt(end) == "of" && chunks.lowerAt(end + 1) == "all") end += 2
            return Marker(n, i, end)
        }
        val leads = if (lang == "de") deNumberLead else enNumberLead
        if (w in leads) {
            val nn = numberOf(chunks.lowerAt(i + 1))
            if (nn != null && chunks[i].trail.isEmpty() && chunks.joinable(i, i + 2)) return Marker(nn, i, i + 2)
        }
        return null
    }

    private fun prevAllowed(chunks: List<FormatChunk>, m: Marker, isFirst: Boolean): Boolean {
        val i = m.start
        if (i == 0) return true
        val prev = chunks[i - 1]
        val c = chunks[i]
        if (c.hasNewlineBefore || prev.trail.isNotEmpty()) return true
        val pw = prev.lower
        if (pw.firstOrNull()?.isDigit() == true) return false
        if (lang == "en" && (EnglishGrammar.isNumberWord(pw))) return false
        val blockers = if (isFirst) firstBlockers else laterBlockers
        if (lang == "en" && pw in blockers) return false
        if (lang == "de" && pw in setOf("der", "die", "das", "ein", "eine", "im", "am", "zum")) return false
        // "first/second" used as a bare ordinal adjective directly before a noun is not a list marker
        // when the marker is `number one`-style; those carry their own guard above.
        return true
    }

    private fun orderedList(chunks: MutableList<FormatChunk>): String? {
        var firstIdx = -1
        for (i in chunks.indices) {
            val m = markerAt(chunks, i) ?: continue
            if (m.n == 1 && prevAllowed(chunks, m, true)) { firstIdx = i; break }
        }
        if (firstIdx < 0) return null
        val markers = ArrayList<Marker>()
        markers.add(markerAt(chunks, firstIdx)!!)
        var cursor = markers[0].end
        var want = 2
        while (cursor < chunks.size) {
            var found: Marker? = null
            var k = cursor
            while (k < chunks.size) {
                val m = markerAt(chunks, k)
                if (m != null && m.n == want && prevAllowed(chunks, m, false)) { found = m; break }
                k++
            }
            if (found == null) break
            markers.add(found)
            cursor = found.end
            want++
        }
        if (markers.size < 2) return null
        return buildList(chunks, markers, firstIdx) { n -> "$n. " }
    }

    private fun bulletList(chunks: MutableList<FormatChunk>): String? {
        val markers = ArrayList<Marker>()
        var i = 0
        while (i < chunks.size) {
            if (chunks.lowerAt(i) == "bullet" && chunks.lowerAt(i + 1) == "point" && chunks.joinable(i, i + 2)) {
                markers.add(Marker(markers.size + 1, i, i + 2)); i += 2
            } else i++
        }
        if (markers.size < 2) return null
        return buildList(chunks, markers, markers[0].start) { "- " }
    }

    private fun buildList(
        chunks: List<FormatChunk>,
        markers: List<Marker>,
        firstIdx: Int,
        prefix: (Int) -> String
    ): String? {
        val items = ArrayList<String>()
        var remainder = ""
        for (idx in markers.indices) {
            val m = markers[idx]
            val bodyStart = m.end
            val limit = if (idx + 1 < markers.size) markers[idx + 1].start else chunks.size
            var bodyEnd = limit
            if (idx == markers.lastIndex) {
                // the last item stops at the first sentence end so trailing prose is not swallowed
                for (k in bodyStart until limit) {
                    if (endsSentence(chunks[k].trail) && k < limit - 1 && chunks[k + 1].core.isNotEmpty()) {
                        bodyEnd = k + 1
                        break
                    }
                }
                if (bodyEnd < limit) {
                    remainder = FormatChunk.join(chunks.subList(bodyEnd, limit)).trim()
                }
            }
            val body = ArrayList<FormatChunk>()
            for (k in bodyStart until bodyEnd) body.add(chunks[k].copyChunk())
            // marker trail punctuation (",") belongs to the marker, not the item
            while (body.isNotEmpty() && body.last().core.isNotEmpty() && body.last().lower in trailingConnectors) {
                body.removeAt(body.lastIndex)
            }
            if (body.isEmpty()) return null
            val last = body.last()
            last.trail = last.trail.trimEnd(',', ';', ' ')
            if (idx < markers.lastIndex && !endsSentence(last.trail)) {
                // drop the connector punctuation entirely between items
                last.trail = last.trail.trimEnd(',', ';')
            }
            body[0].sepBefore = ""
            capitalizeChunk(body[0])
            val itemText = FormatChunk.join(body).trim()
            if (!itemText.any { it.isLetterOrDigit() }) return null
            items.add(itemText)
        }
        val preamble = FormatChunk.join(chunks.subList(0, firstIdx)).trim()
        val sb = StringBuilder()
        if (preamble.isNotEmpty()) {
            val lastCh = preamble.last()
            sb.append(
                when {
                    lastCh == ':' || lastCh == '.' || lastCh == '!' || lastCh == '?' -> preamble
                    lastCh == ',' || lastCh == ';' -> preamble.dropLast(1) + ":"
                    else -> "$preamble:"
                }
            )
            sb.append('\n')
        }
        items.forEachIndexed { idx, item ->
            if (idx > 0) sb.append('\n')
            sb.append(prefix(idx + 1)).append(item)
        }
        if (remainder.isNotEmpty()) sb.append('\n').append(remainder)
        edits++
        return sb.toString()
    }
}
