package com.voicebubble.openflow.core.format

import java.util.Locale

/** Result of parsing a spoken number starting at a chunk index. [end] is exclusive. */
internal class NumParse(val value: Long, val end: Int, val fraction: String? = null)

/** Language-specific spoken-number grammar plus the lexicons the formatting passes need. */
internal interface NumberGrammar {
    val code: String
    val decimalSeparator: Char
    val groupSeparator: Char

    /** True if [w] (lowercase) can start or continue a spoken cardinal. */
    fun isNumberWord(w: String): Boolean

    /** Parses a cardinal (with optional spoken decimals) at chunk [i], or null. */
    fun parseCardinal(ch: List<FormatChunk>, i: Int): NumParse?

    /** Parses an ordinal at [i] (`twenty fourth`, `vierundzwanzigsten`) or null. Returns value + exclusive end. */
    fun parseOrdinal(ch: List<FormatChunk>, i: Int): Pair<Long, Int>?

    /** `zero`..`nine` spoken digit word to its digit value, else null. */
    fun digitWord(w: String): Int?

    val months: Map<String, String>
    val percentWords: Set<String>
    val unitAbbreviations: Map<String, String>
    val measureWords: Set<String>
    val identifierContextWords: Set<String>
}

internal fun formatWithGroups(value: Long, groupSep: Char, grouping: Boolean): String {
    val digits = value.toString()
    if (!grouping || digits.length <= 3) return digits
    val sb = StringBuilder()
    val first = digits.length % 3
    if (first > 0) sb.append(digits, 0, first)
    var i = first
    while (i < digits.length) {
        if (sb.isNotEmpty()) sb.append(groupSep)
        sb.append(digits, i, i + 3)
        i += 3
    }
    return sb.toString()
}

private val COMMON_UNITS = mapOf(
    "gigabyte" to "GB", "gigabytes" to "GB", "megabyte" to "MB", "megabytes" to "MB",
    "kilobyte" to "KB", "kilobytes" to "KB", "terabyte" to "TB", "terabytes" to "TB",
    "millisecond" to "ms", "milliseconds" to "ms", "kilohertz" to "kHz", "megahertz" to "MHz",
    "gigahertz" to "GHz", "hertz" to "Hz", "kilometer" to "km", "kilometers" to "km",
    "kilometre" to "km", "kilometres" to "km", "kilometer" to "km", "centimeter" to "cm",
    "centimeters" to "cm", "centimetre" to "cm", "centimetres" to "cm", "millimeter" to "mm",
    "millimeters" to "mm", "millimetre" to "mm", "millimetres" to "mm", "kilogram" to "kg",
    "kilograms" to "kg", "kilogramm" to "kg", "milliliter" to "ml", "milliliters" to "ml",
    "milliliter" to "ml", "megapixel" to "MP", "megapixels" to "MP", "megapixel" to "MP"
)

internal object EnglishGrammar : NumberGrammar {
    override val code = "en"
    override val decimalSeparator = '.'
    override val groupSeparator = ','

