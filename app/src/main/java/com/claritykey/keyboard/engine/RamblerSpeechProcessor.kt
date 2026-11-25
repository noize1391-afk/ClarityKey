package com.claritykey.keyboard.engine

enum class RamblerStyle(val label: String) {
    NATURAL("Обычный"),
    CONCISE("Кратко"),
    LIST("Список")
}

/**
 * 100% offline Russian & English Rambler speech-to-clean-text processor.
 * Transforms natural spoken thoughts (with pauses, fillers, stutters, and self-corrections)
 * into clean, punctuated written text — zero network calls, zero external dependencies.
 */
object RamblerSpeechProcessor {

    private val FILLER_PHRASES = listOf(
        "как бы", "в общем", "на самом деле", "если честно", "так сказать",
        "короче говоря", "ну типа", "ну вот", "кстати говоря", "в принципе",
        "you know", "i mean", "kind of", "sort of"
    )

    private val FILLER_WORDS = setOf(
        "ээ", "эээ", "эм", "эмм", "мм", "ммм", "аа", "ааа",
        "ну", "типа", "короче", "вот", "блин", "значит", "собственно", "слушай",
        "um", "uh", "erm", "hmm"
    )

    private val CONCISE_OPENERS = listOf(
        "я хотел сказать что", "я хотела сказать что", "я просто хотел сказать что",
        "хотел сказать что", "хотела сказать что", "привет слушай", "смотри короче",
        "я думаю что", "мне кажется что"
    )

    private val RESTART_CUES = listOf(
        "начни заново", "стоп заново", "подожди напиши", "нет напиши", "сотри напиши", "заново"
    )

    private val SELF_CORRECTION_MARKERS = setOf("нет", "ой", "точнее", "вернее", "исправь")

    private val COMMA_BEFORE_WORDS = setOf(
        "что", "чтобы", "чтоб", "который", "которая", "которое", "которые",
        "которого", "которой", "которых", "но", "однако", "хотя", "поэтому",
        "если", "когда", "пока", "потому"
    )

    private val QUESTION_STARTERS = setOf(
        "как", "где", "когда", "почему", "зачем", "сколько", "кто", "куда", "откуда",
        "какой", "какая", "какое", "какие", "можно", "подскажи", "подскажите", "можешь", "можете",
        "what", "where", "when", "why", "how", "who", "can", "could", "would", "do", "does"
    )

    private val YO_FIXES = mapOf(
        "еще" to "ещё",
        "придет" to "придёт",
        "придешь" to "придёшь",
        "пойдет" to "пойдёт",
        "пойдешь" to "пойдёшь",
        "возьмет" to "возьмёт",
        "найдет" to "найдёт",
        "пройдет" to "пройдёт",
        "даст" to "даст",
        "вместе" to "вместе",
        "все-таки" to "всё-таки",
        "всетки" to "всё-таки",
        "учет" to "учёт",
        "отчет" to "отчёт",
        "счет" to "счёт",
        "теплый" to "тёплый",
        "темный" to "тёмный",
        "зеленый" to "зелёный",
        "черный" to "чёрный",
        "желтый" to "жёлтый",
        "четкий" to "чёткий",
        "легкий" to "лёгкий",
        "серьезно" to "серьёзно"
    )

