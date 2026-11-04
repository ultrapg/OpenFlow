package com.voicebubble.openflow.core.format

import java.util.Locale

/**
 * Removes unambiguous vocal hesitations (`um`, `uh`, `äh`, `ähm`, `hmm`), exact word / phrase
 * stutters (`the the`, `I think I think`) and comma-flanked contextual fillers (`, like,`,
 * `, you know,`, `, sozusagen,`) while healing the punctuation and capitalization around them.
 */
internal class DisfluencyPass(private val lang: String) {
    var edits = 0
        private set

    private val hesitation: Regex = when (lang) {
        "en" -> Regex("^(?:u+m+|u+h+m*|e+r+m+|e+r+|h+m+|m{3,}|mhm+)$")
        "de" -> Regex("^(?:ä+h+m*|ö+h+m*|e+h+m+|h+m+|m{3,}|mhm+)$")
        else -> Regex("^(?:u+h+m*|ä+h+m*|e+u+h+|e+h+m+|h+m+|m{3,}|mhm+)$")
    }

    private val stutterAllowlist: Set<String> = if (lang == "de") {
        setOf("sehr", "ja", "nein", "so", "doch", "na", "mal", "immer", "noch", "bla", "hm", "wie", "was", "das", "haha")
    } else {
        setOf(
            "very", "had", "that", "no", "so", "bye", "really", "much", "many", "more", "little", "long", "far",
            "ha", "hey", "yeah", "yes", "okay", "ok", "now", "never", "again", "well", "oh", "go", "bla",
            "knock", "tick", "tock", "ho", "la", "na"
        )
    }

    private val flankedFillers: List<List<String>> = when (lang) {
        "en" -> listOf(listOf("like"), listOf("you", "know"))
        "de" -> listOf(listOf("quasi"), listOf("sozusagen"), listOf("irgendwie"))
        else -> emptyList()
    }

    fun apply(input: List<FormatChunk>): MutableList<FormatChunk> {
        val afterHesitations = removeHesitations(input)
        val afterFlanked = removeFlanked(afterHesitations)
        return collapseStutters(afterFlanked)
    }

    private fun removeHesitations(input: List<FormatChunk>): MutableList<FormatChunk> {
        val out = ArrayList<FormatChunk>(input.size)
        var pendingCap = false
        var pendingSep: String? = null
        for (c in input) {
            if (c.core.isNotEmpty() && c.lead.isEmpty() && hesitation.matches(c.lower)) {
                edits++
                val prev = out.lastOrNull()
                if (prev == null) {
                    pendingCap = true
                    pendingSep = c.sepBefore
                } else if (endsSentence(prev.trail)) {
                    pendingCap = true
                } else if (endsSentence(c.trail)) {
                    val terminal = c.trail.first { it == '.' || it == '!' || it == '?' || it == '…' }
                    if (prev.trail.isEmpty() || prev.trail == ",") prev.trail = terminal.toString()
                    pendingCap = true
                }
                continue
            }
            if (pendingSep != null) { c.sepBefore = pendingSep; pendingSep = null }
            if (pendingCap) { capitalizeChunk(c); pendingCap = false }
            out.add(c)
        }
        return out
    }

    private fun removeFlanked(input: MutableList<FormatChunk>): MutableList<FormatChunk> {
        if (flankedFillers.isEmpty()) return input
        val out = ArrayList<FormatChunk>(input.size)
        var i = 0
        while (i < input.size) {
            var matchedLen = 0
            for (phrase in flankedFillers) {
                if (i + phrase.size > input.size) continue
                val prev = out.lastOrNull() ?: continue
                if (!prev.trail.endsWith(",")) continue
                var ok = true
                for (k in phrase.indices) {
                    val c = input[i + k]
                    if (c.lower != phrase[k] || c.lead.isNotEmpty()) { ok = false; break }
                    if (k < phrase.size - 1 && c.trail.isNotEmpty()) { ok = false; break }
                }
                if (ok && input[i + phrase.size - 1].trail == ",") { matchedLen = phrase.size; break }
            }
            if (matchedLen > 0) {
                edits++
                i += matchedLen
            } else {
                out.add(input[i]); i++
            }
        }
        return out
    }

