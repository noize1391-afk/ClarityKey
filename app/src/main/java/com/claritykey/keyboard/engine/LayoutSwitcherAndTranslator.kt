package com.claritykey.keyboard.engine

/**
 * Phase 2 Language Utilities (100% offline, zero network calls):
 * - #7 Auto-layout wrong-language detection & conversion ("ghbdtn" -> "привет", "руддщ" -> "hello")
 * - #8 Punto Switcher bidirectional RU <-> EN converter for selected text
 * - #6 Fast offline RU <-> EN quick phrase & word translator
 * - #10 Typography & favorite symbols bank
 */
object LayoutSwitcherAndTranslator {

    private val EN_TO_RU = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н', 'u' to 'г', 'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ъ',
        'a' to 'ф', 's' to 'ы', 'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р', 'j' to 'о', 'k' to 'л', 'l' to 'д', ';' to 'ж', '\'' to 'э',
        'z' to 'я', 'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и', 'n' to 'т', 'm' to 'ь', ',' to 'б', '.' to 'ю', '`' to 'ё',
        'Q' to 'Й', 'W' to 'Ц', 'E' to 'У', 'R' to 'К', 'T' to 'Е', 'Y' to 'Н', 'U' to 'Г', 'I' to 'Ш', 'O' to 'Щ', 'P' to 'З', '{' to 'Х', '}' to 'Ъ',
        'A' to 'Ф', 'S' to 'Ы', 'D' to 'В', 'F' to 'А', 'G' to 'П', 'H' to 'Р', 'J' to 'О', 'K' to 'Л', 'L' to 'Д', ':' to 'Ж', '"' to 'Э',
        'Z' to 'Я', 'X' to 'Ч', 'C' to 'С', 'V' to 'М', 'B' to 'И', 'N' to 'Т', 'M' to 'Ь', '<' to 'Б', '>' to 'Ю', '~' to 'Ё'
    )

    private val RU_TO_EN: Map<Char, Char> = EN_TO_RU.entries.associate { (k, v) -> v to k }

    val TYPOGRAPHY_SYMBOLS: List<String> = listOf(
        "«", "»", "—", "–", "…", "№", "₽", "€", "$", "£", "¥", "₸",
        "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√", "π", "§",
        "©", "®", "™", "•", "·", "→", "←", "↑", "↓", "✓", "✕", "★",
        "½", "¼", "¾", "²", "³", "‰", "¶", "†"
    )

    private val RU_EN_DICT: Map<String, String> = mapOf(
        "привет" to "hello", "здравствуйте" to "hello", "пока" to "bye", "до свидания" to "goodbye",
        "спасибо" to "thank you", "спасибо большое" to "thank you very much", "пожалуйста" to "please",
        "извини" to "sorry", "извините" to "excuse me", "да" to "yes", "нет" to "no",
        "хорошо" to "good", "отлично" to "great", "классно" to "cool", "понял" to "got it",
        "как дела" to "how are you", "что нового" to "what's new", "доброе утро" to "good morning",
        "добрый день" to "good afternoon", "добрый вечер" to "good evening", "спокойной ночи" to "good night",
        "увидимся" to "see you", "до завтра" to "see you tomorrow", "до встречи" to "see you soon",
        "сегодня" to "today", "завтра" to "tomorrow", "вчера" to "yesterday", "сейчас" to "now",
        "скоро" to "soon", "позже" to "later", "время" to "time", "день" to "day", "ночь" to "night",
        "работа" to "work", "дом" to "home", "встреча" to "meeting", "звонок" to "call",
        "сообщение" to "message", "письмо" to "email", "файл" to "file", "фото" to "photo",
        "ссылка" to "link", "адрес" to "address", "телефон" to "phone", "номер" to "number",
        "заказ" to "order", "доставка" to "delivery", "цена" to "price", "скидка" to "discount",
        "оплата" to "payment", "готово" to "done", "отправлено" to "sent", "проверь" to "check",
        "отправь" to "send", "позвони" to "call me", "напиши" to "text me", "подожди" to "wait",
        "помоги" to "help", "нужно" to "need", "можно" to "can I", "хочу" to "I want",
        "я" to "I", "ты" to "you", "мы" to "we", "они" to "they", "всё" to "everything",
        "люблю" to "love", "друг" to "friend", "семья" to "family", "деньги" to "money",
        "магазин" to "store", "товар" to "product", "вопрос" to "question", "ответ" to "answer",
        "проблема" to "problem", "решение" to "solution", "обновление" to "update", "версия" to "version",
        "погода" to "weather", "новости" to "news", "музыка" to "music", "фильм" to "movie",
        "игра" to "game", "телефон" to "phone", "компьютер" to "computer", "ноутбук" to "laptop",
        "клавиатура" to "keyboard", "экран" to "screen", "камера" to "camera", "батарея" to "battery",
        "интернет" to "internet", "пароль" to "password", "аккаунт" to "account", "настройки" to "settings",
        "поиск" to "search", "скачать" to "download", "установить" to "install", "удалить" to "delete",
        "открыть" to "open", "закрыть" to "close", "сохранить" to "save", "купить" to "buy",
        "продать" to "sell", "бесплатно" to "free", "быстро" to "fast", "медленно" to "slow",
        "большой" to "big", "маленький" to "small", "новый" to "new", "старый" to "old",
        "лучший" to "best", "хороший" to "good", "плохой" to "bad", "красивый" to "beautiful",
        "удобный" to "comfortable", "важно" to "important", "срочно" to "urgent", "интересно" to "interesting",
        "почему" to "why", "когда" to "when", "где" to "where", "как" to "how", "что" to "what", "кто" to "who",
        "сколько" to "how much", "сколько стоит" to "how much does it cost", "где находится" to "where is",
        "как пройти" to "how to get to", "я не понимаю" to "I don't understand", "помоги мне" to "help me",
        "добро пожаловать" to "welcome", "с днём рождения" to "happy birthday", "поздравляю" to "congratulations",
        "удачи" to "good luck", "береги себя" to "take care", "нет проблем" to "no problem",
        "конечно" to "of course", "возможно" to "maybe", "точно" to "exactly", "правда" to "true",
        "вода" to "water", "кофе" to "coffee", "чай" to "tea", "еда" to "food", "ресторан" to "restaurant",
        "отель" to "hotel", "аэропорт" to "airport", "билет" to "ticket", "поезд" to "train", "такси" to "taxi",
        "машина" to "car", "улица" to "street", "город" to "city", "страна" to "country", "мир" to "world",
        "человек" to "person", "люди" to "people", "мужчина" to "man", "женщина" to "woman", "ребёнок" to "child",
        "утро" to "morning", "вечер" to "evening", "неделя" to "week", "месяц" to "month", "год" to "year",
        "понедельник" to "Monday", "вторник" to "Tuesday", "среда" to "Wednesday", "четверг" to "Thursday",
        "пятница" to "Friday", "суббота" to "Saturday", "воскресенье" to "Sunday",
        "белый" to "white", "чёрный" to "black", "красный" to "red", "синий" to "blue", "зелёный" to "green",
        "жёлтый" to "yellow", "один" to "one", "два" to "two", "три" to "three", "четыре" to "four", "пять" to "five"
    )

    private val EN_RU_DICT: Map<String, String> = RU_EN_DICT.entries.associate { (ru, en) -> en.lowercase() to ru }

    fun convertEnToRu(text: String): String =
        buildString(text.length) { for (ch in text) append(EN_TO_RU[ch] ?: ch) }

    fun convertRuToEn(text: String): String =
        buildString(text.length) { for (ch in text) append(RU_TO_EN[ch] ?: ch) }

    /** Punto Switcher conversion for any selected text (auto-detects dominant script) */
    fun switchLayoutAuto(text: String): String {
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val lat = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (cyr >= lat) convertRuToEn(text) else convertEnToRu(text)
    }

    /**
     * Detects if `composing` was typed in the wrong keyboard layout (e.g. "ghbdtn" in EN -> "привет" in RU)
     * and verifies the converted word exists in the opposite language dictionary.
     */
    fun detectWrongLayoutWord(
        composing: String,
        activeLang: String,
        dictEngine: BreenoDictionaryEngine
    ): String? {
        if (composing.length < 3) return null
        val converted = if (activeLang == "EN") convertEnToRu(composing) else convertRuToEn(composing)
        if (converted.equals(composing, ignoreCase = true)) return null
        val targetLang = if (activeLang == "EN") "RU" else "EN"
        // Confirm original is NOT a known word in activeLang, while converted IS a valid word in targetLang
        val origScore = dictEngine.scorePrefixContinuation(composing.dropLast(1), composing.last(), activeLang)
        if (origScore > 5000L) return null
        val convSuggestions = dictEngine.getSuggestions(converted, targetLang, maxCount = 1)
        val top = convSuggestions.firstOrNull() ?: return null
        return if (top.equals(converted, ignoreCase = true)) converted else null
    }

    /** Fast local offline RU <-> EN word & phrase translator */
    fun translateOffline(input: String, forceRuToEn: Boolean? = null): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val cyr = trimmed.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val isRuSource = forceRuToEn ?: (cyr > 0)
        val dict = if (isRuSource) RU_EN_DICT else EN_RU_DICT
        val lower = trimmed.lowercase()
        dict[lower]?.let { return preserveLeadingCase(it, trimmed) }

        // Word-by-word fallback with punctuation preservation
        val tokens = trimmed.split(Regex("(?<=\\s)|(?=\\s)"))
        val translated = tokens.joinToString("") { tok ->
            if (tok.isBlank()) {
                tok
            } else {
                val core = tok.trim('.', ',', '!', '?', ':', ';')
                val lead = tok.substring(0, tok.indexOf(core).coerceAtLeast(0))
                val trail = tok.substring((tok.indexOf(core) + core.length).coerceAtMost(tok.length))
                val coreLow = core.lowercase()
                val hit = dict[coreLow] ?: if (isRuSource && coreLow.length >= 5) {
                    val stems = listOf(1, 2).mapNotNull { drop ->
                        val base = coreLow.dropLast(drop)
                        listOf("", "а", "о", "е", "и", "ы", "ь", "й").firstNotNullOfOrNull { suf -> dict[base + suf] }
                    }
                    stems.firstOrNull()
                } else null
                if (hit != null) lead + preserveLeadingCase(hit, core) + trail else tok
            }
        }
        return translated
    }

    private fun preserveLeadingCase(target: String, sample: String): String {
        if (sample.isNotEmpty() && sample[0].isUpperCase()) {
            return target.replaceFirstChar { it.uppercaseChar() }
        }
        return target
    }
}
