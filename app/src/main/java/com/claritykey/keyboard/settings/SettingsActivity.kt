package com.claritykey.keyboard.settings

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import com.claritykey.keyboard.skin.BreenoSkinEngine
import android.os.Build
import android.graphics.Shader
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.claritykey.keyboard.R
import com.claritykey.keyboard.clipboard.ClipboardCrypto
import com.claritykey.keyboard.clipboard.ClipboardRepository
import com.claritykey.keyboard.clipboard.ClipboardImagePickerActivity
import com.claritykey.keyboard.clipboard.RecentScreenshotDetector
import java.util.Locale
import com.claritykey.keyboard.engine.PersonalMemoryEngine
import com.claritykey.keyboard.engine.TouchCalibrationModel
import com.claritykey.keyboard.prefs.AutoCorrectStrength
import com.claritykey.keyboard.prefs.OneHandedMode
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.claritykey.keyboard.prefs.BackupExportHelper
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.claritykey.keyboard.layout.BottomPaddingMode
import com.claritykey.keyboard.skin.DayNightMode
import kotlin.math.roundToInt

/**
 * Reorganized ClarityKey Settings:
 * 1. Языки и раскладки (включая переключатель «Цифровой ряд»)
 * 2. Ввод и исправление текста (включая персональный словарь)
 * 3. Внешний вид (классические темы, стеклянная подложка, полное стекло, прозрачность, Blur, всплывающие клавиши)
 * 4. Звук и вибрация
 * 5. Буфер обмена
 * 6. Дополнительные функции (голосовой ввод)
 * 7. Конфиденциальность и данные
 * 8. О клавиатуре
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
        const val EXTRA_OPEN_CLIPBOARD_SETTINGS = "open_clipboard_settings"
        const val EXTRA_REQUEST_SCREENSHOT_PERMISSION = "request_screenshot_permission"
        const val EXTRA_OPEN_VAULT = "open_vault"
        const val EXTRA_OPEN_APPEARANCE_THEME = "open_appearance_theme"
        const val EXTRA_OPEN_APPEARANCE_LAYOUT = "open_appearance_layout"
        const val EXTRA_OPEN_APPEARANCE_TOOLBAR = "open_appearance_toolbar"
        const val EXTRA_OPEN_TEMPLATES = "open_templates"
        const val EXTRA_OPEN_AUTOFILL_MEMORY = "open_autofill_memory"
        private const val REQ_MIC = 1001
        private const val REQ_MEDIA = 1002
        private const val REQ_EXPORT_BACKUP = 1003
        private const val REQ_IMPORT_BACKUP = 1004
        private const val REQ_CONTACTS = 1005
        private const val REQ_EXPORT_DICT = 1006
        private const val REQ_IMPORT_DICT = 1007
    }

    private enum class Screen {
        HOME,
        LANGUAGES,
        KEYBOARD_SETTINGS,
        TEXT_INPUT,
        DICTIONARY,
        CALIBRATION,
        TYPING_STATS,
        EXPANDERS,
        AUTOFILL_MEMORY,
        APPEARANCE,
        APPEARANCE_THEME,
        APPEARANCE_KEY_COLORS,
        APPEARANCE_WALLPAPER,
        APPEARANCE_LAYOUT,
        APPEARANCE_TOOLBAR,
        SOUND_HAPTIC,
        CLIPBOARD,
        CLIPBOARD_TEMPLATES,
        CLIPBOARD_VAULT,
        EXTRA_FEATURES,
        PRIVACY,
        ABOUT
    }

    private lateinit var prefs: BreenoPreferences
    private var currentScreen: Screen = Screen.HOME
    private var vaultUnlockedInSettings: Boolean = false
    private var selectedTemplateFolderIdx: Int = 0
    private var selectedColorTargetIdx: Int = 0
    private var isDarkUi: Boolean = false
    private var density: Float = 1f

    private var bgSurface: Int = 0
    private var cardSurface: Int = 0
    private var textPrimary: Int = 0
    private var textSecondary: Int = 0
    private var accentBlue: Int = 0
    private var dividerColor: Int = 0
    private var iconBgTint: Int = 0

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SettingsLocale.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_screen", currentScreen.name)
        outState.putInt("settings_template_folder", selectedTemplateFolderIdx)
        outState.putInt("settings_color_target", selectedColorTargetIdx)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = BreenoPreferences(this)
        density = resources.displayMetrics.density
        resolvePalette()

        if (savedInstanceState != null) {
            currentScreen = savedInstanceState.getString("settings_screen")?.let { name ->
                Screen.entries.firstOrNull { it.name == name }
            } ?: Screen.HOME
            selectedTemplateFolderIdx = savedInstanceState.getInt("settings_template_folder", 0)
            selectedColorTargetIdx = savedInstanceState.getInt("settings_color_target", 0)
        } else when {
            intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true -> {
                currentScreen = Screen.EXTRA_FEATURES
                requestMicPermission()
            }
            intent?.getBooleanExtra(EXTRA_OPEN_CLIPBOARD_SETTINGS, false) == true -> {
                currentScreen = Screen.CLIPBOARD
            }
            intent?.getBooleanExtra(EXTRA_OPEN_VAULT, false) == true -> {
                currentScreen = Screen.CLIPBOARD_VAULT
                authenticateVaultBiometric()
            }
            intent?.getBooleanExtra(EXTRA_OPEN_APPEARANCE_THEME, false) == true -> currentScreen = Screen.APPEARANCE_THEME
            intent?.getBooleanExtra(EXTRA_OPEN_APPEARANCE_LAYOUT, false) == true -> currentScreen = Screen.APPEARANCE_LAYOUT
            intent?.getBooleanExtra(EXTRA_OPEN_APPEARANCE_TOOLBAR, false) == true -> currentScreen = Screen.APPEARANCE_TOOLBAR
            intent?.getBooleanExtra(EXTRA_OPEN_TEMPLATES, false) == true -> currentScreen = Screen.CLIPBOARD_TEMPLATES
            intent?.getBooleanExtra(EXTRA_OPEN_AUTOFILL_MEMORY, false) == true -> currentScreen = Screen.AUTOFILL_MEMORY
            intent?.getBooleanExtra(EXTRA_REQUEST_SCREENSHOT_PERMISSION, false) == true -> {
                currentScreen = Screen.CLIPBOARD
                if (RecentScreenshotDetector.hasMediaPermission(this)) {
                    prefs.clipboardSuggestScreenshots = true
                    Toast.makeText(this, getString(R.string.settings_screenshot_auto_save_enabled_1), Toast.LENGTH_SHORT).show()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(RecentScreenshotDetector.requiredPermission()),
                        REQ_MEDIA
                    )
                }
            }
        }
        renderScreen()
    }

    private val vaultHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val vaultExpiryCheck = object : Runnable {
        override fun run() {
            if (currentScreen == Screen.CLIPBOARD_VAULT && vaultUnlockedInSettings && !prefs.isVaultUnlocked()) {
                vaultUnlockedInSettings = false
                renderScreen()
            }
            vaultHandler.postDelayed(this, 250L)
        }
    }

    override fun onResume() {
        vaultHandler.post(vaultExpiryCheck)
        super.onResume()
        resolvePalette()
        renderScreen()
    }

    override fun onPause() {
        vaultHandler.removeCallbacks(vaultExpiryCheck)
        super.onPause()
        vaultUnlockedInSettings = false
    }

    private fun parentScreenFor(screen: Screen): Screen? = when (screen) {
        Screen.HOME -> null
        Screen.DICTIONARY, Screen.CALIBRATION, Screen.TYPING_STATS, Screen.EXPANDERS, Screen.AUTOFILL_MEMORY -> Screen.TEXT_INPUT
        Screen.APPEARANCE_THEME, Screen.APPEARANCE_KEY_COLORS, Screen.APPEARANCE_WALLPAPER, Screen.KEYBOARD_SETTINGS,
        Screen.APPEARANCE_LAYOUT, Screen.APPEARANCE_TOOLBAR -> Screen.APPEARANCE
        Screen.CLIPBOARD_TEMPLATES, Screen.CLIPBOARD_VAULT -> Screen.CLIPBOARD
        else -> Screen.HOME
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val parent = parentScreenFor(currentScreen)
        if (parent != null) {
            currentScreen = parent
            renderScreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) getString(R.string.settings_microphone_access_granted_voice_typing_is_ready_2) else getString(R.string.settings_microphone_access_denied_3),
                Toast.LENGTH_SHORT
            ).show()
            renderScreen()
        }
        if (requestCode == REQ_MEDIA) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            prefs.clipboardSuggestScreenshots = granted
            Toast.makeText(
                this,
                if (granted) getString(R.string.settings_recent_screenshot_suggestions_enabled_4) else getString(R.string.settings_photo_access_denied_you_can_still_choose_images_5),
                Toast.LENGTH_SHORT
            ).show()
            renderScreen()
        }
        if (requestCode == REQ_CONTACTS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            prefs.contactsDictionaryEnabled = granted
            if (granted) {
                val added = importContactNamesToDictionary()
                Toast.makeText(this, getString(R.string.settings_contact_names_imported_1_s_6, (added).toString()), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.settings_contacts_access_was_not_granted_7), Toast.LENGTH_SHORT).show()
            }
            renderScreen()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT_BACKUP -> {
                val ok = BackupExportHelper.exportToUri(this, prefs, uri)
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.settings_claritykey_backup_saved_8) else getString(R.string.settings_could_not_save_the_backup_9),
                    Toast.LENGTH_SHORT
                ).show()
            }
            REQ_EXPORT_DICT -> {
                val ok = try {
                    val words = prefs.getUserWords().sorted().joinToString("\n")
                    contentResolver.openOutputStream(uri)?.use { it.write(words.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (_: Exception) { false }
                Toast.makeText(this, if (ok) getString(R.string.settings_dictionary_exported_txt_10) else getString(R.string.settings_could_not_export_the_dictionary_11), Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT_DICT -> {
                var added = 0
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                        lines.forEach { line ->
                            val w = line.trim().split(Regex("[\t,;\\s]+")).firstOrNull()?.trim() ?: ""
                            if (w.length >= 2 && w.all { it.isLetter() || it == '-' || it == '\'' }) {
                                prefs.addUserWord(w)
                                added++
                            }
                        }
                    }
                } catch (_: Exception) {}
                Toast.makeText(this, getString(R.string.settings_words_imported_1_s_12, (added).toString()), Toast.LENGTH_SHORT).show()
                renderScreen()
            }
            REQ_IMPORT_BACKUP -> {
                val ok = BackupExportHelper.importFromUri(this, prefs, uri)
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.settings_settings_and_dictionary_restored_13) else getString(R.string.settings_invalid_claritykey_file_14),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) renderScreen()
            }
        }
    }

    private fun authenticateVaultBiometric() {
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.settings_claritykey_vault_15))
                .setSubtitle(getString(R.string.settings_verify_your_identity_to_access_encrypted_entries_16))
                .setNegativeButton(getString(R.string.settings_cancel_17), mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        vaultUnlockedInSettings = true
                        prefs.vaultUnlockedUntilMs = System.currentTimeMillis() + prefs.vaultAutoLockSeconds * 1000L
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_vault_unlocked_for_1_s_sec_18, (prefs.vaultAutoLockSeconds).toString()), Toast.LENGTH_SHORT).show()
                        renderScreen()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        Toast.makeText(this@SettingsActivity, errString ?: getString(R.string.settings_biometrics_unavailable_19), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (_: Exception) {
            vaultUnlockedInSettings = false
            prefs.vaultUnlockedUntilMs = 0L
            Toast.makeText(this, getString(R.string.settings_could_not_verify_your_identity_the_vault_remains_20), Toast.LENGTH_SHORT).show()
            renderScreen()
        }
    }

    private fun resolvePalette() {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkUi = mask == Configuration.UI_MODE_NIGHT_YES
        if (isDarkUi) {
            bgSurface = Color.parseColor("#FF131417")
            cardSurface = Color.parseColor("#FF1F2126")
            textPrimary = Color.parseColor("#FFF1F3F6")
            textSecondary = Color.parseColor("#FFA0A6B2")
            accentBlue = Color.parseColor("#FF5C9DFF")
            dividerColor = Color.parseColor("#1FFFFFFF")
            iconBgTint = Color.parseColor("#245C9DFF")
        } else {
            bgSurface = Color.parseColor("#FFF4F6FA")
            cardSurface = Color.parseColor("#FFFFFFFF")
            textPrimary = Color.parseColor("#FF1A1C20")
            textSecondary = Color.parseColor("#FF5F6570")
            accentBlue = Color.parseColor("#FF0066FF")
            dividerColor = Color.parseColor("#14000000")
            iconBgTint = Color.parseColor("#1A0066FF")
        }
        window.statusBarColor = bgSurface
        window.navigationBarColor = bgSurface
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }


    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun renderScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSurface)
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(28f))
        }

        when (currentScreen) {
            Screen.HOME -> renderHome(content)
            Screen.LANGUAGES -> renderLanguages(content)
            Screen.KEYBOARD_SETTINGS -> renderKeyboardSettings(content)
            Screen.TEXT_INPUT -> renderTextInput(content)
            Screen.DICTIONARY -> renderDictionary(content)
            Screen.CALIBRATION -> renderCalibration(content)
            Screen.TYPING_STATS -> renderTypingStats(content)
            Screen.EXPANDERS -> renderExpanders(content)
            Screen.AUTOFILL_MEMORY -> renderAutofillMemory(content)
            Screen.APPEARANCE -> renderAppearance(content)
            Screen.APPEARANCE_THEME -> renderAppearanceTheme(content)
            Screen.APPEARANCE_KEY_COLORS -> renderAppearanceKeyColors(content)
            Screen.APPEARANCE_WALLPAPER -> renderAppearanceWallpaper(content)
            Screen.APPEARANCE_LAYOUT -> renderAppearanceLayout(content)
            Screen.APPEARANCE_TOOLBAR -> renderAppearanceToolbar(content)
            Screen.SOUND_HAPTIC -> renderSoundHaptic(content)
            Screen.CLIPBOARD -> renderClipboard(content)
            Screen.CLIPBOARD_TEMPLATES -> renderClipboardTemplates(content)
            Screen.CLIPBOARD_VAULT -> renderClipboardVault(content)
            Screen.EXTRA_FEATURES -> renderExtraFeatures(content)
            Screen.PRIVACY -> renderPrivacy(content)
            Screen.ABOUT -> renderAbout(content)
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val isSensitiveScreen = currentScreen == Screen.CLIPBOARD_VAULT || currentScreen == Screen.AUTOFILL_MEMORY
        if (isSensitiveScreen && prefs.flagSecureInSecretsEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(root)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(36f), dp(16f), dp(12f))
        }
        if (currentScreen != Screen.HOME) {
            val back = TextView(this).apply {
                text = "←"
                contentDescription = getString(R.string.settings_back_21)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(textPrimary)
                setPadding(dp(8f), dp(4f), dp(14f), dp(4f))
                setOnClickListener {
                    currentScreen = parentScreenFor(currentScreen) ?: Screen.HOME
                    renderScreen()
                }
            }
            bar.addView(back)
        }
        val titleText = when (currentScreen) {
            Screen.HOME -> getString(R.string.settings_settings_22)
            Screen.LANGUAGES -> getString(R.string.settings_languages_and_layouts_23)
            Screen.KEYBOARD_SETTINGS -> getString(R.string.settings_keys_font_and_emoji_24)
            Screen.TEXT_INPUT -> getString(R.string.settings_typing_gestures_and_correction_25)
            Screen.DICTIONARY -> getString(R.string.settings_personal_dictionary_26)
            Screen.CALIBRATION -> getString(R.string.settings_gestures_touch_and_calibration_27)
            Screen.TYPING_STATS -> getString(R.string.settings_typing_statistics_and_touch_offset_28)
            Screen.EXPANDERS -> getString(R.string.settings_text_shortcuts_29)
            Screen.AUTOFILL_MEMORY -> getString(R.string.settings_autofill_and_personal_memory_30)
            Screen.APPEARANCE -> getString(R.string.settings_appearance_and_layout_31)
            Screen.APPEARANCE_THEME -> getString(R.string.settings_theme_system_and_glass_32)
            Screen.APPEARANCE_KEY_COLORS -> getString(R.string.settings_key_colors_33)
            Screen.APPEARANCE_WALLPAPER -> getString(R.string.settings_keyboard_wallpaper_34)
            Screen.APPEARANCE_LAYOUT -> getString(R.string.settings_size_and_modes_35)
            Screen.APPEARANCE_TOOLBAR -> getString(R.string.settings_toolbar_5_slots_36)
            Screen.SOUND_HAPTIC -> getString(R.string.settings_sound_and_vibration_37)
            Screen.CLIPBOARD -> getString(R.string.settings_clipboard_38)
            Screen.CLIPBOARD_TEMPLATES -> getString(R.string.settings_quick_reply_templates_39)
            Screen.CLIPBOARD_VAULT -> getString(R.string.settings_secure_vault_40)
            Screen.EXTRA_FEATURES -> getString(R.string.settings_voice_and_app_41)
            Screen.PRIVACY -> getString(R.string.settings_security_and_privacy_42)
            Screen.ABOUT -> getString(R.string.settings_about_43)
        }
        bar.addView(TextView(this).apply {
            text = titleText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (currentScreen == Screen.HOME) 26f else 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(6f), 0, 0, 0)
        })
        return bar
    }

    private fun renderHome(container: LinearLayout) {
        if (!prefs.onboardingCompleted) {
            val obCard = makeCard()
            obCard.addView(sectionTitle(getString(R.string.settings_claritykey_quick_start_44)))
            obCard.addView(TextView(this).apply {
                text = getString(R.string.settings_1_enable_and_select_claritykey_below_2_choose_a_45)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
                setPadding(0, 0, 0, dp(10f))
            })
            val styleRow = makeSegmentedControl(
                listOf(getString(R.string.settings_glass_46), getString(R.string.settings_light_47), getString(R.string.settings_dark_48)),
                when (prefs.themeMode) {
                    DayNightMode.LIGHT -> 1
                    DayNightMode.DARK -> 2
                    else -> 0
                }
            ) { idx ->
                prefs.themeMode = when (idx) {
                    1 -> DayNightMode.LIGHT
                    2 -> DayNightMode.DARK
                    else -> DayNightMode.GLASS_BG_SYSTEM
                }
                renderScreen()
            }
            obCard.addView(styleRow)
            obCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })
            obCard.addView(makePillButton(getString(R.string.settings_done_hide_this_tip_49), primary = false) {
                prefs.onboardingCompleted = true
                renderScreen()
            })
            container.addView(obCard)
        }
        val enabled = isImeEnabled()
        val setupCard = makeCard()
        setupCard.addView(TextView(this).apply {
            text = if (enabled) getString(R.string.settings_claritykey_is_active_50) else getString(R.string.settings_enable_your_keyboard_51)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        setupCard.addView(TextView(this).apply {
            text = if (enabled) getString(R.string.settings_select_a_keyboard_or_try_the_test_field_below_52) else getString(R.string.settings_enable_claritykey_in_android_settings_53)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
            setPadding(0, dp(3f), 0, dp(12f))
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makePillButton(if (enabled) getString(R.string.settings_select_keyboard_54) else getString(R.string.settings_1_enable_in_android_55), primary = true) {
            if (enabled) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            } else {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })
        if (!enabled) {
            btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
            btnRow.addView(makePillButton(getString(R.string.settings_2_select_56), primary = false) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })
        }
        setupCard.addView(btnRow)
        container.addView(setupCard)

        val menuCard = makeCard(padVertical = 4f)
        val langSub = if (prefs.showNumberRow) getString(R.string.settings_ru_en_number_row_on_57) else getString(R.string.settings_russian_english_qwerty_58)
        val inputSub = if (prefs.autoCorrect) getString(R.string.settings_suggestions_and_autocorrection_on_59) else getString(R.string.settings_word_suggestions_dictionary_1_s_60, (prefs.getUserWords().size).toString())
        val themeName = when (prefs.themeMode) {
            DayNightMode.SYSTEM -> getString(R.string.settings_system_61)
            DayNightMode.LIGHT -> getString(R.string.settings_claritykey_light_62)
            DayNightMode.DARK -> getString(R.string.settings_claritykey_dark_63)
            DayNightMode.GLASS_BG_SYSTEM -> getString(R.string.settings_glass_background_1_s_64, (prefs.glassTransparency).toString())
            DayNightMode.GLASS_BG_LIGHT -> getString(R.string.settings_light_glass_background_1_s_65, (prefs.glassTransparency).toString())
            DayNightMode.GLASS_BG_DARK -> getString(R.string.settings_dark_glass_background_1_s_66, (prefs.glassTransparency).toString())
            DayNightMode.GLASS_SYSTEM -> getString(R.string.settings_full_glass_1_s_67, (prefs.glassTransparency).toString())
            DayNightMode.GLASS_LIGHT -> getString(R.string.settings_light_glass_1_s_68, (prefs.glassTransparency).toString())
            DayNightMode.GLASS_DARK -> getString(R.string.settings_dark_glass_1_s_69, (prefs.glassTransparency).toString())
        }
        val soundSub = buildString {
            append(if (prefs.hapticEnabled) getString(R.string.settings_vibration_on_70) else getString(R.string.settings_vibration_off_71))
            append(" • ")
            append(if (prefs.soundEnabled) getString(R.string.settings_sound_on_72) else getString(R.string.settings_sound_off_73))
        }
        val clipSub = if (prefs.clipboardEnabled) getString(R.string.settings_up_to_1_s_entries_2_s_74, (prefs.clipboardMaxItems).toString(), (retentionLabel(prefs.clipboardRetentionHours)).toString()) else getString(R.string.settings_auto_save_off_75)
        val extraSub = if (prefs.voiceInputEnabled) {
            if (hasMicPermission()) getString(R.string.settings_voice_typing_is_ready_76) else getString(R.string.settings_voice_typing_microphone_access_required_77)
        } else {
            getString(R.string.settings_voice_typing_off_78)
        }

        val categories = listOf(
            Triple(R.drawable.ic_breeno_globe, getString(R.string.settings_languages_and_layouts_23), langSub) to Screen.LANGUAGES,
            Triple(R.drawable.ic_toolbar_edit, getString(R.string.settings_typing_gestures_and_correction_25), inputSub) to Screen.TEXT_INPUT,
            Triple(R.drawable.ic_toolbar_settings, getString(R.string.settings_appearance_and_layout_31), themeName) to Screen.APPEARANCE,
            Triple(R.drawable.ic_breeno_header_switch, getString(R.string.settings_sound_and_vibration_37), soundSub) to Screen.SOUND_HAPTIC,
            Triple(R.drawable.ic_toolbar_clip, getString(R.string.settings_clipboard_38), clipSub) to Screen.CLIPBOARD,
            Triple(R.drawable.ic_toolbar_voice, getString(R.string.settings_voice_and_app_41), extraSub) to Screen.EXTRA_FEATURES,
            Triple(R.drawable.ic_toolbar_trash, getString(R.string.settings_security_and_privacy_42), getString(R.string.settings_clipboard_encryption_and_data_deletion_79)) to Screen.PRIVACY,
            Triple(R.drawable.ic_toolbar_emoji, getString(R.string.settings_about_43), "ClarityKey • v${getAppVersionPair().first}") to Screen.ABOUT
        )

        categories.forEachIndexed { idx, (info, target) ->
            if (idx > 0) menuCard.addView(makeDivider())
            menuCard.addView(makeCategoryRow(info.first, info.second, info.third) {
                currentScreen = target
                renderScreen()
            })
        }
        container.addView(menuCard)

        val testCard = makeCard()
        val sandboxHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        sandboxHeader.addView(TextView(this).apply {
            text = getString(R.string.settings_try_your_keyboard_80)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accentBlue)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sandboxInput = EditText(this).apply {
            hint = getString(R.string.settings_tap_here_to_try_layouts_t9_stickers_and_the_clip_81)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        sandboxHeader.addView(TextView(this).apply {
            text = getString(R.string.settings_clear_82)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
            setOnClickListener { sandboxInput.setText("") }
        })
        testCard.addView(sandboxHeader)
        testCard.addView(sandboxInput)
        container.addView(testCard)
    }

    // 1. Языки и раскладки (включая «Цифровой ряд»)
    private fun renderLanguages(container: LinearLayout) {
        val languageCard = makeCard()
        languageCard.addView(makeInfoRow(getString(R.string.settings_ui_language_title),
            getString(R.string.settings_ui_language_description)))
        languageCard.addView(makeCompactChipRow(
            "RU / EN", listOf("ru" to "Русский", "en" to "English"), SettingsLocale.language(this)
        ) { language ->
            if (language != SettingsLocale.language(this)) {
                SettingsLocale.select(this, language)
                recreate()
            }
        })
        container.addView(languageCard)
        val card = makeCard(padVertical = 6f)
        card.addView(makeInfoRow(getString(R.string.settings_russian_83), getString(R.string.settings_all_33_letters_including_with_a_dictionary_of_up_84)))
        card.addView(makeDivider())
        card.addView(makeInfoRow("English (QWERTY)", getString(R.string.settings_standard_layout_with_a_dictionary_of_up_to_200k_85)))
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_number_row_86),
                getString(R.string.settings_show_a_separate_number_row_above_the_letters_87),
                prefs.showNumberRow
            ) { prefs.showNumberRow = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_separate_key_88),
                getString(R.string.settings_12_keys_in_the_top_russian_row_instead_of_holdin_89),
                prefs.separateHardSign
            ) { prefs.separateHardSign = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_separate_key_90),
                getString(R.string.settings_add_to_the_main_russian_layout_instead_of_holdin_91),
                prefs.separateYoKey
            ) { prefs.separateYoKey = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_classic_t9_layout_3_4_92),
                getString(R.string.settings_large_multi_tap_letter_groups_for_one_handed_typ_93),
                prefs.t9ModeEnabled
            ) { prefs.t9ModeEnabled = it }
        )
        container.addView(card)

        val domCard = makeCard()
        domCard.addView(sectionTitle(getString(R.string.settings_quick_domains_for_browsers_and_email_94)))
        val domInput = EditText(this).apply {
            setText(prefs.customQuickDomainsCsv)
            hint = ".ru,.com,.рф,@gmail.com,@yandex.ru"
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        domCard.addView(domInput)
        domCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })
        domCard.addView(makePillButton(getString(R.string.settings_save_domains_96), primary = true) {
            prefs.customQuickDomainsCsv = domInput.text.toString()
            Toast.makeText(this, getString(R.string.settings_quick_domains_saved_97), Toast.LENGTH_SHORT).show()
        })
        container.addView(domCard)
    }

    // 1.5 Параметры клавиатуры (Клавиши, Размер шрифта/эмодзи, Макет, Быстрые команды, Нажатие и Значок)

    private fun importContactNamesToDictionary(): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0
        val wordsBeforeImport = prefs.getUserWords()
        val importedWords = linkedSetOf<String>()
        try {
            contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && importedWords.size < 400) {
                    val display = c.getString(0) ?: continue
                    for (token in display.split(Regex("[^\\p{L}]+"))) {
                        if (token.length >= 2) importedWords.add(token)
                        if (importedWords.size >= 400) break
                    }
                }
            }
        } catch (_: Exception) {
            return 0
        }
        importedWords.forEach(prefs::addUserWord)
        return prefs.getUserWords().count { it !in wordsBeforeImport }
    }

    private fun setLauncherIconVisible(visible: Boolean) {
        try {
            val cn = ComponentName(this, "com.claritykey.keyboard.settings.LauncherAlias")
            val state = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {
        }
    }

    private fun renderKeyboardSettings(container: LinearLayout) {
        val (previewCard, _) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        // Card 1: Клавиши (Screenshot 2)
        val keysCard = makeCard(padVertical = 6f)
        keysCard.addView(sectionTitle(getString(R.string.settings_keys_98)))
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_number_row_99),
                getString(R.string.settings_always_show_numbers_1_0_above_the_letters_100),
                prefs.showNumberRow
            ) {
                prefs.showNumberRow = it
                renderScreen()
            }
        )
        keysCard.addView(makeDivider())
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_number_row_in_password_fields_101),
                getString(R.string.settings_automatically_show_numbers_when_entering_passwor_102),
                prefs.showNumberRowOnPassword
            ) { prefs.showNumberRowOnPassword = it }
        )
        keysCard.addView(makeDivider())
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_emoji_switch_key_103),
                getString(R.string.settings_show_the_emoji_key_on_the_bottom_row_hides_the_l_104),
                prefs.showEmojiKey
            ) { checked ->
                prefs.showEmojiKey = checked
                if (checked) prefs.showLangKey = false
                renderScreen()
            }
        )
        keysCard.addView(makeDivider())
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_language_switch_key_105),
                getString(R.string.settings_show_the_globe_key_on_the_bottom_row_hides_the_e_106),
                prefs.showLangKey
            ) { checked ->
                prefs.showLangKey = checked
                if (checked) prefs.showEmojiKey = false
                renderScreen()
            }
        )
        keysCard.addView(makeDivider())
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_comma_key_107),
                getString(R.string.settings_show_to_the_left_of_the_spacebar_108),
                prefs.showCommaKey
            ) { prefs.showCommaKey = it }
        )
        keysCard.addView(makeDivider())
        keysCard.addView(
            makeSwitchRow(
                getString(R.string.settings_period_key_109),
                getString(R.string.settings_show_to_the_right_of_the_spacebar_110),
                prefs.showPeriodKey
            ) { prefs.showPeriodKey = it }
        )
        container.addView(keysCard)

        // Card 2: Вид — Размер шрифта и Размер эмодзи с живым превью (Screenshot 1)
        val viewCard = makeCard()
        viewCard.addView(sectionTitle(getString(R.string.settings_appearance_111)))

        val fontLabel = TextView(this).apply {
            text = getString(R.string.settings_font_size_1_s_112, (prefs.keyFontScalePercent).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        viewCard.addView(fontLabel)
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }
        fontRow.addView(TextView(this).apply {
            text = "Tt"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
            setPadding(0, 0, dp(8f), 0)
        })
        val fontSeek = SeekBar(this).apply {
            max = 55
            progress = (prefs.keyFontScalePercent - 80).coerceIn(0, 55)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 80
                    prefs.keyFontScalePercent = v
                    fontLabel.text = getString(R.string.settings_font_size_1_s_112, (v).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        fontRow.addView(fontSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fontRow.addView(TextView(this).apply {
            text = "Tt"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(textSecondary)
            setPadding(dp(8f), 0, 0, 0)
        })
        viewCard.addView(fontRow)
        viewCard.addView(makePillButton(getString(R.string.settings_use_device_font_size_113), primary = false) {
            val sysScale = (resources.configuration.fontScale * 100f).roundToInt().coerceIn(80, 135)
            prefs.keyFontScalePercent = sysScale
            Toast.makeText(this, getString(R.string.settings_device_font_size_applied_1_s_114, (sysScale).toString()), Toast.LENGTH_SHORT).show()
            renderScreen()
        })

        viewCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12f)) })
        viewCard.addView(makeDivider())
        viewCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })

        val emojiTitle = TextView(this).apply {
            text = getString(R.string.settings_emoji_size_115)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        viewCard.addView(emojiTitle)
        val emojiPreview = TextView(this).apply {
            text = "😂😘😀❤️😭😎🔥🎉"
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(8f))
            val sp = when (prefs.emojiSizeLevel) { 0 -> 19f; 1 -> 21f; 3 -> 26f; 4 -> 29f; else -> 23f }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
        viewCard.addView(emojiPreview)
        viewCard.addView(SeekBar(this).apply {
            max = 4
            progress = prefs.emojiSizeLevel.coerceIn(0, 4)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.emojiSizeLevel = p
                    val sp = when (p) { 0 -> 19f; 1 -> 21f; 3 -> 26f; 4 -> 29f; else -> 23f }
                    emojiPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })
        viewCard.addView(makePillButton(getString(R.string.settings_default_116), primary = false) {
            prefs.emojiSizeLevel = 2
            renderScreen()
        })
        container.addView(viewCard)

        // Card 3: Макет и Быстрые команды (Screenshots 3 & 4)
        val cmdCard = makeCard(padVertical = 6f)
        cmdCard.addView(sectionTitle(getString(R.string.settings_layout_and_quick_actions_117)))
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_suggestion_bar_118),
                getString(R.string.settings_show_the_suggestion_and_tools_bar_119),
                prefs.showSuggestionsBar
            ) { prefs.showSuggestionsBar = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_automatic_periods_120),
                getString(R.string.settings_insert_a_period_and_space_when_you_double_tap_th_121),
                prefs.doubleSpacePeriod
            ) { prefs.doubleSpacePeriod = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_space_after_punctuation_122),
                getString(R.string.settings_automatically_insert_a_space_after_123),
                prefs.autoSpaceAfterPunctuation
            ) { prefs.autoSpaceAfterPunctuation = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_switch_back_after_an_apostrophe_124),
                getString(R.string.settings_return_to_letters_immediately_after_typing_an_ap_125),
                prefs.autoSwitchAfterApostrophe
            ) { prefs.autoSwitchAfterApostrophe = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_long_press_symbols_126),
                getString(R.string.settings_open_extra_symbols_when_holding_a_key_127),
                prefs.longPressSymbolsEnabled
            ) { prefs.longPressSymbolsEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_swipe_down_for_symbols_128),
                getString(R.string.settings_touch_a_key_and_drag_down_to_enter_its_extra_sym_129),
                prefs.flickDownSymbolsEnabled
            ) { prefs.flickDownSymbolsEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_quick_symbol_gesture_from_123_130),
                getString(R.string.settings_hold_123_slide_to_a_symbol_and_release_to_return_131),
                prefs.quickSymbolSlideEnabled
            ) { prefs.quickSymbolSlideEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_swipe_to_delete_on_132),
                getString(R.string.settings_swipe_left_from_backspace_to_select_1_4_words_dr_133),
                prefs.interactiveDeleteSwipeEnabled
            ) { prefs.interactiveDeleteSwipeEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_change_selection_case_with_shift_134),
                getString(R.string.settings_tap_with_text_selected_to_cycle_text_text_text_135),
                prefs.smartShiftCaseEnabled
            ) { prefs.smartShiftCaseEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(
            makeSwitchRow(
                getString(R.string.settings_prevent_accidental_sends_double_enter_136),
                getString(R.string.settings_in_messaging_apps_double_tap_send_when_the_text_137),
                prefs.safeEnterSendEnabled
            ) { prefs.safeEnterSendEnabled = it }
        )
        cmdCard.addView(makeDivider())
        cmdCard.addView(TextView(this).apply {
            text = getString(R.string.settings_touch_sensitivity_138)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(4f), dp(10f), 0, dp(6f))
        })
        cmdCard.addView(makeSegmentedControl(listOf(getString(R.string.settings_low_139), getString(R.string.settings_normal_140), getString(R.string.settings_high_141)), prefs.touchSensitivity) { idx ->
            prefs.touchSensitivity = idx
            renderScreen()
        })
        container.addView(cmdCard)

        // Card 4: Нажатие клавиши и Значок приложения (Screenshot 5)
        val pressCard = makeCard()
        pressCard.addView(sectionTitle(getString(R.string.settings_key_presses_and_app_142)))
        val lpLabel = TextView(this).apply {
            text = getString(R.string.settings_long_press_duration_1_s_ms_143, (prefs.longPressDelayMs).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        pressCard.addView(lpLabel)
        pressCard.addView(SeekBar(this).apply {
            max = 550
            progress = (prefs.longPressDelayMs - 150).coerceIn(0, 550)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val ms = ((p + 150) / 10) * 10
                    prefs.longPressDelayMs = ms
                    lpLabel.text = getString(R.string.settings_long_press_duration_1_s_ms_143, (ms).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })
        pressCard.addView(makeDivider())
        pressCard.addView(
            makeSwitchRow(
                getString(R.string.settings_app_icon_144),
                getString(R.string.settings_show_claritykey_in_the_app_drawer_145),
                prefs.showLauncherIcon
            ) { visible ->
                prefs.showLauncherIcon = visible
                setLauncherIconVisible(visible)
                Toast.makeText(
                    this,
                    if (visible) getString(R.string.settings_app_icon_shown_in_the_app_drawer_146) else getString(R.string.settings_icon_hidden_settings_are_still_available_through_147),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        container.addView(pressCard)
    }

    // 2. Ввод и исправление текста (Adaptive Typing Engine)
    private fun renderTextInput(container: LinearLayout) {
        val touchCard = makeCard(padVertical = 6f)
        touchCard.addView(sectionTitle(getString(R.string.settings_adaptive_typing_and_gestures_148)))
        touchCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_touch_detection_149),
                getString(R.string.settings_use_touch_coordinates_and_word_context_near_key_150),
                prefs.adaptiveSmartTouch
            ) { prefs.adaptiveSmartTouch = it }
        )
        touchCard.addView(makeDivider())
        touchCard.addView(
            makeSwitchRow(
                getString(R.string.settings_automatic_calibration_151),
                getString(R.string.settings_gradually_adapt_to_finger_offsets_without_moving_152),
                prefs.adaptiveAutoCalibration
            ) { prefs.adaptiveAutoCalibration = it }
        )
        touchCard.addView(makeDivider())
        touchCard.addView(
            makeSwitchRow(
                getString(R.string.settings_swipe_typing_153),
                getString(R.string.settings_slide_your_finger_from_letter_to_letter_ru_en_154),
                prefs.swipeTypingEnabled
            ) { prefs.swipeTypingEnabled = it }
        )
        touchCard.addView(makeDivider())
        val orient = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "LAND" else "PORT"
        val curBias = prefs.getTouchBias("${prefs.activeLanguage}_$orient")
        val calSub = if (curBias.samples > 0) getString(R.string.settings_touches_learned_1_s_155, (curBias.samples).toString()) else getString(R.string.settings_a_short_exercise_to_fine_tune_touch_accuracy_156)
        touchCard.addView(
            makeSwitchRow(
                getString(R.string.settings_dynamic_key_touch_areas_157),
                getString(R.string.settings_slightly_expand_the_touch_areas_of_likely_next_l_158),
                prefs.dynamicHitboxEnabled
            ) { prefs.dynamicHitboxEnabled = it }
        )
        touchCard.addView(makeDivider())
        touchCard.addView(makeCategoryRow(R.drawable.ic_toolbar_settings, getString(R.string.settings_typing_calibration_159), calSub) {
            currentScreen = Screen.CALIBRATION
            renderScreen()
        })
        touchCard.addView(makeDivider())
        touchCard.addView(makeCategoryRow(R.drawable.ic_toolbar_edit, getString(R.string.settings_typing_statistics_and_touch_offset_28), getString(R.string.settings_words_1_s_finger_offset_160, (prefs.totalWordsTyped).toString())) {
            currentScreen = Screen.TYPING_STATS
            renderScreen()
        })
        container.addView(touchCard)

        val card = makeCard(padVertical = 6f)
        card.addView(sectionTitle(getString(R.string.settings_suggestions_and_autocorrection_161)))
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_word_suggestions_162),
                getString(R.string.settings_show_up_to_3_suggestions_while_typing_163),
                prefs.showSuggestions
            ) { prefs.showSuggestions = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_next_word_prediction_164),
                getString(R.string.settings_use_the_previous_word_and_familiar_phrases_165),
                prefs.nextWordPrediction
            ) { prefs.nextWordPrediction = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_mixed_ru_en_suggestions_166),
                getString(R.string.settings_suggest_common_english_words_ok_wi_fi_telegram_p_167),
                prefs.mixedRuEnSuggestions
            ) { prefs.mixedRuEnSuggestions = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_predictive_emoji_suggestions_168),
                getString(R.string.settings_suggest_emoji_based_on_the_word_fire_coffee_169),
                prefs.predictiveEmojiEnabled
            ) { prefs.predictiveEmojiEnabled = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_join_and_split_words_170),
                getString(R.string.settings_suggest_missing_or_extra_spaces_171),
                prefs.spaceSplitJoinEnabled
            ) { prefs.spaceSplitJoinEnabled = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_autocorrection_172),
                getString(R.string.settings_correct_typos_transposed_or_missing_letters_and_173),
                prefs.autoCorrect
            ) {
                prefs.autoCorrect = it
                renderScreen()
            }
        )
        if (prefs.autoCorrect) {
            card.addView(makeDivider())
            card.addView(
                makeSwitchRow(
                    getString(R.string.settings_correct_on_space_174),
                    getString(R.string.settings_apply_corrections_when_pressing_space_undo_with_175),
                    prefs.autoCorrectOnSpace
                ) { prefs.autoCorrectOnSpace = it }
            )
            card.addView(makeDivider())
            card.addView(
                makeSwitchRow(
                    getString(R.string.settings_undo_autocorrection_with_backspace_176),
                    getString(R.string.settings_the_first_immediately_after_a_correction_restore_177),
                    prefs.undoAutoCorrectOnBackspaceEnabled
                ) { prefs.undoAutoCorrectOnBackspaceEnabled = it }
            )
            card.addView(makeDivider())
            card.addView(makeCompactChipRow(
                getString(R.string.settings_autocorrection_strength_178),
                listOf(
                    AutoCorrectStrength.SOFT to getString(R.string.settings_gentle_179),
                    AutoCorrectStrength.STANDARD to getString(R.string.settings_standard_180),
                    AutoCorrectStrength.AGGRESSIVE to getString(R.string.settings_aggressive_181)
                ),
                prefs.autoCorrectStrength
            ) {
                prefs.autoCorrectStrength = it
                renderScreen()
            })
        }
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_auto_capitalization_182),
                getString(R.string.settings_start_each_sentence_with_a_capital_letter_183),
                prefs.autoCap
            ) { prefs.autoCap = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_automatic_punctuation_184),
                getString(R.string.settings_double_tap_the_spacebar_to_insert_a_period_185),
                prefs.doubleSpacePeriod
            ) { prefs.doubleSpacePeriod = it }
        )
        container.addView(card)

        val learnCard = makeCard(padVertical = 6f)
        learnCard.addView(sectionTitle(getString(R.string.settings_personalization_186)))
        learnCard.addView(
            makeSwitchRow(
                getString(R.string.settings_personal_learning_187),
                getString(R.string.settings_learn_frequent_words_names_and_phrases_locally_e_188),
                prefs.personalLearningEnabled
            ) { prefs.personalLearningEnabled = it }
        )
        learnCard.addView(makeDivider())
        learnCard.addView(
            makeSwitchRow(
                getString(R.string.settings_calculator_in_suggestions_189),
                getString(R.string.settings_evaluate_expressions_such_as_1450_12_in_the_sugg_190),
                prefs.inlineCalculatorEnabled
            ) { prefs.inlineCalculatorEnabled = it }
        )
        learnCard.addView(makeDivider())
        learnCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_calculator_and_time_191),
                getString(R.string.settings_calculate_percentages_and_time_offsets_1500_15_1_192),
                prefs.smartInlineMathEnabled
            ) { prefs.smartInlineMathEnabled = it }
        )
        learnCard.addView(makeDivider())
        learnCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_typography_quotes_193),
                getString(R.string.settings_replace_with_with_with_and_c_with_194),
                prefs.autoTypographyEnabled
            ) { prefs.autoTypographyEnabled = it }
        )
        learnCard.addView(makeDivider())
        learnCard.addView(
            makeSwitchRow(
                getString(R.string.settings_detect_the_intended_layout_ru_en_195),
                getString(R.string.settings_suggest_when_you_type_ghbdtn_on_the_english_layo_196),
                prefs.autoLayoutSwitchEnabled
            ) { prefs.autoLayoutSwitchEnabled = it }
        )
        learnCard.addView(makeDivider())
                learnCard.addView(makeCategoryRow(R.drawable.ic_toolbar_settings, getString(R.string.settings_autofill_and_personal_memory_197), getString(R.string.settings_phrases_autofill_profile_gestures_and_password_p_198)) {
            currentScreen = Screen.AUTOFILL_MEMORY
            renderScreen()
        })
        learnCard.addView(makeDivider())
                learnCard.addView(makeCategoryRow(R.drawable.ic_toolbar_edit, getString(R.string.settings_text_shortcuts_29), getString(R.string.settings_shortcuts_1_s_e_g_email_199, (prefs.getTextExpanders().size).toString())) {
            currentScreen = Screen.EXPANDERS
            renderScreen()
        })
        learnCard.addView(makeDivider())
        learnCard.addView(makeCategoryRow(R.drawable.ic_toolbar_edit, getString(R.string.settings_personal_dictionary_26), getString(R.string.settings_saved_words_1_s_200, (prefs.getUserWords().size).toString())) {
            currentScreen = Screen.DICTIONARY
            renderScreen()
        })
        learnCard.addView(makeDivider())
        val resetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(10f), dp(4f), dp(8f))
        }
        resetRow.addView(makePillButton(getString(R.string.settings_reset_personal_typing_data_201), primary = false) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_reset_learned_data_202))
                .setMessage(getString(R.string.settings_this_clears_personal_words_word_frequencies_and_203))
                .setNegativeButton(getString(R.string.settings_cancel_17), null)
                .setPositiveButton(getString(R.string.settings_reset_204)) { _, _ ->
                    prefs.clearPersonalTypingData()
                    Toast.makeText(this, getString(R.string.settings_personal_typing_data_reset_205), Toast.LENGTH_SHORT).show()
                    renderScreen()
                }
                .show()
        })
        learnCard.addView(resetRow)
        container.addView(learnCard)
    }


    // Sub-screen: Статистика набора и тепловая карта касаний
    private fun renderTypingStats(container: LinearLayout) {
        val toggleCard = makeCard(padVertical = 6f)
        toggleCard.addView(
            makeSwitchRow(
                getString(R.string.settings_local_typing_statistics_206),
                getString(R.string.settings_track_typing_speed_deletion_rate_and_touch_offse_207),
                prefs.typingStatsEnabled
            ) { prefs.typingStatsEnabled = it }
        )
        container.addView(toggleCard)

        val chars = prefs.totalCharsTyped
        val words = prefs.totalWordsTyped
        val backs = prefs.totalBackspaces
        val mins = (prefs.totalTypingMs / 60000.0).coerceAtLeast(0.01)
        val cpm = if (chars > 0) (chars / mins).roundToInt().coerceIn(0, 999) else 0
        val wpm = if (words > 0) (words / mins).roundToInt().coerceIn(0, 250) else 0
        val acc = if (chars > 0) (((chars - backs).coerceAtLeast(0).toDouble() / chars) * 100.0).roundToInt().coerceIn(0, 100) else 100

        val metricsCard = makeCard()
        metricsCard.addView(sectionTitle(getString(R.string.settings_speed_and_accuracy_208)))
        metricsCard.addView(makeInfoRow(getString(R.string.settings_typing_speed_209), getString(R.string.settings_1_s_chars_min_2_s_words_min_210, (cpm).toString(), (wpm).toString())))
        metricsCard.addView(makeDivider())
        metricsCard.addView(makeInfoRow(getString(R.string.settings_typing_without_backspace_211), getString(R.string.settings_1_s_characters_typed_2_s_words_3_s_212, (acc).toString(), (chars).toString(), (words).toString())))
        container.addView(metricsCard)

        val orient = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "LAND" else "PORT"
        val bias = prefs.getTouchBias("${prefs.activeLanguage}_$orient")
        val heatCard = makeCard()
        heatCard.addView(sectionTitle(getString(R.string.settings_average_touch_offset_1_s_2_s_213, (prefs.activeLanguage).toString(), (orient).toString())))
        heatCard.addView(TextView(this).apply {
            text = if (bias.samples > 0) {
                getString(R.string.settings_touches_learned_1_s_average_offset_x_2_s_y_3_s_214, (bias.samples).toString(), ("%.2f".format(bias.dxNorm)).toString(), ("%.2f".format(bias.dyNorm)).toString())
            } else {
                getString(R.string.settings_type_or_complete_calibration_to_see_your_average_215)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(10f))
        })
        val heatView = object : View(this) {
            private val pBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgSurface }
            private val pKey = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = cardSurface }
            private val pHeat = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val pDot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accentBlue }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                canvas.drawRoundRect(0f, 0f, w, h, dp(12f).toFloat(), dp(12f).toFloat(), pBg)
                val cols = 10; val rows = 3
                val kw = (w - dp(16f)) / cols; val kh = (h - dp(16f)) / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val kx = dp(8f) + c * kw + dp(2f)
                        val ky = dp(8f) + r * kh + dp(2f)
                        val rRect = android.graphics.RectF(kx, ky, kx + kw - dp(4f), ky + kh - dp(4f))
                        canvas.drawRoundRect(rRect, dp(6f).toFloat(), dp(6f).toFloat(), pKey)
                        val cx = rRect.centerX() + (bias.dxNorm * kw * 0.6f).coerceIn(-kw * 0.35f, kw * 0.35f)
                        val cy = rRect.centerY() + (bias.dyNorm * kh * 0.6f).coerceIn(-kh * 0.35f, kh * 0.35f)
                        val intensity = if (bias.samples > 0) 95 else 45
                        pHeat.color = Color.argb(intensity, 46, 134, 255)
                        canvas.drawCircle(cx, cy, minOf(kw, kh) * 0.28f, pHeat)
                        canvas.drawCircle(cx, cy, dp(2.2f).toFloat(), pDot)
                    }
                }
            }
        }
        heatCard.addView(heatView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126f)))
        heatCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10f)) })
        heatCard.addView(makePillButton(getString(R.string.settings_reset_statistics_216), primary = false) {
            prefs.resetTypingStats()
            Toast.makeText(this, getString(R.string.settings_typing_statistics_cleared_217), Toast.LENGTH_SHORT).show()
            renderScreen()
        })
        container.addView(heatCard)
    }

    // Sub-screen: Ручная калибровка ввода
    private fun renderCalibration(container: LinearLayout) {
        val orient = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "LAND" else "PORT"
        val layoutKey = "${prefs.activeLanguage}_$orient"
        val model = TouchCalibrationModel(prefs)
        val curBias = model.getBias(layoutKey)

        val infoCard = makeCard()
        infoCard.addView(sectionTitle(getString(R.string.settings_current_touch_model_1_s_218, (layoutKey).toString())))
        infoCard.addView(TextView(this).apply {
            val dxPct = (curBias.dxNorm * 100f).roundToInt()
            val dyPct = (curBias.dyNorm * 100f).roundToInt()
            text = getString(R.string.settings_touches_recorded_1_s_nx_offset_2_s_of_key_width_219, (curBias.samples).toString(), (dxPct).toString(), (dyPct).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(10f))
        })

        val targets = if (prefs.activeLanguage == "EN") {
            listOf("Q", "E", "T", "O", "A", "G", "L", "M")
        } else {
            listOf("Й", "У", "Е", "Щ", "Ф", "А", "О", "Ь")
        }
        var stepIdx = 0
        val promptTv = TextView(this).apply {
            text = getString(R.string.settings_tap_the_highlighted_key_naturally_1_s_1_2_s_228, (targets[0]).toString(), (targets.size).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(0, dp(4f), 0, dp(10f))
        }
        infoCard.addView(promptTv)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(10f))
        }
        val btnMap = HashMap<String, TextView>()

        fun refreshHighlights() {
            val curTarget = if (stepIdx < targets.size) targets[stepIdx] else null
            btnMap.forEach { (lbl, tv) ->
                val active = lbl == curTarget
                tv.setTextColor(if (active) Color.WHITE else textPrimary)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (active) accentBlue else bgSurface)
                }
            }
            if (curTarget != null) {
                promptTv.text = getString(R.string.settings_tap_the_key_naturally_1_s_2_s_3_s_229, (curTarget).toString(), (stepIdx + 1).toString(), (targets.size).toString())
            } else {
                val updated = model.getBias(layoutKey)
                val dxP = (updated.dxNorm * 100f).roundToInt()
                val dyP = (updated.dyNorm * 100f).roundToInt()
                promptTv.text = getString(R.string.settings_calibration_complete_offset_x_1_s_y_2_s_touches_230, (dxP).toString(), (dyP).toString(), (updated.samples).toString())
            }
        }

        targets.chunked(4).forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            rowKeys.forEach { ch ->
                val tv = TextView(this).apply {
                    text = ch
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
                        setMargins(dp(4f), 0, dp(4f), 0)
                    }
                    setOnTouchListener { v, ev ->
                        if (ev.action == android.view.MotionEvent.ACTION_DOWN && stepIdx < targets.size && targets[stepIdx] == ch) {
                            val rect = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
                            model.recordTapSample(ev.x, ev.y, rect, layoutKey, manualWeight = true)
                            stepIdx++
                            refreshHighlights()
                        }
                        true
                    }
                }
                btnMap[ch] = tv
                row.addView(tv)
            }
            grid.addView(row)
        }
        refreshHighlights()
        infoCard.addView(grid)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makePillButton(getString(R.string.settings_start_again_231), primary = true) {
            stepIdx = 0
            refreshHighlights()
        })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
        btnRow.addView(makePillButton(getString(R.string.settings_reset_calibration_232), primary = false) {
            model.resetAll()
            Toast.makeText(this, getString(R.string.settings_calibration_reset_233), Toast.LENGTH_SHORT).show()
            renderScreen()
        })
        infoCard.addView(btnRow)
        container.addView(infoCard)
    }

    // 3. Внешний вид — Хаб с живым визуальным превью и подразделами
    private fun currentAppearanceSummary(): String {
        val tone = when (prefs.themeMode) {
            DayNightMode.LIGHT, DayNightMode.GLASS_BG_LIGHT, DayNightMode.GLASS_LIGHT -> getString(R.string.settings_light_47)
            DayNightMode.DARK, DayNightMode.GLASS_BG_DARK, DayNightMode.GLASS_DARK -> getString(R.string.settings_dark_48)
            else -> if (isDarkUi) getString(R.string.settings_system_dark_234) else getString(R.string.settings_system_light_235)
        }
        val style = when (prefs.themeMode) {
            DayNightMode.GLASS_BG_SYSTEM, DayNightMode.GLASS_BG_LIGHT, DayNightMode.GLASS_BG_DARK -> getString(R.string.settings_background_236)
            DayNightMode.GLASS_SYSTEM, DayNightMode.GLASS_LIGHT, DayNightMode.GLASS_DARK -> getString(R.string.settings_glass_46)
            else -> getString(R.string.settings_classic_237)
        }
        val extra = when {
            prefs.floatingModeEnabled -> getString(R.string.settings_floating_238)
            prefs.oneHandedMode == OneHandedMode.LEFT -> getString(R.string.settings_one_handed_left_239)
            prefs.oneHandedMode == OneHandedMode.RIGHT -> getString(R.string.settings_one_handed_right_240)
            else -> getString(R.string.settings_height_1_s_241, (prefs.keyboardHeightPercent).toString())
        }
        return "$tone • $style • $extra"
    }

    private fun buildUniversalAppearancePreviewCard(
        transpOverride: Int = prefs.glassTransparency,
        blurOverride: Int = prefs.glassBlurRadius,
        dimOverride: Int = prefs.wallpaperDimPercent
    ): Pair<LinearLayout, (Int, Int, Int) -> Unit> {
        val card = makeCard()
        card.addView(sectionTitle(getString(R.string.settings_keyboard_preview_242)))

        val previewFrame = FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(bgSurface)
            }
        }
        val baseBackdropBmp = createPreviewBackdropBitmap()
        val wpFile = prefs.wallpaperPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        val customWpBmp = wpFile?.let {
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(it.absolutePath, o)
            } catch (_: Exception) { null }
        }

        val backdropIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(customWpBmp ?: baseBackdropBmp)
        }
        val wpScrimView = View(this)
        previewFrame.addView(backdropIv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewFrame.addView(wpScrimView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val kbWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
        }

        // Toolbar slots preview row (respects showSuggestionsBar!)
        val tbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (prefs.showSuggestionsBar) View.VISIBLE else View.GONE
            setPadding(dp(2f), 0, dp(2f), dp(4f))
        }
        val slotIcons = mapOf(
            "MENU" to R.drawable.ic_toolbar_edit,
            "EDIT" to R.drawable.ic_toolbar_edit,
            "CLIP" to R.drawable.ic_toolbar_clip,
            "EMOJI" to R.drawable.ic_toolbar_emoji,
            "SETTINGS" to R.drawable.ic_toolbar_settings,
            "VOICE" to R.drawable.ic_toolbar_voice,
            "TRANSLATE" to R.drawable.ic_toolbar_translate
        )
        val slotGlyphs = mapOf("INCOGNITO" to "🕶", "ONE_HAND" to "◧")
        val tbViews = prefs.getToolbarSlots().map { slot ->
            val iconRes = slotIcons[slot]
            if (iconRes != null) {
                ImageView(this).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
                    layoutParams = LinearLayout.LayoutParams(0, dp(22f), 1f)
                }
            } else {
                TextView(this).apply {
                    text = slotGlyphs[slot] ?: "•"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(22f), 1f)
                }
            }
        }
        tbViews.forEach { tbRow.addView(it) }
        kbWrap.addView(tbRow)

        val numRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = if (prefs.showNumberRow) View.VISIBLE else View.GONE
        }
        val numViews = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { ch ->
            TextView(this).apply {
                text = ch
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * (prefs.keyFontScalePercent / 100f))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(18f), 1f).apply { setMargins(dp(1.5f), 0, dp(1.5f), 0) }
            }
        }
        numViews.forEach { numRow.addView(it) }
        if (prefs.showNumberRow) {
            kbWrap.addView(numRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(3f)
            })
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previewKeySp = 12f * (prefs.keyFontScalePercent / 100f)
        val keyViews1 = listOf("Й", "Ц", "У", "К", "Е", "Н", "Г", "Ш").map { ch ->
            TextView(this).apply {
                text = ch
                setTextSize(TypedValue.COMPLEX_UNIT_SP, previewKeySp)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(26f), 1f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
            }
        }
        keyViews1.forEach { row1.addView(it) }

        val keyShift = TextView(this).apply {
            text = "⇧"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(26f), 1.0f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        }
        val keyLang = TextView(this).apply {
            text = "🌐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(26f), 1.0f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        }
        val keySpace = TextView(this).apply {
            text = getString(R.string.settings_russian_248)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(26f), 3.0f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        }
        val keyDel = TextView(this).apply {
            text = "⌫"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(26f), 1.0f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        }
        val keyEnter = TextView(this).apply {
            text = "↵"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(26f), 1.2f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        }
        row2.addView(keyShift)
        row2.addView(keyLang)
        row2.addView(keySpace)
        row2.addView(keyDel)
        row2.addView(keyEnter)

        kbWrap.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4f)
        })
        kbWrap.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val kbLp = FrameLayout.LayoutParams(
            if (prefs.floatingModeEnabled) dp(236f) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (prefs.floatingModeEnabled) Gravity.CENTER else Gravity.BOTTOM
        )
        previewFrame.addView(kbWrap, kbLp)

        val statusTv = TextView(this).apply {
            text = currentAppearanceSummary()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            setPadding(dp(2f), dp(8f), dp(2f), 0)
        }

        val updater: (Int, Int, Int) -> Unit = { tVal, bVal, dVal ->
            val basePrevSkin = BreenoSkinEngine.resolveSkin(
                this,
                prefs.themeMode,
                tVal,
                bVal,
                prefs.dynamicColorEnabled,
                prefs.nightScheduleEnabled
            )
            val previewSkin = BreenoSkinEngine.applyCustomKeyColors(
                skin = basePrevSkin,
                normalColor = prefs.customNormalKeyColor,
                shiftColor = prefs.customShiftKeyColor,
                deleteColor = prefs.customDeleteKeyColor,
                langColor = prefs.customLangKeyColor,
                enterColor = prefs.customEnterKeyColor
            )
            val hasWp = customWpBmp != null
            if (hasWp) {
                val dimAlpha = ((dVal / 100f) * 255f).roundToInt().coerceIn(40, 225)
                wpScrimView.setBackgroundColor(Color.argb(dimAlpha, 12, 14, 18))
                kbWrap.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.TRANSPARENT)
                }
            } else {
                wpScrimView.setBackgroundColor(Color.TRANSPARENT)
                kbWrap.background = GradientDrawable().apply {
                    val r = dp(12f).toFloat()
                    cornerRadii = if (prefs.floatingModeEnabled) floatArrayOf(r, r, r, r, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(previewSkin.common.keyboardBackgroundColor)
                }
            }

            tbViews.forEach { v ->
                when (v) {
                    is ImageView -> v.setColorFilter(previewSkin.keyTextColor)
                    is TextView -> v.setTextColor(previewSkin.keyTextColor)
                }
            }
            val keyRad = dp((prefs.keyCornerRadiusDp * 0.65f).coerceIn(2f, 14f)).toFloat()
            numViews.forEach { nv ->
                nv.setTextColor(previewSkin.keyTextColor)
                nv.background = GradientDrawable().apply {
                    cornerRadius = dp(5f).toFloat()
                    setColor(previewSkin.normalKeyBg.normalColor)
                }
            }
            keyViews1.forEachIndexed { idx, kv ->
                kv.setTextColor(previewSkin.keyTextColor)
                kv.background = GradientDrawable().apply {
                    cornerRadius = keyRad
                    setColor(previewSkin.normalKeyBg.normalColor)
                    if (idx == 4 && prefs.keyPressHighlightEnabled) {
                        val hlCol = if (prefs.keyPressHighlightColor != 0) prefs.keyPressHighlightColor else previewSkin.common.candidateTargetColor
                        setStroke(dp(1.8f).coerceAtLeast(2), hlCol)
                    } else if (previewSkin.isGlassKeys) {
                        setStroke(dp(0.75f).coerceAtLeast(1), previewSkin.glassBorderTopColor)
                    }
                }
            }
            val shiftBg = if (previewSkin.customShiftBgColor != 0) previewSkin.customShiftBgColor else previewSkin.funcKeyBg.normalColor
            keyShift.setTextColor(if (previewSkin.customShiftBgColor != 0) (if (BreenoSkinEngine.isColorLight(shiftBg)) Color.parseColor("#FF14161A") else Color.WHITE) else previewSkin.funcKeyTextColor)
            keyShift.background = GradientDrawable().apply { cornerRadius = keyRad; setColor(shiftBg) }

            val langBg = if (previewSkin.customLangBgColor != 0) previewSkin.customLangBgColor else previewSkin.normalKeyBg.normalColor
            keyLang.setTextColor(if (previewSkin.customLangBgColor != 0) (if (BreenoSkinEngine.isColorLight(langBg)) Color.parseColor("#FF14161A") else Color.WHITE) else previewSkin.keyTextColor)
            keyLang.background = GradientDrawable().apply { cornerRadius = keyRad; setColor(langBg) }

            val delBg = if (previewSkin.customDeleteBgColor != 0) previewSkin.customDeleteBgColor else previewSkin.funcKeyBg.normalColor
            keyDel.setTextColor(if (previewSkin.customDeleteBgColor != 0) (if (BreenoSkinEngine.isColorLight(delBg)) Color.parseColor("#FF14161A") else Color.WHITE) else previewSkin.funcKeyTextColor)
            keyDel.background = GradientDrawable().apply { cornerRadius = keyRad; setColor(delBg) }
            keySpace.setTextColor(previewSkin.keyMarkColor)
            keySpace.background = GradientDrawable().apply {
                cornerRadius = keyRad
                setColor(previewSkin.normalKeyBg.normalColor)
            }
            keyEnter.setTextColor(previewSkin.enterActionTextColor)
            keyEnter.background = GradientDrawable().apply {
                cornerRadius = keyRad
                setColor(previewSkin.enterActionBg.normalColor)
            }

            if (!hasWp && previewSkin.isGlassBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (bVal > 0) {
                        val r = (bVal * 0.65f).coerceAtLeast(1f)
                        backdropIv.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                    } else {
                        backdropIv.setRenderEffect(null)
                    }
                } else {
                    val radius = (bVal / 6).coerceIn(0, 14)
                    backdropIv.setImageBitmap(if (radius > 0) blurBitmapFast(baseBackdropBmp, radius) else baseBackdropBmp)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                backdropIv.setRenderEffect(null)
            }
            statusTv.text = currentAppearanceSummary()
        }

        updater(transpOverride, blurOverride, dimOverride)
        card.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(138f)))
        card.addView(statusTv)
        return card to updater
    }

    private fun renderAppearance(container: LinearLayout) {
        val (previewCard, _) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val navCard = makeCard(padVertical = 6f)
        val themeSub = if (prefs.themeMode in setOf(DayNightMode.SYSTEM, DayNightMode.GLASS_BG_SYSTEM, DayNightMode.GLASS_SYSTEM)) {
            getString(R.string.settings_follow_system_1_s_249, (if (isDarkUi) getString(R.string.settings_dark_48) else getString(R.string.settings_light_47)).toString())
        } else {
            currentAppearanceSummary().substringBefore(" • ")
        }
        val wpSub = if (prefs.wallpaperPath.isNotBlank()) getString(R.string.settings_selected_dimming_1_s_250, (prefs.wallpaperDimPercent).toString()) else getString(R.string.settings_not_selected_251)
        val layoutSub = when {
            prefs.floatingModeEnabled -> getString(R.string.settings_floating_keyboard_height_1_s_252, (prefs.keyboardHeightPercent).toString())
            prefs.oneHandedMode != OneHandedMode.OFF -> getString(R.string.settings_one_handed_height_1_s_253, (prefs.keyboardHeightPercent).toString())
            else -> getString(R.string.settings_height_1_s_bottom_padding_2_s_254, (prefs.keyboardHeightPercent).toString(), (prefs.bottomPaddingMode.name).toString())
        }
        val tbSub = prefs.getToolbarSlots().joinToString(" • ")

        navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_settings, getString(R.string.settings_theme_system_and_glass_32), themeSub) {
            currentScreen = Screen.APPEARANCE_THEME
            renderScreen()
        })
        navCard.addView(makeDivider())
        val colorsSub = if (prefs.hasAnyCustomKeyColor()) getString(R.string.settings_custom_key_colors_255) else getString(R.string.settings_follow_theme_presets_and_rgb_for_letters_shift_256)
        navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_emoji, getString(R.string.settings_key_colors_33), colorsSub) {
            currentScreen = Screen.APPEARANCE_KEY_COLORS
            renderScreen()
        })
        navCard.addView(makeDivider())
        navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_clip, getString(R.string.settings_keyboard_wallpaper_34), wpSub) {
            currentScreen = Screen.APPEARANCE_WALLPAPER
            renderScreen()
        })
        navCard.addView(makeDivider())
        val keysSub = getString(R.string.settings_font_1_s_2_s_emoji_per_row_257, (prefs.keyFontScalePercent).toString(), (10 - prefs.emojiSizeLevel).toString()) +
            (if (prefs.showNumberRow) getString(R.string.settings_numbers_on_258) else getString(R.string.settings_numbers_auto_259))
        navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_edit, getString(R.string.settings_keys_font_and_emoji_24), keysSub) {
            currentScreen = Screen.KEYBOARD_SETTINGS
            renderScreen()
        })
        navCard.addView(makeDivider())
                navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_edit, getString(R.string.settings_size_one_handed_and_floating_modes_260), layoutSub) {
            currentScreen = Screen.APPEARANCE_LAYOUT
            renderScreen()
        })
        navCard.addView(makeDivider())
        navCard.addView(makeCategoryRow(R.drawable.ic_toolbar_emoji, getString(R.string.settings_toolbar_5_slots_36), tbSub) {
            currentScreen = Screen.APPEARANCE_TOOLBAR
            renderScreen()
        })
        container.addView(navCard)
    }

    // Подраздел 3.1: Тема, система и стекло
    private fun renderAppearanceTheme(container: LinearLayout) {
        val (previewCard, updatePreview) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val cur = prefs.themeMode
        val isSystemTheme = cur in setOf(DayNightMode.SYSTEM, DayNightMode.GLASS_BG_SYSTEM, DayNightMode.GLASS_SYSTEM)
        val manualDark = cur in setOf(DayNightMode.DARK, DayNightMode.GLASS_BG_DARK, DayNightMode.GLASS_DARK)
        val styleIdx = when (cur) {
            DayNightMode.GLASS_BG_SYSTEM, DayNightMode.GLASS_BG_LIGHT, DayNightMode.GLASS_BG_DARK -> 1
            DayNightMode.GLASS_SYSTEM, DayNightMode.GLASS_LIGHT, DayNightMode.GLASS_DARK -> 2
            else -> 0
        }

        fun saveMode(useSystem: Boolean, dark: Boolean, style: Int) {
            prefs.themeMode = when (style) {
                1 -> when {
                    useSystem -> DayNightMode.GLASS_BG_SYSTEM
                    dark -> DayNightMode.GLASS_BG_DARK
                    else -> DayNightMode.GLASS_BG_LIGHT
                }
                2 -> when {
                    useSystem -> DayNightMode.GLASS_SYSTEM
                    dark -> DayNightMode.GLASS_DARK
                    else -> DayNightMode.GLASS_LIGHT
                }
                else -> when {
                    useSystem -> DayNightMode.SYSTEM
                    dark -> DayNightMode.DARK
                    else -> DayNightMode.LIGHT
                }
            }
            renderScreen()
        }

        val themeCard = makeCard()
        themeCard.addView(sectionTitle(getString(R.string.settings_light_and_dark_theme_261)))
        themeCard.addView(
            makeSwitchRow(
                getString(R.string.settings_follow_system_theme_auto_262),
                getString(R.string.settings_current_system_theme_1_s_263, (if (isDarkUi) getString(R.string.settings_dark_48) else getString(R.string.settings_light_47)).toString()),
                isSystemTheme
            ) { checked ->
                saveMode(useSystem = checked, dark = isDarkUi, style = styleIdx)
            }
        )
        if (!isSystemTheme) {
            themeCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })
            themeCard.addView(makeSegmentedControl(listOf(getString(R.string.settings_light_47), getString(R.string.settings_dark_48)), if (manualDark) 1 else 0) { idx ->
                saveMode(useSystem = false, dark = idx == 1, style = styleIdx)
            })
        }

        themeCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14f)) })
        themeCard.addView(sectionTitle(getString(R.string.settings_surface_style_264)))
        themeCard.addView(makeSegmentedControl(listOf(getString(R.string.settings_classic_237), getString(R.string.settings_background_236), getString(R.string.settings_glass_46)), styleIdx) { idx ->
            saveMode(useSystem = isSystemTheme, dark = manualDark, style = idx)
        })
        container.addView(themeCard)

        if (styleIdx > 0) {
            val glassCard = makeCard()
            glassCard.addView(sectionTitle(getString(R.string.settings_liquid_glass_settings_265)))
            val transpLabel = TextView(this).apply {
                text = getString(R.string.settings_opacity_1_s_266, (prefs.glassTransparency).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textPrimary)
            }
            glassCard.addView(transpLabel)
            glassCard.addView(SeekBar(this).apply {
                max = 70
                progress = (prefs.glassTransparency - 15).coerceIn(0, 70)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + 15
                        transpLabel.text = getString(R.string.settings_opacity_1_s_266, (v).toString())
                        if (fromUser) {
                            prefs.glassTransparency = v
                            updatePreview(v, prefs.glassBlurRadius, prefs.wallpaperDimPercent)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })

            val blurLabel = TextView(this).apply {
                text = getString(R.string.settings_blur_1_s_px_267, (prefs.glassBlurRadius).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textPrimary)
                setPadding(0, dp(8f), 0, 0)
            }
            glassCard.addView(blurLabel)
            glassCard.addView(SeekBar(this).apply {
                max = 80
                progress = prefs.glassBlurRadius.coerceIn(0, 80)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        blurLabel.text = getString(R.string.settings_blur_1_s_px_267, (p).toString())
                        if (fromUser) {
                            prefs.glassBlurRadius = p
                            updatePreview(prefs.glassTransparency, p, prefs.wallpaperDimPercent)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
            container.addView(glassCard)
        }

        val extrasCard = makeCard(padVertical = 6f)
        extrasCard.addView(
            makeSwitchRow(
                "Dynamic Color (Material You)",
                getString(R.string.settings_use_wallpaper_colors_for_enter_and_suggestions_o_268),
                prefs.dynamicColorEnabled
            ) {
                prefs.dynamicColorEnabled = it
                renderScreen()
            }
        )
        extrasCard.addView(makeDivider())
        extrasCard.addView(
            makeSwitchRow(
                getString(R.string.settings_scheduled_night_theme_22_00_07_00_269),
                getString(R.string.settings_automatically_switch_to_a_dark_theme_at_night_270),
                prefs.nightScheduleEnabled
            ) {
                prefs.nightScheduleEnabled = it
                renderScreen()
            }
        )
        container.addView(extrasCard)

        val codeCard = makeCard()
        codeCard.addView(sectionTitle(getString(R.string.settings_share_a_theme_with_a_code_271)))
        val codeInput = EditText(this).apply {
            hint = getString(R.string.settings_paste_a_ck1_code_to_apply_a_theme_272)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        codeCard.addView(codeInput)
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        cRow.addView(makePillButton(getString(R.string.settings_copy_code_273), primary = true) {
            val code = prefs.exportThemeCode()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ClarityKey Theme", code))
            codeInput.setText(code)
            Toast.makeText(this, getString(R.string.settings_theme_code_copied_to_clipboard_274), Toast.LENGTH_SHORT).show()
        })
        cRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
        cRow.addView(makePillButton(getString(R.string.settings_apply_275), primary = false) {
            val ok = prefs.importThemeCode(codeInput.text.toString())
            Toast.makeText(this, if (ok) getString(R.string.settings_theme_applied_276) else getString(R.string.settings_invalid_theme_code_277), Toast.LENGTH_SHORT).show()
            if (ok) renderScreen()
        })
        codeCard.addView(cRow)
        container.addView(codeCard)
    }

    // Подраздел 3.1.5: Цвета клавиш (Шаблонные спокойные цвета + RGB бегунок для Букв, Shift, ⌫, 🌐, Enter)
    private fun renderAppearanceKeyColors(container: LinearLayout) {
        val (previewCard, updatePreview) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val targets = listOf(getString(R.string.settings_letters_278), "Shift", "⌫", "🌐", "Enter", getString(R.string.settings_press_279))
        val targetIdx = selectedColorTargetIdx.coerceIn(0, targets.lastIndex)

        fun getTargetColor(idx: Int): Int = when (idx) {
            0 -> prefs.customNormalKeyColor
            1 -> prefs.customShiftKeyColor
            2 -> prefs.customDeleteKeyColor
            3 -> prefs.customLangKeyColor
            4 -> prefs.customEnterKeyColor
            else -> prefs.keyPressHighlightColor
        }

        fun setTargetColor(idx: Int, color: Int) {
            when (idx) {
                0 -> prefs.customNormalKeyColor = color
                1 -> prefs.customShiftKeyColor = color
                2 -> prefs.customDeleteKeyColor = color
                3 -> prefs.customLangKeyColor = color
                4 -> prefs.customEnterKeyColor = color
                else -> {
                    prefs.keyPressHighlightColor = color
                    if (color != 0) prefs.keyPressHighlightEnabled = true
                }
            }
        }

        val targetCard = makeCard()
        targetCard.addView(sectionTitle(getString(R.string.settings_choose_a_key_group_280)))
        targetCard.addView(makeSegmentedControl(targets, targetIdx) { idx ->
            selectedColorTargetIdx = idx
            renderScreen()
        })

        // Curated muted, tasteful swatch palette (zero harsh neon colors!)
        val swatches = listOf(
            getString(R.string.settings_follow_theme_281) to 0,
            getString(R.string.settings_milk_282) to Color.parseColor("#FFF4F2EE"),
            getString(R.string.settings_graphite_283) to Color.parseColor("#FF34373D"),
            getString(R.string.settings_slate_284) to Color.parseColor("#FF4A6583"),
            getString(R.string.settings_ocean_285) to Color.parseColor("#FF2B6CB0"),
            getString(R.string.settings_sage_286) to Color.parseColor("#FF4D7C6F"),
            getString(R.string.settings_olive_287) to Color.parseColor("#FF6B7752"),
            getString(R.string.settings_terracotta_288) to Color.parseColor("#FFA65E4E"),
            getString(R.string.settings_burgundy_289) to Color.parseColor("#FF8C4A58"),
            getString(R.string.settings_lavender_290) to Color.parseColor("#FF6E5D8C"),
            getString(R.string.settings_sand_291) to Color.parseColor("#FF9E825E")
        )

        val curCol = getTargetColor(targetIdx)
        targetCard.addView(TextView(this).apply {
            text = getString(R.string.settings_soft_color_presets_292)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), dp(14f), 0, dp(8f))
        })

        swatches.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3f), 0, dp(3f))
            }
            rowItems.forEach { (name, colVal) ->
                val isSel = curCol == colVal
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(if (isSel) iconBgTint else bgSurface)
                        if (isSel) setStroke(dp(1.5f), accentBlue)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3f), 0, dp(3f), 0)
                    }
                    setOnClickListener {
                        setTargetColor(targetIdx, colVal)
                        renderScreen()
                    }
                }
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (colVal == 0) cardSurface else colVal)
                        setStroke(dp(1f), if (isSel) accentBlue else dividerColor)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f))
                }
                val lbl = TextView(this).apply {
                    text = name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setTextColor(if (isSel) accentBlue else textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4f), 0, 0)
                    maxLines = 1
                }
                chip.addView(dot)
                chip.addView(lbl)
                row.addView(chip)
            }
            // Pad incomplete last row to keep equal widths
            repeat(4 - rowItems.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            targetCard.addView(row)
        }
        container.addView(targetCard)

        // Card 2: RGB Sliders for fine custom tuning
        val rgbCard = makeCard()
        val initColor = if (curCol != 0) curCol else Color.parseColor("#FF4A6583")
        var rVal = Color.red(initColor)
        var gVal = Color.green(initColor)
        var bVal = Color.blue(initColor)

        val headerRgb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        headerRgb.addView(TextView(this).apply {
            text = getString(R.string.settings_fine_tune_rgb_1_s_293, (targets[targetIdx]).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accentBlue)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val hexBadge = TextView(this).apply {
            text = String.format(Locale.US, "#%02X%02X%02X", rVal, gVal, bVal)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextColor(if (BreenoSkinEngine.isColorLight(initColor)) Color.parseColor("#FF14161A") else Color.WHITE)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(initColor)
            }
        }
        headerRgb.addView(hexBadge)
        rgbCard.addView(headerRgb)

        fun onRgbChanged() {
            val c = Color.rgb(rVal, gVal, bVal)
            setTargetColor(targetIdx, c)
            hexBadge.text = String.format(Locale.US, "#%02X%02X%02X", rVal, gVal, bVal)
            hexBadge.setTextColor(if (BreenoSkinEngine.isColorLight(c)) Color.parseColor("#FF14161A") else Color.WHITE)
            hexBadge.background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(c)
            }
            updatePreview(prefs.glassTransparency, prefs.glassBlurRadius, prefs.wallpaperDimPercent)
        }

        fun addChannelSlider(name: String, initial: Int, onVal: (Int) -> Unit) {
            val tv = TextView(this).apply {
                text = "$name: $initial"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
                setPadding(dp(2f), dp(6f), 0, dp(2f))
            }
            rgbCard.addView(tv)
            rgbCard.addView(SeekBar(this).apply {
                max = 255
                progress = initial.coerceIn(0, 255)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        tv.text = "$name: $p"
                        if (fromUser) {
                            onVal(p)
                            onRgbChanged()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
        }

        addChannelSlider(getString(R.string.settings_red_r_294), rVal) { rVal = it }
        addChannelSlider(getString(R.string.settings_green_g_295), gVal) { gVal = it }
        addChannelSlider(getString(R.string.settings_blue_b_296), bVal) { bVal = it }

        rgbCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10f)) })
        rgbCard.addView(makePillButton(getString(R.string.settings_reset_all_colors_to_theme_297), primary = false) {
            prefs.clearCustomKeyColors()
            Toast.makeText(this, getString(R.string.settings_key_colors_reset_to_theme_298), Toast.LENGTH_SHORT).show()
            renderScreen()
        })
        container.addView(rgbCard)
    }

    // Подраздел 3.2: Обои клавиатуры
    private fun renderAppearanceWallpaper(container: LinearLayout) {
        val (previewCard, updatePreview) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val wpCard = makeCard()
        wpCard.addView(sectionTitle(getString(R.string.settings_background_and_dimming_299)))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makePillButton(getString(R.string.settings_choose_photo_300), primary = true) {
            startActivity(Intent(this, ClipboardImagePickerActivity::class.java).apply {
                putExtra(ClipboardImagePickerActivity.EXTRA_PICK_WALLPAPER, true)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (prefs.wallpaperPath.isNotBlank()) {
            btnRow.addView(View(this), LinearLayout.LayoutParams(dp(8f), 1))
            btnRow.addView(makePillButton(getString(R.string.settings_reset_204), primary = false) {
                prefs.wallpaperPath = ""
                Toast.makeText(this, getString(R.string.settings_keyboard_wallpaper_reset_301), Toast.LENGTH_SHORT).show()
                renderScreen()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        }
        wpCard.addView(btnRow)

        if (prefs.wallpaperPath.isNotBlank()) {
            val dimLabel = TextView(this).apply {
                text = getString(R.string.settings_wallpaper_dimming_1_s_302, (prefs.wallpaperDimPercent).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textPrimary)
                setPadding(dp(2f), dp(14f), 0, dp(4f))
            }
            wpCard.addView(dimLabel)
            wpCard.addView(SeekBar(this).apply {
                max = 65
                progress = (prefs.wallpaperDimPercent - 20).coerceIn(0, 65)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + 20
                        dimLabel.text = getString(R.string.settings_wallpaper_dimming_1_s_302, (v).toString())
                        if (fromUser) {
                            prefs.wallpaperDimPercent = v
                            updatePreview(prefs.glassTransparency, prefs.glassBlurRadius, v)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
        }
        if (prefs.wallpaperPath.isNotBlank()) {
            wpCard.addView(makeDivider())
            wpCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_automatic_wallpaper_contrast_303),
                    getString(R.string.settings_adjust_text_contrast_for_light_and_dark_photos_304),
                    prefs.wallpaperAutoContrast
                ) { prefs.wallpaperAutoContrast = it }
            )
        }
        container.addView(wpCard)
    }

    // Подраздел 3.3: Размеры, 1 рука и плавающий режим
    private fun renderAppearanceLayout(container: LinearLayout) {
        val (previewCard, _) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val sizeCard = makeCard()
        sizeCard.addView(sectionTitle(getString(R.string.settings_size_and_typing_modes_305)))
        val heightLabel = TextView(this).apply {
            text = getString(R.string.settings_portrait_height_1_s_306, (prefs.keyboardHeightPercent).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), 0, 0, dp(4f))
        }
        sizeCard.addView(heightLabel)
        sizeCard.addView(SeekBar(this).apply {
            max = 35
            progress = (prefs.keyboardHeightPercent - 85).coerceIn(0, 35)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 85
                    prefs.keyboardHeightPercent = v
                    heightLabel.text = getString(R.string.settings_portrait_height_1_s_306, (v).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })

        val landHeightLabel = TextView(this).apply {
            text = getString(R.string.settings_landscape_height_1_s_307, (prefs.landscapeHeightPercent).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), dp(10f), 0, dp(4f))
        }
        sizeCard.addView(landHeightLabel)
        sizeCard.addView(SeekBar(this).apply {
            max = 40
            progress = (prefs.landscapeHeightPercent - 75).coerceIn(0, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 75
                    prefs.landscapeHeightPercent = v
                    landHeightLabel.text = getString(R.string.settings_landscape_height_1_s_307, (v).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })

        sizeCard.addView(makeDivider())
        sizeCard.addView(TextView(this).apply {
            text = getString(R.string.settings_one_handed_mode_308)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), dp(8f), 0, dp(6f))
        })
        val oneHandModes = listOf(OneHandedMode.OFF to getString(R.string.settings_off_309), OneHandedMode.LEFT to getString(R.string.settings_left_310), OneHandedMode.RIGHT to getString(R.string.settings_right_311))
        val curOh = oneHandModes.indexOfFirst { it.first == prefs.oneHandedMode }.coerceAtLeast(0)
        sizeCard.addView(makeSegmentedControl(oneHandModes.map { it.second }, curOh) { idx ->
            prefs.oneHandedMode = oneHandModes[idx].first
            if (prefs.oneHandedMode != OneHandedMode.OFF) prefs.floatingModeEnabled = false
            renderScreen()
        })

        sizeCard.addView(makeDivider())
        sizeCard.addView(
            makeSwitchRow(
                getString(R.string.settings_floating_keyboard_312),
                getString(R.string.settings_drag_the_bottom_handle_to_move_the_keyboard_over_313),
                prefs.floatingModeEnabled
            ) {
                prefs.floatingModeEnabled = it
                if (it) prefs.oneHandedMode = OneHandedMode.OFF
                renderScreen()
            }
        )
        if (prefs.floatingModeEnabled) {
            sizeCard.addView(makePillButton(getString(R.string.settings_reset_position_to_center_314), primary = false) {
                prefs.floatingOffsetX = 0
                prefs.floatingOffsetYDp = 72
                Toast.makeText(this, getString(R.string.settings_floating_keyboard_position_reset_315), Toast.LENGTH_SHORT).show()
            })
        }
        container.addView(sizeCard)

        val bottomCard = makeCard()
        bottomCard.addView(sectionTitle(getString(R.string.settings_bottom_padding_and_keys_316)))
        val bottomModes = listOf(
            BottomPaddingMode.AUTO to getString(R.string.settings_auto_317),
            BottomPaddingMode.COMPACT to getString(R.string.settings_compact_318),
            BottomPaddingMode.STANDARD to getString(R.string.settings_standard_319),
            BottomPaddingMode.LARGE to getString(R.string.settings_large_320)
        )
        val curBottomIdx = bottomModes.indexOfFirst { it.first == prefs.bottomPaddingMode }.coerceAtLeast(0)
        bottomCard.addView(makeSegmentedControl(bottomModes.map { it.second }, curBottomIdx) { idx ->
            prefs.bottomPaddingMode = bottomModes[idx].first
            renderScreen()
        })
        bottomCard.addView(makeDivider())
        bottomCard.addView(
            makeSwitchRow(
                getString(R.string.settings_key_popup_321),
                getString(R.string.settings_show_an_enlarged_character_preview_when_pressing_322),
                prefs.popupEnabled
            ) { prefs.popupEnabled = it }
        )
        bottomCard.addView(makeDivider())
        bottomCard.addView(
            makeSwitchRow(
                getString(R.string.settings_key_shadows_323),
                getString(R.string.settings_soft_depth_below_keys_in_classic_and_glass_theme_324),
                prefs.keyShadowEnabled
            ) { prefs.keyShadowEnabled = it }
        )
        bottomCard.addView(makeDivider())
        bottomCard.addView(
            makeSwitchRow(
                getString(R.string.settings_colored_key_press_highlight_325),
                getString(R.string.settings_highlight_the_pressed_key_outline_with_the_selec_326),
                prefs.keyPressHighlightEnabled
            ) {
                prefs.keyPressHighlightEnabled = it
                renderScreen()
            }
        )
        if (prefs.keyPressHighlightEnabled) {
            bottomCard.addView(makePillButton(getString(R.string.settings_choose_highlight_color_327), primary = false) {
                selectedColorTargetIdx = 5
                currentScreen = Screen.APPEARANCE_KEY_COLORS
                renderScreen()
            })
        }
        bottomCard.addView(makeDivider())
        val radiusLabel = TextView(this).apply {
            text = getString(R.string.settings_key_corner_radius_1_s_dp_328, (prefs.keyCornerRadiusDp).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), dp(10f), 0, dp(4f))
        }
        bottomCard.addView(radiusLabel)
        bottomCard.addView(SeekBar(this).apply {
            max = 20
            progress = prefs.keyCornerRadiusDp.coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.keyCornerRadiusDp = p
                    radiusLabel.text = getString(R.string.settings_key_corner_radius_1_s_dp_328, (p).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = renderScreen()
            })
        })
        bottomCard.addView(makeDivider())
        bottomCard.addView(TextView(this).apply {
            text = getString(R.string.settings_landscape_layout_329)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(2f), dp(10f), 0, dp(6f))
        })
        val landModes = listOf(0 to getString(R.string.settings_compact_330), 1 to getString(R.string.settings_split_331), 2 to getString(R.string.settings_full_332))
        val curLand = landModes.indexOfFirst { it.first == prefs.landscapeMode }.coerceAtLeast(0)
        bottomCard.addView(makeSegmentedControl(landModes.map { it.second }, curLand) { idx ->
            prefs.landscapeMode = landModes[idx].first
            renderScreen()
        })
        bottomCard.addView(makeDivider())
        bottomCard.addView(
            makeSwitchRow(
                getString(R.string.settings_arrow_row_333),
                getString(R.string.settings_a_slim_navigation_row_for_text_spreadsheets_and_334),
                prefs.arrowBarEnabled
            ) { prefs.arrowBarEnabled = it }
        )
        container.addView(bottomCard)
    }

    // Подраздел 3.4: Верхняя панель инструментов (5 слотов)
    private fun renderAppearanceToolbar(container: LinearLayout) {
        val (previewCard, _) = buildUniversalAppearancePreviewCard()
        container.addView(previewCard)

        val tbCard = makeCard()
        tbCard.addView(sectionTitle(getString(R.string.settings_toolbar_button_preset_335)))
        val presets = listOf(
            "MENU,CLIP,TRANSLATE,SETTINGS,VOICE" to getString(R.string.settings_menu_translate_like_gboard_336),
            "MENU,CLIP,EMOJI,SETTINGS,VOICE" to getString(R.string.settings_menu_emoji_337),
            "MENU,CLIP,ONE_HAND,INCOGNITO,VOICE" to getString(R.string.settings_menu_one_handed_incognito_338)
        )
        val curPresetIdx = presets.indexOfFirst { it.first == prefs.toolbarSlotsCsv }.coerceAtLeast(0)
        presets.forEachIndexed { idx, (csv, title) ->
            val sel = idx == curPresetIdx
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (sel) iconBgTint else bgSurface)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8f)
                }
                setOnClickListener {
                    prefs.toolbarSlotsCsv = csv
                    renderScreen()
                }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (sel) accentBlue else textPrimary)
            })
            col.addView(TextView(this).apply {
                text = csv.replace(",", " • ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(textSecondary)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (sel) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(accentBlue)
                })
            }
            tbCard.addView(row)
        }
        container.addView(tbCard)

        val customTbCard = makeCard()
        customTbCard.addView(sectionTitle(getString(R.string.settings_customize_slots_tap_a_slot_339)))
        val availableActions = listOf(
            "MENU" to getString(R.string.settings_menu_2_3_340),
            "CLIP" to getString(R.string.settings_clipboard_341),
            "TRANSLATE" to getString(R.string.settings_a_translate_342),
            "EMOJI" to getString(R.string.settings_emoji_and_stickers_343),
            "SETTINGS" to getString(R.string.settings_settings_344),
            "VOICE" to getString(R.string.settings_voice_typing_345),
            "INCOGNITO" to getString(R.string.settings_incognito_346),
            "ONE_HAND" to getString(R.string.settings_one_handed_347),
            "EDIT" to getString(R.string.settings_text_editor_348)
        )
        val curSlots = prefs.getToolbarSlots().toMutableList()
        while (curSlots.size < 5) curSlots.add("MENU")
        curSlots.take(5).forEachIndexed { slotIdx, code ->
            if (slotIdx > 0) customTbCard.addView(makeDivider())
            val actionLabel = availableActions.firstOrNull { it.first == code }?.second ?: code
            val slotRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
                setOnClickListener {
                    val curIdx = availableActions.indexOfFirst { it.first == code }
                    val nextCode = availableActions[(curIdx + 1) % availableActions.size].first
                    curSlots[slotIdx] = nextCode
                    prefs.toolbarSlotsCsv = curSlots.take(5).joinToString(",")
                    renderScreen()
                }
            }
            slotRow.addView(TextView(this).apply {
                text = getString(R.string.settings_slot_1_s_349, (slotIdx + 1).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(textSecondary)
            }, LinearLayout.LayoutParams(dp(74f), ViewGroup.LayoutParams.WRAP_CONTENT))
            slotRow.addView(TextView(this).apply {
                text = actionLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTextColor(textPrimary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            slotRow.addView(TextView(this).apply {
                text = getString(R.string.settings_change_350)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(accentBlue)
            })
            customTbCard.addView(slotRow)
        }
        container.addView(customTbCard)
    }

    private fun makeSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        labels.forEachIndexed { idx, label ->
            val active = idx == selectedIndex
            val btn = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
                setTextColor(if (active) Color.WHITE else textPrimary)
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(9f), dp(6f), dp(9f))
                if (active) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(9f).toFloat()
                        setColor(accentBlue)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onSelect(idx) }
            }
            wrap.addView(btn)
        }
        return wrap
    }

    // 4. Звук и вибрация
    private fun renderSoundHaptic(container: LinearLayout) {
        val card = makeCard(padVertical = 6f)
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_haptic_feedback_351),
                getString(R.string.settings_brief_vibration_when_pressing_keys_352),
                prefs.hapticEnabled
            ) {
                prefs.hapticEnabled = it
                renderScreen()
            }
        )
        if (prefs.hapticEnabled) {
            card.addView(makeDivider())
            card.addView(
                makeSwitchRow(
                    getString(R.string.settings_different_haptics_by_key_type_353),
                    getString(R.string.settings_a_soft_click_for_letters_firmer_for_shift_and_st_354),
                    prefs.differentialHapticsEnabled
                ) { prefs.differentialHapticsEnabled = it }
            )
            val durTv = TextView(this).apply {
                text = getString(R.string.settings_vibration_duration_1_s_ms_355, (prefs.hapticDurationMs).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
                setPadding(dp(4f), dp(6f), 0, dp(2f))
            }
            card.addView(durTv)
            card.addView(SeekBar(this).apply {
                max = 35
                progress = (prefs.hapticDurationMs - 5).coerceIn(0, 35)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val ms = p + 5
                        prefs.hapticDurationMs = ms
                        durTv.text = getString(R.string.settings_vibration_duration_1_s_ms_355, (ms).toString())
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
        }
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_keypress_sound_356),
                getString(R.string.settings_play_claritykey_key_sounds_ogg_while_typing_357),
                prefs.soundEnabled
            ) {
                prefs.soundEnabled = it
                renderScreen()
            }
        )
        if (prefs.soundEnabled) {
            card.addView(TextView(this).apply {
                text = getString(R.string.settings_sound_profile_358)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(textPrimary)
                setPadding(dp(4f), dp(8f), 0, dp(6f))
            })
            card.addView(makeSegmentedControl(listOf(getString(R.string.settings_classic_237), getString(R.string.settings_quiet_359), getString(R.string.settings_mechanical_360)), prefs.soundProfile) { idx ->
                prefs.soundProfile = idx
                renderScreen()
            })
            val volTv = TextView(this).apply {
                text = getString(R.string.settings_keypress_volume_1_s_361, (prefs.soundVolumePercent).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
                setPadding(dp(4f), dp(10f), 0, dp(2f))
            }
            card.addView(volTv)
            card.addView(SeekBar(this).apply {
                max = 80
                progress = (prefs.soundVolumePercent - 20).coerceIn(0, 80)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + 20
                        prefs.soundVolumePercent = v
                        volTv.text = getString(R.string.settings_keypress_volume_1_s_361, (v).toString())
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
        }
        container.addView(card)
    }

    // 5. Буфер обмена
    private fun retentionLabel(hours: Long): String = when (hours) {
        1L -> getString(R.string.settings_1_hour_362)
        24L -> getString(R.string.settings_24_hours_363)
        168L -> getString(R.string.settings_7_days_364)
        720L -> getString(R.string.settings_30_days_365)
        else -> getString(R.string.settings_forever_366)
    }

    private fun renderClipboard(container: LinearLayout) {
        val mainCard = makeCard(padVertical = 6f)
        mainCard.addView(
            makeSwitchRow(
                getString(R.string.settings_auto_save_367),
                getString(R.string.settings_save_copied_text_to_local_history_368),
                prefs.clipboardEnabled
            ) { prefs.clipboardEnabled = it }
        )
        mainCard.addView(makeDivider())
        mainCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_clipboard_replies_369),
                getString(R.string.settings_suggest_a_reply_or_your_saved_email_phone_number_370),
                prefs.smartClipboardReplyEnabled
            ) { prefs.smartClipboardReplyEnabled = it }
        )
        mainCard.addView(makeDivider())
        val nlGranted = hasNotificationListenerAccess()
        mainCard.addView(
            makeSwitchRow(
                getString(R.string.settings_sms_and_push_verification_codes_otp_371),
                if (nlGranted) getString(R.string.settings_active_codes_stay_in_ram_for_90_sec_and_are_remo_372)
                else getString(R.string.settings_requires_notification_access_no_read_sms_permiss_373),
                prefs.otpNotificationAutofillEnabled
            ) { checked ->
                prefs.otpNotificationAutofillEnabled = checked
                if (checked && !hasNotificationListenerAccess()) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, getString(R.string.settings_open_settings_notification_access_claritykey_374), Toast.LENGTH_LONG).show()
                    }
                }
                renderScreen()
            }
        )
        if (prefs.otpNotificationAutofillEnabled && !nlGranted) {
            mainCard.addView(makePillButton(getString(R.string.settings_open_notification_access_375), primary = true) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.settings_open_settings_notification_access_claritykey_374), Toast.LENGTH_LONG).show()
                }
            })
        }
        mainCard.addView(makeDivider())
        mainCard.addView(
            makeSwitchRow(
                getString(R.string.settings_quick_paste_on_the_toolbar_376),
                getString(R.string.settings_show_a_chip_for_the_most_recently_copied_text_377),
                prefs.clipboardQuickChipEnabled
            ) { prefs.clipboardQuickChipEnabled = it }
        )
        mainCard.addView(makeDivider())
        mainCard.addView(
            makeSwitchRow(
                getString(R.string.settings_confirm_bulk_deletion_378),
                getString(R.string.settings_ask_before_clearing_recent_entries_379),
                prefs.clipboardConfirmClear
            ) { prefs.clipboardConfirmClear = it }
        )
        container.addView(mainCard)

        val ttlCard = makeCard()
        ttlCard.addView(sectionTitle(getString(R.string.settings_keep_unpinned_entries_for_380)))
        listOf(1L to getString(R.string.settings_1_hour_362), 24L to getString(R.string.settings_24_hours_363), 168L to getString(R.string.settings_7_days_364), 720L to getString(R.string.settings_30_days_default_381), 0L to getString(R.string.settings_forever_366))
            .forEachIndexed { idx, (hrs, label) ->
                if (idx > 0) ttlCard.addView(makeDivider())
                ttlCard.addView(makeRadioRow(label, prefs.clipboardRetentionHours == hrs) {
                    prefs.clipboardRetentionHours = hrs
                    renderScreen()
                })
            }
        container.addView(ttlCard)

        val limitCard = makeCard()
        limitCard.addView(sectionTitle(getString(R.string.settings_unpinned_entry_limit_382)))
        listOf(50, 100, 200).forEachIndexed { idx, lim ->
            if (idx > 0) limitCard.addView(makeDivider())
            val lbl = if (lim == 100) getString(R.string.settings_1_s_entries_default_383, (lim).toString()) else getString(R.string.settings_1_s_entries_384, (lim).toString())
            limitCard.addView(makeRadioRow(lbl, prefs.clipboardMaxItems == lim) {
                prefs.clipboardMaxItems = lim
                renderScreen()
            })
        }
        container.addView(limitCard)

        val uiCard = makeCard(padVertical = 6f)
        uiCard.addView(sectionTitle(getString(R.string.settings_clipboard_cards_385)))
        uiCard.addView(
            makeSwitchRow(
                getString(R.string.settings_show_source_app_386),
                getString(R.string.settings_show_its_icon_and_name_when_source_information_i_387),
                prefs.clipboardShowSource
            ) { prefs.clipboardShowSource = it }
        )
        uiCard.addView(makeDivider())
        uiCard.addView(
            makeSwitchRow(
                getString(R.string.settings_show_copy_time_388),
                getString(R.string.settings_a_compact_timestamp_at_the_bottom_right_of_each_389),
                prefs.clipboardShowTime
            ) { prefs.clipboardShowTime = it }
        )
        uiCard.addView(makeDivider())
        uiCard.addView(
            makeSwitchRow(
                getString(R.string.settings_view_full_text_on_long_press_390),
                getString(R.string.settings_add_full_text_to_the_context_menu_for_long_entri_391),
                prefs.clipboardFullTextEnabled
            ) { prefs.clipboardFullTextEnabled = it }
        )
        uiCard.addView(makeDivider())
        val linesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        linesRow.addView(TextView(this).apply {
            text = getString(R.string.settings_preview_lines_392)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textPrimary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(1, 2, 3).forEach { n ->
            val sel = prefs.clipboardPreviewLines == n
            linesRow.addView(TextView(this).apply {
                text = "$n"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (sel) Color.WHITE else textPrimary)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (sel) accentBlue else bgSurface)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4f), 0, 0, 0)
                }
                setOnClickListener {
                    prefs.clipboardPreviewLines = n
                    renderScreen()
                }
            })
        }
        uiCard.addView(linesRow)
        container.addView(uiCard)

        // Подраздел: Изображения и скриншоты (Smart Clipboard 2.0)
        val mediaCard = makeCard(padVertical = 6f)
        mediaCard.addView(sectionTitle(getString(R.string.settings_images_and_screenshots_393)))
        mediaCard.addView(
            makeSwitchRow(
                getString(R.string.settings_save_copied_images_394),
                getString(R.string.settings_automatically_save_images_png_jpg_webp_gif_from_395),
                prefs.clipboardSaveImages
            ) { prefs.clipboardSaveImages = it }
        )
        mediaCard.addView(makeDivider())
        mediaCard.addView(
            makeSwitchRow(
                getString(R.string.settings_show_image_suggestions_396),
                getString(R.string.settings_show_a_small_thumbnail_for_copied_images_on_the_397),
                prefs.clipboardShowImageSuggestions
            ) { prefs.clipboardShowImageSuggestions = it }
        )
        mediaCard.addView(makeDivider())
        val hasMediaPerm = RecentScreenshotDetector.hasMediaPermission(this)
        mediaCard.addView(
            makeSwitchRow(
                getString(R.string.settings_suggest_recent_screenshots_398),
                if (hasMediaPerm) getString(R.string.settings_automatically_add_a_new_screenshot_to_the_clipbo_399)
                else getString(R.string.settings_requires_photo_access_or_you_can_use_photo_picke_400),
                prefs.clipboardSuggestScreenshots && hasMediaPerm
            ) { enabled ->
                if (enabled) {
                    if (RecentScreenshotDetector.hasMediaPermission(this)) {
                        prefs.clipboardSuggestScreenshots = true
                        renderScreen()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.settings_recent_screenshot_access_401))
                            .setMessage(getString(R.string.settings_the_keyboard_checks_only_the_latest_screenshot_f_402))
                            .setNegativeButton(getString(R.string.settings_cancel_17)) { _, _ ->
                                prefs.clipboardSuggestScreenshots = false
                                renderScreen()
                            }
                            .setPositiveButton(getString(R.string.settings_allow_403)) { _, _ ->
                                ActivityCompat.requestPermissions(
                                    this,
                                    arrayOf(RecentScreenshotDetector.requiredPermission()),
                                    REQ_MEDIA
                                )
                            }
                            .show()
                    }
                } else {
                    prefs.clipboardSuggestScreenshots = false
                    renderScreen()
                }
            }
        )
        mediaCard.addView(makeDivider())
        mediaCard.addView(
            makeSwitchRow(
                getString(R.string.settings_automatic_cleanup_404),
                getString(R.string.settings_delete_old_unpinned_images_when_limits_are_excee_405),
                prefs.clipboardImageAutoClean
            ) { prefs.clipboardImageAutoClean = it }
        )
        mediaCard.addView(makeDivider())

        // Хранить изображения (1д / 7д / 30д / ∞)
        mediaCard.addView(makeCompactChipRow(
            getString(R.string.settings_keep_images_for_406),
            listOf(24L to getString(R.string.settings_1_day_407), 168L to getString(R.string.settings_7_days_408), 720L to getString(R.string.settings_30_days_409), 0L to "∞"),
            prefs.clipboardImageRetentionHours
        ) {
            prefs.clipboardImageRetentionHours = it
            renderScreen()
        })
        mediaCard.addView(makeDivider())

        // Максимум изображений (10 / 20 / 50)
        mediaCard.addView(makeCompactChipRow(
            getString(R.string.settings_maximum_images_410),
            listOf(10 to "10", 20 to "20", 50 to "50"),
            prefs.clipboardMaxImages
        ) {
            prefs.clipboardMaxImages = it
            renderScreen()
        })
        mediaCard.addView(makeDivider())

        // Лимит памяти (25 / 50 / 100 МБ)
        mediaCard.addView(makeCompactChipRow(
            getString(R.string.settings_storage_limit_411),
            listOf(25 to getString(R.string.settings_25_mb_412), 50 to getString(R.string.settings_50_mb_413), 100 to getString(R.string.settings_100_mb_414)),
            prefs.clipboardMaxImageMb
        ) {
            prefs.clipboardMaxImageMb = it
            renderScreen()
        })
        mediaCard.addView(makeDivider())

        val usageTv = TextView(this).apply {
            text = getString(R.string.settings_calculating_image_storage_415)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        }
        mediaCard.addView(usageTv)
        val statsRepo = ClipboardRepository(this, prefs)
        statsRepo.getMediaStorageStatsAsync { st ->
            val unpinnedMb = st.unpinnedBytes / (1024.0 * 1024.0)
            val pinnedMb = st.pinnedBytes / (1024.0 * 1024.0)
            val warn = if (pinnedMb >= 50.0) getString(R.string.settings_nnote_pinned_images_use_substantial_storage_and_416) else ""
            usageTv.text = String.format(
                Locale.US,
                getString(R.string.settings_unpinned_d_1f_mb_pinned_d_1f_mb_s_417),
                st.unpinnedCount, unpinnedMb, st.pinnedCount, pinnedMb, warn
            )
            statsRepo.close()
        }

        val pickerBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        }
        pickerBtnRow.addView(makePillButton(getString(R.string.settings_choose_image_photo_picker_418), primary = false) {
            startActivity(Intent(this, ClipboardImagePickerActivity::class.java))
        })
        mediaCard.addView(pickerBtnRow)
        container.addView(mediaCard)

        // Подраздел: Умная вставка (Smart Paste 1.0)
        val smartCard = makeCard(padVertical = 6f)
        smartCard.addView(sectionTitle(getString(R.string.settings_smart_paste_419)))
        smartCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_detection_420),
                getString(R.string.settings_find_links_phone_numbers_emails_dates_and_orders_421),
                prefs.smartPasteEnabled
            ) {
                prefs.smartPasteEnabled = it
                renderScreen()
            }
        )
        if (prefs.smartPasteEnabled) {
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_suggest_individual_fragments_422),
                    getString(R.string.settings_show_a_compact_detected_content_indicator_on_cli_423),
                    prefs.smartPasteShowFragments
                ) { prefs.smartPasteShowFragments = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_quick_suggestions_424),
                    getString(R.string.settings_show_paste_link_phone_email_or_smart_paste_on_th_425),
                    prefs.smartPasteQuickSuggestions
                ) { prefs.smartPasteQuickSuggestions = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_detect_links_426),
                    getString(R.string.settings_http_https_and_www_addresses_keeping_their_param_427),
                    prefs.smartPasteDetectUrls
                ) { prefs.smartPasteDetectUrls = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_detect_phone_numbers_428),
                    getString(R.string.settings_international_and_russian_phone_numbers_filterin_429),
                    prefs.smartPasteDetectPhones
                ) { prefs.smartPasteDetectPhones = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_detect_email_addresses_430),
                    getString(R.string.settings_email_addresses_within_messages_431),
                    prefs.smartPasteDetectEmails
                ) { prefs.smartPasteDetectEmails = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_dates_and_times_432),
                    getString(R.string.settings_25_11_2026_25_2026_11_25_14_30_18_45_433),
                    prefs.smartPasteDetectDatesAndTime
                ) { prefs.smartPasteDetectDatesAndTime = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_addresses_and_coordinates_434),
                    getString(R.string.settings_city_street_building_apartment_and_gps_coordinat_435),
                    prefs.smartPasteDetectAddressesAndCoords
                ) { prefs.smartPasteDetectAddressesAndCoords = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_amounts_and_bank_details_436),
                    getString(R.string.settings_amounts_iban_mod_97_russian_tax_ids_checksum_ban_437),
                    prefs.smartPasteDetectFinance
                ) { prefs.smartPasteDetectFinance = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_orders_and_shipments_438),
                    getString(R.string.settings_order_references_parcel_tracking_numbers_and_fli_439),
                    prefs.smartPasteDetectOrdersAndTracking
                ) { prefs.smartPasteDetectOrdersAndTracking = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_promo_codes_and_product_ids_440),
                    getString(R.string.settings_promo_codes_coupons_product_ids_and_license_plat_441),
                    prefs.smartPasteDetectPromosAndSkus
                ) { prefs.smartPasteDetectPromosAndSkus = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_names_and_organizations_442),
                    getString(R.string.settings_russian_full_names_patronymic_label_checks_and_c_443),
                    prefs.smartPasteDetectPeopleAndOrgs
                ) { prefs.smartPasteDetectPeopleAndOrgs = it }
            )
            smartCard.addView(makeDivider())
            smartCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_hide_sensitive_data_444),
                    getString(R.string.settings_hide_verification_codes_and_card_numbers_from_qu_445),
                    prefs.smartPasteHideSensitive
                ) { prefs.smartPasteHideSensitive = it }
            )
        }
        container.addView(smartCard)

        // Подраздел: Умная вставка -> Распознавание текста на изображениях (Vision 3.0)
        val visionCard = makeCard(padVertical = 6f)
        visionCard.addView(sectionTitle(getString(R.string.settings_text_recognition_in_images_446)))
        visionCard.addView(
            makeSwitchRow(
                getString(R.string.settings_text_recognition_in_images_446),
                getString(R.string.settings_local_tesseract_ru_en_ocr_with_extract_text_with_447),
                prefs.visionOcrEnabled
            ) {
                prefs.visionOcrEnabled = it
                renderScreen()
            }
        )
        if (prefs.visionOcrEnabled) {
            visionCard.addView(makeDivider())
            visionCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_automatically_recognize_new_images_448),
                    getString(R.string.settings_run_ocr_in_the_background_after_copying_an_image_449),
                    prefs.visionAutoOcr
                ) { prefs.visionAutoOcr = it }
            )
            visionCard.addView(makeDivider())
            visionCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_recognize_russian_and_english_450),
                    getString(R.string.settings_use_mixed_ru_en_recognition_otherwise_russian_on_451),
                    prefs.visionOcrRusEng
                ) { prefs.visionOcrRusEng = it }
            )
            visionCard.addView(makeDivider())
            visionCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_suggest_individual_fragments_422),
                    getString(R.string.settings_extract_links_phone_numbers_addresses_amounts_an_452),
                    prefs.visionShowFragments
                ) { prefs.visionShowFragments = it }
            )
            visionCard.addView(makeDivider())
            visionCard.addView(
                makeSwitchRow(
                    getString(R.string.settings_save_ocr_results_453),
                    getString(R.string.settings_cache_recognized_text_locally_excluding_detected_454),
                    prefs.visionSaveResults
                ) { prefs.visionSaveResults = it }
            )
        }
        container.addView(visionCard)

        // Подразделы буфера обмена: Шаблоны и Защищённый сейф
        val subCard = makeCard(padVertical = 6f)
        subCard.addView(makeCategoryRow(
            R.drawable.ic_toolbar_edit,
            getString(R.string.settings_quick_reply_templates_39),
            getString(R.string.settings_saved_templates_1_s_work_delivery_personal_455, (prefs.getReplyTemplates().size).toString())
        ) {
            currentScreen = Screen.CLIPBOARD_TEMPLATES
            renderScreen()
        })
        subCard.addView(makeDivider())
        subCard.addView(makeCategoryRow(
            R.drawable.ic_toolbar_clip,
            getString(R.string.settings_secure_vault_biometrics_456),
            getString(R.string.settings_entries_1_s_aes_256_gcm_encryption_457, (prefs.getVaultItemCount()).toString())
        ) {
            currentScreen = Screen.CLIPBOARD_VAULT
            renderScreen()
        })
        container.addView(subCard)
    }

    // Подраздел 5.1: Шаблоны быстрых ответов (интерактивные вкладки + редактирование по тапу!)
    private fun templateFolderTitle(folder: String): String = when (folder) {
        "Работа" -> getString(R.string.settings_work_458)
        "Доставка" -> getString(R.string.settings_delivery_459)
        "Личное" -> getString(R.string.settings_personal_460)
        else -> folder
    }

    private fun renderClipboardTemplates(container: LinearLayout) {
        val folders = listOf("Работа", "Доставка", "Личное")
        val activeFolder = folders[selectedTemplateFolderIdx.coerceIn(0, folders.lastIndex)]

        val addCard = makeCard()
        addCard.addView(sectionTitle(getString(R.string.settings_new_template_461)))
        addCard.addView(TextView(this).apply {
            text = getString(R.string.settings_tip_marks_the_cursor_position_and_insert_the_dat_462)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(6f))
        })
        val tplInput = EditText(this).apply {
            hint = getString(R.string.settings_template_text_e_g_i_ll_be_there_in_15_minutes_463)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        addCard.addView(tplInput)
        val folderSeg = makeSegmentedControl(folders.map(::templateFolderTitle), selectedTemplateFolderIdx.coerceIn(0, folders.lastIndex)) { idx ->
            selectedTemplateFolderIdx = idx
            renderScreen()
        }
        addCard.addView(folderSeg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
            bottomMargin = dp(10f)
        })
        addCard.addView(makePillButton(getString(R.string.settings_add_to_1_s_464, (templateFolderTitle(activeFolder)).toString()), primary = true) {
            val txt = tplInput.text?.toString()?.trim() ?: ""
            if (txt.isNotEmpty()) {
                prefs.addReplyTemplate(activeFolder, txt)
                Toast.makeText(this, getString(R.string.settings_template_added_to_1_s_465, (templateFolderTitle(activeFolder)).toString()), Toast.LENGTH_SHORT).show()
                renderScreen()
            } else {
                Toast.makeText(this, getString(R.string.settings_enter_template_text_466), Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(addCard)

        val listCard = makeCard()
        listCard.addView(sectionTitle(getString(R.string.settings_saved_templates_tap_to_edit_467)))
        val allTemplates = prefs.getReplyTemplates()
        if (allTemplates.isEmpty()) {
            listCard.addView(TextView(this).apply {
                text = getString(R.string.settings_no_templates_yet_468)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(textSecondary)
            })
        } else {
            allTemplates.forEachIndexed { idx, (folder, body) ->
                if (idx > 0) listCard.addView(makeDivider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                    setOnClickListener {
                        val editBox = EditText(this@SettingsActivity).apply {
                            setText(body)
                            setSelection(body.length)
                        }
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(getString(R.string.settings_edit_template_1_s_469, (templateFolderTitle(folder)).toString()))
                            .setView(editBox)
                            .setNegativeButton(getString(R.string.settings_cancel_17), null)
                            .setPositiveButton(getString(R.string.settings_save_470)) { _, _ ->
                                val updated = editBox.text?.toString()?.trim() ?: ""
                                if (updated.isNotEmpty()) {
                                    prefs.updateReplyTemplate(idx, folder, updated)
                                    renderScreen()
                                }
                            }
                            .show()
                    }
                }
                row.addView(TextView(this).apply {
                    text = "[${templateFolderTitle(folder)}] $body"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setTextColor(textPrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = getString(R.string.settings_delete_471)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(Color.parseColor("#FFDB382C"))
                    setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
                    setOnClickListener {
                        prefs.removeReplyTemplate(idx)
                        renderScreen()
                    }
                })
                listCard.addView(row)
            }
        }
        container.addView(listCard)

        val blocked = prefs.getBlockedWords().sorted()
        val blockedCard = makeCard(padVertical = 6f)
        blockedCard.addView(sectionTitle(getString(R.string.settings_hidden_suggestion_words_1_s_472, (blocked.size).toString())))
        if (blocked.isEmpty()) {
            blockedCard.addView(makeInfoRow(getString(R.string.settings_nothing_here_yet_473), getString(R.string.settings_long_press_a_suggested_word_to_hide_it_permanent_474)))
        } else {
            blocked.forEachIndexed { idx, word ->
                if (idx > 0) blockedCard.addView(makeDivider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                }
                row.addView(TextView(this).apply {
                    text = word
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(textPrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = getString(R.string.settings_restore_475)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(accentBlue)
                    setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener {
                        prefs.unblockWord(word)
                        renderScreen()
                    }
                })
                blockedCard.addView(row)
            }
        }
        container.addView(blockedCard)
    }

    // Подраздел 5.2: Защищённый сейф (Биометрия + AES-256-GCM)
    private fun renderClipboardVault(container: LinearLayout) {
        val vaultCard = makeCard()
        vaultCard.addView(sectionTitle(getString(R.string.settings_secure_vault_aes_256_gcm_476)))
        val vaultItems = prefs.getVaultItems()
        val isUnlocked = prefs.isVaultUnlocked()
        vaultUnlockedInSettings = isUnlocked
        if (!isUnlocked) {
            vaultCard.addView(TextView(this).apply {
                text = getString(R.string.settings_vault_entries_1_s_biometric_verification_is_requ_477, (prefs.getVaultItemCount()).toString())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(textSecondary)
                setPadding(0, 0, 0, dp(12f))
            })
            vaultCard.addView(makePillButton(getString(R.string.settings_unlock_with_biometrics_478), primary = true) {
                authenticateVaultBiometric()
            })
        } else {
            val vInput = EditText(this).apply {
                hint = getString(R.string.settings_add_a_vault_entry_tax_id_bank_details_code_479)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setTextColor(textPrimary)
                setHintTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            vaultCard.addView(vInput)
            vaultCard.addView(makePillButton(getString(R.string.settings_save_to_vault_480), primary = true) {
                val txt = vInput.text?.toString()?.trim() ?: ""
                if (txt.isNotEmpty()) {
                    if (!prefs.isVaultUnlocked()) { renderScreen(); return@makePillButton }
                    val saved = prefs.addVaultItem(txt)
                    Toast.makeText(this, if (saved) getString(R.string.settings_entry_encrypted_and_saved_to_the_vault_481) else getString(R.string.settings_could_not_save_the_entry_check_the_device_keysto_482), Toast.LENGTH_SHORT).show()
                    renderScreen()
                }
            })
            vaultItems.forEachIndexed { idx, secret ->
                vaultCard.addView(makeDivider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                }
                row.addView(TextView(this).apply {
                    text = "🔑 $secret"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setTextColor(textPrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = getString(R.string.settings_delete_471)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(Color.parseColor("#FFDB382C"))
                    setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
                    setOnClickListener {
                        prefs.removeVaultItem(idx)
                        renderScreen()
                    }
                })
                vaultCard.addView(row)
            }
            vaultCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10f)) })
            vaultCard.addView(makePillButton(getString(R.string.settings_lock_vault_483), primary = false) {
                vaultUnlockedInSettings = false
                prefs.vaultUnlockedUntilMs = 0L
                renderScreen()
            })
        }
        container.addView(vaultCard)
    }

    private fun <T> makeCompactChipRow(
        title: String,
        options: List<Pair<T, String>>,
        selectedValue: T,
        onSelect: (T) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(6f))
        })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { idx, (value, label) ->
            val sel = value == selectedValue
            chips.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (sel) Color.WHITE else textPrimary)
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (sel) accentBlue else bgSurface)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) setMargins(dp(6f), 0, 0, 0)
                }
                setOnClickListener { onSelect(value) }
            })
        }
        row.addView(chips)
        return row
    }

    // 6. Дополнительные функции
    private fun renderExtraFeatures(container: LinearLayout) {
        val card = makeCard(padVertical = 8f)
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_voice_typing_484),
                getString(R.string.settings_show_the_microphone_button_on_the_keyboard_toolb_485),
                prefs.voiceInputEnabled
            ) { prefs.voiceInputEnabled = it }
        )
        card.addView(makeDivider())
        card.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_dictation_with_local_text_processing_486),
                getString(R.string.settings_remove_russian_filler_words_repetitions_and_self_487),
                prefs.ramblerVoiceModeEnabled
            ) {
                prefs.ramblerVoiceModeEnabled = it
                renderScreen()
            }
        )
        if (prefs.ramblerVoiceModeEnabled) {
            card.addView(TextView(this).apply {
                text = getString(R.string.settings_default_processing_style_488)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(textPrimary)
                setPadding(dp(4f), dp(6f), 0, dp(6f))
            })
            card.addView(makeSegmentedControl(listOf(getString(R.string.settings_full_332), getString(R.string.settings_concise_489), getString(R.string.settings_list_490)), prefs.ramblerStyleIndex) { idx ->
                prefs.ramblerStyleIndex = idx
                renderScreen()
            })
        }
        card.addView(makeDivider())

        val micOk = hasMicPermission()
        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(12f), dp(4f), dp(8f))
        }
        permRow.addView(TextView(this).apply {
            text = if (micOk) getString(R.string.settings_microphone_access_granted_491) else getString(R.string.settings_microphone_permission_required_492)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        permRow.addView(TextView(this).apply {
            text = getString(R.string.settings_uses_the_android_speech_recognition_service_ru_r_493)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(0, dp(4f), 0, dp(10f))
        })
        if (!micOk) {
            permRow.addView(makePillButton(getString(R.string.settings_allow_microphone_494), primary = true) { requestMicPermission() })
        }
        card.addView(permRow)
        container.addView(card)
        val pinSnipCard = makeCard()
        pinSnipCard.addView(sectionTitle(getString(R.string.settings_pinned_toolbar_chips_separated_by_495)))
        val pinInput = EditText(this).apply {
            setText(prefs.pinnedTopSnippetsCsv)
            hint = getString(R.string.settings_e_g_7_999_000_00_00_https_t_me_myname_496)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        pinSnipCard.addView(pinInput)
        pinSnipCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })
        pinSnipCard.addView(makePillButton(getString(R.string.settings_save_chips_497), primary = true) {
            prefs.pinnedTopSnippetsCsv = pinInput.text.toString()
            Toast.makeText(this, getString(R.string.settings_pinned_chips_saved_498), Toast.LENGTH_SHORT).show()
        })
        container.addView(pinSnipCard)

        val appCard = makeCard(padVertical = 6f)
        appCard.addView(sectionTitle(getString(R.string.settings_app_499)))
        appCard.addView(
            makeSwitchRow(
                getString(R.string.settings_automatic_app_profiles_500),
                getString(R.string.settings_enable_the_number_row_and_disable_autocorrection_501),
                prefs.perAppProfilesEnabled
            ) { prefs.perAppProfilesEnabled = it }
        )
        appCard.addView(makeDivider())
        appCard.addView(
            makeSwitchRow(
                getString(R.string.settings_show_app_icon_502),
                getString(R.string.settings_show_or_hide_claritykey_in_the_launcher_settings_503),
                prefs.showLauncherIcon
            ) { visible ->
                prefs.showLauncherIcon = visible
                setLauncherIconVisible(visible)
                Toast.makeText(
                    this,
                    if (visible) getString(R.string.settings_app_icon_shown_in_the_app_drawer_146) else getString(R.string.settings_icon_hidden_open_settings_through_on_the_keyboar_504),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        container.addView(appCard)
    }

    // 7. Безопасность и приватность (OWASP MASVS & Android 14–15 IME Security Center)
    private fun renderPrivacy(container: LinearLayout) {
        val keystoreOk = ClipboardCrypto.isKeystoreReady()
        val hasInternetPerm = try {
            val pi = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            pi.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        } catch (_: Exception) {
            false
        }

        // Card 1: Live Security Audit Dashboard
        val auditCard = makeCard()
        auditCard.addView(sectionTitle(getString(R.string.settings_security_status_live_checks_505)))
        val checks = listOf(
            (if (!hasInternetPerm) "✓" else "✕") to getString(R.string.settings_internet_permission_not_declared_speech_recognit_506),
            (if (keystoreOk) "✓" else "!") to getString(R.string.settings_encryption_android_keystore_aes_256_gcm_1_s_507, (if (keystoreOk) getString(R.string.settings_active_508) else getString(R.string.settings_unavailable_new_encrypted_saves_are_blocked_509)).toString()),
            "✓" to getString(R.string.settings_cloud_backup_disabled_allowbackup_false_app_data_510),
            "✓" to getString(R.string.settings_logging_logcat_no_typed_text_logged_511),
            (if (prefs.tapjackingProtectionEnabled && prefs.flagSecureInSecretsEnabled) "✓" else "!") to getString(R.string.settings_overlay_tapjacking_and_screenshot_protection_fla_512)
        )
        checks.forEachIndexed { idx, (mark, desc) ->
            if (idx > 0) auditCard.addView(makeDivider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), dp(7f), dp(2f), dp(7f))
            }
            row.addView(TextView(this).apply {
                text = mark
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (mark == "✓") Color.parseColor("#FF2EC4B6") else Color.parseColor("#FFFFA726"))
                layoutParams = LinearLayout.LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(textPrimary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            auditCard.addView(row)
        }
        container.addView(auditCard)

        // Card 2: Active Protection Controls
        val shieldCard = makeCard(padVertical = 6f)
        shieldCard.addView(sectionTitle(getString(R.string.settings_input_protection_513)))
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_manual_incognito_mode_514),
                getString(R.string.settings_temporarily_pause_clipboard_capture_dictionary_l_515),
                prefs.manualIncognito
            ) { prefs.manualIncognito = it }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_auto_incognito_in_banking_and_password_apps_516),
                getString(R.string.settings_disable_learning_and_phrase_collection_in_suppor_517),
                prefs.autoIncognitoSensitiveApps
            ) { prefs.autoIncognitoSensitiveApps = it }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_protect_against_tapjacking_518),
                getString(R.string.settings_block_keyboard_touches_obscured_by_another_app_s_519),
                prefs.tapjackingProtectionEnabled
            ) {
                prefs.tapjackingProtectionEnabled = it
                renderScreen()
            }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_block_screenshots_of_secrets_flag_secure_520),
                getString(R.string.settings_prevent_screenshots_and_screen_recording_of_the_521),
                prefs.flagSecureInSecretsEnabled
            ) {
                prefs.flagSecureInSecretsEnabled = it
                renderScreen()
            }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_ignore_sensitive_clipboard_items_522),
                getString(R.string.settings_do_not_save_passwords_or_items_marked_extra_is_s_523),
                prefs.clipboardIgnoreSensitive
            ) { prefs.clipboardIgnoreSensitive = it }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(
            makeSwitchRow(
                getString(R.string.settings_hide_clipboard_previews_524),
                getString(R.string.settings_mask_text_and_thumbnails_until_insertion_525),
                prefs.clipboardHidePreview
            ) { prefs.clipboardHidePreview = it }
        )
        shieldCard.addView(makeDivider())
        shieldCard.addView(TextView(this).apply {
            text = getString(R.string.settings_lock_vault_after_unlocking_526)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            setPadding(dp(4f), dp(10f), 0, dp(6f))
        })
        val lockOpts = listOf(30 to getString(R.string.settings_30_sec_527), 60 to getString(R.string.settings_1_min_528), 120 to getString(R.string.settings_2_min_529))
        val curLockIdx = lockOpts.indexOfFirst { it.first == prefs.vaultAutoLockSeconds }.coerceAtLeast(1)
        shieldCard.addView(makeSegmentedControl(lockOpts.map { it.second }, curLockIdx) { idx ->
            prefs.vaultAutoLockSeconds = lockOpts[idx].first
            renderScreen()
        })
        container.addView(shieldCard)

        // Card 3: Local SAF Backup (.claritykey)
        val backupCard = makeCard()
        backupCard.addView(sectionTitle(getString(R.string.settings_local_backup_claritykey_530)))
        backupCard.addView(TextView(this).apply {
            text = getString(R.string.settings_export_settings_dictionary_shortcuts_and_templat_531)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(10f))
        })
        val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bRow.addView(makePillButton(getString(R.string.settings_export_claritykey_532), primary = true) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "claritykey_backup.claritykey")
            }
            startActivityForResult(intent, REQ_EXPORT_BACKUP)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bRow.addView(View(this), LinearLayout.LayoutParams(dp(8f), 1))
        bRow.addView(makePillButton(getString(R.string.settings_import_claritykey_533), primary = false) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_BACKUP)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backupCard.addView(bRow)
        container.addView(backupCard)

        // Card 4: Data Wipe & Emergency Panic Wipe
        val clearCard = makeCard()
        clearCard.addView(sectionTitle(getString(R.string.settings_clear_data_534)))
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }
        btnRow.addView(makePillButton(getString(R.string.settings_clear_all_clipboard_history_including_pinned_ent_535), primary = false) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_clear_all_clipboard_history_536))
                .setMessage(getString(R.string.settings_all_text_and_image_entries_will_be_deleted_inclu_537))
                .setNegativeButton(getString(R.string.settings_cancel_17), null)
                .setPositiveButton(getString(R.string.settings_clear_82)) { _, _ ->
                    val repo = ClipboardRepository(this, prefs)
                    repo.clearAllAsync { ok ->
                        repo.close()
                        Toast.makeText(this, if (ok) getString(R.string.settings_all_clipboard_history_cleared_538) else getString(R.string.settings_could_not_fully_clear_clipboard_history_539), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10f)) })
        btnRow.addView(makePillButton(getString(R.string.settings_emergency_deletion_of_all_personal_data_panic_wi_540), primary = false) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_delete_all_personal_data_541))
                .setMessage(getString(R.string.settings_this_permanently_deletes_clipboard_history_saved_542))
                .setNegativeButton(getString(R.string.settings_cancel_17), null)
                .setPositiveButton(getString(R.string.settings_delete_all_543)) { _, _ ->
                    val repo = ClipboardRepository(this, prefs)
                    repo.clearAllAsync { ok ->
                        repo.close()
                        val stickersDeleted = java.io.File(filesDir, "breeno_stickers").let { !it.exists() || it.deleteRecursively() }
                        Toast.makeText(this, if (ok && stickersDeleted) getString(R.string.settings_personal_data_cleared_544) else getString(R.string.settings_some_files_could_not_be_deleted_545), Toast.LENGTH_LONG).show()
                    }
                    PersonalMemoryEngine(this, prefs).clearAll()
                    prefs.wipeAllSensitiveData()
                    vaultUnlockedInSettings = false
                    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.clearPrimaryClip()
                    renderScreen()
                }
                .show()
        })
        clearCard.addView(btnRow)
        container.addView(clearCard)
    }

    private fun createPreviewBackdropBitmap(): Bitmap {
        val w = 240
        val h = 100
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(if (isDarkUi) Color.parseColor("#FF141824") else Color.parseColor("#FFE8EEF8"))
        p.color = Color.parseColor("#FF0066FF")
        c.drawCircle(48f, 36f, 28f, p)
        p.color = Color.parseColor("#FFFF6B6B")
        c.drawCircle(128f, 52f, 24f, p)
        p.color = Color.parseColor("#FF2EC4B6")
        c.drawCircle(202f, 34f, 26f, p)
        p.color = if (isDarkUi) Color.parseColor("#FF2A324B") else Color.WHITE
        c.drawRoundRect(RectF(18f, 14f, 118f, 36f), 10f, 10f, p)
        c.drawRoundRect(RectF(96f, 44f, 222f, 68f), 10f, 10f, p)
        p.color = Color.parseColor("#FFFFBE0B")
        c.drawRoundRect(RectF(26f, 62f, 94f, 84f), 10f, 10f, p)
        return bmp
    }

    private fun blurBitmapFast(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dx in -radius..radius) {
                    val c = pix[row + (x + dx).coerceIn(0, w - 1)]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                tmp[row + x] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0
                for (dy in -radius..radius) {
                    val c = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                out[y * w + x] = (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun getAppVersionPair(): Pair<String, Long> {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            (pi.versionName ?: "2.1.0-beta.1") to pi.longVersionCode
        } catch (_: Exception) {
            "2.1.0-beta.1" to 21001L
        }
    }

    // 8. О клавиатуре
    private fun renderAbout(container: LinearLayout) {
        val card = makeCard(padVertical = 6f)
        val (vName, vCode) = getAppVersionPair()
        card.addView(makeInfoRow("ClarityKey", getString(R.string.settings_version_1_s_build_2_s_546, (vName).toString(), (vCode).toString())))
        card.addView(makeDivider())
        card.addView(makeInfoRow(getString(R.string.settings_package_547), packageName))
        card.addView(makeDivider())
        card.addView(makeInfoRow(getString(R.string.settings_compatibility_548), getString(R.string.settings_universal_android_keyboard_android_9_official_an_549)))
        card.addView(makeDivider())
        card.addView(makeInfoRow(getString(R.string.settings_dictionaries_and_open_source_components_550), getString(R.string.settings_ru_en_frequency_dictionaries_frequencywords_mit_551)))
        container.addView(card)
    }

    // Sub-screen: Автозаполнение, эффект памяти и умные жесты (v3.6)
    private fun renderAutofillMemory(container: LinearLayout) {
        val memEngine = PersonalMemoryEngine(this, prefs)
        val stats = memEngine.getStats()

        // Card 1: Memory Effect Stats & Toggles
        val memCard = makeCard()
        memCard.addView(sectionTitle(getString(R.string.settings_personal_typing_memory_552)))
        memCard.addView(TextView(this).apply {
            text = getString(R.string.settings_words_1_s_pairs_2_s_three_word_phrases_3_s_prote_553, (stats.words).toString(), (stats.bigrams).toString(), (stats.trigrams).toString(), (stats.protectedWords).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(8f))
        })
        memCard.addView(
            makeSwitchRow(
                getString(R.string.settings_phrase_memory_bigrams_and_trigrams_554),
                getString(R.string.settings_learn_common_phrases_and_word_order_and_protect_555),
                prefs.memoryEffectEnabled
            ) { prefs.memoryEffectEnabled = it }
        )
        memCard.addView(makeDivider())
        memCard.addView(makePillButton(getString(R.string.settings_clear_phrase_and_word_pair_memory_556), primary = false) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_clear_personal_memory_557))
                .setMessage(getString(R.string.settings_learned_word_pairs_phrases_and_the_protected_wor_558))
                .setNegativeButton(getString(R.string.settings_cancel_17), null)
                .setPositiveButton(getString(R.string.settings_clear_82)) { _, _ ->
                    memEngine.clearAll()
                    Toast.makeText(this, getString(R.string.settings_phrase_memory_cleared_559), Toast.LENGTH_SHORT).show()
                    renderScreen()
                }
                .show()
        })
        container.addView(memCard)

        // Card 2: Local Smart Autofill Profile (AES-256-GCM encrypted)
        val afCard = makeCard()
        afCard.addView(sectionTitle(getString(R.string.settings_local_autofill_aes_256_gcm_560)))
        afCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_autofill_by_field_type_561),
                getString(R.string.settings_offer_one_tap_insertion_of_your_email_phone_numb_562),
                prefs.localAutofillEnabled
            ) {
                prefs.localAutofillEnabled = it
                renderScreen()
            }
        )
        if (prefs.localAutofillEnabled) {
            fun makeField(hintStr: String, initial: String): EditText = EditText(this).apply {
                hint = hintStr
                setText(initial)
                setSingleLine(true)
                setTextColor(textPrimary)
                setHintTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            val fName = makeField(getString(R.string.settings_name_full_name_563), prefs.autofillName)
            val fEmail = makeField(getString(R.string.settings_email_e_g_name_gmail_com_564), prefs.autofillEmail)
            val fPhone = makeField(getString(R.string.settings_phone_e_g_7_999_123_45_67_565), prefs.autofillPhone)
            val fAddr = makeField(getString(R.string.settings_delivery_address_566), prefs.autofillAddress)
            val fUser = makeField(getString(R.string.settings_username_telegram_567), prefs.autofillUsername)

            listOf(fName, fEmail, fPhone, fAddr, fUser).forEach { et ->
                afCard.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4f)
                })
            }
            afCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8f)) })
            afCard.addView(makePillButton(getString(R.string.settings_save_autofill_profile_568), primary = true) {
                val saved = prefs.saveAutofillProfile(fName.text.toString(), fEmail.text.toString(),
                    fPhone.text.toString(), fAddr.text.toString(), fUser.text.toString())
                Toast.makeText(this, if (saved) getString(R.string.settings_autofill_profile_encrypted_and_saved_569) else getString(R.string.settings_could_not_save_the_profile_check_the_device_keys_570), Toast.LENGTH_SHORT).show()
                renderScreen()
            })
        }
        container.addView(afCard)

        // Card 3: Lightweight Power Features toggles
        val powerCard = makeCard(padVertical = 6f)
        powerCard.addView(sectionTitle(getString(R.string.settings_smart_gestures_and_privacy_571)))
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_undo_word_deletion_572),
                getString(R.string.settings_show_restore_after_deleting_a_word_with_a_backsp_573),
                prefs.undoDeleteEnabled
            ) { prefs.undoDeleteEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_quick_swipe_up_on_keys_574),
                getString(R.string.settings_swipe_up_to_enter_a_number_or_extra_symbol_immed_575),
                prefs.quickFlickEnabled
            ) { prefs.quickFlickEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_paired_quotes_and_brackets_576),
                getString(R.string.settings_close_pairs_with_the_cursor_inside_or_wrap_selec_577),
                prefs.smartPairsEnabled
            ) { prefs.smartPairsEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_password_field_privacy_shield_578),
                getString(R.string.settings_automatically_disable_key_popups_and_sound_when_579),
                prefs.privacyShieldEnabled
            ) { prefs.privacyShieldEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_remove_tracking_parameters_from_links_580),
                getString(R.string.settings_remove_utm_yclid_fbclid_and_si_when_pasting_copi_581),
                prefs.cleanUrlPasteEnabled
            ) { prefs.cleanUrlPasteEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_password_generator_in_password_fields_582),
                getString(R.string.settings_offer_create_password_16_characters_and_save_it_583),
                prefs.passwordGeneratorEnabled
            ) { prefs.passwordGeneratorEnabled = it }
        )
        powerCard.addView(makeDivider())
        powerCard.addView(
            makeSwitchRow(
                getString(R.string.settings_smart_lists_with_enter_and_1_584),
                getString(R.string.settings_continue_bulleted_and_numbered_lists_in_notes_an_585),
                prefs.smartListsEnabled
            ) { prefs.smartListsEnabled = it }
        )
        powerCard.addView(makeDivider())
        val edgeLabel = TextView(this).apply {
            text = getString(R.string.settings_palm_rejection_at_edges_1_s_dp_586, (prefs.edgeRejectionDp).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textPrimary)
            setPadding(dp(4f), dp(10f), 0, dp(2f))
        }
        powerCard.addView(edgeLabel)
        powerCard.addView(SeekBar(this).apply {
            max = 10
            progress = prefs.edgeRejectionDp.coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edgeRejectionDp = p
                    edgeLabel.text = getString(R.string.settings_palm_rejection_at_edges_1_s_dp_586, (p).toString())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })
        container.addView(powerCard)
    }

    // Sub-screen: Текстовые сокращения (Text Expanders)
    private fun renderExpanders(container: LinearLayout) {
        val addCard = makeCard()
        addCard.addView(sectionTitle(getString(R.string.settings_new_shortcut_587)))
        addCard.addView(
            makeSwitchRow(
                getString(R.string.settings_expand_text_shortcuts_588),
                getString(R.string.settings_expand_a_trigger_on_space_or_from_the_toolbar_589),
                prefs.textExpandersEnabled
            ) { prefs.textExpandersEnabled = it }
        )
        addCard.addView(makeDivider())

        val keyInput = EditText(this).apply {
            hint = getString(R.string.settings_trigger_e_g_or_addr_590)
            setSingleLine(true)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(bgSurface) }
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        val valInput = EditText(this).apply {
            hint = getString(R.string.settings_full_text_to_insert_591)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(bgSurface) }
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        addCard.addView(keyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f); bottomMargin = dp(8f)
        })
        addCard.addView(valInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })
        addCard.addView(makePillButton(getString(R.string.settings_add_shortcut_592), primary = true) {
            val k = keyInput.text?.toString()?.trim() ?: ""
            val v = valInput.text?.toString()?.trim() ?: ""
            if (k.isNotEmpty() && v.isNotEmpty()) {
                prefs.putTextExpander(k, v)
                Toast.makeText(this, getString(R.string.settings_saved_1_s_2_s_593, (k).toString(), (v).toString()), Toast.LENGTH_SHORT).show()
                renderScreen()
            }
        })
        container.addView(addCard)

        val listCard = makeCard()
        val items = prefs.getTextExpanders()
        listCard.addView(sectionTitle(getString(R.string.settings_saved_shortcuts_1_s_594, (items.size).toString())))
        if (items.isEmpty()) {
            listCard.addView(TextView(this).apply {
                text = getString(R.string.settings_no_shortcuts_yet_add_a_trigger_above_such_as_for_595)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
            })
        } else {
            items.entries.forEachIndexed { idx, (k, v) ->
                if (idx > 0) listCard.addView(makeDivider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = k
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(accentBlue)
                })
                col.addView(TextView(this).apply {
                    text = v
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(textPrimary)
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = getString(R.string.settings_delete_471)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(Color.parseColor("#FFD93025"))
                    setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener {
                        prefs.removeTextExpander(k)
                        renderScreen()
                    }
                })
                listCard.addView(row)
            }
        }
        container.addView(listCard)
    }

    // Sub-screen: Персональный словарь
    private fun renderDictionary(container: LinearLayout) {
        val contactsCard = makeCard(padVertical = 6f)
        contactsCard.addView(
            makeSwitchRow(
                getString(R.string.settings_contact_names_optional_596),
                getString(R.string.settings_add_contact_names_to_the_dictionary_so_autocorre_597),
                prefs.contactsDictionaryEnabled
            ) { checked ->
                if (checked) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        prefs.contactsDictionaryEnabled = true
                        val added = importContactNamesToDictionary()
                        Toast.makeText(this, getString(R.string.settings_contact_names_imported_1_s_6, (added).toString()), Toast.LENGTH_SHORT).show()
                        renderScreen()
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
                    }
                } else {
                    prefs.contactsDictionaryEnabled = false
                }
            }
        )
        container.addView(contactsCard)
        val addCard = makeCard()
        val input = EditText(this).apply {
            hint = getString(R.string.settings_enter_a_word_to_add_598)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(bgSurface)
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        addCard.addView(input)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        btnRow.addView(makePillButton(getString(R.string.settings_add_599), primary = true) {
            val w = input.text.toString().trim()
            if (w.length >= 2) {
                prefs.addUserWord(w)
                input.setText("")
                renderScreen()
            }
        })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
        btnRow.addView(makePillButton(getString(R.string.settings_export_txt_600), primary = false) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "claritykey_dictionary.txt")
            }
            startActivityForResult(intent, REQ_EXPORT_DICT)
        })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) })
        btnRow.addView(makePillButton(getString(R.string.settings_import_txt_601), primary = false) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, REQ_IMPORT_DICT)
        })
        addCard.addView(btnRow)
        container.addView(addCard)

        val words = prefs.getUserWords().sorted()
        val listCard = makeCard(padVertical = 6f)
        if (words.isEmpty()) {
            listCard.addView(makeInfoRow(getString(R.string.settings_your_dictionary_is_empty_602), getString(R.string.settings_new_words_are_learned_as_you_type_except_in_pass_603)))
        } else {
            words.forEachIndexed { idx, word ->
                if (idx > 0) listCard.addView(makeDivider())
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                }
                row.addView(TextView(this).apply {
                    text = word
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(textPrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = getString(R.string.settings_delete_471)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Color.parseColor("#FFDB382C"))
                    setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener {
                        prefs.removeUserWord(word)
                        renderScreen()
                    }
                })
                listCard.addView(row)
            }
        }
        container.addView(listCard)
    }

    // ------------------------------------------------------------------
    // UI Helpers
    // ------------------------------------------------------------------
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accentBlue)
        setPadding(dp(4f), 0, 0, dp(6f))
    }

    private fun makeRadioRow(label: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(11f), dp(4f), dp(11f))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (selected) accentBlue else textPrimary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = if (selected) "✓" else ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(accentBlue)
        })
        return row
    }

    private fun addThemeRows(card: LinearLayout, modes: List<Triple<DayNightMode, String, String>>) {
        modes.forEachIndexed { idx, (mode, title, sub) ->
            if (idx > 0) card.addView(makeDivider())
            val selected = prefs.themeMode == mode
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
                setOnClickListener {
                    prefs.themeMode = mode
                    renderScreen()
                }
            }
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(26f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(
                        when (mode) {
                            DayNightMode.LIGHT -> Color.parseColor("#FFE2E4E7")
                            DayNightMode.DARK -> Color.parseColor("#FF2C2D2E")
                            DayNightMode.SYSTEM -> accentBlue
                            DayNightMode.GLASS_BG_LIGHT -> Color.argb(175, 226, 232, 245)
                            DayNightMode.GLASS_BG_DARK -> Color.argb(195, 42, 44, 52)
                            DayNightMode.GLASS_BG_SYSTEM -> Color.argb(180, 96, 146, 240)
                            DayNightMode.GLASS_LIGHT -> Color.argb(145, 226, 232, 245)
                            DayNightMode.GLASS_DARK -> Color.argb(170, 32, 36, 48)
                            DayNightMode.GLASS_SYSTEM -> Color.argb(160, 82, 132, 240)
                        }
                    )
                    setStroke(dp(if (selected) 2f else 1f), if (selected) accentBlue else Color.argb(120, 255, 255, 255))
                }
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), 0, dp(8f), 0)
            }
            textCol.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) accentBlue else textPrimary)
            })
            textCol.addView(TextView(this).apply {
                text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(textSecondary)
            })
            val check = TextView(this).apply {
                text = if (selected) "✓" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(accentBlue)
            }
            row.addView(swatch)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(check)
            card.addView(row)
        }
    }

    private fun makeCard(padVertical: Float = 14f): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f).toFloat()
            setColor(cardSurface)
        }
        setPadding(dp(16f), dp(padVertical), dp(16f), dp(padVertical))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(6f), 0, dp(8f))
        }
    }

    private fun makeCategoryRow(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13f), 0, dp(13f))
            setOnClickListener { onClick() }
        }
        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(iconBgTint)
            }
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(accentBlue)
            val p = dp(9f)
            setPadding(p, p, p, p)
        }
        iconWrap.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, dp(8f), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
        })
        val chevron = TextView(this).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(textSecondary)
        }
        row.addView(iconWrap)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chevron)
        return row
    }

    private fun makeSwitchRow(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(12f), dp(2f), dp(12f))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12f), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        return row
    }

    private fun makeInfoRow(title: String, subtitle: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2f), dp(12f), dp(2f), dp(12f))
        }
        col.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textSecondary)
        })
        return col
    }

    private fun makePillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else accentBlue)
        gravity = Gravity.CENTER
        setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f).toFloat()
            setColor(if (primary) accentBlue else iconBgTint)
        }
        setOnClickListener { onClick() }
    }

    private fun makeDivider(): View = View(this).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.75f).coerceAtLeast(1))
    }
}