    private val UNITS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
        "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L,
        "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L
    )
    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fourty" to 40L, "fifty" to 50L, "sixty" to 60L,
        "seventy" to 70L, "eighty" to 80L, "ninety" to 90L
    )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ORD_UNITS = mapOf(
        "first" to 1L, "second" to 2L, "third" to 3L, "fourth" to 4L, "fifth" to 5L, "sixth" to 6L,
        "seventh" to 7L, "eighth" to 8L, "ninth" to 9L, "tenth" to 10L, "eleventh" to 11L,
        "twelfth" to 12L, "thirteenth" to 13L, "fourteenth" to 14L, "fifteenth" to 15L,
        "sixteenth" to 16L, "seventeenth" to 17L, "eighteenth" to 18L, "nineteenth" to 19L
    )
    private val ORD_TENS = mapOf(
        "twentieth" to 20L, "thirtieth" to 30L, "fortieth" to 40L, "fiftieth" to 50L,
        "sixtieth" to 60L, "seventieth" to 70L, "eightieth" to 80L, "ninetieth" to 90L
    )

    override val months = mapOf(
        "january" to "January", "february" to "February", "march" to "March", "april" to "April",
        "may" to "May", "june" to "June", "july" to "July", "august" to "August",
        "september" to "September", "october" to "October", "november" to "November", "december" to "December"
    )
    override val percentWords = setOf("percent")
    override val unitAbbreviations = COMMON_UNITS
    override val measureWords = setOf(
        "minutes", "minute", "hours", "hour", "seconds", "days", "weeks", "months", "years",
        "meters", "metres", "miles", "feet", "inches", "pounds", "ounces", "people", "times",
        "items", "points", "steps", "pages", "cents", "degrees", "calories", "lines", "words", "users"
    )
    override val identifierContextWords = setOf(
        "version", "v", "release", "build", "chapter", "page", "section", "step", "number", "room",
        "item", "figure", "table", "line", "row", "column", "level", "grade", "part", "episode", "track",
        "channel", "gate", "flight", "route", "exit", "issue", "ticket", "pr", "gemma", "qwen", "granite",
        "llama", "gpt", "iphone", "android", "windows", "python", "ios", "pixel", "galaxy", "ubuntu",
        "node", "java", "angular", "ps", "xbox", "area", "model", "series", "phase", "stage", "type"
    )

    private val DIGIT_WORDS = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

    override fun digitWord(w: String): Int? = if (w in DIGIT_WORDS) UNITS[w]?.toInt() else null

    override fun isNumberWord(w: String): Boolean =
        w in UNITS || w in TENS || w in SCALES || w == "hundred"

    private fun unitAt(ch: List<FormatChunk>, i: Int): Long? = UNITS[ch.lowerAt(i)]
    private fun tensAt(ch: List<FormatChunk>, i: Int): Long? = TENS[ch.lowerAt(i)]

    /** 0..99 at [i] as (value, endExclusive). */
    private fun twoDigit(ch: List<FormatChunk>, i: Int): Pair<Long, Int>? {
        val t = tensAt(ch, i)
        if (t != null) {
            val u = unitAt(ch, i + 1)
            if (u != null && u in 1..9 && ch.joinable(i, i + 2)) return (t + u) to (i + 2)
            return t to (i + 1)
        }
        val u = unitAt(ch, i) ?: return null
        return u to (i + 1)
    }

    private fun group(ch: List<FormatChunk>, i: Int): Pair<Long, Int>? {
        var v = 0L
        var k = i
        val u0 = unitAt(ch, i)
        if (u0 != null && u0 in 1..9 && ch.lowerAt(i + 1) == "hundred" && ch.joinable(i, i + 2)) {
            v = u0 * 100
            k = i + 2
            if (ch.lowerAt(k) == "and" && ch.joinable(k - 1, k + 2) && twoDigit(ch, k + 1) != null) k++
        } else {
            val td = twoDigit(ch, i)
            if (td != null && td.first >= 10 && ch.lowerAt(td.second) == "hundred" &&
                ch.joinable(i, td.second + 1)
            ) {
                v = td.first * 100
                k = td.second + 1
            }
        }
        val rest = twoDigit(ch, k)
        if (rest != null && (k == i || ch.joinable(k - 1, rest.second))) {
            // "zero" is only valid as a stand-alone number
            if (!(rest.first == 0L && k != i)) {
                v += rest.first
                k = rest.second
            }
        }
        return if (k == i) null else v to k
    }

    override fun parseCardinal(ch: List<FormatChunk>, i: Int): NumParse? {
        if (!isNumberWord(ch.lowerAt(i)) || ch.lowerAt(i) == "hundred") return null
        var j = i
        var total = 0L
        var lastScale = Long.MAX_VALUE
        var any = false
        while (true) {
            val g = group(ch, j) ?: break
            val scale = SCALES[ch.lowerAt(g.second)]
            if (scale != null && scale < lastScale && g.first in 1..999 && ch.joinable(j, g.second + 1)) {
                total += g.first * scale
                lastScale = scale
                j = g.second + 1
                any = true
                if (ch.lowerAt(j) == "and" && ch.joinable(j - 1, j + 2) && group(ch, j + 1) != null) j++
                continue
            }
            if (scale != null && scale >= lastScale) break
            total += g.first
            j = g.second
            any = true
            break
        }
        if (!any) return null
        var fraction: String? = null
        if (ch.lowerAt(j) == "point" && ch.joinable(j - 1, j + 2) && ch.lowerAt(j + 1) in DIGIT_WORDS) {
            val sb = StringBuilder()
            var k = j + 1
            while (ch.lowerAt(k) in DIGIT_WORDS && ch.joinable(k - 1, k + 1)) {
                sb.append(UNITS[ch.lowerAt(k)])
                k++
            }
            fraction = sb.toString()
            j = k
        }
        return NumParse(total, j, fraction)
    }

    override fun parseOrdinal(ch: List<FormatChunk>, i: Int): Pair<Long, Int>? {
        val w = ch.lowerAt(i)
        ORD_UNITS[w]?.let { return it to (i + 1) }
        ORD_TENS[w]?.let { return it to (i + 1) }
        val t = TENS[w] ?: return null
        val next = ORD_UNITS[ch.lowerAt(i + 1)]
        if (next != null && next in 1..9 && ch.joinable(i, i + 2)) return (t + next) to (i + 2)
        return null
    }

    /** Two-digit pair years: `twenty twenty six`, `nineteen ninety nine`, `twenty oh five`. */
    fun parseSpokenYear(ch: List<FormatChunk>, i: Int): Pair<Long, Int>? {
        val a = twoDigit(ch, i) ?: return null
        if (a.first !in 19..21) return null
        if (a.second >= ch.size || !ch.joinable(i, a.second + 1)) return null
        if (ch.lowerAt(a.second) == "oh") {
            val u = unitAt(ch, a.second + 1)
            if (u != null && u in 1..9 && ch.joinable(a.second, a.second + 2)) return (a.first * 100 + u) to (a.second + 2)
            return null
        }
        val b = twoDigit(ch, a.second) ?: return null
        if (b.first < 10 && b.first != 0L) return null
        if (b.first == 0L) return null
        return (a.first * 100 + b.first) to b.second
    }
}

