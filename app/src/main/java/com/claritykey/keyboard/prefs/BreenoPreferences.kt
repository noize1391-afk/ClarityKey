package com.claritykey.keyboard.prefs

import android.content.Context
import android.content.SharedPreferences
import com.claritykey.keyboard.layout.BottomPaddingMode
import com.claritykey.keyboard.engine.TouchBias
import com.claritykey.keyboard.clipboard.ClipboardCrypto
import com.claritykey.keyboard.skin.DayNightMode

enum class OneHandedMode {
    OFF,
    LEFT,
    RIGHT
}

enum class AutoCorrectStrength {
    SOFT,
    STANDARD,
    AGGRESSIVE
}

class BreenoPreferences(context: Context) {

    private val vaultUnavailableText = context.getString(com.claritykey.keyboard.R.string.vault_entry_unavailable)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("breeno_keyboard_ru_prefs", Context.MODE_PRIVATE)

    // Empty means choose RU for a Russian device locale, otherwise EN on first use.
    // This never changes activeLanguage, dictionaries or the IME subtype.
    var settingsUiLanguage: String
        get() = prefs.getString("settings_ui_language", "").orEmpty()
        set(value) {
            require(value == "ru" || value == "en" || value.isEmpty())
            prefs.edit().putString("settings_ui_language", value).apply()
        }

    var themeMode: DayNightMode
        get() = when (prefs.getString("theme_mode", "GLASS_BG_SYSTEM")) {
            "SYSTEM" -> DayNightMode.SYSTEM
            "LIGHT" -> DayNightMode.LIGHT
            "DARK" -> DayNightMode.DARK
            "GLASS_BG_SYSTEM" -> DayNightMode.GLASS_BG_SYSTEM
            "GLASS_BG_LIGHT" -> DayNightMode.GLASS_BG_LIGHT
            "GLASS_BG_DARK" -> DayNightMode.GLASS_BG_DARK
            "GLASS_SYSTEM" -> DayNightMode.GLASS_SYSTEM
            "GLASS_LIGHT" -> DayNightMode.GLASS_LIGHT
            "GLASS_DARK" -> DayNightMode.GLASS_DARK
            else -> DayNightMode.GLASS_BG_SYSTEM
        }
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()

    var glassTransparency: Int
        get() = prefs.getInt("glass_transparency", 55).coerceIn(15, 85)
        set(value) = prefs.edit().putInt("glass_transparency", value.coerceIn(15, 85)).apply()

    var glassBlurRadius: Int
        get() = prefs.getInt("glass_blur_radius", 48).coerceIn(0, 80)
        set(value) = prefs.edit().putInt("glass_blur_radius", value.coerceIn(0, 80)).apply()

    var bottomPaddingMode: BottomPaddingMode
        get() = when (prefs.getString("bottom_padding_mode", "AUTO")) {
            "COMPACT" -> BottomPaddingMode.COMPACT
            "STANDARD" -> BottomPaddingMode.STANDARD
            "LARGE" -> BottomPaddingMode.LARGE
            else -> BottomPaddingMode.AUTO
        }
        set(value) = prefs.edit().putString("bottom_padding_mode", value.name).apply()

    var autoCap: Boolean
        get() = prefs.getBoolean("auto_cap", true)
        set(value) = prefs.edit().putBoolean("auto_cap", value).apply()

    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean("double_space_period", true)
        set(value) = prefs.edit().putBoolean("double_space_period", value).apply()

    var showSuggestions: Boolean
        get() = prefs.getBoolean("show_suggestions", true)
        set(value) = prefs.edit().putBoolean("show_suggestions", value).apply()