    private fun collapseStutters(input: MutableList<FormatChunk>): MutableList<FormatChunk> {
        val list = ArrayList(input)
        var k = 0
        while (k < list.size) {
            var collapsed = false
            for (size in 3 downTo 1) {
                if (k + 2 * size > list.size) continue
                if (!isStutter(list, k, size)) continue
                val a = list[k]
                val b = list[k + size]
                b.sepBefore = a.sepBefore
                b.lead = a.lead
                // keep the capital of the first copy if the second was lowercase
                if (a.core.firstOrNull()?.isUpperCase() == true) capitalizeChunk(b)
                repeat(size) { list.removeAt(k) }
                edits++
                collapsed = true
                break
            }
            if (!collapsed) k++
        }
        return list
    }

    private fun isStutter(list: List<FormatChunk>, k: Int, size: Int): Boolean {
        var allAllowlisted = true
        for (j in 0 until size) {
            val a = list[k + j]
            val b = list[k + size + j]
            if (a.core.isEmpty() || b.core.isEmpty()) return false
            if (!a.lower.equals(b.lower)) return false
            if (a.core.all { it.isDigit() }) return false
            if (EnglishGrammar.isNumberWord(a.lower) || GermanGrammar.isNumberWord(a.lower)) return false
            if (j > 0 && (b.lead.isNotEmpty() || b.hasNewlineBefore)) return false
            if (j == 0 && (b.lead.isNotEmpty() || b.hasNewlineBefore)) return false
            if (a.trail.isNotEmpty()) return false
            if (j < size - 1 && b.trail.isNotEmpty()) return false
            if (a.lower !in stutterAllowlist) allAllowlisted = false
        }
        if (allAllowlisted) return false
        if (size == 1 && list[k].core.length == 1 && list[k].lower !in setOf("i", "a")) return false
        return true
    }
}

/** Applies canonical casing to well-known lowercase technical terms and product names. */
internal object TechCasingPass {
    private val CANONICAL: Map<String, String> = mapOf(
        "api" to "API", "apis" to "APIs", "npu" to "NPU", "gpu" to "GPU", "cpu" to "CPU", "ram" to "RAM",
        "llm" to "LLM", "llms" to "LLMs", "json" to "JSON", "sql" to "SQL", "usb" to "USB", "sdk" to "SDK",
        "apk" to "APK", "ios" to "iOS", "ui" to "UI", "ux" to "UX", "http" to "HTTP", "https" to "HTTPS",
        "url" to "URL", "urls" to "URLs", "cli" to "CLI", "ssh" to "SSH", "jwt" to "JWT", "oauth" to "OAuth",
        "html" to "HTML", "css" to "CSS", "xml" to "XML", "yaml" to "YAML", "csv" to "CSV", "pdf" to "PDF",
        "sms" to "SMS", "gps" to "GPS", "ssd" to "SSD", "vpn" to "VPN", "dns" to "DNS", "hdmi" to "HDMI",
        "gpt" to "GPT", "chatgpt" to "ChatGPT", "github" to "GitHub", "gitlab" to "GitLab",
        "openai" to "OpenAI", "youtube" to "YouTube", "linkedin" to "LinkedIn", "whatsapp" to "WhatsApp",
        "javascript" to "JavaScript", "typescript" to "TypeScript", "iphone" to "iPhone", "ipad" to "iPad",
        "macos" to "macOS", "wifi" to "Wi-Fi", "gemma" to "Gemma", "qwen" to "Qwen", "minicpm" to "MiniCPM",
        "smollm" to "SmolLM", "ministral" to "Ministral"
    )

    var lastEdits = 0
        private set

    fun apply(input: List<FormatChunk>): List<FormatChunk> {
        var n = 0
        for (c in input) {
            if (c.core.isEmpty() || c.core.any { it.isUpperCase() }) continue
            val canon = CANONICAL[c.core] ?: continue
            c.core = canon
            n++
        }
        lastEdits = n
        return input
    }
}
