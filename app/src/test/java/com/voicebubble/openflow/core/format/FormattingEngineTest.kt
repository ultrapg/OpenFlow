package com.voicebubble.openflow.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingEngineTest {
    private fun en(s: String) = FormattingEngine.format(s, FormatContext("en")).text
    private fun de(s: String) = FormattingEngine.format(s, FormatContext("de")).text

    private fun check(lang: String, cases: List<Pair<String, String>>) {
        val failures = cases.mapNotNull { (input, expected) ->
            val got = if (lang == "de") de(input) else en(input)
            if (got != expected) "[$input]\n   expected: [$expected]\n   got:      [$got]" else null
        }
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun englishNumbers() = check("en", listOf(
        "It costs two thousand four hundred and fifty euros." to "It costs €2,450.",
        "We need twenty five people." to "We need 25 people.",
        "I have two cats." to "I have two cats.",
        "That was the one I wanted." to "That was the one I wanted.",
        "The answer is forty two." to "The answer is 42.",
        "Pi is three point one four." to "Pi is 3.14.",
        "It is ninety eight point five percent done." to "It is 98.5% done.",
        "Pay fifteen hundred dollars." to "Pay $1,500.",
        "That is forty nine dollars and ninety nine cents." to "That is $49.99.",
        "I use six gigabytes of RAM." to "I use 6 GB of RAM.",
        "Latency is three hundred eighty milliseconds." to "Latency is 380 ms.",
        "Set it to twenty-five percent." to "Set it to 25%.",
        "It takes five minutes." to "It takes 5 minutes.",
        "One two three go." to "One two three go.",
        "A population of two point five million people." to "A population of 2.5 million people.",
        "Meet me at three thirty pm." to "Meet me at 3:30 PM.",
        "The call is at four oh five pm." to "The call is at 4:05 PM.",
        "See you at five pm." to "See you at 5 PM.",
        "Come at three thirty." to "Come at 3:30.",
        "Update to version two point one seven." to "Update to version 2.17.",
        "Use Gemma four for this." to "Use Gemma 4 for this.",
        "I read page three." to "I read page 3.",
        "It happened in twenty twenty six." to "It happened in 2026.",
        "Born in nineteen ninety nine." to "Born in 1999.",
        "On November twenty fourth twenty twenty six we ship." to "On November 24, 2026 we ship.",
        "The meeting is on March third." to "The meeting is on March 3.",
        "She finished in the twenty first place." to "She finished in the 21st place.",
        "It reached one hundred and one degrees Celsius." to "It reached 101°C.",
        "twenty twenty is a good year" to "twenty twenty is a good year",
    ))

    @Test fun germanNumbers() = check("de", listOf(
        "Das kostet dreiundzwanzig Euro." to "Das kostet 23 €.",
        "Das kostet neunundvierzig Euro neunundneunzig." to "Das kostet 49,99 €.",
        "Wir treffen uns um fünfzehn Uhr dreißig." to "Wir treffen uns um 15:30 Uhr.",
        "Es ist acht Uhr." to "Es ist 8 Uhr.",
        "Es sind zweitausendvierhundert Teilnehmer." to "Es sind 2.400 Teilnehmer.",
        "Pi ist drei komma eins vier." to "Pi ist 3,14.",
        "Das sind fünfzig Prozent." to "Das sind 50 %.".replace(" %", "%"),
        "Ich habe zwei Katzen." to "Ich habe zwei Katzen.",
        "Ich brauche sechs Gigabyte Speicher." to "Ich brauche 6 GB Speicher.",
        "Am vierundzwanzigsten November zweitausendsechsundzwanzig geht es los." to
            "Am 24. November 2026 geht es los.",
        "Wir sind eine Elf." to "Wir sind eine Elf.",
    ))

    @Test fun disfluencies() {
        check("en", listOf(
            "So um I think uh we should go." to "So I think we should go.",
            "Um, I think so." to "I think so.",
            "I think I think we should go." to "I think we should go.",
            "The the problem is here." to "The problem is here.",
            "It was very very good." to "It was very very good.",
            "He had had enough." to "He had had enough.",
            "It was, like, really fast." to "It was, really fast.",
            "I like pizza." to "I like pizza.",
            "It is is that simple." to "It is that simple.",
            "Well, you know, it works." to "Well, it works.",
        ))
        check("de", listOf(
            "Das ist äh ganz einfach." to "Das ist ganz einfach.",
            "Wir treffen uns um drei Uhr." to "Wir treffen uns um 3 Uhr.",
            "Ähm, ich glaube schon." to "Ich glaube schon.",
        ))
    }

    @Test fun lists() = check("en", listOf(
        "I need three things first buy milk second call mom third pay rent." to
            "I need three things:\n1. Buy milk\n2. Call mom\n3. Pay rent.",
        "First, open the app. Second, press record." to "1. Open the app.\n2. Press record.",
        "I came in second." to "I came in second.",
        "At first it was hard." to "At first it was hard.",
        "Shopping: bullet point milk bullet point eggs." to "Shopping:\n- Milk\n- Eggs.",
        "The second time was a charm." to "The second time was a charm.",
    ))

    @Test fun germanLists() = check("de", listOf(
        "Wir brauchen erstens Brot zweitens Milch drittens Eier." to
            "Wir brauchen:\n1. Brot\n2. Milch\n3. Eier.",
    ))

    @Test fun corrections() {
        check("en", listOf(
            "Meet on Tuesday, wait no, Wednesday." to "Meet on Wednesday.",
            "Meet at three thirty pm, I mean four fifteen pm." to "Meet at 4:15 PM.",
            "I need eight gigabytes, make that six gigabytes." to "I need 6 GB.",
            "Send it to John, I mean Sarah." to "Send it to Sarah.",
            "Send it to John, scratch that. Send it to Sarah." to "Send it to Sarah.",
            "I mean it." to "I mean it.",
        ))
        assertTrue(FormattingEngine.format("Call Tuesday, wait no, the other client.", FormatContext("en")).needsLlm)
        assertFalse(FormattingEngine.format("Meet on Tuesday, wait no, Wednesday.", FormatContext("en")).needsLlm)
    }

    @Test fun techCasing() = check("en", listOf(
        "Push it to github using the api." to "Push it to GitHub using the API.",
        "The npu is faster than the gpu." to "The NPU is faster than the GPU.",
    ))

    @Test fun idempotentAndSafe() {
        val inputs = listOf(
            "I need three things first buy milk second call mom third pay rent.",
            "Meet at three thirty pm on November twenty fourth twenty twenty six.",
            "um so the the price is forty nine dollars and ninety nine cents",
            "", "   ", "...", "one", "twenty", "wait no", "scratch that",
        )
        for (s in inputs) {
            val once = FormattingEngine.format(s, FormatContext("en")).text
            val twice = FormattingEngine.format(once, FormatContext("en")).text
            assertEquals("idempotence for [$s]", once, twice)
        }
    }

    @Test fun performance() {
        val sample = "So um I think the the meeting is at three thirty pm on November twenty fourth and we need " +
            "six gigabytes of RAM, make that eight gigabytes. " .repeat(12)
        FormattingEngine.format(sample, FormatContext("en"))
        val t0 = System.nanoTime()
        repeat(20) { FormattingEngine.format(sample, FormatContext("en")) }
        val avgMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("avg $avgMs ms", avgMs < 25.0)
    }
}