    var autoCorrect: Boolean
        get() = prefs.getBoolean("auto_correct", true)
        set(value) = prefs.edit().putBoolean("auto_correct", value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound_enabled", false)
        set(value) = prefs.edit().putBoolean("sound_enabled", value).apply()

    var hapticEnabled: Boolean
        get() = prefs.getBoolean("haptic_enabled", true)
        set(value) = prefs.edit().putBoolean("haptic_enabled", value).apply()

    var popupEnabled: Boolean
        get() = prefs.getBoolean("popup_enabled", true)
        set(value) = prefs.edit().putBoolean("popup_enabled", value).apply()

    var separateHardSign: Boolean
        get() = prefs.getBoolean("separate_hard_sign", false)
        set(value) = prefs.edit().putBoolean("separate_hard_sign", value).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean("show_number_row", false)
        set(value) = prefs.edit().putBoolean("show_number_row", value).apply()

    var voiceInputEnabled: Boolean
        get() = prefs.getBoolean("voice_input_enabled", true)
        set(value) = prefs.edit().putBoolean("voice_input_enabled", value).apply()

    var clipboardEnabled: Boolean
        get() = prefs.getBoolean("clipboard_enabled", true)
        set(value) = prefs.edit().putBoolean("clipboard_enabled", value).apply()

    // Retention in hours for text items: 1, 24, 168 (7d), 720 (30d default), 0 (forever)
    var clipboardRetentionHours: Long
        get() = prefs.getLong("clipboard_retention_hours", 720L)
        set(value) = prefs.edit().putLong("clipboard_retention_hours", value).apply()

    // Max unpinned text items: 50, 100 (default), 200
    var clipboardMaxItems: Int
        get() = prefs.getInt("clipboard_max_items", 100)
        set(value) = prefs.edit().putInt("clipboard_max_items", value).apply()

    var clipboardIgnoreSensitive: Boolean
        get() = prefs.getBoolean("clipboard_ignore_sensitive", true)
        set(value) = prefs.edit().putBoolean("clipboard_ignore_sensitive", value).apply()

    var clipboardHidePreview: Boolean
        get() = prefs.getBoolean("clipboard_hide_preview", false)
        set(value) = prefs.edit().putBoolean("clipboard_hide_preview", value).apply()

    var clipboardConfirmClear: Boolean
        get() = prefs.getBoolean("clipboard_confirm_clear", true)
        set(value) = prefs.edit().putBoolean("clipboard_confirm_clear", value).apply()

    var clipboardQuickChipEnabled: Boolean
        get() = prefs.getBoolean("clipboard_quick_chip", true)
        set(value) = prefs.edit().putBoolean("clipboard_quick_chip", value).apply()

    var clipboardShowSource: Boolean
        get() = prefs.getBoolean("clipboard_show_source", true)
        set(value) = prefs.edit().putBoolean("clipboard_show_source", value).apply()

    var clipboardShowTime: Boolean
        get() = prefs.getBoolean("clipboard_show_time", true)
        set(value) = prefs.edit().putBoolean("clipboard_show_time", value).apply()

    var clipboardPreviewLines: Int
        get() = prefs.getInt("clipboard_preview_lines", 2).coerceIn(1, 3)
        set(value) = prefs.edit().putInt("clipboard_preview_lines", value.coerceIn(1, 3)).apply()

    var clipboardFullTextEnabled: Boolean
        get() = prefs.getBoolean("clipboard_full_text_enabled", true)
        set(value) = prefs.edit().putBoolean("clipboard_full_text_enabled", value).apply()

    // --- Smart Clipboard 2.0: Images & Screenshots ---
    var clipboardSaveImages: Boolean
        get() = prefs.getBoolean("clipboard_save_images", true)
        set(value) = prefs.edit().putBoolean("clipboard_save_images", value).apply()

    // Off by default until explicit user consent for media access
    var clipboardSuggestScreenshots: Boolean
        get() = prefs.getBoolean("clipboard_suggest_screenshots", false)
        set(value) = prefs.edit().putBoolean("clipboard_suggest_screenshots", value).apply()

    var clipboardShowImageSuggestions: Boolean
        get() = prefs.getBoolean("clipboard_show_image_suggestions", true)
        set(value) = prefs.edit().putBoolean("clipboard_show_image_suggestions", value).apply()

    // Image retention in hours: 24 (1d), 168 (7d default), 720 (30d), 0 (forever)
    var clipboardImageRetentionHours: Long
        get() = prefs.getLong("clipboard_image_retention_hours", 168L)
        set(value) = prefs.edit().putLong("clipboard_image_retention_hours", value).apply()

    // Max unpinned images: 10, 20 (default), 50
    var clipboardMaxImages: Int
        get() = prefs.getInt("clipboard_max_images", 20).coerceIn(5, 100)
        set(value) = prefs.edit().putInt("clipboard_max_images", value.coerceIn(5, 100)).apply()

    // Max unpinned image storage in MB: 25, 50 (default), 100
    var clipboardMaxImageMb: Int
        get() = prefs.getInt("clipboard_max_image_mb", 50).coerceIn(10, 250)
        set(value) = prefs.edit().putInt("clipboard_max_image_mb", value.coerceIn(10, 250)).apply()

    var clipboardImageAutoClean: Boolean
        get() = prefs.getBoolean("clipboard_image_auto_clean", true)
        set(value) = prefs.edit().putBoolean("clipboard_image_auto_clean", value).apply()

    var lastDismissedScreenshotKey: String
        get() = prefs.getString("last_dismissed_screenshot_key", "") ?: ""
        set(value) = prefs.edit().putString("last_dismissed_screenshot_key", value).apply()

    var lastAutoImportedScreenshotKey: String
        get() = prefs.getString("last_auto_imported_screenshot_key", "") ?: ""
        set(value) = prefs.edit().putString("last_auto_imported_screenshot_key", value).apply()

    // --- Smart Paste 1.0 ---
    var smartPasteEnabled: Boolean
        get() = prefs.getBoolean("smart_paste_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_paste_enabled", value).apply()

    var smartPasteShowFragments: Boolean
        get() = prefs.getBoolean("smart_paste_show_fragments", true)
        set(value) = prefs.edit().putBoolean("smart_paste_show_fragments", value).apply()

    var smartPasteQuickSuggestions: Boolean
        get() = prefs.getBoolean("smart_paste_quick_suggestions", true)
        set(value) = prefs.edit().putBoolean("smart_paste_quick_suggestions", value).apply()

    var smartPasteDetectUrls: Boolean
        get() = prefs.getBoolean("smart_paste_detect_urls", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_urls", value).apply()

    var smartPasteDetectPhones: Boolean
        get() = prefs.getBoolean("smart_paste_detect_phones", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_phones", value).apply()

    var smartPasteDetectEmails: Boolean
        get() = prefs.getBoolean("smart_paste_detect_emails", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_emails", value).apply()

    var smartPasteDetectDatesAndTime: Boolean
        get() = prefs.getBoolean("smart_paste_detect_dates_time", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_dates_time", value).apply()

    var smartPasteDetectAddressesAndCoords: Boolean
        get() = prefs.getBoolean("smart_paste_detect_addresses_coords", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_addresses_coords", value).apply()

    var smartPasteDetectFinance: Boolean
        get() = prefs.getBoolean("smart_paste_detect_finance", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_finance", value).apply()

    var smartPasteDetectOrdersAndTracking: Boolean
        get() = prefs.getBoolean("smart_paste_detect_orders_tracking", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_orders_tracking", value).apply()

    var smartPasteDetectPromosAndSkus: Boolean
        get() = prefs.getBoolean("smart_paste_detect_promos_skus", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_promos_skus", value).apply()

    var smartPasteDetectPeopleAndOrgs: Boolean
        get() = prefs.getBoolean("smart_paste_detect_people_orgs", true)
        set(value) = prefs.edit().putBoolean("smart_paste_detect_people_orgs", value).apply()

    var smartPasteHideSensitive: Boolean
        get() = prefs.getBoolean("smart_paste_hide_sensitive", true)
        set(value) = prefs.edit().putBoolean("smart_paste_hide_sensitive", value).apply()

    // --- Smart Paste Vision 3.0 (On-Device OCR) ---
    var visionOcrEnabled: Boolean
        get() = prefs.getBoolean("vision_ocr_enabled", true)
        set(value) = prefs.edit().putBoolean("vision_ocr_enabled", value).apply()

    // Off by default; runs on-demand when tapping "Извлечь текст"
    var visionAutoOcr: Boolean
        get() = prefs.getBoolean("vision_auto_ocr", false)
        set(value) = prefs.edit().putBoolean("vision_auto_ocr", value).apply()

    var visionOcrRusEng: Boolean
        get() = prefs.getBoolean("vision_ocr_rus_eng", true)
        set(value) = prefs.edit().putBoolean("vision_ocr_rus_eng", value).apply()

    var visionShowFragments: Boolean
        get() = prefs.getBoolean("vision_show_fragments", true)
        set(value) = prefs.edit().putBoolean("vision_show_fragments", value).apply()

    var visionSaveResults: Boolean
        get() = prefs.getBoolean("vision_save_results", true)
        set(value) = prefs.edit().putBoolean("vision_save_results", value).apply()

    // --- Adaptive Typing Engine (v3.1) ---
    var adaptiveSmartTouch: Boolean
        get() = prefs.getBoolean("adaptive_smart_touch", true)
        set(value) = prefs.edit().putBoolean("adaptive_smart_touch", value).apply()

    var adaptiveAutoCalibration: Boolean
        get() = prefs.getBoolean("adaptive_auto_calibration", true)
        set(value) = prefs.edit().putBoolean("adaptive_auto_calibration", value).apply()

    var autoCorrectOnSpace: Boolean
        get() = prefs.getBoolean("auto_correct_on_space", true)
        set(value) = prefs.edit().putBoolean("auto_correct_on_space", value).apply()

    var autoCorrectStrength: AutoCorrectStrength
        get() = when (prefs.getString("auto_correct_strength", "STANDARD")) {
            "SOFT" -> AutoCorrectStrength.SOFT
            "AGGRESSIVE" -> AutoCorrectStrength.AGGRESSIVE
            else -> AutoCorrectStrength.STANDARD
        }
        set(value) = prefs.edit().putString("auto_correct_strength", value.name).apply()

    var nextWordPrediction: Boolean
        get() = prefs.getBoolean("next_word_prediction", true)
        set(value) = prefs.edit().putBoolean("next_word_prediction", value).apply()

    var swipeTypingEnabled: Boolean
        get() = prefs.getBoolean("swipe_typing_enabled", true)
        set(value) = prefs.edit().putBoolean("swipe_typing_enabled", value).apply()

    var personalLearningEnabled: Boolean
        get() = prefs.getBoolean("personal_learning_enabled", true)
        set(value) = prefs.edit().putBoolean("personal_learning_enabled", value).apply()

    fun getTouchBias(layoutKey: String): TouchBias {
        val dx = prefs.getFloat("touch_bias_dx_$layoutKey", 0f)
        val dy = prefs.getFloat("touch_bias_dy_$layoutKey", 0f)
        val n = prefs.getInt("touch_bias_n_$layoutKey", 0)
        return TouchBias(dx, dy, n)
    }

    fun saveTouchBias(layoutKey: String, dx: Float, dy: Float, samples: Int) {
        prefs.edit()
            .putFloat("touch_bias_dx_$layoutKey", dx)
            .putFloat("touch_bias_dy_$layoutKey", dy)
            .putInt("touch_bias_n_$layoutKey", samples)
            .apply()
    }

    fun clearTouchCalibration() {
        val ed = prefs.edit()
        prefs.all.keys.filter { it.startsWith("touch_bias_") }.forEach { ed.remove(it) }
        ed.apply()
    }

    fun getUserWordWeight(word: String): Int =
        prefs.getInt("uw_freq_${word.lowercase()}", 0)

    fun recordUserWordUsage(word: String) {
        if (!personalLearningEnabled) return
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        addUserWord(clean)
        val cur = prefs.getInt("uw_freq_$clean", 0)
        prefs.edit().putInt("uw_freq_$clean", (cur + 1).coerceAtMost(500)).apply()
    }

    fun clearPersonalTypingData() {
        val ed = prefs.edit()
        ed.remove("user_dictionary_words")
        prefs.all.keys.filter { it.startsWith("touch_bias_") || it.startsWith("uw_freq_") }.forEach { ed.remove(it) }
        ed.apply()
    }

    // --- Phase 1 Ergonomics: One-Handed, Floating, Height, Expanders, Calculator ---
    var oneHandedMode: OneHandedMode
        get() = when (prefs.getString("one_handed_mode", "OFF")) {
            "LEFT" -> OneHandedMode.LEFT
            "RIGHT" -> OneHandedMode.RIGHT
            else -> OneHandedMode.OFF
        }
        set(value) = prefs.edit().putString("one_handed_mode", value.name).apply()

    var floatingModeEnabled: Boolean
        get() = prefs.getBoolean("floating_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("floating_mode_enabled", value).apply()

    // 0 = LEFT, 1 = CENTER, 2 = RIGHT
    var floatingDockAlign: Int
        get() = prefs.getInt("floating_dock_align", 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("floating_dock_align", value.coerceIn(0, 2)).apply()

    var keyboardHeightPercent: Int
        get() = prefs.getInt("keyboard_height_percent", 100).coerceIn(85, 120)
        set(value) = prefs.edit().putInt("keyboard_height_percent", value.coerceIn(85, 120)).apply()

    var inlineCalculatorEnabled: Boolean
        get() = prefs.getBoolean("inline_calculator_enabled", true)
        set(value) = prefs.edit().putBoolean("inline_calculator_enabled", value).apply()

    var textExpandersEnabled: Boolean
        get() = prefs.getBoolean("text_expanders_enabled", true)
        set(value) = prefs.edit().putBoolean("text_expanders_enabled", value).apply()

    fun getTextExpanders(): LinkedHashMap<String, String> {
        val raw = prefs.getString("text_expanders_map", "") ?: ""
        val map = LinkedHashMap<String, String>()
        if (raw.isBlank()) return map
        raw.split("").forEach { pair ->
            val sep = pair.indexOf('')
            if (sep > 0 && sep < pair.length - 1) {
                map[pair.substring(0, sep)] = pair.substring(sep + 1)
            }
        }
        return map
    }

    fun putTextExpander(shortcut: String, expansion: String) {
        val k = shortcut.trim().lowercase()
        val v = expansion.trim()
        if (k.isEmpty() || v.isEmpty()) return
        val map = getTextExpanders()
        map[k] = v
        saveTextExpanders(map)
    }

    fun removeTextExpander(shortcut: String) {
        val map = getTextExpanders()
        map.remove(shortcut.trim().lowercase())
        saveTextExpanders(map)
    }

    private fun saveTextExpanders(map: Map<String, String>) {
        val encoded = map.entries.joinToString("") { "${it.key}${it.value}" }
        prefs.edit().putString("text_expanders_map", encoded).apply()
    }

    var activeLanguage: String
        get() = prefs.getString("active_lang", "RU") ?: "RU"
        set(value) = prefs.edit().putString("active_lang", value).apply()

    fun getUserWords(): Set<String> =
        prefs.getStringSet("user_dictionary_words", emptySet())?.toSet() ?: emptySet()

    fun addUserWord(word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        val updated = getUserWords().toMutableSet()
        updated.add(clean)
        prefs.edit().putStringSet("user_dictionary_words", updated).apply()
    }

    fun removeUserWord(word: String) {
        val updated = getUserWords().toMutableSet()
        updated.remove(word.trim().lowercase())
        prefs.edit().putStringSet("user_dictionary_words", updated).apply()
    }

    fun clearUserWords() {
        prefs.edit().remove("user_dictionary_words").apply()
    }

    fun getRecentEmojis(): List<String> {
        val raw = prefs.getString("recent_emojis", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001F").filter { it.isNotEmpty() }
    }

    fun recordRecentEmoji(emoji: String) {
        val list = getRecentEmojis().toMutableList()
        list.remove(emoji)
        list.add(0, emoji)
        val trimmed = list.take(36)
        prefs.edit().putString("recent_emojis", trimmed.joinToString("\u001F")).apply()
    }

    fun getPinnedClips(): List<String> {
        val raw = prefs.getString("pinned_clips", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").filter { it.isNotEmpty() }
    }

    fun savePinnedClips(items: List<String>) {
        prefs.edit().putString("pinned_clips", items.take(20).joinToString("\u001E")).apply()
    }

    // --- Phases 2-4: Auto-Layout, Incognito, Templates, Vault, Dynamic Color, Wallpaper, Toolbar, Haptics/Sound, Night Schedule ---
    var autoLayoutSwitchEnabled: Boolean
        get() = prefs.getBoolean("auto_layout_switch_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_layout_switch_enabled", value).apply()

    var manualIncognito: Boolean
        get() = prefs.getBoolean("manual_incognito", false)
        set(value) = prefs.edit().putBoolean("manual_incognito", value).apply()

    var dynamicColorEnabled: Boolean
        get() = prefs.getBoolean("dynamic_color_enabled", false)
        set(value) = prefs.edit().putBoolean("dynamic_color_enabled", value).apply()

    var wallpaperPath: String
        get() = prefs.getString("wallpaper_path", "") ?: ""
        set(value) = prefs.edit().putString("wallpaper_path", value).apply()

    var wallpaperDimPercent: Int
        get() = prefs.getInt("wallpaper_dim_percent", 45).coerceIn(20, 85)
        set(value) = prefs.edit().putInt("wallpaper_dim_percent", value.coerceIn(20, 85)).apply()

    var toolbarSlotsCsv: String
        get() = prefs.getString("toolbar_slots_csv", "MENU,CLIP,TRANSLATE,SETTINGS,VOICE")
            ?: "MENU,CLIP,TRANSLATE,SETTINGS,VOICE"
        set(value) = prefs.edit().putString("toolbar_slots_csv", value).apply()

    fun getToolbarSlots(): List<String> =
        toolbarSlotsCsv.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.take(5)
            .ifEmpty { listOf("MENU", "CLIP", "TRANSLATE", "SETTINGS", "VOICE") }

    var hapticDurationMs: Int
        get() = prefs.getInt("haptic_duration_ms", 16).coerceIn(5, 40)
        set(value) = prefs.edit().putInt("haptic_duration_ms", value.coerceIn(5, 40)).apply()

    // 0 = Breeno, 1 = Тихий клик, 2 = Механика
    var soundProfile: Int
        get() = prefs.getInt("sound_profile", 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("sound_profile", value.coerceIn(0, 2)).apply()

    var soundVolumePercent: Int
        get() = prefs.getInt("sound_volume_percent", 55).coerceIn(20, 100)
        set(value) = prefs.edit().putInt("sound_volume_percent", value.coerceIn(20, 100)).apply()

    var nightScheduleEnabled: Boolean
        get() = prefs.getBoolean("night_schedule_enabled", false)
        set(value) = prefs.edit().putBoolean("night_schedule_enabled", value).apply()

    fun getReplyTemplates(): List<Pair<String, String>> {
        if (!prefs.getBoolean("reply_templates_seeded", false)) {
            val defaults = listOf(
                "Работа" to "Добрый день! Сейчас уточню и вернусь с ответом.",
                "Доставка" to "Здравствуйте! Оставьте, пожалуйста, у двери и напишите сюда.",
                "Личное" to "Перезвоню через 10 минут, сейчас неудобно говорить."
            )
            saveReplyTemplates(defaults)
            prefs.edit().putBoolean("reply_templates_seeded", true).apply()
            return defaults
        }
        val raw = prefs.getString("reply_templates_data", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").mapNotNull { row ->
            val sep = row.indexOf('\u001F')
            if (sep > 0 && sep < row.length - 1) {
                row.substring(0, sep) to row.substring(sep + 1)
            } else null
        }
    }

    fun addReplyTemplate(folder: String, text: String) {
        val f = folder.trim().ifEmpty { "Работа" }
        val t = text.trim()
        if (t.isEmpty()) return
        val list = getReplyTemplates().toMutableList()
        list.add(0, f to t)
        saveReplyTemplates(list.take(40))
    }

    fun removeReplyTemplate(index: Int) {
        val list = getReplyTemplates().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            saveReplyTemplates(list)
        }
    }

    private fun saveReplyTemplates(items: List<Pair<String, String>>) {
        val encoded = items.joinToString("\u001E") { "${it.first}\u001F${it.second}" }
        prefs.edit().putString("reply_templates_data", encoded).apply()
    }

    fun getVaultItems(): List<String> {
        if (!isVaultUnlocked()) return emptyList()
        val records = vaultRecords()
        val decrypted = records.map { ClipboardCrypto.decrypt(it) }
        val migrated = records.mapIndexed { index, record ->
            val plain = decrypted[index]
            if (!record.startsWith("enc1:") && plain != null) runCatching { ClipboardCrypto.encrypt(plain) }.getOrDefault(record) else record
        }
        if (migrated != records) prefs.edit().putString("vault_items_enc", migrated.joinToString("\u001E")).apply()
        return decrypted.map { it ?: vaultUnavailableText }
    }

    fun getVaultItemCount(): Int = vaultRecords().size

    private fun vaultRecords(): List<String> =
        prefs.getString("vault_items_enc", "").orEmpty().split("\u001E").filter { it.isNotBlank() }

    @Synchronized
    fun addVaultItem(plainText: String): Boolean {
        val clean = plainText.trim()
        if (clean.isEmpty()) return false
        return try {
            val encrypted = ClipboardCrypto.encrypt(clean)
            val records = vaultRecords().filter { ClipboardCrypto.decrypt(it) != clean }
            prefs.edit().putString("vault_items_enc", (listOf(encrypted) + records).joinToString("\u001E")).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun removeVaultItem(index: Int) {
        if (!isVaultUnlocked()) return
        val records = vaultRecords().toMutableList()
        if (index in records.indices) {
            records.removeAt(index)
            prefs.edit().putString("vault_items_enc", records.joinToString("\u001E")).apply()
        }
    }

    companion object {
        @Volatile private var vaultDeadline = 0L
        @Volatile private var capturePaused = true
    }

    var clipboardCapturePaused: Boolean
        get() = capturePaused || manualIncognito
        set(value) { capturePaused = value }

    fun isVaultUnlocked(): Boolean = android.os.SystemClock.elapsedRealtime() < vaultDeadline

    var vaultUnlockedUntilMs: Long
        get() = if (isVaultUnlocked()) System.currentTimeMillis() +
            (vaultDeadline - android.os.SystemClock.elapsedRealtime()) else 0L
        set(value) {
            val remaining = (value - System.currentTimeMillis()).coerceIn(0L, 300_000L)
            vaultDeadline = android.os.SystemClock.elapsedRealtime() + remaining
            prefs.edit().remove("vault_unlocked_until_ms").apply()
        }

    fun saveAutofillProfile(name: String, email: String, phone: String, address: String, username: String): Boolean {
        return try {
            val values = mapOf("af_name_enc" to name, "af_email_enc" to email,
                "af_phone_enc" to phone, "af_address_enc" to address, "af_user_enc" to username)
            val encrypted = values.mapValues { (_, value) -> ClipboardCrypto.encrypt(value.trim()) }
            val editor = prefs.edit()
            encrypted.forEach { (key, value) -> editor.putString(key, value) }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    var floatingOffsetX: Int
        get() = prefs.getInt("floating_offset_x", 0)
        set(value) = prefs.edit().putInt("floating_offset_x", value).apply()

    // Lift above bottom edge in dp when floating mode is active (default 110dp so it visibly floats immediately)
    var floatingOffsetYDp: Int
        get() = prefs.getInt("floating_offset_y_dp", 72).coerceIn(16, 420)
        set(value) = prefs.edit().putInt("floating_offset_y_dp", value.coerceIn(16, 420)).apply()

    fun updateReplyTemplate(index: Int, folder: String, text: String) {
        val f = folder.trim().ifEmpty { "Работа" }
        val t = text.trim()
        if (t.isEmpty()) return
        val list = getReplyTemplates().toMutableList()
        if (index in list.indices) {
            list[index] = f to t
            saveReplyTemplates(list)
        }
    }


    // --- v3.6 Lightweight Power Features + Personal Memory & Local Autofill ---
    var undoDeleteEnabled: Boolean
        get() = prefs.getBoolean("undo_delete_enabled", true)
        set(value) = prefs.edit().putBoolean("undo_delete_enabled", value).apply()

    var quickFlickEnabled: Boolean
        get() = prefs.getBoolean("quick_flick_enabled", true)
        set(value) = prefs.edit().putBoolean("quick_flick_enabled", value).apply()

    var smartPairsEnabled: Boolean
        get() = prefs.getBoolean("smart_pairs_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_pairs_enabled", value).apply()

    var privacyShieldEnabled: Boolean
        get() = prefs.getBoolean("privacy_shield_enabled", true)
        set(value) = prefs.edit().putBoolean("privacy_shield_enabled", value).apply()

    var wallpaperAutoContrast: Boolean
        get() = prefs.getBoolean("wallpaper_auto_contrast", true)
        set(value) = prefs.edit().putBoolean("wallpaper_auto_contrast", value).apply()

    var wallpaperIsBright: Boolean
        get() = prefs.getBoolean("wallpaper_is_bright", false)
        set(value) = prefs.edit().putBoolean("wallpaper_is_bright", value).apply()

    var memoryEffectEnabled: Boolean
        get() = prefs.getBoolean("memory_effect_enabled", true)
        set(value) = prefs.edit().putBoolean("memory_effect_enabled", value).apply()

    var localAutofillEnabled: Boolean
        get() = prefs.getBoolean("local_autofill_enabled", true)
        set(value) = prefs.edit().putBoolean("local_autofill_enabled", value).apply()

    private fun getEncryptedPref(key: String): String {
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isBlank()) return ""
        val plain = ClipboardCrypto.decrypt(raw) ?: return ""
        if (!raw.startsWith("enc1:")) runCatching {
            prefs.edit().putString(key, ClipboardCrypto.encrypt(plain)).apply()
        }
        return plain
    }

    private fun setEncryptedPref(key: String, plain: String) {
        val clean = plain.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, ClipboardCrypto.encrypt(clean)).apply()
        }
    }

    var autofillName: String
        get() = getEncryptedPref("af_name_enc")
        set(value) = setEncryptedPref("af_name_enc", value)

    var autofillEmail: String
        get() = getEncryptedPref("af_email_enc")
        set(value) = setEncryptedPref("af_email_enc", value)

    var autofillPhone: String
        get() = getEncryptedPref("af_phone_enc")
        set(value) = setEncryptedPref("af_phone_enc", value)

    var autofillAddress: String
        get() = getEncryptedPref("af_address_enc")
        set(value) = setEncryptedPref("af_address_enc", value)

    var autofillUsername: String
        get() = getEncryptedPref("af_user_enc")
        set(value) = setEncryptedPref("af_user_enc", value)


    // --- v3.7 Keyboard Preferences (Font/Emoji size, Bottom Row keys, Commands, Sensitivity, Long-Press, Launcher Icon) ---
    var keyFontScalePercent: Int
        get() = prefs.getInt("key_font_scale_percent", 100).coerceIn(80, 135)
        set(value) = prefs.edit().putInt("key_font_scale_percent", value.coerceIn(80, 135)).apply()

    // 0..4 (10, 9, 8 [default], 7, 6 columns)
    var emojiSizeLevel: Int
        get() = prefs.getInt("emoji_size_level", 2).coerceIn(0, 4)
        set(value) = prefs.edit().putInt("emoji_size_level", value.coerceIn(0, 4)).apply()

    var showNumberRowOnPassword: Boolean
        get() = prefs.getBoolean("show_number_row_on_password", true)
        set(value) = prefs.edit().putBoolean("show_number_row_on_password", value).apply()

    var showEmojiKey: Boolean
        get() = prefs.getBoolean("show_emoji_key", false)
        set(value) = prefs.edit().putBoolean("show_emoji_key", value).apply()

    var showLangKey: Boolean
        get() = prefs.getBoolean("show_lang_key", true)
        set(value) = prefs.edit().putBoolean("show_lang_key", value).apply()

    var showCommaKey: Boolean
        get() = prefs.getBoolean("show_comma_key", true)
        set(value) = prefs.edit().putBoolean("show_comma_key", value).apply()

    var showPeriodKey: Boolean
        get() = prefs.getBoolean("show_period_key", true)
        set(value) = prefs.edit().putBoolean("show_period_key", value).apply()

    var showSuggestionsBar: Boolean
        get() = prefs.getBoolean("show_suggestions_bar", true)
        set(value) = prefs.edit().putBoolean("show_suggestions_bar", value).apply()

    var autoSpaceAfterPunctuation: Boolean
        get() = prefs.getBoolean("auto_space_after_punctuation", false)
        set(value) = prefs.edit().putBoolean("auto_space_after_punctuation", value).apply()

    var autoSwitchAfterApostrophe: Boolean
        get() = prefs.getBoolean("auto_switch_after_apostrophe", true)
        set(value) = prefs.edit().putBoolean("auto_switch_after_apostrophe", value).apply()

    var longPressSymbolsEnabled: Boolean
        get() = prefs.getBoolean("long_press_symbols_enabled", true)
        set(value) = prefs.edit().putBoolean("long_press_symbols_enabled", value).apply()

    var flickDownSymbolsEnabled: Boolean
        get() = prefs.getBoolean("flick_down_symbols_enabled", false)
        set(value) = prefs.edit().putBoolean("flick_down_symbols_enabled", value).apply()

    // 0 = Низкая, 1 = Нормальная, 2 = Высокая
    var touchSensitivity: Int
        get() = prefs.getInt("touch_sensitivity", 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("touch_sensitivity", value.coerceIn(0, 2)).apply()

    var longPressDelayMs: Int
        get() = prefs.getInt("long_press_delay_ms", 300).coerceIn(150, 700)
        set(value) = prefs.edit().putInt("long_press_delay_ms", value.coerceIn(150, 700)).apply()

    var showLauncherIcon: Boolean
        get() = prefs.getBoolean("show_launcher_icon", true)
        set(value) = prefs.edit().putBoolean("show_launcher_icon", value).apply()


    // --- v3.9 Power Tools (Clean URL, Password Gen, Smart Lists, Edge Palm Rejection, Quick Notepad) ---
    var cleanUrlPasteEnabled: Boolean
        get() = prefs.getBoolean("clean_url_paste_enabled", true)
        set(value) = prefs.edit().putBoolean("clean_url_paste_enabled", value).apply()

    var passwordGeneratorEnabled: Boolean
        get() = prefs.getBoolean("password_generator_enabled", true)
        set(value) = prefs.edit().putBoolean("password_generator_enabled", value).apply()

    var smartListsEnabled: Boolean
        get() = prefs.getBoolean("smart_lists_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_lists_enabled", value).apply()

    var edgeRejectionDp: Int
        get() = prefs.getInt("edge_rejection_dp", 0).coerceIn(0, 10)
        set(value) = prefs.edit().putInt("edge_rejection_dp", value.coerceIn(0, 10)).apply()

    var quickNoteText: String
        get() = prefs.getString("quick_note_text", "") ?: ""
        set(value) = prefs.edit().putString("quick_note_text", value).apply()


    // --- v4.1 Custom Key Colors (0 = default theme color) ---
    var customNormalKeyColor: Int
        get() = prefs.getInt("custom_normal_key_color", 0)
        set(value) = prefs.edit().putInt("custom_normal_key_color", value).apply()

    var customShiftKeyColor: Int
        get() = prefs.getInt("custom_shift_key_color", 0)
        set(value) = prefs.edit().putInt("custom_shift_key_color", value).apply()

    var customDeleteKeyColor: Int
        get() = prefs.getInt("custom_delete_key_color", 0)
        set(value) = prefs.edit().putInt("custom_delete_key_color", value).apply()

    var customLangKeyColor: Int
        get() = prefs.getInt("custom_lang_key_color", 0)
        set(value) = prefs.edit().putInt("custom_lang_key_color", value).apply()

    var customEnterKeyColor: Int
        get() = prefs.getInt("custom_enter_key_color", 0)
        set(value) = prefs.edit().putInt("custom_enter_key_color", value).apply()

    fun clearCustomKeyColors() {
        prefs.edit()
            .remove("custom_normal_key_color")
            .remove("custom_shift_key_color")
            .remove("custom_delete_key_color")
            .remove("custom_lang_key_color")
            .remove("custom_enter_key_color")
            .apply()
    }

    fun hasAnyCustomKeyColor(): Boolean =
        customNormalKeyColor != 0 || customShiftKeyColor != 0 ||
            customDeleteKeyColor != 0 || customLangKeyColor != 0 || customEnterKeyColor != 0


    // --- v4.2 Security Hardening (OWASP MASVS & Android 14-15 IME Standards) ---
    var autoIncognitoSensitiveApps: Boolean
        get() = prefs.getBoolean("auto_incognito_sensitive_apps", true)
        set(value) = prefs.edit().putBoolean("auto_incognito_sensitive_apps", value).apply()

    var tapjackingProtectionEnabled: Boolean
        get() = prefs.getBoolean("tapjacking_protection_enabled", true)
        set(value) = prefs.edit().putBoolean("tapjacking_protection_enabled", value).apply()

    var flagSecureInSecretsEnabled: Boolean
        get() = prefs.getBoolean("flag_secure_in_secrets_enabled", true)
        set(value) = prefs.edit().putBoolean("flag_secure_in_secrets_enabled", value).apply()

    // Vault auto-lock timeout in seconds: 30, 60 (default), 120
    var vaultAutoLockSeconds: Int
        get() = prefs.getInt("vault_auto_lock_seconds", 60).coerceIn(30, 300)
        set(value) = prefs.edit().putInt("vault_auto_lock_seconds", value.coerceIn(30, 300)).apply()

    fun wipeAllSensitiveData() {
        vaultUnlockedUntilMs = 0L
        prefs.edit()
            .remove("text_expanders_map")
            .remove("reply_templates_data")
            .remove("local_sticker_paths")
            .remove("pinned_clips")
            .remove("pinned_top_snippets_csv")
            .remove("recent_emojis")
            .remove("blocked_suggestion_words")
            .remove("vault_items_enc")
            .remove("vault_unlocked_until_ms")
            .remove("af_name_enc")
            .remove("af_email_enc")
            .remove("af_phone_enc")
            .remove("af_address_enc")
            .remove("af_user_enc")
            .remove("quick_note_text")
            .remove("user_dictionary_words")
            .apply()
        clearPersonalTypingData()
        resetTypingStats()
    }


    // --- v4.3 Complete Must-Have Pack (Yo key, Mixed RU+EN, Predictive Emoji, T9, Landscape Mode, Corner Radius, Shadows, Floating Size, Blacklist, Stickers) ---
    var separateYoKey: Boolean
        get() = prefs.getBoolean("separate_yo_key", false)
        set(value) = prefs.edit().putBoolean("separate_yo_key", value).apply()

    var mixedRuEnSuggestions: Boolean
        get() = prefs.getBoolean("mixed_ru_en_suggestions", true)
        set(value) = prefs.edit().putBoolean("mixed_ru_en_suggestions", value).apply()

    var predictiveEmojiEnabled: Boolean
        get() = prefs.getBoolean("predictive_emoji_enabled", true)
        set(value) = prefs.edit().putBoolean("predictive_emoji_enabled", value).apply()

    var t9ModeEnabled: Boolean
        get() = prefs.getBoolean("t9_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("t9_mode_enabled", value).apply()

    // 0 = Компактная, 1 = Разделённая (Split), 2 = Обычная
    var landscapeMode: Int
        get() = prefs.getInt("landscape_mode", 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("landscape_mode", value.coerceIn(0, 2)).apply()

    var keyCornerRadiusDp: Int
        get() = prefs.getInt("key_corner_radius_dp", 9).coerceIn(0, 20)
        set(value) = prefs.edit().putInt("key_corner_radius_dp", value.coerceIn(0, 20)).apply()

    var keyShadowEnabled: Boolean
        get() = prefs.getBoolean("key_shadow_enabled", true)
        set(value) = prefs.edit().putBoolean("key_shadow_enabled", value).apply()

    var floatingWidthPercent: Int
        get() = prefs.getInt("floating_width_percent", 84).coerceIn(68, 96)
        set(value) = prefs.edit().putInt("floating_width_percent", value.coerceIn(68, 96)).apply()

    var floatingHeightPercent: Int
        get() = prefs.getInt("floating_height_percent", 100).coerceIn(80, 120)
        set(value) = prefs.edit().putInt("floating_height_percent", value.coerceIn(80, 120)).apply()

    fun getBlockedWords(): Set<String> =
        prefs.getStringSet("blocked_suggestion_words", emptySet())?.toSet() ?: emptySet()

    fun blockWord(word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        val updated = getBlockedWords().toMutableSet()
        updated.add(clean)
        prefs.edit().putStringSet("blocked_suggestion_words", updated).apply()
    }

    fun unblockWord(word: String) {
        val updated = getBlockedWords().toMutableSet()
        updated.remove(word.trim().lowercase())
        prefs.edit().putStringSet("blocked_suggestion_words", updated).apply()
    }

    fun clearBlockedWords() {
        prefs.edit().remove("blocked_suggestion_words").apply()
    }

    fun getStickerPaths(): List<String> {
        val raw = prefs.getString("local_sticker_paths", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").filter { it.isNotBlank() && java.io.File(it).exists() }
    }

    fun addStickerPath(path: String) {
        val list = getStickerPaths().toMutableList()
        list.remove(path)
        list.add(0, path)
        prefs.edit().putString("local_sticker_paths", list.take(40).joinToString("\u001E")).apply()
    }

    fun removeStickerPath(path: String) {
        val list = getStickerPaths().toMutableList()
        list.remove(path)
        prefs.edit().putString("local_sticker_paths", list.joinToString("\u001E")).apply()
    }


    var interactiveDeleteSwipeEnabled: Boolean
        get() = prefs.getBoolean("interactive_delete_swipe_enabled", true)
        set(value) = prefs.edit().putBoolean("interactive_delete_swipe_enabled", value).apply()

    var smartShiftCaseEnabled: Boolean
        get() = prefs.getBoolean("smart_shift_case_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_shift_case_enabled", value).apply()

    var autoTypographyEnabled: Boolean
        get() = prefs.getBoolean("auto_typography_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_typography_enabled", value).apply()

    var spaceSplitJoinEnabled: Boolean
        get() = prefs.getBoolean("space_split_join_enabled", true)
        set(value) = prefs.edit().putBoolean("space_split_join_enabled", value).apply()

    var dynamicHitboxEnabled: Boolean
        get() = prefs.getBoolean("dynamic_hitbox_enabled", true)
        set(value) = prefs.edit().putBoolean("dynamic_hitbox_enabled", value).apply()

    var safeEnterSendEnabled: Boolean
        get() = prefs.getBoolean("safe_enter_send_enabled", false)
        set(value) = prefs.edit().putBoolean("safe_enter_send_enabled", value).apply()

    var smartInlineMathEnabled: Boolean
        get() = prefs.getBoolean("smart_inline_math_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_inline_math_enabled", value).apply()

    var perAppProfilesEnabled: Boolean
        get() = prefs.getBoolean("per_app_profiles_enabled", true)
        set(value) = prefs.edit().putBoolean("per_app_profiles_enabled", value).apply()

    var typingStatsEnabled: Boolean
        get() = prefs.getBoolean("typing_stats_enabled", true)
        set(value) = prefs.edit().putBoolean("typing_stats_enabled", value).apply()

    val totalCharsTyped: Long get() = prefs.getLong("stat_total_chars", 0L)
    val totalWordsTyped: Long get() = prefs.getLong("stat_total_words", 0L)
    val totalBackspaces: Long get() = prefs.getLong("stat_total_backspaces", 0L)
    val totalTypingMs: Long get() = prefs.getLong("stat_total_typing_ms", 0L)

    fun recordTypingStat(charsDelta: Int, wordsDelta: Int, backspacesDelta: Int, elapsedMs: Long) {
        prefs.edit()
            .putLong("stat_total_chars", (totalCharsTyped + charsDelta).coerceAtLeast(0L))
            .putLong("stat_total_words", (totalWordsTyped + wordsDelta).coerceAtLeast(0L))
            .putLong("stat_total_backspaces", (totalBackspaces + backspacesDelta).coerceAtLeast(0L))
            .putLong("stat_total_typing_ms", (totalTypingMs + elapsedMs.coerceIn(0L, 10_000L)).coerceAtLeast(0L))
            .apply()
    }

    fun resetTypingStats() {
        prefs.edit()
            .remove("stat_total_chars")
            .remove("stat_total_words")
            .remove("stat_total_backspaces")
            .remove("stat_total_typing_ms")
            .apply()
    }

    var keyPressHighlightEnabled: Boolean
        get() = prefs.getBoolean("key_press_highlight_enabled", false)
        set(value) = prefs.edit().putBoolean("key_press_highlight_enabled", value).apply()

    var keyPressHighlightColor: Int
        get() = prefs.getInt("key_press_highlight_color", 0)
        set(value) = prefs.edit().putInt("key_press_highlight_color", value).apply()

    var quickSymbolSlideEnabled: Boolean
        get() = prefs.getBoolean("quick_symbol_slide_enabled", true)
        set(value) = prefs.edit().putBoolean("quick_symbol_slide_enabled", value).apply()

    var undoAutoCorrectOnBackspaceEnabled: Boolean
        get() = prefs.getBoolean("undo_autocorrect_on_backspace", true)
        set(value) = prefs.edit().putBoolean("undo_autocorrect_on_backspace", value).apply()

    var contactsDictionaryEnabled: Boolean
        get() = prefs.getBoolean("contacts_dictionary_enabled", false)
        set(value) = prefs.edit().putBoolean("contacts_dictionary_enabled", value).apply()

    var landscapeHeightPercent: Int
        get() = prefs.getInt("landscape_height_percent", 92).coerceIn(75, 115)
        set(value) = prefs.edit().putInt("landscape_height_percent", value.coerceIn(75, 115)).apply()

    var ramblerVoiceModeEnabled: Boolean
        get() = prefs.getBoolean("rambler_voice_mode_enabled", true)
        set(value) = prefs.edit().putBoolean("rambler_voice_mode_enabled", value).apply()

    var ramblerStyleIndex: Int
        get() = prefs.getInt("rambler_style_index", 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("rambler_style_index", value.coerceIn(0, 2)).apply()

    var differentialHapticsEnabled: Boolean
        get() = prefs.getBoolean("differential_haptics_enabled", true)
        set(value) = prefs.edit().putBoolean("differential_haptics_enabled", value).apply()

    var smartClipboardReplyEnabled: Boolean
        get() = prefs.getBoolean("smart_clipboard_reply_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_clipboard_reply_enabled", value).apply()

    var arrowBarEnabled: Boolean
        get() = prefs.getBoolean("arrow_bar_enabled", false)
        set(value) = prefs.edit().putBoolean("arrow_bar_enabled", value).apply()

    var customQuickDomainsCsv: String
        get() = prefs.getString("custom_quick_domains_csv", ".ru,.com,.рф,@gmail.com,@yandex.ru,@mail.ru") ?: ".ru,.com,.рф,@gmail.com,@yandex.ru,@mail.ru"
        set(value) = prefs.edit().putString("custom_quick_domains_csv", value.trim()).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    fun exportThemeCode(): String {
        val raw = listOf(
            themeMode.name,
            glassTransparency.toString(),
            glassBlurRadius.toString(),
            keyCornerRadiusDp.toString(),
            if (keyShadowEnabled) "1" else "0",
            if (keyPressHighlightEnabled) "1" else "0",
            keyPressHighlightColor.toString(),
            customNormalKeyColor.toString(),
            customShiftKeyColor.toString(),
            customDeleteKeyColor.toString(),
            customLangKeyColor.toString(),
            customEnterKeyColor.toString()
        ).joinToString("|")
        val b64 = android.util.Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "CK1:$b64"
    }

    fun importThemeCode(code: String): Boolean {
        val clean = code.trim()
        if (!clean.startsWith("CK1:")) return false
        return try {
            val decoded = String(android.util.Base64.decode(clean.removePrefix("CK1:"), android.util.Base64.DEFAULT), Charsets.UTF_8)
            val p = decoded.split("|")
            if (p.size < 12) return false
            themeMode = DayNightMode.values().firstOrNull { it.name == p[0] } ?: DayNightMode.GLASS_BG_SYSTEM
            glassTransparency = p[1].toIntOrNull() ?: glassTransparency
            glassBlurRadius = p[2].toIntOrNull() ?: glassBlurRadius
            keyCornerRadiusDp = p[3].toIntOrNull() ?: keyCornerRadiusDp
            keyShadowEnabled = p[4] == "1"
            keyPressHighlightEnabled = p[5] == "1"
            keyPressHighlightColor = p[6].toIntOrNull() ?: 0
            customNormalKeyColor = p[7].toIntOrNull() ?: 0
            customShiftKeyColor = p[8].toIntOrNull() ?: 0
            customDeleteKeyColor = p[9].toIntOrNull() ?: 0
            customLangKeyColor = p[10].toIntOrNull() ?: 0
            customEnterKeyColor = p[11].toIntOrNull() ?: 0
            true
        } catch (_: Exception) {
            false
        }
    }

    var otpNotificationAutofillEnabled: Boolean
        get() = prefs.getBoolean("otp_notification_autofill_enabled", false)
        set(value) = prefs.edit().putBoolean("otp_notification_autofill_enabled", value).apply()

    var pinnedTopSnippetsCsv: String
        get() = prefs.getString("pinned_top_snippets_csv", "") ?: ""
        set(value) = prefs.edit().putString("pinned_top_snippets_csv", value.trim()).apply()
}