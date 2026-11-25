package com.claritykey.keyboard.engine

import android.net.Uri
import java.security.SecureRandom

enum class UnicodeStyle(val shortLabel: String) {
    NORMAL("Стиль"),
    BOLD("𝐁𝐨𝐥𝐝"),
    ITALIC("𝘐𝘵𝘢𝘭"),
    MONO("𝙼𝚘𝚗𝚘"),
    STRIKE("З̶а̶ч̶ё̶р̶")
}

data class SmartListAction(
    val deleteCharsBefore: Int = 0,
    val insertText: String = ""
)

/**
 * Lightweight 100% offline text & privacy power utilities (v3.9):
 * - #1 Clean URL (strips utm_*, fbclid, yclid, ysclid, gclid, si, etc.)
 * - #2 SecureRandom 16-char password generator
 * - #3 Smart list continuation on Enter (• , - , 1. )
 * - #6 Bidirectional RU <-> LAT transliteration
 * - #7 Clean messy text (zero-width chars, double spaces, extra blank lines)
 * - #8 Character & word counter
 * - #9 Unicode text styles (Bold, Italic, Mono, Strikethrough)
 * - #10 Kaomoji bank
 */
object TextPowerTools {

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "yclid", "ysclid", "si", "igshid",
        "mc_cid", "mc_eid", "ref", "ref_src", "ref_url", "spm", "scm",
        "from", "feature", "app", "s", "t", "ved", "ei", "oq"
    )

    private val SECURE_RNG = SecureRandom()

    val KAOMOJI_LIST: List<String> = listOf(
        "¯\\_(ツ)_/¯", "(づ｡◕‿‿◕｡)づ", "(╯°□°)╯︵ ┻━┻",
        "(•_•)", "(⌐■_■)", "ʕ•ᴥ•ʔ",
        "(¬‿¬)", "ಥ_ಥ", "(☞ﾟヮﾟ)☞",
        "(｡♥‿♥｡)", "(o_O)", "(-_-;)",
        "\\(^o^)/", "(≧◡≦)", "(ಥ﹏ಥ)",
        "(⊙_⊙)", "(ง •̀_•́)ง", "(｡•́︿•̀｡)",
        "٩(◕‿◕｡)۶", "(￣▽￣)ノ", "(*^▽^*)",
        "(；一_一)", "(ノ_<。)", "(=^･ω･^=)"
    )

    /** #1 Clean URL: returns tracker-free URL if at least 1 tracking param was stripped, else null */
    fun stripTrackingParams(rawText: String): String? {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return null
        }
        if (trimmed.contains(' ') || trimmed.contains('\n') || !trimmed.contains('?')) return null
        return try {
            val uri = Uri.parse(trimmed)
            val names = uri.queryParameterNames
            if (names.isEmpty()) return null
            var removedAny = false
            val builder = uri.buildUpon().clearQuery()
            for (name in names) {
                val low = name.lowercase()
                if (low.startsWith("utm_") || low in TRACKING_PARAMS) {
                    removedAny = true
                } else {
                    uri.getQueryParameters(name).forEach { v ->
                        builder.appendQueryParameter(name, v)
                    }
                }
            }
            if (!removedAny) null else builder.build().toString()
        } catch (_: Exception) {
            null
        }
    }

    /** #2 Generate strong local 16-char password using SecureRandom */
    fun generateSecurePassword(length: Int = 16): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val syms = "!@#\$%&*?-_"
        val all = upper + lower + digits + syms
        val chars = CharArray(length.coerceAtLeast(12))
        chars[0] = upper[SECURE_RNG.nextInt(upper.length)]
        chars[1] = lower[SECURE_RNG.nextInt(lower.length)]
        chars[2] = digits[SECURE_RNG.nextInt(digits.length)]
        chars[3] = syms[SECURE_RNG.nextInt(syms.length)]
        for (i in 4 until chars.size) {
            chars[i] = all[SECURE_RNG.nextInt(all.length)]
        }
        // Fisher-Yates shuffle
        for (i in chars.lastIndex downTo 1) {
            val j = SECURE_RNG.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    /** #3 Smart list continuation on Enter */
    private val NUMBERED_LIST_RX = Regex("^(\\s*)(\\d{1,3})([.)])\\s(.*)$")
    private val BULLET_LIST_RX = Regex("^(\\s*)([•\\-*])\\s(.*)$")

    fun computeSmartListContinuation(textBeforeCursor: String): SmartListAction? {
        val lastNl = textBeforeCursor.lastIndexOf('\n')
        val line = if (lastNl >= 0) textBeforeCursor.substring(lastNl + 1) else textBeforeCursor

        BULLET_LIST_RX.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1]
            val bullet = m.groupValues[2]
            val body = m.groupValues[3]
            return if (body.isBlank()) {
                SmartListAction(deleteCharsBefore = line.length, insertText = "")
            } else {
                SmartListAction(deleteCharsBefore = 0, insertText = "\n$indent$bullet ")
            }
        }

        NUMBERED_LIST_RX.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1]
            val num = m.groupValues[2].toIntOrNull() ?: return null
            val sep = m.groupValues[3]
            val body = m.groupValues[4]
            return if (body.isBlank()) {
                SmartListAction(deleteCharsBefore = line.length, insertText = "")
            } else {
                SmartListAction(deleteCharsBefore = 0, insertText = "\n$indent${num + 1}$sep ")
            }
        }
        return null
    }

    /** #6 Bidirectional Russian <-> Latin transliteration */
    private val RU_TO_LAT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val LAT_DIGRAPHS = listOf(
        "shch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч",
        "sh" to "ш", "yo" to "ё", "yu" to "ю", "ya" to "я"
    )

    private val LAT_SINGLE = mapOf(
        'a' to 'а', 'b' to 'б', 'v' to 'в', 'g' to 'г', 'd' to 'д', 'e' to 'е', 'z' to 'з',
        'i' to 'и', 'y' to 'й', 'k' to 'к', 'l' to 'л', 'm' to 'м', 'n' to 'н', 'o' to 'о',
        'p' to 'п', 'r' to 'р', 's' to 'с', 't' to 'т', 'u' to 'у', 'f' to 'ф', 'h' to 'х',
        'c' to 'ц', 'w' to 'в', 'j' to 'ж'
    )

    fun transliterateAuto(text: String): String {
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val lat = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (cyr >= lat) transliterateRuToLat(text) else transliterateLatToRu(text)
    }

    private fun transliterateRuToLat(text: String): String = buildString(text.length) {
        for (ch in text) {
            val low = ch.lowercaseChar()
            val mapped = RU_TO_LAT[low]
            if (mapped == null) {
                append(ch)
            } else if (ch.isUpperCase() && mapped.isNotEmpty()) {
                append(mapped.replaceFirstChar { it.uppercaseChar() })
            } else {
                append(mapped)
            }
        }
    }

    private fun transliterateLatToRu(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val restLow = text.substring(i).lowercase()
            var matched = false
            for ((latSeq, ruChar) in LAT_DIGRAPHS) {
                if (restLow.startsWith(latSeq)) {
                    val isUpper = text[i].isUpperCase()
                    append(if (isUpper) ruChar.uppercase() else ruChar)
                    i += latSeq.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = text[i]
                val mapped = LAT_SINGLE[ch.lowercaseChar()]
                if (mapped != null) {
                    append(if (ch.isUpperCase()) mapped.uppercaseChar() else mapped)
                } else {
                    append(ch)
                }
                i++
            }
        }
    }

    /** #7 Clean messy copied text (zero-width chars, repeated spaces, 3+ blank lines) */
    fun cleanMessyText(text: String): String {
        return text
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { line ->
                line.replace(Regex("[ \\t]{2,}"), " ").trim()
            }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** #8 Count characters & words */
    fun countStats(text: String): Pair<Int, Int> {
        val chars = text.length
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return chars to words
    }

    /** #9 Unicode text styles (Bold, Italic, Mono for Latin/digits, Strikethrough for RU + EN) */
    fun applyUnicodeStyle(text: String, style: UnicodeStyle): String {
        if (style == UnicodeStyle.NORMAL) return text
        if (style == UnicodeStyle.STRIKE) {
            return buildString(text.length * 2) {
                for (ch in text) {
                    append(ch)
                    if (!ch.isWhitespace()) append('\u0336')
                }
            }
        }
        return buildString(text.length * 2) {
            for (ch in text) {
                val cp = when (style) {
                    UnicodeStyle.BOLD -> when (ch) {
                        in 'A'..'Z' -> 0x1D400 + (ch - 'A')
                        in 'a'..'z' -> 0x1D41A + (ch - 'a')
                        in '0'..'9' -> 0x1D7CE + (ch - '0')
                        else -> -1
                    }
                    UnicodeStyle.ITALIC -> when (ch) {
                        in 'A'..'Z' -> 0x1D608 + (ch - 'A')
                        in 'a'..'z' -> 0x1D622 + (ch - 'a')
                        else -> -1
                    }
                    UnicodeStyle.MONO -> when (ch) {
                        in 'A'..'Z' -> 0x1D670 + (ch - 'A')
                        in 'a'..'z' -> 0x1D68A + (ch - 'a')
                        in '0'..'9' -> 0x1D7F6 + (ch - '0')
                        else -> -1
                    }
                    else -> -1
                }
                if (cp > 0) append(Character.toChars(cp)) else append(ch)
            }
        }
    }

    private val EMOJI_PREDICT_MAP = mapOf(
        "огонь" to "🔥", "fire" to "🔥", "кофе" to "☕", "coffee" to "☕",
        "люблю" to "❤️", "любовь" to "❤️", "сердце" to "❤️", "love" to "❤️",
        "спасибо" to "🙏", "благодарю" to "🙏", "thanks" to "🙏",
        "привет" to "👋", "здравствуй" to "👋", "hello" to "👋", "hi" to "👋",
        "смешно" to "😂", "ржу" to "😂", "хаха" to "😂", "lol" to "😂",
        "круто" to "😎", "класс" to "👍", "ок" to "👌", "хорошо" to "👍", "супер" to "🤩",
        "деньги" to "💸", "оплата" to "💳", "цена" to "💰", "скидка" to "🏷️",
        "дом" to "🏠", "работа" to "💼", "время" to "⏰", "часы" to "⌚",
        "погода" to "🌤️", "солнце" to "☀️", "дождь" to "🌧️", "снег" to "❄️",
        "машина" to "🚗", "такси" to "🚕", "поезд" to "🚆", "самолёт" to "✈️",
        "пицца" to "🍕", "еда" to "🍽️", "торт" to "🎂", "праздник" to "🎉",
        "подарок" to "🎁", "цветы" to "💐", "музыка" to "🎵", "фото" to "📸",
        "телефон" to "📱", "звонок" to "📞", "письмо" to "✉️", "замок" to "🔒",
        "грустно" to "😔", "плачу" to "😭", "ура" to "🎉", "звезда" to "⭐", "ночь" to "🌙"
    )

    fun predictEmojiForWord(word: String): String? {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return null
        return EMOJI_PREDICT_MAP[clean]
    }

    private val MIXED_RU_EN_WORDS = listOf(
        "ок" to "OK", "окей" to "OK", "вайфай" to "Wi-Fi", "вифи" to "Wi-Fi",
        "телеграм" to "Telegram", "тг" to "Telegram", "ватсап" to "WhatsApp",
        "гугл" to "Google", "андроид" to "Android", "айфон" to "iPhone",
        "пдф" to "PDF", "емейл" to "Email", "имейл" to "Email", "почта" to "Email",
        "ютуб" to "YouTube", "инста" to "Instagram", "вк" to "VK",
        "вип" to "VIP", "про" to "Pro", "макс" to "Max", "блютуз" to "Bluetooth",
        "юсб" to "USB", "хд" to "HD", "4к" to "4K", "онлайн" to "Online", "офлайн" to "Offline"
    )

    fun matchMixedEnglishWord(rawRu: String): String? {
        val clean = rawRu.trim().lowercase()
        if (clean.length < 2) return null
        return MIXED_RU_EN_WORDS.firstOrNull { it.first == clean }?.second
    }

    class UndoRedoStack(private val maxDepth: Int = 20) {
        private val undoStack = ArrayDeque<String>()
        private val redoStack = ArrayDeque<String>()
        private var lastSnapshot: String? = null

        fun record(snapshot: String) {
            if (snapshot == lastSnapshot) return
            val previous = lastSnapshot
            if (previous != null) {
                undoStack.addLast(previous)
                if (undoStack.size > maxDepth) undoStack.removeFirst()
                redoStack.clear()
            }
            lastSnapshot = snapshot
        }

        fun undo(current: String): String? {
            if (undoStack.isEmpty()) return null
            redoStack.addLast(current)
            val prev = undoStack.removeLast()
            lastSnapshot = prev
            return prev
        }

        fun redo(current: String): String? {
            if (redoStack.isEmpty()) return null
            undoStack.addLast(current)
            val next = redoStack.removeLast()
            lastSnapshot = next
            return next
        }

        fun clear() {
            undoStack.clear()
            redoStack.clear()
            lastSnapshot = null
        }
    }


    /**
     * Auto-typography replacements on the fly:
     * - "--" -> "—"
     * - "..." -> "…"
     * - "(c)" / "(с)" -> "©"
     * - '"' in RU -> '«' or '»'
     */
    fun applyAutoTypography(beforeCursor: String, newChar: String, lang: String): Pair<Int, String>? {
        if (newChar == "-" && beforeCursor.endsWith("-")) {
            return 1 to "—"
        }
        if (newChar == "." && beforeCursor.endsWith("..")) {
            return 2 to "…"
        }
        if (newChar == ")" && (beforeCursor.endsWith("(c", ignoreCase = true) || beforeCursor.endsWith("(с", ignoreCase = true))) {
            return 2 to "©"
        }
        if (newChar == "\"" && lang == "RU") {
            val lastOpen = beforeCursor.lastIndexOf('«')
            val lastClose = beforeCursor.lastIndexOf('»')
            val quote = if (lastOpen > lastClose) "»" else "«"
            return 0 to quote
        }
        return null
    }

    /**
     * Smart inline math for percentages and time offsets:
     * - "1500+15%" -> "1725"
     * - "2500-20%" -> "2000"
     * - "14:30+45м" -> "15:15"
     */
    fun tryEvalSmartMath(raw: String): String? {
        val clean = raw.trim().removeSuffix("=")
        val pctRegex = Regex("^(\\d+(?:[.,]\\d+)?)([+-])(\\d+(?:[.,]\\d+)?)%$")
        pctRegex.matchEntire(clean)?.let { m ->
            val base = m.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return null
            val pct = m.groupValues[3].replace(',', '.').toBigDecimalOrNull() ?: return null
            val delta = base.multiply(pct).movePointLeft(2)
            val result = if (m.groupValues[2] == "+") base.add(delta) else base.subtract(delta)
            return result.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
        val timeRegex = Regex("^(\\d{1,2}):(\\d{2})([+-])(\\d{1,3})[мm]$")
        timeRegex.matchEntire(clean)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) return null
            val delta = m.groupValues[4].toIntOrNull() ?: return null
            val total = h * 60 + min + if (m.groupValues[3] == "+") delta else -delta
            val norm = ((total % 1440) + 1440) % 1440
            return String.format(java.util.Locale.US, "%02d:%02d", norm / 60, norm % 60)
        }
        return null
    }

    /**
     * Offline smart quick-reply suggestion based on copied question/request text.
     * Returns a Pair(chipLabel, textToInsert) or null if not a recognized prompt.
     */
    fun suggestSmartReplyForClipboard(
        copiedText: String,
        savedEmail: String = "",
        savedPhone: String = "",
        savedAddress: String = ""
    ): Pair<String, String>? {
        val lc = copiedText.trim().lowercase()
        if (lc.length < 4 || lc.length > 260) return null
        if ((lc.contains("почт") || lc.contains("email") || lc.contains("e-mail") || lc.contains("мейл")) && savedEmail.isNotBlank()) {
            return "📧 $savedEmail" to savedEmail
        }
        if ((lc.contains("телефон") || lc.contains("номер") || lc.contains("позвон")) && savedPhone.isNotBlank()) {
            return "📞 $savedPhone" to savedPhone
        }
        if ((lc.contains("адрес") || lc.contains("куда ехать") || lc.contains("куда достав")) && savedAddress.isNotBlank()) {
            return "📍 $savedAddress" to savedAddress
        }
        if (lc.contains("во сколько") || lc.contains("когда удобн") || lc.contains("когда встрет")) {
            return "💬 Давайте в 15:00" to "Давайте в 15:00, удобно?"
        }
        if (lc.contains("как дела") || lc.contains("как ты")) {
            return "💬 Всё отлично, спасибо!" to "Всё отлично, спасибо! А у тебя?"
        }
        if (lc.contains("спасибо") || lc.contains("благодар")) {
            return "💬 Пожалуйста!" to "Пожалуйста, всегда рад помочь!"
        }
        return null
    }
}