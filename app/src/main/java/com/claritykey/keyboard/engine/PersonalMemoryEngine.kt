package com.claritykey.keyboard.engine

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.claritykey.keyboard.prefs.BreenoPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class MemoryStats(
    val words: Int,
    val bigrams: Int,
    val trigrams: Int,
    val protectedWords: Int
)

data class LocalAutofillSuggestion(
    val icon: String,
    val displayLabel: String,
    val value: String
)

/**
 * Lightweight On-Device Personal Memory ("Эффект памяти") & Smart Local Autofill Engine:
 * - Persistent recency-decayed word frequencies, 2-word bigrams, and 3-word trigrams (< 30 KB JSON)
 * - Auto-learns rejected autocorrections (after 2 undos, stops correcting that word)
 * - Context-aware local autofill chip resolver by EditorInfo inputType & hintText
 * - Dynamic date/time variable expansion (/дата, /время, /завтра, /день, {дата}, {время})
 * Strictly offline, zero background threads, respects Incognito & Password fields.
 */
class PersonalMemoryEngine(context: Context, private val prefs: BreenoPreferences) {

    private val store = context.getSharedPreferences("breeno_personal_memory_v1", Context.MODE_PRIVATE)

    // word -> score (decayed usage count)
    private val wordScores = LinkedHashMap<String, Int>()
    // "prev" -> map of "next" -> count
    private val bigrams = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    // "prev1 prev2" -> map of "next" -> count
    private val trigrams = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    // raw lowercase word -> undo count (>= 2 protects word from autocorrect)
    private val rejectedUndoCounts = LinkedHashMap<String, Int>()

    private var dirtyCount = 0
    private var observedReset = store.getLong("reset_generation", 0L)

    private fun syncReset() {
        val current = store.getLong("reset_generation", 0L)
        if (current == observedReset) return
        observedReset = current
        wordScores.clear(); bigrams.clear(); trigrams.clear(); rejectedUndoCounts.clear()
        dirtyCount = 0
    }

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun recordCommittedPhrase(prev2: String, prev1: String, current: String) {
        syncReset()
        if (!prefs.memoryEffectEnabled || !prefs.personalLearningEnabled) return
        val w = cleanToken(current)
        if (w.length < 2) return

        wordScores[w] = ((wordScores[w] ?: 0) + 2).coerceAtMost(500)
        trimMap(wordScores, 300)

        val p1 = cleanToken(prev1)
        if (p1.length >= 2) {
            val bMap = bigrams.getOrPut(p1) { LinkedHashMap() }
            bMap[w] = ((bMap[w] ?: 0) + 2).coerceAtMost(200)
            trimMap(bMap, 6)
            if (bigrams.size > 350) bigrams.remove(bigrams.keys.first())

            val p2 = cleanToken(prev2)
            if (p2.length >= 2) {
                val triKey = "$p2 $p1"
                val tMap = trigrams.getOrPut(triKey) { LinkedHashMap() }
                tMap[w] = ((tMap[w] ?: 0) + 3).coerceAtMost(200)
                trimMap(tMap, 4)
                if (trigrams.size > 200) trigrams.remove(trigrams.keys.first())
            }
        }

        dirtyCount++
        if (dirtyCount >= 4) {
            dirtyCount = 0
            persistToPrefs()
        }
    }

    @Synchronized
    fun predictNextWords(prev2: String, prev1: String, maxCount: Int = 3): List<String> {
        syncReset()
        if (!prefs.memoryEffectEnabled) return emptyList()
        val p1 = cleanToken(prev1)
        if (p1.isEmpty()) return emptyList()
        val p2 = cleanToken(prev2)

        val scored = LinkedHashMap<String, Int>()
        if (p2.isNotEmpty()) {
            trigrams["$p2 $p1"]?.forEach { (w, sc) ->
                scored[w] = (scored[w] ?: 0) + sc * 4
            }
        }
        bigrams[p1]?.forEach { (w, sc) ->
            scored[w] = (scored[w] ?: 0) + sc * 2
        }
        return scored.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(maxCount)
    }

    @Synchronized
    fun matchPersonalPrefix(prefix: String, maxCount: Int = 2): List<String> {
        syncReset()
        if (!prefs.memoryEffectEnabled || prefix.length < 2) return emptyList()
        val low = prefix.lowercase()
        return wordScores.entries
            .filter { it.key.startsWith(low) && it.key != low }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(maxCount)
    }

    @Synchronized
    fun recordRejectedAutocorrect(rawWord: String) {
        syncReset()
        if (!prefs.personalLearningEnabled || !prefs.memoryEffectEnabled) return
        val clean = cleanToken(rawWord)
        if (clean.length < 2) return
        rejectedUndoCounts[clean] = ((rejectedUndoCounts[clean] ?: 0) + 1).coerceAtMost(10)
        if (rejectedUndoCounts.size > 120) {
            rejectedUndoCounts.remove(rejectedUndoCounts.keys.first())
        }
        persistToPrefs()
    }

    @Synchronized
    fun isProtectedFromAutocorrect(rawWord: String): Boolean {
        syncReset()
        val clean = cleanToken(rawWord)
        return (rejectedUndoCounts[clean] ?: 0) >= 2
    }

    @Synchronized
    fun getStats(): MemoryStats = MemoryStats(
        words = wordScores.size,
        bigrams = bigrams.values.sumOf { it.size },
        trigrams = trigrams.values.sumOf { it.size },
        protectedWords = rejectedUndoCounts.count { it.value >= 2 }
    )

    @Synchronized
    fun flush() {
        syncReset()
        if (dirtyCount > 0) {
            dirtyCount = 0
            persistToPrefs()
        }
    }