internal object GermanGrammar : NumberGrammar {
    override val code = "de"
    override val decimalSeparator = ','
    override val groupSeparator = '.'

    private val SIMPLE = mapOf(
        "null" to 0L, "eins" to 1L, "zwei" to 2L, "drei" to 3L, "vier" to 4L, "fünf" to 5L, "sechs" to 6L,
        "sieben" to 7L, "acht" to 8L, "neun" to 9L, "zehn" to 10L, "elf" to 11L, "zwölf" to 12L,
        "dreizehn" to 13L, "vierzehn" to 14L, "fünfzehn" to 15L, "sechzehn" to 16L, "siebzehn" to 17L,
        "achtzehn" to 18L, "neunzehn" to 19L, "zwanzig" to 20L, "dreißig" to 30L, "dreissig" to 30L,
        "vierzig" to 40L, "fünfzig" to 50L, "sechzig" to 60L, "siebzig" to 70L, "achtzig" to 80L, "neunzig" to 90L
    )
    private val ONES = mapOf(
        "ein" to 1L, "eins" to 1L, "zwei" to 2L, "drei" to 3L, "vier" to 4L, "fünf" to 5L,
        "sechs" to 6L, "sieben" to 7L, "acht" to 8L, "neun" to 9L
    )
    private val DIGIT_WORDS = setOf("null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")

    override val months = mapOf(
        "januar" to "Januar", "februar" to "Februar", "märz" to "März", "april" to "April", "mai" to "Mai",
        "juni" to "Juni", "juli" to "Juli", "august" to "August", "september" to "September",
        "oktober" to "Oktober", "november" to "November", "dezember" to "Dezember"
    )
    override val percentWords = setOf("prozent")
    override val unitAbbreviations = COMMON_UNITS
    override val measureWords = setOf(
        "minuten", "minute", "stunden", "stunde", "sekunden", "tage", "tagen", "wochen", "monate", "monaten",
        "jahre", "jahren", "meter", "kilometer", "personen", "mal", "punkte", "schritte", "seiten", "grad"
    )
    override val identifierContextWords = setOf(
        "version", "v", "kapitel", "seite", "abschnitt", "schritt", "nummer", "nr", "raum", "zimmer",
        "gemma", "qwen", "granite", "llama", "gpt", "iphone", "android", "windows", "python", "ios",
        "stufe", "level", "teil", "folge", "gleis", "linie", "ticket", "modell", "serie", "phase"
    )

