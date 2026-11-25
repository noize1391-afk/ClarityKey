package com.claritykey.keyboard.engine

import android.content.Context
import android.graphics.PointF
import kotlin.math.ln
import com.claritykey.keyboard.prefs.AutoCorrectStrength
import com.claritykey.keyboard.prefs.BreenoPreferences
import kotlin.math.abs

/**
 * High-speed 3-tier prefix-indexed Russian & English dictionary engine.
 * Engineered for 200,000–250,000+ words per language with <1ms keypress lookup:
 * - Supports "word frequency" (FrequencyWords full/50k) and plain "word" formats
 * - 1-char, 2-char, and 3-char capped prefix buckets (by1<=160, by2<=200, by3<=250)
 *   keeping RAM lean and lookup sub-millisecond even with 450,000+ total words
 * - Rich built-in Russian & English next-word bigrams + adaptive session bigram learning
 * - Automatic 'е' -> 'ё' normalization and Levenshtein-1 typo correction
 */
class BreenoDictionaryEngine(
    private val context: Context,
    private val prefs: BreenoPreferences
) {
    companion object {
        private const val MAX_BY1_PER_BUCKET = 160
        private const val MAX_BY2_PER_BUCKET = 200
        private const val MAX_BY3_PER_BUCKET = 250
        private const val MAX_LEARNED_BIGRAM_KEYS = 400


        private val RU_NEIGHBORS_STATIC: Map<Char, String> = mapOf(
            'й' to "цуф", 'ц' to "йуфыв", 'у' to "цкыва", 'к' to "уеыап", 'е' to "кнвапё",
            'н' to "егапр", 'г' to "ншпро", 'ш' to "гщрол", 'щ' to "шзоло", 'з' to "щходж",
            'х' to "зъждэ", 'ъ' to "хэж", 'ё' to "е",
            'ф' to "йцыяч", 'ы' to "йцуфвячс", 'в' to "цукыачсм", 'а' to "укепвсми",
            'п' to "кенрамти", 'р' to "енгопить", 'о' to "нгшлртьб", 'л' to "гшщдоьбю",
            'д' to "шщзжлбю", 'ж' to "щзхэдю", 'э' to "зхъж",
            'я' to "фыч", 'ч' to "фывяс", 'с' to "ывачм", 'м' to "вапси",
            'и' to "апсмт", 'т' to "проиь", 'ь' to "ролтб", 'б' to "олдью", 'ю' to "лджб"
        )
        private val EN_NEIGHBORS_STATIC: Map<Char, String> = mapOf(
            'q' to "wa", 'w' to "qesa", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
            'y' to "tugh", 'u' to "yijh", 'i' to "uokj", 'o' to "iplk", 'p' to "ol",
            'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
            'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
            'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
            'n' to "bhjm", 'm' to "njk"
        )

        /**
         * Computes keyboard-aware neighbor typo penalty between same-length words.
         * - Exact char: 0
         * - Adjacent key on ЙЦУКЕН/QWERTY (or е<->ё): 1
         * - Non-neighbor substitution: 3
         * Supports up to 3 simultaneous neighbor-key typos for words of length >= 5 (and 2 for len 4).
         * Returns -1 if penalty exceeds threshold.
         */
        fun keyboardNeighborPenalty(typed: String, cand: String, lang: String = "RU"): Int {
            if (typed.length != cand.length || typed.length < 3) return -1
            val maxPenalty = when {
                typed.length >= 6 -> 3
                typed.length == 5 -> 3
                typed.length == 4 -> 2
                else -> 1
            }
            val map = if (lang == "RU") RU_NEIGHBORS_STATIC else EN_NEIGHBORS_STATIC
            var penalty = 0
            for (i in typed.indices) {
                val a = typed[i].lowercaseChar()
                val b = cand[i].lowercaseChar()
                if (a == b) continue
                if (lang == "RU" && ((a == 'е' && b == 'ё') || (a == 'ё' && b == 'е'))) continue
                val isNb = map[a]?.contains(b) == true || map[b]?.contains(a) == true
                penalty += if (isNb) 1 else 3
                if (penalty > maxPenalty) return -1
            }
            return penalty
        }

        /** Pure-Kotlin spatial cost helper shared with JVM unit tests. Returns -1.0 if out of range. */
        fun computeSpatialCost(
            word: String,
            tapCoords: List<Pair<Float, Float>>,
            keyCenters: Map<Char, Pair<Float, Float>>,
            keyW: Float,
            keyH: Float
        ): Double {
            if (word.length != tapCoords.size || word.isEmpty()) return -1.0
            val kw = keyW.coerceAtLeast(1f)
            val kh = keyH.coerceAtLeast(1f)
            var totalCost = 0.0
            for (i in word.indices) {
                val ch = word[i].lowercaseChar()
                val lookupCh = if (ch == 'ё' && !keyCenters.containsKey('ё')) 'е' else ch
                val center = keyCenters[lookupCh] ?: return -1.0
                val pt = tapCoords[i]
                val nx = ((pt.first - center.first) / kw) * 0.92f
                val ny = ((pt.second - center.second) / kh) * 1.18f
                val dSq = nx * nx + ny * ny
                if (dSq > 2.25f) return -1.0
                totalCost += dSq
            }
            return totalCost / word.length
        }

        /** Pure-Kotlin Russian grammatical case/agreement context scorer for JVM unit tests. */
        fun scoreWordForContext(prevWord: String, candidateWord: String): Int {
            val p = prevWord.trim().lowercase()
            val w = candidateWord.trim().lowercase()
            if (p.isEmpty() || w.length < 3) return 0
            return when {
                p in setOf("хочу", "могу", "буду", "нужно", "надо", "можно", "планирую") ->
                    if (w.endsWith("ть") || w.endsWith("ться") || w.endsWith("ти") || w.endsWith("чь")) 12000 else 0
                p in setOf("без", "для", "до", "из", "от", "после", "у", "около", "нет") ->
                    if (w.endsWith("ого") || w.endsWith("его") || w.endsWith("ов") || w.endsWith("ев") ||
                        w.endsWith("ей") || w.endsWith("ых") || w.endsWith("их") || w.endsWith("ой") ||
                        w.endsWith("ы") || w.endsWith("и") || w.endsWith("я")) 6500 else 0
                p in setOf("с", "со", "над", "перед", "между", "доволен", "пользоваться") ->
                    if (w.endsWith("ом") || w.endsWith("ем") || w.endsWith("ой") || w.endsWith("ей") ||
                        w.endsWith("ью") || w.endsWith("ами") || w.endsWith("ями") || w.endsWith("ым") || w.endsWith("им")) 7500 else 0
                p in setOf("пять", "шесть", "семь", "восемь", "девять", "десять", "много", "мало", "несколько") ->
                    if (w.endsWith("ов") || w.endsWith("ев") || w.endsWith("ей") || w.endsWith("ых") || w.endsWith("их")) 7500 else 0
                p.endsWith("ую") || p.endsWith("юю") ->
                    if (w.endsWith("у") || w.endsWith("ю") || w.endsWith("ь")) 7500 else 0
                else -> 0
            }
        }
    }

    private data class WordEntry(val word: String, val freq: Int)

    private class LangIndex(initialExactCapacity: Int) {
        val exact = HashMap<String, Int>(initialExactCapacity)
        val by1 = HashMap<Char, ArrayList<WordEntry>>(40)
        val by2 = HashMap<String, ArrayList<WordEntry>>(1400)
        val by3 = HashMap<String, ArrayList<WordEntry>>(24000)

        fun add(word: String, freq: Int) {
            if (word.isEmpty()) return
            val prev = exact[word]
            if (prev != null && prev >= freq) return
            exact[word] = freq
            val entry = WordEntry(word, freq)

            // Dictionaries are pre-sorted by descending frequency; capping bucket size
            // during load prevents storing hundreds of thousands of redundant references.
            val b1 = by1.getOrPut(word[0]) { ArrayList(32) }
            if (b1.size < MAX_BY1_PER_BUCKET) {
                b1.add(entry)
            }
            if (word.length >= 2) {
                val p2 = word.substring(0, 2)
                val b2 = by2.getOrPut(p2) { ArrayList(24) }
                if (b2.size < MAX_BY2_PER_BUCKET) {
                    b2.add(entry)
                }
            }
            if (word.length >= 3) {
                val p3 = word.substring(0, 3)
                val b3 = by3.getOrPut(p3) { ArrayList(16) }
                if (b3.size < MAX_BY3_PER_BUCKET) {
                    b3.add(entry)
                }
            }
        }

        fun finalizeIndex() {
            by1.values.forEach { list -> list.sortByDescending { it.freq } }
            by2.values.forEach { list -> list.sortByDescending { it.freq } }
            by3.values.forEach { list -> list.sortByDescending { it.freq } }
        }
    }

    private val ruIndex = LangIndex(260_000)
    private val enIndex = LangIndex(210_000)

    // Adaptive in-memory bigram learning (never populated in password/incognito fields)
    private val learnedRuBigrams = HashMap<String, LinkedHashMap<String, Int>>()
    private val learnedEnBigrams = HashMap<String, LinkedHashMap<String, Int>>()

    @Volatile
    private var loaded = false

    val ruWordCount: Int get() = ruIndex.exact.size
    val enWordCount: Int get() = enIndex.exact.size

    private val ruBigrams: Map<String, List<String>> = mapOf(
        "привет" to listOf("как", "что", "брат"),
        "здравствуйте" to listOf("подскажите", "спасибо", "можно"),
        "доброе" to listOf("утро", "дело", "время"),
        "добрый" to listOf("день", "вечер", "путь"),
        "доброй" to listOf("ночи", "дороги", "недели"),
        "спокойной" to listOf("ночи", "жизни", "работы"),
        "как" to listOf("дела", "ты", "раз"),
        "спасибо" to listOf("большое", "огромное", "за"),
        "большое" to listOf("спасибо", "дело", "значение"),
        "пожалуйста" to listOf("подскажите", "проверьте", "отправьте"),
        "извини" to listOf("что", "за", "пожалуйста"),
        "извините" to listOf("за", "пожалуйста", "можно"),
        "я" to listOf("не", "уже", "сейчас"),
        "ты" to listOf("где", "как", "уже"),
        "вы" to listOf("можете", "уже", "где"),
        "мы" to listOf("уже", "сегодня", "будем"),
        "он" to listOf("уже", "не", "сказал"),
        "она" to listOf("уже", "не", "сказала"),
        "они" to listOf("уже", "не", "будут"),
        "всё" to listOf("хорошо", "отлично", "будет"),
        "все" to listOf("хорошо", "равно", "будет"),
        "у" to listOf("меня", "тебя", "нас"),
        "меня" to listOf("есть", "нет", "сейчас"),
        "тебя" to listOf("есть", "как", "получится"),
        "нас" to listOf("есть", "сегодня", "всё"),
        "не" to listOf("знаю", "могу", "буду"),
        "нет" to listOf("проблем", "ещё", "времени"),
        "да" to listOf("конечно", "хорошо", "сейчас"),
        "на" to listOf("работе", "связи", "месте"),
        "в" to listOf("общем", "принципе", "итоге"),
        "по" to listOf("поводу", "делу", "телефону"),
        "до" to listOf("связи", "встречи", "завтра"),
        "за" to listOf("информацию", "помощь", "ответ"),
        "с" to listOf("удовольствием", "утра", "собой"),
        "от" to listOf("меня", "него", "всей"),
        "для" to listOf("тебя", "меня", "этого"),
        "что" to listOf("делаешь", "случилось", "нового"),
        "где" to listOf("ты", "вы", "сейчас"),
        "когда" to listOf("будешь", "сможешь", "приедешь"),
        "почему" to listOf("ты", "так", "не"),
        "сколько" to listOf("стоит", "времени", "нужно"),
        "какой" to listOf("именно", "размер", "номер"),
        "какая" to listOf("цена", "разница", "модель"),
        "можно" to listOf("будет", "ли", "сейчас"),
        "нужно" to listOf("сделать", "будет", "проверить"),
        "надо" to listOf("будет", "сделать", "посмотреть"),
        "хочу" to listOf("сказать", "посмотреть", "спросить"),
        "могу" to listOf("сегодня", "завтра", "помочь"),
        "буду" to listOf("через", "дома", "ждать"),
        "будет" to listOf("готово", "удобно", "отлично"),
        "есть" to listOf("вопрос", "время", "вариант"),
        "сейчас" to listOf("посмотрю", "сделаю", "приду"),
        "сегодня" to listOf("вечером", "буду", "успею"),
        "завтра" to listOf("утром", "днём", "после"),
        "через" to listOf("час", "минут", "пару"),
        "потом" to listOf("напишу", "позвоню", "посмотрим"),
        "скоро" to listOf("буду", "приеду", "напишу"),
        "уже" to listOf("еду", "дома", "готово"),
        "ещё" to listOf("раз", "не", "чуть"),
        "очень" to listOf("хорошо", "круто", "удобно"),
        "хорошо" to listOf("спасибо", "договорились", "тогда"),
        "отлично" to listOf("спасибо", "тогда", "договорились"),
        "понял" to listOf("спасибо", "хорошо", "сейчас"),
        "понятно" to listOf("спасибо", "хорошо", "тогда"),
        "ладно" to listOf("давай", "тогда", "пока"),
        "давай" to listOf("завтра", "сегодня", "позже"),
        "давайте" to listOf("завтра", "сегодня", "обсудим"),
        "скинь" to listOf("пожалуйста", "ссылку", "фото"),
        "отправь" to listOf("пожалуйста", "мне", "сюда"),
        "напиши" to listOf("как", "когда", "мне"),
        "позвони" to listOf("мне", "как", "когда"),
        "подскажи" to listOf("пожалуйста", "как", "где"),
        "если" to listOf("что", "удобно", "получится"),
        "тогда" to listOf("давай", "завтра", "до"),
        "просто" to listOf("хотел", "думаю", "так"),
        "конечно" to listOf("можно", "буду", "давай"),
        "надеюсь" to listOf("всё", "что", "успеем"),
        "думаю" to listOf("что", "да", "лучше"),
        "кажется" to listOf("что", "это", "тут"),
        "может" to listOf("быть", "завтра", "позже")
    )

    private val enBigrams: Map<String, List<String>> = mapOf(
        "hello" to listOf("how", "there", "everyone"),
        "hi" to listOf("there", "how", "everyone"),
        "hey" to listOf("there", "how", "man"),
        "how" to listOf("are", "much", "is"),
        "what" to listOf("are", "is", "do"),
        "where" to listOf("are", "is", "do"),
        "when" to listOf("are", "will", "do"),
        "thank" to listOf("you", "god", "goodness"),
        "thanks" to listOf("a", "for", "so"),
        "good" to listOf("morning", "night", "luck"),
        "have" to listOf("a", "you", "to"),
        "see" to listOf("you", "what", "if"),
        "i" to listOf("am", "will", "have"),
        "i'm" to listOf("not", "going", "here"),
        "you" to listOf("are", "can", "have"),
        "we" to listOf("are", "can", "will"),
        "they" to listOf("are", "will", "have"),
        "are" to listOf("you", "we", "they"),
        "do" to listOf("you", "not", "it"),
        "can" to listOf("you", "we", "i"),
        "could" to listOf("you", "we", "be"),
        "would" to listOf("you", "be", "like"),
        "will" to listOf("be", "do", "send"),
        "let" to listOf("me", "us", "it"),
        "let's" to listOf("go", "do", "see"),
        "please" to listOf("let", "send", "check"),
        "sorry" to listOf("for", "about", "i"),
        "no" to listOf("problem", "worries", "way"),
        "sounds" to listOf("good", "great", "like"),
        "looking" to listOf("forward", "for", "at"),
        "on" to listOf("my", "the", "it"),
        "in" to listOf("the", "a", "case"),
        "for" to listOf("the", "you", "me"),
        "with" to listOf("you", "the", "me"),
        "at" to listOf("the", "home", "work"),
        "to" to listOf("be", "do", "see"),
        "just" to listOf("wanted", "let", "got"),
        "right" to listOf("now", "here", "away")
    )

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadAssetDict("ru_freq_dict.txt", ruIndex, isCyrillic = true)
            loadAssetDict("en_freq_dict.txt", enIndex, isCyrillic = false)
            ruIndex.finalizeIndex()
            enIndex.finalizeIndex()
            loaded = true
        }
    }

    private fun loadAssetDict(assetName: String, target: LangIndex, isCyrillic: Boolean) {
        try {
            context.assets.open(assetName).bufferedReader(Charsets.UTF_8).useLines { lines ->
                var fallbackRank = 10_000_000
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val spaceIdx = line.indexOf(' ')
                    val word: String
                    val freq: Int
                    if (spaceIdx > 0) {
                        word = line.substring(0, spaceIdx).lowercase()
                        freq = line.substring(spaceIdx + 1).trim().toIntOrNull() ?: fallbackRank
                    } else {
                        word = line.lowercase()
                        freq = fallbackRank
                    }
                    fallbackRank = (fallbackRank - 1).coerceAtLeast(1)
                    if (word.isEmpty() || word.length > 24) continue
                    val valid = if (isCyrillic) {
                        word.all { it in 'а'..'я' || it == 'ё' || it == '-' }
                    } else {
                        word.all { it in 'a'..'z' || it == '\'' }
                    }
                    if (valid) target.add(word, freq)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Records a user-typed word pair (prevWord -> nextWord) in session memory
     * to enrich next-word sentence predictions. Never call for password/incognito fields.
     */
    fun learnBigram(previousWord: String, nextWord: String, lang: String) {
        val prev = previousWord.trim().lowercase()
        val next = nextWord.trim().lowercase()
        if (prev.isEmpty() || next.length < 2 || prev == next) return
        val store = if (lang == "RU") learnedRuBigrams else learnedEnBigrams
        synchronized(store) {
            if (store.size >= MAX_LEARNED_BIGRAM_KEYS && !store.containsKey(prev)) {
                val oldest = store.keys.firstOrNull()
                if (oldest != null) store.remove(oldest)
            }
            val map = store.getOrPut(prev) { LinkedHashMap() }
            map[next] = (map[next] ?: 0) + 1
        }
    }

    fun clearLearnedBigrams() {
        synchronized(learnedRuBigrams) { learnedRuBigrams.clear() }
        synchronized(learnedEnBigrams) { learnedEnBigrams.clear() }
    }

    /**
     * Scores how probable `composing + nextChar` is as a word prefix in the active language.
     * Used by TouchCalibrationModel to disambiguate border taps between neighboring keys.
     */
    fun scorePrefixContinuation(composing: String, nextChar: Char, lang: String): Long {
        if (!loaded) return 0L
        val prefix = (composing + nextChar).lowercase()
        val idx = if (lang == "RU") ruIndex else enIndex
        var best = idx.exact[prefix]?.toLong() ?: 0L
        val bucket = when {
            prefix.length >= 3 -> idx.by3[prefix.substring(0, 3)]
            prefix.length == 2 -> idx.by2[prefix]
            else -> idx.by1[prefix[0]]
        }
        if (bucket != null) {
            for (entry in bucket) {
                if (entry.word.startsWith(prefix)) {
                    if (entry.freq.toLong() > best) best = entry.freq.toLong()
                    break // buckets are sorted descending by frequency
                }
            }
        }
        return best
    }

    /**
     * Retrieves frequency-ranked candidate words for SwipeGestureDecoder matching
     * start/end letter neighborhoods.
     */
    fun getSwipeCandidateWords(
        startChars: List<Char>,
        endChars: List<Char>,
        lang: String,
        minLen: Int
    ): List<Pair<String, Int>> {
        ensureLoaded()
        val idx = if (lang == "RU") ruIndex else enIndex
        val endSet = HashSet<Char>(endChars.size + 1)
        endChars.forEach {
            endSet.add(it)
            if (it == 'е') endSet.add('ё')
        }
        val out = ArrayList<Pair<String, Int>>(260)
        val seen = HashSet<String>()

        // 1. Personal dictionary words first (boosted frequency)
        for (uw in prefs.getUserWords()) {
            if (uw.length >= minLen && startChars.contains(uw[0]) && endSet.contains(uw.last())) {
                if (seen.add(uw)) {
                    out.add(uw to (50_000 + prefs.getUserWordWeight(uw) * 2000))
                }
            }
        }

        // 2. Top frequency words from by1 buckets of startChars
        for (sc in startChars) {
            val b1 = idx.by1[sc] ?: continue
            for (entry in b1) {
                if (entry.word.length >= minLen && endSet.contains(entry.word.last())) {
                    if (seen.add(entry.word)) {
                        out.add(entry.word to entry.freq)
                        if (out.size >= 320) return out
                    }
                }
            }
        }
        return out
    }

    fun getNextWordSuggestions(previousWord: String, lang: String, maxCount: Int = 3): List<String> {
        val key = previousWord.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val blocked = prefs.getBlockedWords()
        val results = ArrayList<String>(maxCount)
        val seen = HashSet<String>()

        // 1. User's adaptive learned bigrams first
        val store = if (lang == "RU") learnedRuBigrams else learnedEnBigrams
        synchronized(store) {
            store[key]?.entries
                ?.sortedByDescending { it.value }
                ?.forEach { (w, _) ->
                    if (results.size < maxCount && w.lowercase() !in blocked && seen.add(w)) results.add(w)
                }
        }

        // 2. Built-in high-frequency bigrams
        if (results.size < maxCount) {
            val map = if (lang == "RU") ruBigrams else enBigrams
            map[key]?.forEach { w ->
                if (results.size < maxCount && w.lowercase() !in blocked && seen.add(w)) results.add(w)
            }
        }
        return results
    }

    /**
     * Returns Gboard-style 3-slot suggestions using 3-tier prefix buckets (by1 / by2 / by3):
     * Slot 0: exact typed prefix (if 1 char) or top exact/user match
     * Slots 1..2: highest-frequency completions from 200k–250k+ vocabulary!
     */
    private val ruKeyNeighbors: Map<Char, String> = mapOf(
        'й' to "цуф", 'ц' to "йуфыв", 'у' to "цкыва", 'к' to "уеыап", 'е' to "кнвап",
        'н' to "егапр", 'г' to "ншпро", 'ш' to "гщрол", 'щ' to "шзоло", 'з' to "щходж",
        'х' to "зъждэ", 'ъ' to "хэж", 'ё' to "е12",
        'ф' to "йцыяч", 'ы' to "йцуфвячс", 'в' to "цукыачсм", 'а' to "укепвсми",
        'п' to "кенрамти", 'р' to "енгопить", 'о' to "нгшлртьб", 'л' to "гшщдоьбю",
        'д' to "шщзжлбю", 'ж' to "щзхэдю", 'э' to "зхъж",
        'я' to "фыч", 'ч' to "фывяс", 'с' to "ывачм", 'м' to "вапси",
        'и' to "апсмт", 'т' to "проиь", 'ь' to "ролтб", 'б' to "олдью", 'ю' to "лджб"
    )

    private val enKeyNeighbors: Map<Char, String> = mapOf(
        'q' to "wa", 'w' to "qesa", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
        'y' to "tugh", 'u' to "yijh", 'i' to "uokj", 'o' to "iplk", 'p' to "ol",
        'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
        'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
        'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
        'n' to "bhjm", 'm' to "njk"
    )

    private fun isNeighborKey(a: Char, b: Char, lang: String): Boolean {
        if (a == b) return true
        val map = if (lang == "RU") ruKeyNeighbors else enKeyNeighbors
        return map[a]?.contains(b) == true
    }

    private fun neighborSubstitutionScore(typed: String, cand: String, lang: String): Int {
        if (typed.length != cand.length) {
            // 1-char insertion or omission still gets mild score
            return 1500
        }
        var diffIdx = -1
        var diffs = 0
        for (i in typed.indices) {
            if (typed[i] != cand[i]) {
                diffs++
                diffIdx = i
                if (diffs > 1) return 0
            }
        }
        if (diffs == 1 && diffIdx >= 0) {
            return if (isNeighborKey(typed[diffIdx], cand[diffIdx], lang)) 9500 else 2000
        }
        return 0
    }

    private enum class ExpectedCase {
        GENITIVE, DATIVE, ACCUSATIVE, INSTRUMENTAL, PREPOSITIONAL,
        INFINITIVE, PLURAL_GEN, FEM_OBLIQUE, FEM_ACC, MASC_PREP, NEUT_NOM,
        MASC_NOM, FEM_NOM, PLURAL_NOM
    }

    private fun inferExpectedCases(prevWord: String): Set<ExpectedCase> {
        val p = prevWord.trim().lowercase()
        if (p.isEmpty()) return emptySet()
        val out = HashSet<ExpectedCase>(4)
        when (p) {
            "без", "для", "до", "из", "изо", "от", "ото", "после", "у", "около",
            "вокруг", "вместо", "кроме", "ради", "среди", "против", "нет",
            "много", "мало", "сколько", "несколько", "больше", "меньше", "пару" ->
                out.add(ExpectedCase.GENITIVE)

            "к", "ко", "по", "благодаря", "вопреки", "навстречу", "согласно",
            "позвонить", "позвони", "написать", "напиши", "отправить", "отправь",
            "передать", "передай", "сказать", "скажи", "ответить", "ответь",
            "помочь", "помоги", "показать", "покажи" ->
                out.add(ExpectedCase.DATIVE)

            "про", "через", "сквозь", "под", "купить", "куплю", "сделать", "сделаю",
            "посмотреть", "посмотрю", "открыть", "открою", "проверить", "проверю",
            "скачать", "найти", "взять", "дать", "прислать", "пришли", "скинуть", "скинь",
            "увидеть", "люблю", "жду", "ищу" ->
                out.add(ExpectedCase.ACCUSATIVE)

            "с", "со", "над", "надо", "перед", "передо", "между", "рядом",
            "доволен", "довольна", "заниматься", "стать", "является", "пользоваться" ->
                out.add(ExpectedCase.INSTRUMENTAL)

            "о", "об", "обо", "при" ->
                out.add(ExpectedCase.PREPOSITIONAL)

            "в", "во", "на" -> {
                out.add(ExpectedCase.PREPOSITIONAL)
                out.add(ExpectedCase.ACCUSATIVE)
            }

            "хочу", "хочешь", "хочет", "хотим", "хотят", "хотел", "хотела",
            "могу", "можешь", "может", "можем", "могут", "смогу", "сможешь",
            "буду", "будешь", "будет", "будем", "будут", "стану",
            "нужно", "надо", "можно", "нельзя", "стоит", "пора",
            "готов", "готова", "рад", "рада", "должен", "должна",
            "умею", "планирую", "собираюсь", "пытаюсь" ->
                out.add(ExpectedCase.INFINITIVE)
        }

        when (p) {
            "два", "три", "четыре", "оба", "обе" -> out.add(ExpectedCase.GENITIVE)
            "пять", "шесть", "семь", "восемь", "девять", "десять", "много", "мало", "несколько" ->
                out.add(ExpectedCase.PLURAL_GEN)
            "мой", "твой", "наш", "ваш", "этот", "какой", "один" -> out.add(ExpectedCase.MASC_NOM)
            "моя", "твоя", "наша", "ваша", "эта", "какая", "одна" -> out.add(ExpectedCase.FEM_NOM)
            "мои", "твои", "наши", "ваши", "эти", "какие", "все", "многие", "некоторые" ->
                out.add(ExpectedCase.PLURAL_NOM)
        }

        if (out.isEmpty() && p.length >= 4) {
            when {
                p.endsWith("ого") || p.endsWith("его") -> out.add(ExpectedCase.GENITIVE)
                p.endsWith("ому") || p.endsWith("ему") -> out.add(ExpectedCase.DATIVE)
                p.endsWith("ым") || p.endsWith("им") -> {
                    out.add(ExpectedCase.INSTRUMENTAL)
                    out.add(ExpectedCase.DATIVE)
                }
                p.endsWith("ом") || p.endsWith("ем") -> {
                    out.add(ExpectedCase.PREPOSITIONAL)
                    out.add(ExpectedCase.MASC_PREP)
                }
                p.endsWith("ой") || p.endsWith("ей") -> out.add(ExpectedCase.FEM_OBLIQUE)
                p.endsWith("ую") || p.endsWith("юю") -> out.add(ExpectedCase.FEM_ACC)
                p.endsWith("ых") || p.endsWith("их") -> out.add(ExpectedCase.PLURAL_GEN)
                p.endsWith("ая") || p.endsWith("яя") -> out.add(ExpectedCase.FEM_NOM)
                p.endsWith("ый") || p.endsWith("ий") -> out.add(ExpectedCase.MASC_NOM)
                p.endsWith("ые") || p.endsWith("ие") -> out.add(ExpectedCase.PLURAL_NOM)
                p.endsWith("ое") || p.endsWith("ее") -> out.add(ExpectedCase.NEUT_NOM)
            }
        }
        return out
    }

    private fun scoreWordForExpectedCases(word: String, expected: Set<ExpectedCase>): Int {
        if (expected.isEmpty() || word.length < 3) return 0
        var bonus = 0
        for (c in expected) {
            when (c) {
                ExpectedCase.INFINITIVE -> {
                    if (word.endsWith("ть") || word.endsWith("ться") || word.endsWith("ти") || word.endsWith("чь")) {
                        bonus = maxOf(bonus, 12000)
                    }
                }
                ExpectedCase.GENITIVE -> {
                    if (word.endsWith("ого") || word.endsWith("его") || word.endsWith("ов") ||
                        word.endsWith("ев") || word.endsWith("ей") || word.endsWith("ых") ||
                        word.endsWith("их") || word.endsWith("ой") || word.endsWith("а") ||
                        word.endsWith("я") || word.endsWith("ы") || word.endsWith("и")
                    ) {
                        bonus = maxOf(bonus, 6500)
                    }
                }
                ExpectedCase.DATIVE -> {
                    if (word.endsWith("ому") || word.endsWith("ему") || word.endsWith("ам") ||
                        word.endsWith("ям") || word.endsWith("ым") || word.endsWith("им") ||
                        word.endsWith("у") || word.endsWith("ю") || word.endsWith("е") || word.endsWith("и")
                    ) {
                        bonus = maxOf(bonus, 6500)
                    }
                }
                ExpectedCase.ACCUSATIVE -> {
                    if (word.endsWith("ую") || word.endsWith("юю") || word.endsWith("у") ||
                        word.endsWith("ю") || word.endsWith("а") || word.endsWith("я") ||
                        word.endsWith("о") || word.endsWith("е") || word.endsWith("ые") || word.endsWith("ие")
                    ) {
                        bonus = maxOf(bonus, 5500)
                    }
                }
                ExpectedCase.INSTRUMENTAL -> {
                    if (word.endsWith("ом") || word.endsWith("ем") || word.endsWith("ой") ||
                        word.endsWith("ей") || word.endsWith("ью") || word.endsWith("ами") ||
                        word.endsWith("ями") || word.endsWith("ым") || word.endsWith("им") ||
                        word.endsWith("ыми") || word.endsWith("ими")
                    ) {
                        bonus = maxOf(bonus, 7500)
                    }
                }
                ExpectedCase.PREPOSITIONAL, ExpectedCase.MASC_PREP -> {
                    if (word.endsWith("е") || word.endsWith("и") || word.endsWith("у") ||
                        word.endsWith("ом") || word.endsWith("ем") || word.endsWith("ой") ||
                        word.endsWith("ей") || word.endsWith("ах") || word.endsWith("ях") ||
                        word.endsWith("ых") || word.endsWith("их")
                    ) {
                        bonus = maxOf(bonus, 6500)
                    }
                }
                ExpectedCase.PLURAL_GEN -> {
                    if (word.endsWith("ов") || word.endsWith("ев") || word.endsWith("ей") ||
                        word.endsWith("ах") || word.endsWith("ях") || word.endsWith("ых") || word.endsWith("их")
                    ) {
                        bonus = maxOf(bonus, 7500)
                    }
                }
                ExpectedCase.FEM_OBLIQUE -> {
                    if (word.endsWith("ы") || word.endsWith("и") || word.endsWith("е") ||
                        word.endsWith("ой") || word.endsWith("ей") || word.endsWith("ью")
                    ) {
                        bonus = maxOf(bonus, 7000)
                    }
                }
                ExpectedCase.FEM_ACC -> {
                    if (word.endsWith("у") || word.endsWith("ю") || word.endsWith("ь")) {
                        bonus = maxOf(bonus, 7500)
                    }
                }
                ExpectedCase.NEUT_NOM -> {
                    if (word.endsWith("о") || word.endsWith("е") || word.endsWith("мя")) {
                        bonus = maxOf(bonus, 6500)
                    }
                }
                ExpectedCase.MASC_NOM -> {
                    val last = word.last()
                    if (last !in "аяоеуыи" || word.endsWith("й") || word.endsWith("ь")) {
                        bonus = maxOf(bonus, 5500)
                    }
                }
                ExpectedCase.FEM_NOM -> {
                    if (word.endsWith("а") || word.endsWith("я") || word.endsWith("ь")) {
                        bonus = maxOf(bonus, 6500)
                    }
                }
                ExpectedCase.PLURAL_NOM -> {
                    if (word.endsWith("ы") || word.endsWith("и") || word.endsWith("а") || word.endsWith("я")) {
                        bonus = maxOf(bonus, 6000)
                    }
                }
            }
        }
        return bonus
    }

    fun getSuggestions(
        composing: String,
        lang: String,
        maxCount: Int = 3,
        previousWord: String = ""
    ): List<String> {
        if (composing.isEmpty()) return emptyList()
        ensureLoaded()

        val lower = composing.lowercase()
        val idx = if (lang == "RU") ruIndex else enIndex
        val results = ArrayList<String>(maxCount + 2)
        val seen = HashSet<String>()

        val blocked = prefs.getBlockedWords()
        fun addUnique(w: String) {
            if (w.lowercase() in blocked) return
            val cased = applyCaseLike(w, composing)
            if (seen.add(cased.lowercase())) {
                results.add(cased)
            }
        }

        val contextNext = if (previousWord.isNotBlank()) {
            getNextWordSuggestions(previousWord, lang, maxCount = 16)
        } else emptyList()

        // Gboard behavior: for 1-char input, put typed letter on the left slot and context-aware top words next to it
        if (lower.length == 1) {
            addUnique(lower)
            for (cw in contextNext) {
                if (cw.length > 1 && cw.startsWith(lower)) {
                    addUnique(cw)
                    if (results.size >= maxCount) return results.take(maxCount)
                }
            }
            for (uw in prefs.getUserWords()) {
                if (uw.length > 1 && uw[0] == lower[0]) {
                    addUnique(uw)
                    if (results.size >= maxCount) return results.take(maxCount)
                }
            }
            val expectedCases = if (lang == "RU" && previousWord.isNotBlank()) inferExpectedCases(previousWord) else emptySet()
            val bucket = idx.by1[lower[0]]
            if (bucket != null) {
                if (expectedCases.isEmpty()) {
                    for (entry in bucket) {
                        if (entry.word.length >= 2) {
                            addUnique(entry.word)
                            if (results.size >= maxCount) break
                        }
                    }
                } else {
                    val scored = ArrayList<Pair<String, Int>>(48)
                    for (entry in bucket) {
                        if (entry.word.length >= 2) {
                            val sc = entry.freq + scoreWordForExpectedCases(entry.word, expectedCases)
                            scored.add(entry.word to sc)
                            if (scored.size >= 48) break
                        }
                    }
                    scored.sortByDescending { it.second }
                    for ((w, _) in scored) {
                        addUnique(w)
                        if (results.size >= maxCount) break
                    }
                }
            }
            return results.take(maxCount)
        }

        // 0. Context-predicted next words matching current prefix
        for (cw in contextNext) {
            if (cw.startsWith(lower)) {
                addUnique(cw)
                if (results.size >= maxCount) return results.take(maxCount)
            }
        }

        // 1. User dictionary prefix matches
        for (uw in prefs.getUserWords().sortedByDescending { prefs.getUserWordWeight(it) }) {
            if (uw.startsWith(lower)) {
                addUnique(uw)
                if (results.size >= maxCount) return results.take(maxCount)
            }
        }

        // 2. Exact match
        if (idx.exact.containsKey(lower)) {
            addUnique(lower)
        }

        // 3. Fast prefix bucket lookup with Russian grammatical case/agreement scoring
        if (results.size < maxCount) {
            val expectedCases = if (lang == "RU" && previousWord.isNotBlank()) inferExpectedCases(previousWord) else emptySet()
            val bucket = if (lower.length >= 3) {
                idx.by3[lower.substring(0, 3)]
            } else {
                idx.by2[lower.substring(0, 2)]
            }
            if (bucket != null) {
                if (expectedCases.isEmpty()) {
                    for (entry in bucket) {
                        if (entry.word.startsWith(lower)) {
                            addUnique(entry.word)
                            if (results.size >= maxCount) return results.take(maxCount)
                        }
                    }
                } else {
                    val scored = ArrayList<Pair<String, Int>>(60)
                    for (entry in bucket) {
                        if (entry.word.startsWith(lower)) {
                            val sc = entry.freq + scoreWordForExpectedCases(entry.word, expectedCases)
                            scored.add(entry.word to sc)
                            if (scored.size >= 60) break
                        }
                    }
                    scored.sortByDescending { it.second }
                    for ((w, _) in scored) {
                        addUnique(w)
                        if (results.size >= maxCount) return results.take(maxCount)
                    }
                }
            }
        }

        // 4. 'е' -> 'ё' match if Russian (exact + prefix in ё-variant bucket)
        if (lang == "RU" && lower.contains('е') && results.size < maxCount) {
            val yo = lower.replace('е', 'ё')
            if (idx.exact.containsKey(yo)) {
                addUnique(yo)
            } else if (yo.length >= 3) {
                val yoBucket = idx.by3[yo.substring(0, 3)]
                if (yoBucket != null) {
                    for (entry in yoBucket) {
                        if (entry.word.startsWith(yo)) {
                            addUnique(entry.word)
                            if (results.size >= maxCount) break
                        }
                    }
                }
            }
        }

        // 5. Fast 1-edit typo suggestions (by2 first for precision, then by1 top words)
        if (lower.length >= 3 && results.size < maxCount) {
            val b2 = idx.by2[lower.substring(0, 2)]
            if (b2 != null) {
                for (entry in b2) {
                    val w = entry.word
                    if (abs(w.length - lower.length) <= 1 && editDistanceAtMost1(lower, w)) {
                        addUnique(w)
                        if (results.size >= maxCount) return results.take(maxCount)
                    }
                }
            }
            val b1 = if (prefs.autoCorrectStrength == AutoCorrectStrength.SOFT) null else idx.by1[lower[0]]
            if (b1 != null) {
                for (entry in b1) {
                    val w = entry.word
                    if (abs(w.length - lower.length) <= 1 && editDistanceAtMost1(lower, w)) {
                        addUnique(w)
                        if (results.size >= maxCount) break
                    }
                }
            }
        }

        // 6. Full bilingual secondary-language suggestions (e.g. typing English words while in RU mode)
        if (prefs.mixedRuEnSuggestions && results.size < maxCount && lower.length >= 2) {
            val other = if (lang == "RU") enIndex else ruIndex
            val ob = if (lower.length >= 3) other.by3[lower.substring(0, 3)] else other.by2[lower.substring(0, 2)]
            if (ob != null) {
                for (entry in ob) {
                    if (entry.word.startsWith(lower)) {
                        addUnique(entry.word)
                        if (results.size >= maxCount) break
                    }
                }
            }
        }

        // Ensure raw typed word is always tappable if nothing matched
        if (results.isEmpty()) addUnique(lower)
        return results.take(maxCount)
    }

    fun getAutoCorrection(composing: String, lang: String, previousWord: String = ""): String? {
        if (!prefs.autoCorrect || composing.length < 2) return null
        // Never autocorrect numbers, identifiers, snake_case, camelCase, or ALLCAPS acronyms
        if (composing.any { it.isDigit() || it == '_' || it == '/' || it == '@' || it == '.' }) return null
        val isAllCaps = composing.length >= 2 && composing.all { !it.isLetter() || it.isUpperCase() }
        if (isAllCaps) return null
        ensureLoaded()
        val lower = composing.lowercase()
        val idx = if (lang == "RU") ruIndex else enIndex
        val otherIdx = if (lang == "RU") enIndex else ruIndex
        if (idx.exact.containsKey(lower) || prefs.getUserWords().contains(lower)) return null
        if (prefs.mixedRuEnSuggestions && otherIdx.exact.containsKey(lower)) return null

        // Check collapsed accidental double letters (e.g. "приввет" -> "привет")
        val collapsed = collapseDoubleLetters(lower)
        if (collapsed != lower && idx.exact.containsKey(collapsed)) {
            return applyCaseLike(collapsed, composing)
        }

        // Check adjacent character transposition (e.g. "првиет" -> "привет")
        if (lower.length >= 3) {
            val chars = lower.toCharArray()
            for (i in 0 until chars.size - 1) {
                val tmp = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = tmp
                val swapped = String(chars)
                if (idx.exact.containsKey(swapped)) {
                    return applyCaseLike(swapped, composing)
                }
                chars[i + 1] = chars[i]; chars[i] = tmp
            }
        }

        val strength = prefs.autoCorrectStrength

        // Check ё normalization first (exact replacement + by2/by1 check)
        if (lang == "RU" && lower.contains('е')) {
            val allYo = lower.replace('е', 'ё')
            if (idx.exact.containsKey(allYo)) {
                return applyCaseLike(allYo, composing)
            }
            val b2 = idx.by2[lower.substring(0, 2)]
            if (b2 != null) {
                for (entry in b2) {
                    val w = entry.word
                    if (w.length == lower.length && w.replace('ё', 'е') == lower) {
                        return applyCaseLike(w, composing)
                    }
                }
            }
            val b1 = idx.by1[lower[0]]
            if (b1 != null) {
                for (entry in b1) {
                    val w = entry.word
                    if (w.length == lower.length && w.replace('ё', 'е') == lower) {
                        return applyCaseLike(w, composing)
                    }
                }
            }
        }

        if (lower.length >= 3) {
            val expectedCases = if (lang == "RU" && previousWord.isNotBlank()) inferExpectedCases(previousWord) else emptySet()
            var bestWord: String? = null
            var bestScore = Int.MIN_VALUE

            fun consider(w: String, baseFreq: Int) {
                if (abs(w.length - lower.length) > 1 || !editDistanceAtMost1(lower, w)) return
                val nbScore = neighborSubstitutionScore(lower, w, lang)
                val morphScore = if (expectedCases.isNotEmpty()) scoreWordForExpectedCases(w, expectedCases) else 0
                val userScore = prefs.getUserWordWeight(w) * 250
                val total = baseFreq + nbScore + morphScore + userScore
                if (total > bestScore) {
                    bestScore = total
                    bestWord = w
                }
            }

            // 1. Personal dictionary words
            for (uw in prefs.getUserWords()) {
                consider(uw, 8000)
            }

            val maxBy2 = when (strength) {
                AutoCorrectStrength.SOFT -> 45
                AutoCorrectStrength.STANDARD -> 160
                AutoCorrectStrength.AGGRESSIVE -> 280
            }
            val b2 = idx.by2[lower.substring(0, 2)]
            if (b2 != null) {
                val lim = b2.size.coerceAtMost(maxBy2)
                for (k in 0 until lim) {
                    consider(b2[k].word, b2[k].freq)
                }
            }
            // 2. First-letter neighbor bucket check (catches typos on the very 1st letter like "лривет" -> "привет")
            val firstNeighbors = (if (lang == "RU") ruKeyNeighbors[lower[0]] else enKeyNeighbors[lower[0]]) ?: ""
            for (nb in firstNeighbors) {
                val nbBucket = idx.by2["$nb${lower[1]}"]
                if (nbBucket != null) {
                    val lim = nbBucket.size.coerceAtMost(60)
                    for (k in 0 until lim) {
                        consider(nbBucket[k].word, nbBucket[k].freq)
                    }
                }
            }
            if (bestWord == null) {
                val b1 = idx.by1[lower[0]]
                if (b1 != null) {
                    val lim = b1.size.coerceAtMost(200)
                    for (k in 0 until lim) {
                        consider(b1[k].word, b1[k].freq)
                    }
                }
            }
            bestWord?.let { return applyCaseLike(it, composing) }

            // 3. Multi-typo (up to 3 adjacent-key slips, e.g. "сргодгч" -> "сегодня", "лржвлуйста" -> "пожалуйста")
            if (lower.length >= 4 && strength != AutoCorrectStrength.SOFT) {
                var fuzzyBest: String? = null
                var fuzzyBestScore = Double.NEGATIVE_INFINITY
                val nb0 = (listOf(lower[0]) + (if (lang == "RU") ruKeyNeighbors[lower[0]] else enKeyNeighbors[lower[0]]).orEmpty().toList()).take(4)
                val nb1 = (listOf(lower[1]) + (if (lang == "RU") ruKeyNeighbors[lower[1]] else enKeyNeighbors[lower[1]]).orEmpty().toList()).take(4)
                for (a in nb0) {
                    for (b in nb1) {
                        val bkt = idx.by2["$a$b"] ?: continue
                        val lim = bkt.size.coerceAtMost(90)
                        for (k in 0 until lim) {
                            val entry = bkt[k]
                            if (entry.word.length != lower.length) continue
                            val pen = keyboardNeighborPenalty(lower, entry.word, lang)
                            if (pen in 1..3) {
                                val morph = if (expectedCases.isNotEmpty()) scoreWordForExpectedCases(entry.word, expectedCases) * 0.4 else 0.0
                                val sc = ln((entry.freq.coerceAtLeast(1) + 1).toDouble()) * 1800.0 - pen * 3200.0 + morph
                                if (sc > fuzzyBestScore) {
                                    fuzzyBestScore = sc
                                    fuzzyBest = entry.word
                                }
                            }
                        }
                    }
                }
                fuzzyBest?.let { return applyCaseLike(it, composing) }
            }
        }
        return null
    }

    private fun applyCaseLike(target: String, sample: String): String {
        if (sample.isEmpty()) return target
        val allUpper = sample.length > 1 && sample.all { !it.isLetter() || it.isUpperCase() }
        if (allUpper) return target.uppercase()
        if (sample[0].isUpperCase()) {
            return target.replaceFirstChar { it.uppercaseChar() }
        }
        return target
    }

    private fun collapseDoubleLetters(s: String): String {
        if (s.length < 3) return s
        val sb = StringBuilder(s.length)
        var prev = '\u0000'
        for (ch in s) {
            if (ch != prev) {
                sb.append(ch)
                prev = ch
            }
        }
        return sb.toString()
    }

    private fun editDistanceAtMost1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) {
                i++; j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    la > lb -> i++
                    lb > la -> j++
                    else -> { i++; j++ }
                }
            }
        }
        if (i < la || j < lb) edits++
        return edits <= 1
    }

    fun suggestSplitOrJoin(composing: String, prevWord: String, lang: String): Pair<String, Boolean>? {
        ensureLoaded()
        val idx = if (lang == "RU") ruIndex else enIndex
        val lower = composing.lowercase()
        // 1. Join check: prevWord + composing is a valid known word (e.g. "при" + "вет" -> "привет")
        if (prevWord.isNotBlank() && lower.isNotEmpty()) {
            val joined = prevWord.lowercase() + lower
            if (joined.length >= 4 && idx.exact.containsKey(joined)) {
                return applyCaseLike(joined, prevWord) to true
            }
        }
        // 2. Split check: composing >= 5 chars not in dict, split into two valid words
        if (lower.length >= 5 && !idx.exact.containsKey(lower)) {
            for (split in 2..(lower.length - 2)) {
                val left = lower.substring(0, split)
                val right = lower.substring(split)
                if ((idx.exact[left] ?: 0) >= 8 && (idx.exact[right] ?: 0) >= 8) {
                    return "${applyCaseLike(left, composing)} $right" to false
                }
            }
        }
        return null
    }

    fun getLikelyNextLetters(composing: String, lang: String): Set<Char> {
        if (composing.isEmpty()) return emptySet()
        ensureLoaded()
        val lower = composing.lowercase()
        val idx = if (lang == "RU") ruIndex else enIndex
        val bucket = when {
            lower.length >= 3 -> idx.by3[lower.substring(0, 3)]
            lower.length == 2 -> idx.by2[lower.substring(0, 2)]
            else -> idx.by1[lower[0]]
        } ?: return emptySet()
        val out = HashSet<Char>(8)
        var scanned = 0
        for (entry in bucket) {
            if (entry.word.length > lower.length && entry.word.startsWith(lower)) {
                out.add(entry.word[lower.length])
                scanned++
                if (scanned >= 14 || out.size >= 8) break
            }
        }
        return out
    }

    /**
     * Gboard-grade Full-Word Spatial Gaussian Decoder:
     * Scores candidate words using the exact (x, y) tap coordinates of EVERY key in the word,
     * rescuing 1, 2, or even 3 simultaneous neighbor-key typos (including 1st-letter slips!)
     * by combining spatial likelihood + 250k word frequency + Russian case morphology + user weights.
     */
    fun decodeSpatialWord(
        composing: String,
        tapPoints: List<PointF>,
        keyCenters: Map<Char, PointF>,
        keyW: Float,
        keyH: Float,
        lang: String,
        previousWord: String = "",
        maxResults: Int = 3
    ): List<String> {
        val len = composing.length
        if (len < 2 || tapPoints.size != len || keyCenters.isEmpty()) return emptyList()
        ensureLoaded()

        val kw = keyW.coerceAtLeast(1f)
        val kh = keyH.coerceAtLeast(1f)
        val idx = if (lang == "RU") ruIndex else enIndex
        val blocked = prefs.getBlockedWords()
        val expectedCases = if (lang == "RU" && previousWord.isNotBlank()) inferExpectedCases(previousWord) else emptySet()

        fun nearestChars(pt: PointF, limit: Int): List<Char> {
            val list = ArrayList<Pair<Char, Float>>(keyCenters.size)
            for ((ch, center) in keyCenters) {
                val nx = ((pt.x - center.x) / kw) * 0.92f
                val ny = ((pt.y - center.y) / kh) * 1.18f
                val dSq = nx * nx + ny * ny
                if (dSq <= 2.3f) list.add(ch to dSq)
            }
            list.sortBy { it.second }
            return list.take(limit).map { it.first }
        }

        val c0List = nearestChars(tapPoints[0], 4)
        val c1List = nearestChars(tapPoints[1], 4)
        if (c0List.isEmpty() || c1List.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<String, Double>>(24)
        val seen = HashSet<String>(64)

        fun evalCandidate(word: String, freq: Int) {
            if (word.length != len || word in blocked || !seen.add(word)) return
            var totalCost = 0.0
            for (i in 0 until len) {
                val ch = word[i]
                val lookupCh = if (ch == 'ё' && !keyCenters.containsKey('ё')) 'е' else ch
                val center = keyCenters[lookupCh] ?: return
                val pt = tapPoints[i]
                val nx = ((pt.x - center.x) / kw) * 0.92f
                val ny = ((pt.y - center.y) / kh) * 1.18f
                val dSq = nx * nx + ny * ny
                if (dSq > 2.25f) return // Too far from tap (>1.5 key widths)
                totalCost += dSq
            }
            val avgCost = totalCost / len
            if (avgCost > 1.35) return
            val freqScore = ln((freq.coerceAtLeast(1) + 1).toDouble()) * 1650.0
            val morphBonus = if (expectedCases.isNotEmpty()) scoreWordForExpectedCases(word, expectedCases) * 0.35 else 0.0
            val userBonus = prefs.getUserWordWeight(word) * 320.0
            val exactBonus = if (word == composing.lowercase()) 1400.0 else 0.0
            val total = -totalCost * 6200.0 + freqScore + morphBonus + userBonus + exactBonus
            scored.add(word to total)
        }

        // 1. Check exact typed word if known
        val lower = composing.lowercase()
        idx.exact[lower]?.let { evalCandidate(lower, it) }

        // 2. Check user personal words of same length
        for (uw in prefs.getUserWords()) {
            if (uw.length == len) evalCandidate(uw, 6000)
        }

        // 3. Check collapsed accidental double-letter word (e.g. "приивет" -> "привет")
        val collapsed = collapseDoubleLetters(lower)
        if (collapsed != lower && collapsed.length >= 3) {
            idx.exact[collapsed]?.let { fr ->
                if (collapsed !in blocked && seen.add(collapsed)) {
                    val fScore = ln((fr.coerceAtLeast(1) + 1).toDouble()) * 1650.0 + 900.0
                    scored.add(collapsed to fScore)
                }
            }
        }

        // 4. Check top-3 x top-3 spatial starting pairs in by2 buckets (same length + 1-char omission rescue)
        for (a in c0List) {
            for (b in c1List) {
                val bucket = idx.by2["$a$b"] ?: continue
                val lim = bucket.size.coerceAtMost(140)
                for (k in 0 until lim) {
                    val entry = bucket[k]
                    val w = entry.word
                    if (w.length == len) {
                        evalCandidate(w, entry.freq)
                    } else if (len >= 4 && w.length == len + 1 && w !in blocked && seen.add(w)) {
                        // 1-char omission rescue (e.g. "првет" -> "привет")
                        if (editDistanceAtMost1(lower, w)) {
                            val freqScore = ln((entry.freq.coerceAtLeast(1) + 1).toDouble()) * 1650.0
                            val morphBonus = if (expectedCases.isNotEmpty()) scoreWordForExpectedCases(w, expectedCases) * 0.35 else 0.0
                            scored.add(w to (freqScore + morphBonus - 4200.0))
                        }
                    }
                }
            }
        }

        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.second }
        return scored.take(maxResults).map { applyCaseLike(it.first, composing) }
    }
}