    fun process(rawSpeech: String, style: RamblerStyle = RamblerStyle.NATURAL): String {
        var text = rawSpeech.trim()
        if (text.isEmpty()) return ""

        // 1. Voice punctuation commands
        text = applyVoicePunctuationCommands(text)

        // 2. Restart cues ("подожди напиши ...", "начни заново ...")
        val lower = text.lowercase()
        for (cue in RESTART_CUES) {
            val idx = lower.lastIndexOf(cue)
            if (idx >= 0 && idx + cue.length < text.length - 1) {
                text = text.substring(idx + cue.length).trim()
                break
            }
        }

        // 3. Concise opener trimming if CONCISE style
        if (style == RamblerStyle.CONCISE) {
            val lc = text.lowercase()
            for (op in CONCISE_OPENERS) {
                if (lc.startsWith(op)) {
                    text = text.substring(op.length).trim()
                    break
                }
            }
        }

        // 4. Strip multi-word filler phrases
        for (phrase in FILLER_PHRASES) {
            text = text.replace(Regex("(?iu)(^|\\s)${Regex.escape(phrase)}(?=\\s|[.,!?]|$)"), " ")
        }

        // 5. Token-level processing: self-corrections, fillers, stutter repeats, ё restoration
        val rawTokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val cleaned = ArrayList<String>(rawTokens.size)
        var i = 0
        while (i < rawTokens.size) {
            val tok = rawTokens[i]
            val bare = tok.lowercase().trim('.', ',', '!', '?', ':', ';', '—', '-')

            // Self-correction: "в пять нет в шесть" or "завтра точнее послезавтра"
            if (bare in SELF_CORRECTION_MARKERS && cleaned.isNotEmpty() && i + 1 < rawTokens.size) {
                // Remove 1-2 previous words (if previous word is a preposition like "в"/"на", pop 2)
                cleaned.removeAt(cleaned.lastIndex)
                if (cleaned.isNotEmpty() && cleaned.last().lowercase() in setOf("в", "на", "к", "по", "с", "до", "от", "за")) {
                    cleaned.removeAt(cleaned.lastIndex)
                }
                i++
                continue
            }

            // Skip hesitation filler words
            if (bare in FILLER_WORDS) {
                i++
                continue
            }

            // Collapse immediate stutter duplicate ("я я хотел" -> "я хотел")
            if (cleaned.isNotEmpty()) {
                val prevBare = cleaned.last().lowercase().trim('.', ',', '!', '?', ':', ';')
                if (prevBare == bare && bare.isNotEmpty() && bare.all { it.isLetter() }) {
                    i++
                    continue
                }
            }

            // Restore common 'ё' words
            val fixed = YO_FIXES[bare]?.let { repl ->
                val prefix = tok.takeWhile { !it.isLetter() }
                val suffix = tok.takeLastWhile { !it.isLetter() }
                prefix + repl + suffix
            } ?: tok

            cleaned.add(fixed)
            i++
        }

        if (cleaned.isEmpty()) return ""

        // 6. Insert missing Russian subordinate commas
        val withCommas = ArrayList<String>(cleaned.size)
        for (idx in cleaned.indices) {
            val w = cleaned[idx]
            val bare = w.lowercase().trim('.', ',', '!', '?', ':', ';')
            if (idx > 0 && bare in COMMA_BEFORE_WORDS) {
                val prev = withCommas.last()
                if (!prev.endsWith(",") && !prev.endsWith(".") && !prev.endsWith("!") && !prev.endsWith("?") && !prev.endsWith(":") && !prev.endsWith("\n")) {
                    withCommas[withCommas.lastIndex] = "$prev,"
                }
            }
            withCommas.add(w)
        }

        // 7. Rejoin & normalize spacing around punctuation
        var result = withCommas.joinToString(" ")
            .replace(Regex("\\s+([.,!?:;])"), "$1")
            .replace(Regex("([.,!?:;])([^\\s\\d\\n])"), "$1 $2")
            .replace(Regex(",{2,}"), ",")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimStart(',', ';', ':')
            .trim()

        if (result.isEmpty()) return ""

        // 8. List formatting if LIST style
        if (style == RamblerStyle.LIST) {
            val items = result
                .replace(Regex("(?iu)\\b(во-первых|во-вторых|в-третьих|в-четвёртых|ещё|также|и ещё)\\b"), "\n")
                .split(Regex("[\\n;,]+"))
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() }
            if (items.size >= 2) {
                return items.joinToString("\n") { "• ${it.replaceFirstChar { c -> c.uppercaseChar() }}" }
            }
            return "• ${result.replaceFirstChar { it.uppercaseChar() }}"
        }

        // 9. Sentence capitalization & terminal punctuation (. or ?)
        result = capitalizeSentences(result)
        val lastChar = result.last()
        if (lastChar !in ".!?…") {
            val firstWord = result.substringBefore(' ').lowercase().trim(',', '.', '!')
            val isQuestion = firstWord in QUESTION_STARTERS || result.lowercase().contains(" ли ")
            result += if (isQuestion) "?" else "."
        }
        return result
    }

    private fun applyVoicePunctuationCommands(input: String): String {
        return input
            .replace(Regex("(?iu)\\s+новая строка\\b"), "\n")
            .replace(Regex("(?iu)\\s+новый абзац\\b"), "\n")
            .replace(Regex("(?iu)\\s+абзац\\b"), "\n")
            .replace(Regex("(?iu)\\s+запятая\\b"), ",")
            .replace(Regex("(?iu)\\s+точка\\b"), ".")
            .replace(Regex("(?iu)\\s+знак вопроса\\b"), "?")
            .replace(Regex("(?iu)\\s+вопросительный знак\\b"), "?")
            .replace(Regex("(?iu)\\s+восклицательный знак\\b"), "!")
            .replace(Regex("(?iu)\\s+двоеточие\\b"), ":")
            .replace(Regex("(?iu)\\s+тире\\b"), " — ")
    }

    private fun capitalizeSentences(s: String): String {
        val sb = StringBuilder(s.length)
        var capNext = true
        for (ch in s) {
            if (capNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    capNext = true
                }
            }
        }
        return sb.toString()
    }
}