    override fun digitWord(w: String): Int? = if (w in DIGIT_WORDS) SIMPLE[w]?.toInt() else null

    override fun isNumberWord(w: String): Boolean = parseWord(w) != null && w != "ein" && w != "eine"

    /** Parses a single lowercase German compound (`dreiundzwanzig`, `zweitausendvierhundert`). */
    fun parseWord(raw: String): Long? {
        val w = raw.lowercase(Locale.ROOT)
        if (w.isEmpty() || !w.all { it.isLetter() }) return null
        return compound(w, 0)
    }

    private fun compound(w: String, depth: Int): Long? {
        if (w.isEmpty() || depth > 6) return null
        SIMPLE[w]?.let { return it }
        ONES[w]?.let { return it }
        for ((marker, scale) in listOf("million" to 1_000_000L, "tausend" to 1_000L, "hundert" to 100L)) {
            val idx = w.indexOf(marker)
            if (idx < 0) continue
            val left = if (idx == 0) 1L else compound(w.substring(0, idx), depth + 1) ?: return null
            if (idx > 0 && left !in 1..999) return null
            var restStart = idx + marker.length
            if (marker == "million" && w.startsWith("en", restStart)) restStart += 2
            val rest = w.substring(restStart)
            val right = if (rest.isEmpty()) 0L else compound(rest, depth + 1) ?: return null
            return left * scale + right
        }
        val u = w.indexOf("und")
        if (u > 0) {
            val unit = ONES[w.substring(0, u)] ?: return null
            val tens = SIMPLE[w.substring(u + 3)] ?: return null
            if (tens < 20 || tens % 10 != 0L) return null
            return tens + unit
        }
        return null
    }

    override fun parseCardinal(ch: List<FormatChunk>, i: Int): NumParse? {
        val c = ch.getOrNull(i) ?: return null
        if (c.core.isEmpty() || c.core.first().isUpperCase() && i > 0 && !endsSentence(ch[i - 1].trail)) return null
        val w = c.lower
        if (w == "ein" || w == "eine") return null
        val v = parseWord(w) ?: return null
        var j = i + 1
        var fraction: String? = null
        if (ch.lowerAt(j) == "komma" && ch.joinable(i, j + 2) && ch.lowerAt(j + 1) in DIGIT_WORDS) {
            val sb = StringBuilder()
            var k = j + 1
            while (ch.lowerAt(k) in DIGIT_WORDS && ch.joinable(k - 1, k + 1)) {
                sb.append(SIMPLE[ch.lowerAt(k)])
                k++
            }
            fraction = sb.toString()
            j = k
        }
        return NumParse(v, j, fraction)
    }

    private val ORDINAL_SPECIALS = mapOf("erst" to 1L, "dritt" to 3L, "siebt" to 7L, "acht" to 8L)
    private val SPECIAL_ENDINGS = setOf("", "e", "en", "er", "es", "em")
    private val ORDINAL_SUFFIXES = listOf("sten", "ster", "stes", "stem", "ste", "ten", "ter", "tes", "tem", "te")

    override fun parseOrdinal(ch: List<FormatChunk>, i: Int): Pair<Long, Int>? {
        val c = ch.getOrNull(i) ?: return null
        if (c.core.isEmpty() || !c.core.all { it.isLetter() }) return null
        val w = c.lower
        for ((stem, v) in ORDINAL_SPECIALS) {
            if (w.startsWith(stem) && w.substring(stem.length) in SPECIAL_ENDINGS) return v to (i + 1)
        }
        for (suffix in ORDINAL_SUFFIXES) {
            if (!w.endsWith(suffix) || w.length <= suffix.length + 1) continue
            val stem = w.substring(0, w.length - suffix.length)
            val v = parseWord(stem) ?: if (suffix.startsWith("s")) parseWord(stem + "s") else null
            if (v != null && v > 0) return v to (i + 1)
        }
        return null
    }
}
