package com.voicebubble.openflow.core.format

import com.voicebubble.openflow.core.speech.GemmaTextProcessor
import java.util.Locale

/** Per-call options for [FormattingEngine]. All layers default to on. */
data class FormatContext(
    val languageCode: String? = null,
    val spokenPunctuation: Boolean = true,
    val disfluencies: Boolean = true,
    val lists: Boolean = true,
    val numbers: Boolean = true,
    val corrections: Boolean = true,
    val techCasing: Boolean = true
)

/**
 * @property text formatted output
 * @property edits number of individual rewrites performed
 * @property needsLlm true when the engine saw something it deliberately did not resolve (for
 *   example a punctuated self-correction cue between unlike values) and an LLM could do better
 */
data class FormatResult(
    val text: String,
    val edits: Int,
    val needsLlm: Boolean,
    val language: String
)

/**
 * Deterministic, model-free formatting engine (Inverse Text Normalization + disfluency / list /
 * self-correction handling). Pure Kotlin, no RAM beyond the string being processed, no
 * hallucination risk: every pass either applies an unambiguous rule or leaves the text alone.
 *
 * Pass order matters: spoken punctuation -> disfluencies -> lists -> numbers -> corrections ->
 * technical casing. Every pass is isolated in a try/catch so a bug can never lose the dictation.
 */
object FormattingEngine {
    private val GERMAN_HINTS = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ich", "wir", "sie", "mit", "für", "auf", "ein", "eine",
        "zu", "den", "dem", "von", "es", "auch", "wie", "aber", "oder", "wenn", "dass", "uhr", "euro", "prozent"
    )
    private val EXTRA_SPACES = Regex("[ \\t]{2,}")

    fun resolveLanguage(languageCode: String?, text: String): String {
        val code = languageCode?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (code.length >= 2 && code != "auto") return code.take(2)
        var hits = 0
        var words = 0
        for (t in text.split(' ', '\n')) {
            val w = t.trim { !it.isLetter() }.lowercase(Locale.ROOT)
            if (w.isEmpty()) continue
            words++
            if (w in GERMAN_HINTS) hits++
        }
        return if (words > 0 && hits * 6 >= words) "de" else "en"
    }

    fun format(text: String, ctx: FormatContext = FormatContext()): FormatResult {
        if (text.isBlank()) return FormatResult(text, 0, false, resolveLanguage(ctx.languageCode, text))
        val lang = resolveLanguage(ctx.languageCode, text)
        var current = text.trim()
        var edits = 0
        var needsLlm = false

        fun guarded(block: (String) -> String) {
            val before = current
            val after = try { block(before) } catch (_: Throwable) { before }
            current = if (after.isBlank()) before else after
        }

        if (ctx.spokenPunctuation) {
            guarded { s ->
                val r = GemmaTextProcessor.preNormalizeSpokenConstructs(s)
                if (r != s) edits++
                r
            }
        }
        if (ctx.disfluencies) {
            guarded { s ->
                val pass = DisfluencyPass(lang)
                val out = FormatChunk.join(pass.apply(FormatChunk.split(s)))
                edits += pass.edits
                tidy(out)
            }
        }
        if (ctx.lists) {
            guarded { s ->
                val pass = ListPass(lang)
                val out = pass.apply(s)
                edits += pass.edits
                out
            }
        }
        if (ctx.numbers && (lang == "en" || lang == "de")) {
            guarded { s ->
                val pass = NumberPass(if (lang == "de") GermanGrammar else EnglishGrammar)
                val out = FormatChunk.join(pass.apply(FormatChunk.split(s)))
                edits += pass.edits
                out
            }
        }
        if (ctx.corrections) {
            guarded { s ->
                val pass = BacktrackPass(lang)
                val out = pass.apply(s)
                edits += pass.edits
                if (pass.unresolvedCue) needsLlm = true
                tidy(out)
            }
        }
        if (ctx.techCasing) {
            guarded { s ->
                val chunks = FormatChunk.split(s)
                TechCasingPass.apply(chunks)
                edits += TechCasingPass.lastEdits
                FormatChunk.join(chunks)
            }
        }
        return FormatResult(current.trim(), edits, needsLlm, lang)
    }

    private fun tidy(s: String): String = s.replace(EXTRA_SPACES, " ").trim()
}
