package com.voicebubble.openflow.core.format

import java.util.Locale

/**
 * One whitespace-delimited token of dictated text, split into the punctuation that surrounds the
 * word ([lead] / [trail]) and the word itself ([core]). [sepBefore] keeps the exact whitespace
 * (including newlines) that preceded the token, so a chunk list always re-joins losslessly.
 *
 * A chunk whose [core] is empty is a pure-punctuation barrier (for example an em dash); no pass
 * may merge across it.
 */
internal class FormatChunk(
    var sepBefore: String,
    var lead: String,
    var core: String,
    var trail: String
) {
    val lower: String get() = core.lowercase(Locale.ROOT)
    val hasNewlineBefore: Boolean get() = sepBefore.indexOf('\n') >= 0

    fun render(): String = sepBefore + lead + core + trail

    fun copyChunk(): FormatChunk = FormatChunk(sepBefore, lead, core, trail)

    companion object {
        private val TOKEN_REGEX = Regex("(\\s*)(\\S+)")
        private const val CORE_EDGE_SYMBOLS = "%°+#$€£¥"

        private fun isCoreChar(c: Char): Boolean = c.isLetterOrDigit() || CORE_EDGE_SYMBOLS.indexOf(c) >= 0

        fun split(text: String): MutableList<FormatChunk> {
            val out = ArrayList<FormatChunk>()
            val trailingWs = text.takeLastWhile { it.isWhitespace() }
            for (m in TOKEN_REGEX.findAll(text)) {
                val sep = m.groupValues[1]
                val token = m.groupValues[2]
                var start = 0
                while (start < token.length && !isCoreChar(token[start])) start++
                if (start == token.length) {
                    out.add(FormatChunk(sep, token, "", ""))
                    continue
                }
                var end = token.length
                while (end > start && !isCoreChar(token[end - 1])) end--
                out.add(FormatChunk(sep, token.substring(0, start), token.substring(start, end), token.substring(end)))
            }
            if (trailingWs.isNotEmpty() && out.isNotEmpty()) {
                out.last().trail += trailingWs
            }
            return out
        }

        fun join(chunks: List<FormatChunk>): String {
            val sb = StringBuilder()
            for (c in chunks) sb.append(c.render())
            return sb.toString()
        }
    }
}

/** True when chunks `[a, b)` form one uninterrupted phrase (no punctuation or line break inside). */
internal fun List<FormatChunk>.joinable(a: Int, b: Int): Boolean {
    if (a < 0 || b > size || a >= b) return false
    for (k in a until b) {
        val c = this[k]
        if (c.core.isEmpty()) return false
        if (k > a) {
            if (c.lead.isNotEmpty() || c.hasNewlineBefore) return false
        }
        if (k < b - 1 && c.trail.isNotEmpty()) return false
    }
    return true
}

internal fun List<FormatChunk>.lowerAt(i: Int): String = if (i in indices) this[i].lower else ""

/** Uppercases the first letter of [chunk]'s core (used to heal sentence starts after removals). */
internal fun capitalizeChunk(chunk: FormatChunk) {
    val idx = chunk.core.indexOfFirst { it.isLetter() }
    if (idx >= 0 && chunk.core[idx].isLowerCase()) {
        chunk.core = chunk.core.substring(0, idx) +
            chunk.core[idx].titlecase(Locale.ROOT) + chunk.core.substring(idx + 1)
    }
}

internal fun endsSentence(trail: String): Boolean = trail.any { it == '.' || it == '!' || it == '?' || it == '…' }