    @Synchronized
    fun clearAll() {
        wordScores.clear()
        bigrams.clear()
        trigrams.clear()
        rejectedUndoCounts.clear()
        dirtyCount = 0
        observedReset = store.getLong("reset_generation", 0L) + 1L
        store.edit().clear().putLong("reset_generation", observedReset).apply()
    }

    fun resolveLocalAutofillChip(info: EditorInfo?): LocalAutofillSuggestion? {
        if (!prefs.localAutofillEnabled || info == null) return null
        val inputType = info.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val hint = (info.hintText?.toString() ?: "").lowercase()
        val fieldName = (info.fieldName?.toString() ?: "").lowercase()
        val meta = "$hint $fieldName"

        val isEmail = (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) ||
            meta.contains("email") || meta.contains("e-mail") || meta.contains("почт")
        if (isEmail) {
            val v = prefs.autofillEmail
            if (v.isNotBlank()) return LocalAutofillSuggestion("✉", v, v)
        }

        val isPhone = cls == InputType.TYPE_CLASS_PHONE ||
            meta.contains("тел") || meta.contains("phone") || meta.contains("мобил")
        if (isPhone) {
            val v = prefs.autofillPhone
            if (v.isNotBlank()) return LocalAutofillSuggestion("📞", v, v)
        }

        val isAddress = (cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS) ||
            meta.contains("адрес") || meta.contains("улиц") || meta.contains("доставк") || meta.contains("address")
        if (isAddress) {
            val v = prefs.autofillAddress
            if (v.isNotBlank()) return LocalAutofillSuggestion("📍", v, v)
        }

        val isName = (cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME) ||
            meta.contains("имя") || meta.contains("фио") || meta.contains("фамил") || meta.contains("full name")
        if (isName) {
            val v = prefs.autofillName
            if (v.isNotBlank()) return LocalAutofillSuggestion("👤", v, v)
        }

        val isNick = meta.contains("ник") || meta.contains("username") || meta.contains("логин") || meta.contains("telegram")
        if (isNick) {
            val v = prefs.autofillUsername
            if (v.isNotBlank()) return LocalAutofillSuggestion("@", v, v)
        }

        return null
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val RU_DAYS = arrayOf(
            "воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"
        )

        /** Resolves built-in dynamic date/time tokens (/дата, /время, /завтра, /день) */
        fun resolveDynamicToken(raw: String): String? {
            val cal = Calendar.getInstance()
            return when (raw.trim().lowercase()) {
                "/дата", "/date" -> DATE_FMT.format(cal.time)
                "/время", "/time" -> TIME_FMT.format(cal.time)
                "/завтра" -> {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    DATE_FMT.format(cal.time)
                }
                "/день", "/деньнедели" -> RU_DAYS[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
                else -> null
            }
        }

        /** Expands {дата} and {время} variables inside user text expanders */
        fun interpolateVariables(text: String): String {
            if (!text.contains('{')) return text
            val now = Date()
            val cal = Calendar.getInstance()
            val dayName = RU_DAYS[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val tomorrowStr = DATE_FMT.format(cal.time)
            return text
                .replace("{дата}", DATE_FMT.format(now))
                .replace("{время}", TIME_FMT.format(now))
                .replace("{завтра}", tomorrowStr)
                .replace("{день}", dayName)
        }
    }

    private fun cleanToken(raw: String): String =
        raw.trim().lowercase().trim('.', ',', '!', '?', ':', ';', '"', '«', '»', '(', ')')

    private fun trimMap(map: LinkedHashMap<String, Int>, limit: Int) {
        while (map.size > limit) {
            val minKey = map.entries.minByOrNull { it.value }?.key ?: break
            map.remove(minKey)
        }
    }

    private fun persistToPrefs() {
        syncReset()
        try {
            val root = JSONObject()
            val wObj = JSONObject()
            wordScores.forEach { (k, v) -> wObj.put(k, v) }
            root.put("w", wObj)

            val bObj = JSONObject()
            bigrams.forEach { (k, inner) ->
                val io = JSONObject()
                inner.forEach { (nk, nv) -> io.put(nk, nv) }
                bObj.put(k, io)
            }
            root.put("b", bObj)

            val tObj = JSONObject()
            trigrams.forEach { (k, inner) ->
                val io = JSONObject()
                inner.forEach { (nk, nv) -> io.put(nk, nv) }
                tObj.put(k, io)
            }
            root.put("t", tObj)

            val rObj = JSONObject()
            rejectedUndoCounts.forEach { (k, v) -> rObj.put(k, v) }
            root.put("r", rObj)

            store.edit().putString("mem_json", root.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadFromPrefs() {
        try {
            val raw = store.getString("mem_json", null) ?: return
            val root = JSONObject(raw)
            root.optJSONObject("w")?.let { o ->
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    wordScores[k] = o.optInt(k, 1)
                }
            }
            root.optJSONObject("b")?.let { o ->
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val inner = o.optJSONObject(k) ?: continue
                    val map = LinkedHashMap<String, Int>()
                    val iks = inner.keys()
                    while (iks.hasNext()) {
                        val ik = iks.next()
                        map[ik] = inner.optInt(ik, 1)
                    }
                    bigrams[k] = map
                }
            }
            root.optJSONObject("t")?.let { o ->
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val inner = o.optJSONObject(k) ?: continue
                    val map = LinkedHashMap<String, Int>()
                    val iks = inner.keys()
                    while (iks.hasNext()) {
                        val ik = iks.next()
                        map[ik] = inner.optInt(ik, 1)
                    }
                    trigrams[k] = map
                }
            }
            root.optJSONObject("r")?.let { o ->
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    rejectedUndoCounts[k] = o.optInt(k, 1)
                }
            }
        } catch (_: Exception) {
        }
    }
}
