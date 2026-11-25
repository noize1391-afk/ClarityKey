package com.claritykey.keyboard.ime

import android.content.Intent
import android.content.ClipDescription
import android.widget.Toast
import androidx.core.view.inputmethod.EditorInfoCompat
import com.claritykey.keyboard.layout.AdaptiveBottomMetrics
import com.claritykey.keyboard.layout.AdaptiveBottomLayout
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import android.view.animation.PathInterpolator
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.claritykey.keyboard.clipboard.ClipEntry
import com.claritykey.keyboard.clipboard.ClipItemType
import com.claritykey.keyboard.clipboard.ClipboardImagePickerActivity
import com.claritykey.keyboard.engine.QuickImageSuggestion
import com.claritykey.keyboard.engine.QuickSmartSuggestion
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.claritykey.keyboard.R
import com.claritykey.keyboard.engine.BreenoClipboardManager
import android.graphics.PointF
import android.widget.TextView
import android.util.TypedValue
import com.claritykey.keyboard.engine.BreenoDictionaryEngine
import com.claritykey.keyboard.engine.InlineCalculator
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.claritykey.keyboard.engine.LayoutSwitcherAndTranslator
import com.claritykey.keyboard.engine.PersonalMemoryEngine
import com.claritykey.keyboard.engine.TextPowerTools
import com.claritykey.keyboard.engine.RamblerSpeechProcessor
import com.claritykey.keyboard.engine.RamblerStyle
import com.claritykey.keyboard.engine.UnicodeStyle
import java.io.File
import com.claritykey.keyboard.prefs.OneHandedMode
import com.claritykey.keyboard.engine.SwipeGestureDecoder
import com.claritykey.keyboard.engine.TouchCalibrationModel
import com.claritykey.keyboard.layout.ComputedKey
import com.claritykey.keyboard.layout.InputContextMode
import com.claritykey.keyboard.layout.KeyRole
import com.claritykey.keyboard.layout.KeyboardMode
import com.claritykey.keyboard.layout.ShiftState
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.claritykey.keyboard.settings.SettingsActivity
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import com.claritykey.keyboard.view.BreenoClipboardPanelView
import com.claritykey.keyboard.view.BreenoEmojiPanelView
import com.claritykey.keyboard.view.BreenoHeaderBarView
import com.claritykey.keyboard.view.BreenoKeyboardView
import com.claritykey.keyboard.view.BreenoMenuPanelView
import com.claritykey.keyboard.view.BreenoTextEditPanelView
import com.claritykey.keyboard.voice.BreenoVoiceHelper
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class BreenoRuInputMethodService : InputMethodService(),
    BreenoKeyboardView.Listener,
    BreenoHeaderBarView.Listener,
    BreenoVoiceHelper.Callback,
    BreenoEmojiPanelView.Listener,
    BreenoClipboardPanelView.Listener,
    BreenoTextEditPanelView.Listener,
    BreenoMenuPanelView.Listener {

    private enum class ActivePanel {
        KEYBOARD, MENU, EMOJI, CLIPBOARD, TEXT_EDIT
    }

    private lateinit var prefs: BreenoPreferences
    private lateinit var dictEngine: BreenoDictionaryEngine
    private lateinit var personalMemory: PersonalMemoryEngine
    private lateinit var touchCalibration: TouchCalibrationModel
    private lateinit var swipeDecoder: SwipeGestureDecoder
    private var lastSwipedWordLen: Int = 0
    private var lastSwipedText: String = ""
    private lateinit var clipboardManager: BreenoClipboardManager
    private lateinit var voiceHelper: BreenoVoiceHelper

    private lateinit var rootContainer: LinearLayout
    private lateinit var modeRowContainer: LinearLayout
    private lateinit var keyboardCardContainer: LinearLayout
    private lateinit var floatingHandleBar: LinearLayout
    private lateinit var oneHandedRail: LinearLayout
    private lateinit var headerBar: BreenoHeaderBarView
    private lateinit var arrowNavBar: LinearLayout
    private lateinit var bodyContainer: FrameLayout
    private lateinit var keyboardView: BreenoKeyboardView
    private lateinit var emojiPanel: BreenoEmojiPanelView
    private lateinit var clipboardPanel: BreenoClipboardPanelView
    private lateinit var textEditPanel: BreenoTextEditPanelView
    private lateinit var menuPanel: BreenoMenuPanelView

    private var activePanel: ActivePanel = ActivePanel.KEYBOARD
    private var currentSkin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var cachedWallpaperPath: String = ""
    private var cachedWallpaperBitmap: Bitmap? = null
    private var lastWindowInsets: WindowInsetsCompat? = null
    private var currentMode: KeyboardMode = KeyboardMode.ALPHA_RU
    private var contextMode: InputContextMode = InputContextMode.NORMAL
    private var shiftState: ShiftState = ShiftState.OFF
    private var lastShiftTapMs: Long = 0L
    private var lastSpaceTapMs: Long = 0L
    private var isSensitiveField: Boolean = false
    private var isIncognito: Boolean = false
    private var activeUnicodeStyle: UnicodeStyle = UnicodeStyle.NORMAL
    private val editHistoryStack = TextPowerTools.UndoRedoStack(20)
    private var t9LastKeyLabel: String = ""
    private var t9LastTapMs: Long = 0L
    private var t9CharIdx: Int = 0
    private var liveTranslateActive: Boolean = false
    private var ramblerActive: Boolean = false
    private val ramblerBuffer = StringBuilder()
    private var liveTranslateRuToEn: Boolean = true
    private val liveTranslateBuffer = StringBuilder()

    // Gboard-style smart state
    private val composing = StringBuilder()
    private val wordTapPoints = ArrayList<PointF>(24)
    private var pendingLetterTapPoint: PointF? = null
    private var lastAutoCorrectedFrom: String? = null
    private var lastAutoCorrectedTo: String? = null
    private var lastCommittedSpaceFromSuggestion: Boolean = false
    private var lastCommittedWord: String = ""
    private var prevCommittedWord: String = ""
    private var isTerminalAppProfile: Boolean = false
    private var lastSafeEnterTapMs: Long = 0L
    private var lastTypingEventMs: Long = 0L
    private var lastSplitJoinCandidate: Pair<String, Boolean>? = null

    private var soundPool: SoundPool? = null
    private var soundStandardId: Int = 0
    private var soundDeleteId: Int = 0
    private var soundFuncId: Int = 0
    private var vibrator: Vibrator? = null
    private val autofillExecutor = Executors.newSingleThreadExecutor()
    private var inputSession = 0L
    private var inputActive = false
    private var isSensitiveHostApp = false
    private var clipboardGeneration = com.claritykey.keyboard.clipboard.ClipboardRepository.dataGeneration
    private data class PendingSwipe(val path: List<PointF>, val keys: List<ComputedKey>, val lang: String, val shift: ShiftState)
    private val swipeQueue = java.util.ArrayDeque<PendingSwipe>()
    private var swipeInFlight = false
    private var swipeRequest = 0L
    private var autofillRequest = 0L

    private fun resetEditorState() {
        val generation = com.claritykey.keyboard.clipboard.ClipboardRepository.dataGeneration
        if (clipboardGeneration != generation) {
            clipboardGeneration = generation
            if (::dictEngine.isInitialized) dictEngine.clearLearnedBigrams()
            if (::clipboardManager.isInitialized) clipboardManager.clearPendingSuggestions()
        }
        inputSession++
        swipeInFlight = false
        swipeRequest++
        swipeQueue.clear()
        autofillRequest++
        voiceHelper.destroyRecognizer()
        editHistoryStack.clear()
        composing.setLength(0)
        wordTapPoints.clear()
        lastSwipedWordLen = 0
        lastSwipedText = ""
        lastCommittedWord = ""
        prevCommittedWord = ""
        lastSplitJoinCandidate = null
        t9LastKeyLabel = ""
        t9LastTapMs = 0L
        lastSpaceTapMs = 0L
        lastSafeEnterTapMs = 0L
        lastTypingEventMs = 0L
        activeUnicodeStyle = UnicodeStyle.NORMAL
        shiftState = ShiftState.OFF
        if (::keyboardView.isInitialized) {
            keyboardView.likelyNextChars = emptySet()
            keyboardView.setShiftState(shiftState)
        }
        clearAutoCorrectUndo()
    }

    private fun updatePrivacyPolicy() {
        isIncognito = isSensitiveField || isSensitiveHostApp || prefs.manualIncognito ||
            ((currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        prefs.clipboardCapturePaused = !inputActive || isIncognito
        if (isIncognito) {
            editHistoryStack.clear()
            prevCommittedWord = ""
            lastCommittedWord = ""
            clipboardManager.clearPendingSuggestions()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = false
        prefs.clipboardCapturePaused = true
        resetEditorState()
    }

    override fun onFinishInput() {
        inputActive = false
        prefs.clipboardCapturePaused = true
        resetEditorState()
        super.onFinishInput()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = BreenoPreferences(this)
        dictEngine = BreenoDictionaryEngine(this, prefs)
        personalMemory = PersonalMemoryEngine(this, prefs)
        touchCalibration = TouchCalibrationModel(prefs)
        swipeDecoder = SwipeGestureDecoder(dictEngine)
        clipboardManager = BreenoClipboardManager(this, prefs)
        voiceHelper = BreenoVoiceHelper(this, this)
        currentMode = if (prefs.activeLanguage == "EN") KeyboardMode.ALPHA_EN else if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU

        clipboardManager.setOnNewClipListener { text ->
            if (::headerBar.isInitialized && inputActive && !isIncognito && composing.isEmpty()) {
                val cleanUrl = if (prefs.cleanUrlPasteEnabled) TextPowerTools.stripTrackingParams(text) else null
                if (cleanUrl != null) {
                    headerBar.showLocalAutofillChip("🔗 Чистая ссылка:", cleanUrl, cleanUrl)
                } else {
                    val preview = if (prefs.clipboardHidePreview) "Буфер (${text.length} симв.)" else text
                    headerBar.showQuickClipChip(text, preview)
                }
            }
        }
        clipboardManager.setOnNewSmartClipListener { smartSug ->
            if (::headerBar.isInitialized && inputActive && !isIncognito && composing.isEmpty()) {
                headerBar.showQuickSmartSuggestion(smartSug)
            }
        }
        clipboardManager.setOnNewImageClipListener { suggestion ->
            if (::headerBar.isInitialized && inputActive && !isIncognito && composing.isEmpty()) {
                headerBar.showQuickImageSuggestion(suggestion)
            }
        }
        clipboardManager.setOnHistoryChangedListener {
            if (::clipboardPanel.isInitialized && activePanel == ActivePanel.CLIPBOARD) {
                clipboardPanel.refresh()
            }
        }
        OtpMemoryBridge.setListener { code, source ->
            if (::headerBar.isInitialized && prefs.otpNotificationAutofillEnabled && inputActive && !isIncognito && composing.isEmpty()) {
                val label = if (prefs.clipboardHidePreview) "Код ($source)" else "Код: $code"
                headerBar.showLocalAutofillChip("🔐", label, code)
            }
        }
        initFeedback()
        autofillExecutor.execute { dictEngine.ensureLoaded() }
    }

    private fun initFeedback() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build().also { sp ->
                soundStandardId = sp.load(this, R.raw.key_press_standard, 1)
                soundDeleteId = sp.load(this, R.raw.key_press_delete, 1)
                soundFuncId = sp.load(this, R.raw.key_press_function, 1)
            }
    }

    override fun onDestroy() {
        inputActive = false
        prefs.clipboardCapturePaused = true
        inputSession++
        OtpMemoryBridge.setListener(null)
        OtpMemoryBridge.clear()
        voiceHelper.destroyRecognizer()
        clipboardManager.release()
        if (::headerBar.isInitialized) headerBar.release()
        if (::clipboardPanel.isInitialized) clipboardPanel.release()
        if (::emojiPanel.isInitialized) emojiPanel.release()
        if (::keyboardView.isInitialized) keyboardView.dismissPopup()
        cachedWallpaperBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedWallpaperBitmap = null
        soundPool?.release()
        soundPool = null
        autofillExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        AdaptiveBottomLayout.attachListener(rootContainer) { insets ->
            lastWindowInsets = insets
            applyAdaptiveBottomLayout(insets)
        }
        headerBar = BreenoHeaderBarView(this).apply {
            listener = this@BreenoRuInputMethodService
        }
        bodyContainer = FrameLayout(this)

        keyboardView = BreenoKeyboardView(this).apply {
            listener = this@BreenoRuInputMethodService
        }
        emojiPanel = BreenoEmojiPanelView(this, prefs).apply {
            listener = this@BreenoRuInputMethodService
            visibility = View.GONE
        }
        clipboardPanel = BreenoClipboardPanelView(this, clipboardManager, prefs).apply {
            listener = this@BreenoRuInputMethodService
            visibility = View.GONE
        }
        textEditPanel = BreenoTextEditPanelView(this).apply {
            listener = this@BreenoRuInputMethodService
            visibility = View.GONE
        }
        menuPanel = BreenoMenuPanelView(this).apply {
            listener = this@BreenoRuInputMethodService
            visibility = View.GONE
        }

        bodyContainer.addView(keyboardView)
        bodyContainer.addView(menuPanel)
        bodyContainer.addView(emojiPanel)
        bodyContainer.addView(clipboardPanel)
        bodyContainer.addView(textEditPanel)

        floatingHandleBar = buildFloatingHandleBar()
        oneHandedRail = buildOneHandedRail()

        arrowNavBar = buildArrowNavBar()
        keyboardCardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerBar)
            addView(arrowNavBar)
            addView(bodyContainer)
            addView(floatingHandleBar)
        }
        modeRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        rootContainer.addView(
            modeRowContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        refreshThemeAndLayout()
        return rootContainer
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetEditorState()
        inputActive = true
        val session = inputSession
        composing.setLength(0); wordTapPoints.clear()
        liveTranslateActive = false
        liveTranslateBuffer.setLength(0)
        ramblerActive = false
        ramblerBuffer.setLength(0)
        clearAutoCorrectUndo()
        lastCommittedSpaceFromSuggestion = false

        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val imeOptions = info?.imeOptions ?: 0

        val hostPkg = info?.packageName?.lowercase() ?: ""
        isTerminalAppProfile = prefs.perAppProfilesEnabled && (
            hostPkg.contains("termux") || hostPkg.contains("connectbot") || hostPkg.contains("androidterm")
        )
        isSensitiveHostApp = prefs.autoIncognitoSensitiveApps && isSensitiveSecurityApp(hostPkg)
        isSensitiveField = isPasswordVariation(inputClass, variation)
        updatePrivacyPolicy()

        contextMode = when {
            inputClass == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> InputContextMode.EMAIL
            inputClass == InputType.TYPE_CLASS_TEXT &&
                variation == InputType.TYPE_TEXT_VARIATION_URI -> InputContextMode.URI
            else -> InputContextMode.NORMAL
        }

        currentMode = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> KeyboardMode.NUMBER_PAD
            else -> if (prefs.activeLanguage == "EN") KeyboardMode.ALPHA_EN else if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU
        }

        val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
        if (::keyboardView.isInitialized) {
            keyboardView.popupEnabled = prefs.popupEnabled && !(isSensitiveField && prefs.privacyShieldEnabled)
            keyboardView.filterTouchesWhenObscured = prefs.tapjackingProtectionEnabled
            applyImeWindowSecurityFlag(isSensitiveField && prefs.flagSecureInSecretsEnabled)
            keyboardView.quickFlickEnabled = prefs.quickFlickEnabled
            keyboardView.swipeTypingEnabled = prefs.swipeTypingEnabled && !isSensitiveField && contextMode == InputContextMode.NORMAL
            keyboardView.setImeAction(actionId)
            switchPanel(ActivePanel.KEYBOARD)
            refreshThemeAndLayout()
            updateAutoCap()
        }
        if (::headerBar.isInitialized) {
            headerBar.setVoiceButtonVisible(prefs.voiceInputEnabled && !isSensitiveField)
            headerBar.setRamblerVoiceIcon(prefs.ramblerVoiceModeEnabled)
            headerBar.showToolbar()
            clipboardManager.syncFromSystemClipboard(notifyQuickChip = false)
            if (prefs.otpNotificationAutofillEnabled && inputActive && !isIncognito) {
                OtpMemoryBridge.peekLatest()?.let { (otpCode, otpSrc) ->
                    val lbl = if (prefs.clipboardHidePreview) "Код ($otpSrc)" else "Код: $otpCode"
                    headerBar.showLocalAutofillChip("🔐", lbl, otpCode)
                }
            }
            if (!isSensitiveField) {
                clipboardManager.checkPendingImageOrScreenshotAsync { imgSuggestion ->
                    if (!::headerBar.isInitialized || !inputActive || session != inputSession || isIncognito || composing.isNotEmpty()) return@checkPendingImageOrScreenshotAsync
                    if (imgSuggestion != null) {
                        headerBar.showQuickImageSuggestion(imgSuggestion)
                    } else {
                        clipboardManager.checkPendingSmartSuggestionAsync { smartSug ->
                            if (!::headerBar.isInitialized || !inputActive || session != inputSession || isIncognito || composing.isNotEmpty()) return@checkPendingSmartSuggestionAsync
                            if (smartSug != null) {
                                headerBar.showQuickSmartSuggestion(smartSug)
                            } else {
                                val localAf = personalMemory.resolveLocalAutofillChip(info)
                                if (localAf != null) {
                                    headerBar.showLocalAutofillChip(localAf.icon, localAf.displayLabel, localAf.value)
                                } else {
                                    clipboardManager.getPendingQuickSuggestion()?.let { clipText ->
                                        val preview = if (prefs.clipboardHidePreview) "Буфер (${clipText.length} симв.)" else clipText
                                        headerBar.showQuickClipChip(clipText, preview)
                                    } ?: run {
                                        headerBar.hideQuickClipChip()
                                        showContextDomainChipsIfRelevant()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (prefs.passwordGeneratorEnabled) {
                    headerBar.showPasswordGenChip()
                } else {
                    headerBar.hideQuickClipChip()
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputActive = false
        prefs.clipboardCapturePaused = true
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        if (ramblerActive) {
            ramblerActive = false
            ramblerBuffer.setLength(0)
            if (::headerBar.isInitialized) headerBar.hideRamblerBar()
        }
        voiceHelper.stopListening()
        commitComposingIfNeeded(applyAutocorrect = false)
        personalMemory.flush()
        if (::keyboardView.isInitialized) keyboardView.dismissPopup()
        if (::headerBar.isInitialized) headerBar.clearAutofillViews()
        resetEditorState()
        super.onFinishInputView(finishingInput)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::keyboardView.isInitialized) refreshThemeAndLayout()
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null || !::prefs.isInitialized) return
        if (prefs.floatingModeEnabled && ::keyboardCardContainer.isInitialized) {
            val decor = window?.window?.decorView
            val decorH = decor?.height ?: resources.displayMetrics.heightPixels
            outInsets.contentTopInsets = decorH
            outInsets.visibleTopInsets = decorH
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            val cardLoc = IntArray(2)
            val decorLoc = IntArray(2)
            keyboardCardContainer.getLocationInWindow(cardLoc)
            decor?.getLocationInWindow(decorLoc)
            val left = (cardLoc[0] - decorLoc[0]).coerceAtLeast(0)
            val top = (cardLoc[1] - decorLoc[1]).coerceAtLeast(0)
            val right = left + keyboardCardContainer.width.coerceAtLeast(1)
            val bottom = top + keyboardCardContainer.height.coerceAtLeast(1)
            outInsets.touchableRegion.set(left, top, right, bottom)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composing.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.setLength(0); wordTapPoints.clear()
            currentInputConnection?.finishComposingText()
            if (::headerBar.isInitialized) headerBar.showToolbar()
        }
        updateAutoCap()
    }

    private fun switchPanel(panel: ActivePanel) {
        val prevPanel = activePanel
        activePanel = panel
        val targetView: View = when (panel) {
            ActivePanel.KEYBOARD -> keyboardView
            ActivePanel.MENU -> menuPanel
            ActivePanel.EMOJI -> emojiPanel
            ActivePanel.CLIPBOARD -> clipboardPanel
            ActivePanel.TEXT_EDIT -> textEditPanel
        }
        keyboardView.visibility = if (panel == ActivePanel.KEYBOARD) View.VISIBLE else View.GONE
        menuPanel.visibility = if (panel == ActivePanel.MENU) View.VISIBLE else View.GONE
        emojiPanel.visibility = if (panel == ActivePanel.EMOJI) View.VISIBLE else View.GONE
        clipboardPanel.visibility = if (panel == ActivePanel.CLIPBOARD) View.VISIBLE else View.GONE
        textEditPanel.visibility = if (panel == ActivePanel.TEXT_EDIT) View.VISIBLE else View.GONE
        if (prevPanel != panel) {
            targetView.animate().cancel()
            targetView.alpha = 0f
            targetView.translationY = 10f * resources.displayMetrics.density
            targetView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(165L)
                .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .start()
        }
        if (::headerBar.isInitialized) headerBar.setMenuOpen(panel == ActivePanel.MENU)

        if (panel == ActivePanel.MENU) {
            menuPanel.updateStates(
                incognito = prefs.manualIncognito,
                oneHanded = prefs.oneHandedMode != OneHandedMode.OFF,
                floating = prefs.floatingModeEnabled
            )
            menuPanel.resetToFirstPage()
        }
        if (panel == ActivePanel.EMOJI) emojiPanel.refreshRecents()
        if (panel == ActivePanel.CLIPBOARD) clipboardPanel.refresh()
        if (panel == ActivePanel.TEXT_EDIT && !isIncognito) {
            val snapIc = currentInputConnection
            if (snapIc != null) {
                fullEditorText()?.let { editHistoryStack.record(it) }
            }
            val ic = currentInputConnection
            val before = ic?.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val after = ic?.getTextAfterCursor(2000, 0)?.toString() ?: ""
            val (chars, words) = TextPowerTools.countStats(before + after)
            textEditPanel.setTextStats(chars, words)
            textEditPanel.setUnicodeStyle(activeUnicodeStyle)
        }
    }

    // ------------------------------------------------------------------
    // Emoji, Clipboard, TextEdit Panel Listeners
    // ------------------------------------------------------------------
    override fun onEmojiSelected(emoji: String) {
        commitComposingIfNeeded(applyAutocorrect = false)
        currentInputConnection?.commitText(emoji, 1)
        onKeyFeedback(KeyRole.NORMAL)
    }

    override fun onEmojiBackspace() {
        onDeleteChar()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onEmojiClose() = switchPanel(ActivePanel.KEYBOARD)

    override fun onStickerSelected(path: String) {
        if (isSensitiveField) return
        val f = File(path)
        if (!f.exists()) return
        val stickerEntry = ClipEntry(
            id = -2L,
            text = "",
            contentHash = "sticker_${f.lastModified()}",
            pinned = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            sourcePackage = packageName,
            sourceLabel = "Стикер ClarityKey",
            type = ClipItemType.IMAGE,
            mimeType = if (f.name.endsWith(".gif", ignoreCase = true)) "image/gif" else "image/png",
            filePath = path,
            thumbPath = path,
            width = 0,
            height = 0,
            fileSize = f.length()
        )
        if (commitImageToEditor(stickerEntry)) {
            onKeyFeedback(KeyRole.NORMAL)
            switchPanel(ActivePanel.KEYBOARD)
        } else {
            onClipImageShare(stickerEntry)
        }
    }

    override fun onAddStickerClick() {
        try {
            startActivity(Intent(this, ClipboardImagePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ClipboardImagePickerActivity.EXTRA_PICK_STICKER, true)
            })
        } catch (_: Exception) {
        }
    }

    override fun onShowInputMethodPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun fullEditorText(): String? {
        val ic = currentInputConnection ?: return null
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
            hintMaxChars = 100_000
        }, 0) ?: return null
        if (extracted.startOffset != 0 || extracted.partialStartOffset >= 0) return null
        val text = extracted.text?.toString() ?: return null
        if (text.length > 100_000) return null
        if (extracted.selectionStart !in 0..text.length || extracted.selectionEnd !in 0..text.length) return null
        // Ensure this snapshot reaches the end of the document.
        val after = ic.getTextAfterCursor(text.length + 1, 0)?.toString() ?: return null
        if (after != text.substring(maxOf(extracted.selectionStart, extracted.selectionEnd))) return null
        return text
    }

    private fun restoreEditorText(text: String) {
        val ic = currentInputConnection ?: return
        val current = fullEditorText() ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            composing.setLength(0)
            wordTapPoints.clear()
            if (ic.setSelection(0, current.length)) ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun onUndoEdit() {
        if (isIncognito) return
        val current = fullEditorText() ?: return
        editHistoryStack.record(current)
        val previous = editHistoryStack.undo(current) ?: return
        restoreEditorText(previous)
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onSelectCurrentWord() {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val full = extracted?.text?.toString()
        val cursor = extracted?.selectionStart ?: -1
        if (full != null && cursor in 0..full.length) {
            var start = cursor
            while (start > 0 && (full[start - 1].isLetterOrDigit() || full[start - 1] == '_')) start--
            var end = cursor
            while (end < full.length && (full[end].isLetterOrDigit() || full[end] == '_')) end++
            if (end > start) {
                ic.setSelection(start, end)
                onKeyFeedback(KeyRole.FUNCTION)
                return
            }
        }
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onDeleteToLineStart() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(300, 0)?.toString() ?: return
        if (before.isEmpty()) return
        val nl = before.lastIndexOf('\n')
        val delCount = if (nl >= 0) before.length - nl - 1 else before.length
        if (delCount > 0) {
            val deleted = before.substring(before.length - delCount)
            ic.deleteSurroundingText(delCount, 0)
            if (prefs.undoDeleteEnabled && !isSensitiveField && ::headerBar.isInitialized) {
                headerBar.showUndoDeleteChip(deleted)
            }
            onKeyFeedback(KeyRole.FUNCTION)
        }
    }

    override fun onCopyAllText() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.performContextMenuAction(android.R.id.copy)
        Toast.makeText(this, "Весь текст скопирован", Toast.LENGTH_SHORT).show()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onRedoEdit() {
        if (isIncognito) return
        val current = fullEditorText() ?: return
        val next = editHistoryStack.redo(current) ?: return
        restoreEditorText(next)
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onClipPaste(text: String) {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        // Direct safe InputConnection paste: replaces selection if active, preserves exact whitespace/newlines/Unicode
        val committed = ic.commitText(text, 1)
        if (committed) {
            clipboardManager.markSuggestionConsumed(text)
            headerBar.hideQuickClipChip()
            onKeyFeedback(KeyRole.NORMAL)
            switchPanel(ActivePanel.KEYBOARD)
        }
    }

    override fun onClipFragmentPaste(fragment: String) {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        if (ic.commitText(fragment, 1)) {
            headerBar.hideQuickClipChip()
            onKeyFeedback(KeyRole.NORMAL)
            switchPanel(ActivePanel.KEYBOARD)
        }
    }

    override fun onClipOpenSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_OPEN_CLIPBOARD_SETTINGS, true)
        }
        startActivity(intent)
    }

    override fun onClipImagePaste(entry: ClipEntry) {
        if (isSensitiveField) {
            Toast.makeText(this, "Вставка изображений недоступна в защищённом поле", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = commitImageToEditor(entry)
        if (ok) {
            headerBar.hideQuickClipChip()
            onKeyFeedback(KeyRole.NORMAL)
            switchPanel(ActivePanel.KEYBOARD)
        } else {
            if (activePanel == ActivePanel.CLIPBOARD) {
                clipboardPanel.showUnsupportedEditorBanner(entry)
            } else {
                Toast.makeText(
                    this,
                    "Это поле не поддерживает вставку изображений с клавиатуры. Используйте «Поделиться» в буфере.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onClipImageShare(entry: ClipEntry) {
        val uri = clipboardManager.repository.mediaStorage.getShareableUri(entry.filePath) ?: run {
            Toast.makeText(this, "Файл изображения недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = entry.mimeType ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Отправить изображение").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
            switchPanel(ActivePanel.KEYBOARD)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть меню «Поделиться»", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onClipOpenPhotoPicker() {
        try {
            val intent = Intent(this, ClipboardImagePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор фото", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onClipRequestScreenshotAccess() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_REQUEST_SCREENSHOT_PERMISSION, true)
        }
        startActivity(intent)
    }

    private fun commitImageToEditor(entry: ClipEntry): Boolean {
        val ic = currentInputConnection ?: return false
        val editor = currentInputEditorInfo ?: return false
        val mime = entry.mimeType ?: "image/png"
        val supportedMimes = EditorInfoCompat.getContentMimeTypes(editor)
        val accepted = supportedMimes.any { pattern ->
            pattern.equals(mime, ignoreCase = true) ||
                pattern.equals("image/*", ignoreCase = true) ||
                pattern.equals("*/*", ignoreCase = true)
        }
        if (!accepted) return false

        val contentUri = clipboardManager.repository.mediaStorage.getShareableUri(entry.filePath) ?: return false
        commitComposingIfNeeded(applyAutocorrect = false)

        // Grant explicit read URI permission to target editor package for broad OEM compatibility
        if (!editor.packageName.isNullOrBlank()) {
            try {
                grantUriPermission(editor.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        val clipDesc = ClipDescription("ClarityKey Clipboard Image", arrayOf(mime))
        val contentInfo = InputContentInfoCompat(contentUri, clipDesc, null)
        return try {
            InputConnectionCompat.commitContent(
                ic,
                editor,
                contentInfo,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (_: Exception) {
            false
        }
    }

    override fun onClipOpenVault() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_OPEN_VAULT, true)
        }
        startActivity(intent)
    }

    override fun onClipOpenTemplates() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_OPEN_CLIPBOARD_SETTINGS, true)
        }
        startActivity(intent)
    }

    override fun onClipRedactedImagePaste(filePath: String) {
        val f = File(filePath)
        if (!f.exists()) return
        val fakeEntry = ClipEntry(
            id = -1L,
            text = "",
            contentHash = "redacted_${f.lastModified()}",
            pinned = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            sourcePackage = packageName,
            sourceLabel = "Скрытые данные",
            type = ClipItemType.IMAGE,
            mimeType = "image/png",
            filePath = filePath,
            thumbPath = filePath,
            width = 0,
            height = 0,
            fileSize = f.length()
        )
        val ok = commitImageToEditor(fakeEntry)
        if (ok) {
            onKeyFeedback(KeyRole.NORMAL)
            switchPanel(ActivePanel.KEYBOARD)
        } else {
            onClipImageShare(fakeEntry)
        }
    }

    override fun onClipClose() = switchPanel(ActivePanel.KEYBOARD)

    override fun onMoveCursor(keyCode: Int, selecting: Boolean) {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val now = SystemClock.uptimeMillis()
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        onKeyFeedback(KeyRole.NORMAL)
    }

    override fun onEditAction(actionId: Int) {
        commitComposingIfNeeded(applyAutocorrect = false)
        currentInputConnection?.performContextMenuAction(actionId)
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onPuntoSwitchSelected() {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val switched = LayoutSwitcherAndTranslator.switchLayoutAuto(selected)
            ic.commitText(switched, 1)
            onKeyFeedback(KeyRole.FUNCTION)
            return
        }
        val lastWord = extractLastWordBeforeCursor(ic)
        if (lastWord.isNotEmpty()) {
            val switched = LayoutSwitcherAndTranslator.switchLayoutAuto(lastWord)
            ic.deleteSurroundingText(lastWord.length, 0)
            ic.commitText(switched, 1)
            onKeyFeedback(KeyRole.FUNCTION)
        } else {
            Toast.makeText(this, "Выделите текст или наберите слово для RU⇄EN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onQuickTranslateSelected() {
        val ic = currentInputConnection
        commitComposingIfNeeded(applyAutocorrect = false)
        val selected = ic?.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val translated = LayoutSwitcherAndTranslator.translateOffline(selected)
            if (translated.isNotEmpty()) ic.commitText(translated, 1)
            onKeyFeedback(KeyRole.FUNCTION)
            return
        }
        // Toggle interactive Live Translate Bar right above the keyboard (works even on empty input fields!)
        if (liveTranslateActive) {
            onTranslateClose()
        } else {
            liveTranslateActive = true
            liveTranslateRuToEn = prefs.activeLanguage == "RU"
            liveTranslateBuffer.setLength(0)
            switchPanel(ActivePanel.KEYBOARD)
            headerBar.showTranslateBar(liveTranslateRuToEn, "", "")
            onKeyFeedback(KeyRole.FUNCTION)
        }
    }

    private fun refreshLiveTranslateBar(overridePreviewSource: String? = null) {
        val src = overridePreviewSource ?: liveTranslateBuffer.toString()
        val tr = if (src.isBlank()) "" else LayoutSwitcherAndTranslator.translateOffline(src, forceRuToEn = liveTranslateRuToEn)
        headerBar.showTranslateBar(liveTranslateRuToEn, src, tr, voiceListening = voiceHelper.isCurrentlyListening())
    }

    override fun onTranslateVoiceClick() {
        if (isSensitiveField) return
        val lang = if (liveTranslateRuToEn) "RU" else "EN"
        voiceHelper.toggleListening(lang)
        refreshLiveTranslateBar()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onTranslateToggleDirection() {
        liveTranslateRuToEn = !liveTranslateRuToEn
        refreshLiveTranslateBar()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onTranslateCommit() {
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        val src = liveTranslateBuffer.toString().trim()
        if (src.isNotEmpty()) {
            val translated = LayoutSwitcherAndTranslator.translateOffline(src, forceRuToEn = liveTranslateRuToEn)
            currentInputConnection?.commitText(translated, 1)
            onKeyFeedback(KeyRole.NORMAL)
        }
        liveTranslateBuffer.setLength(0)
        liveTranslateActive = false
        headerBar.hideTranslateBar()
    }

    override fun onTranslateClose() {
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        liveTranslateBuffer.setLength(0)
        liveTranslateActive = false
        headerBar.hideTranslateBar()
    }

    override fun onTransliterateSelected() {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            ic.commitText(TextPowerTools.transliterateAuto(selected), 1)
            onKeyFeedback(KeyRole.FUNCTION)
            return
        }
        val lastWord = extractLastWordBeforeCursor(ic)
        if (lastWord.isNotEmpty()) {
            ic.deleteSurroundingText(lastWord.length, 0)
            ic.commitText(TextPowerTools.transliterateAuto(lastWord), 1)
            onKeyFeedback(KeyRole.FUNCTION)
        } else {
            Toast.makeText(this, "Выделите текст или слово для транслитерации", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleanTextSelected() {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            ic.commitText(TextPowerTools.cleanMessyText(selected), 1)
            Toast.makeText(this, "Текст очищен от лишних пробелов и спецсимволов", Toast.LENGTH_SHORT).show()
            onKeyFeedback(KeyRole.FUNCTION)
        } else {
            Toast.makeText(this, "Выделите текст для очистки", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCycleUnicodeStyle() {
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString()
        val values = UnicodeStyle.values()
        activeUnicodeStyle = values[(activeUnicodeStyle.ordinal + 1) % values.size]
        textEditPanel.setUnicodeStyle(activeUnicodeStyle)
        if (!selected.isNullOrEmpty() && activeUnicodeStyle != UnicodeStyle.NORMAL) {
            ic.commitText(TextPowerTools.applyUnicodeStyle(selected, activeUnicodeStyle), 1)
        }
        Toast.makeText(this, "Стиль шрифта: ${activeUnicodeStyle.shortLabel}", Toast.LENGTH_SHORT).show()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onToggleManualIncognito() {
        prefs.manualIncognito = !prefs.manualIncognito
        updatePrivacyPolicy()
        if (::textEditPanel.isInitialized) textEditPanel.setIncognitoState(prefs.manualIncognito)
        if (::headerBar.isInitialized) headerBar.setIncognitoActive(prefs.manualIncognito)
        val msg = if (prefs.manualIncognito) "Режим инкогнито ВКЛ (буфер и обучение на паузе)" else "Режим инкогнито ВЫКЛ"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onCycleOneHanded() {
        prefs.floatingModeEnabled = false
        prefs.oneHandedMode = when (prefs.oneHandedMode) {
            OneHandedMode.OFF -> OneHandedMode.RIGHT
            OneHandedMode.RIGHT -> OneHandedMode.LEFT
            OneHandedMode.LEFT -> OneHandedMode.OFF
        }
        refreshThemeAndLayout()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onToggleFloating() {
        prefs.floatingModeEnabled = !prefs.floatingModeEnabled
        if (prefs.floatingModeEnabled) prefs.oneHandedMode = OneHandedMode.OFF
        refreshThemeAndLayout()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onEditClose() = switchPanel(ActivePanel.KEYBOARD)

    // ------------------------------------------------------------------
    // Voice Input Callbacks
    // ------------------------------------------------------------------
    private fun currentRamblerStyle(): RamblerStyle =
        RamblerStyle.values().getOrElse(prefs.ramblerStyleIndex) { RamblerStyle.NATURAL }

    private fun refreshRamblerBar(overridePreview: String? = null) {
        if (!::headerBar.isInitialized) return
        val preview = overridePreview ?: ramblerBuffer.toString()
        headerBar.showRamblerBar(
            previewText = preview,
            styleLabel = currentRamblerStyle().label,
            voiceListening = voiceHelper.isCurrentlyListening()
        )
    }

    override fun onRamblerCycleStyle() {
        prefs.ramblerStyleIndex = (prefs.ramblerStyleIndex + 1) % RamblerStyle.values().size
        refreshRamblerBar()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onRamblerToggleMic() {
        if (isSensitiveField) return
        voiceHelper.toggleListening(prefs.activeLanguage)
        refreshRamblerBar()
    }

    override fun onRamblerCommit() {
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        val raw = ramblerBuffer.toString().trim()
        ramblerActive = false
        ramblerBuffer.setLength(0)
        if (::headerBar.isInitialized) headerBar.hideRamblerBar()
        if (raw.isNotEmpty()) {
            val polished = RamblerSpeechProcessor.process(raw, currentRamblerStyle())
            if (polished.isNotEmpty()) {
                currentInputConnection?.commitText("$polished ", 1)
                updateAutoCap()
            }
        }
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onRamblerCancel() {
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        ramblerActive = false
        ramblerBuffer.setLength(0)
        if (::headerBar.isInitialized) headerBar.hideRamblerBar()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    override fun onVoiceClick() {
        if (isSensitiveField) return
        commitComposingIfNeeded(applyAutocorrect = false)
        if (prefs.ramblerVoiceModeEnabled && !liveTranslateActive) {
            if (!ramblerActive) {
                ramblerActive = true
                ramblerBuffer.setLength(0)
                refreshRamblerBar()
            }
            voiceHelper.toggleListening(prefs.activeLanguage)
            refreshRamblerBar()
            return
        }
        voiceHelper.toggleListening(prefs.activeLanguage)
    }

    override fun onVoiceStateChanged(listening: Boolean, statusText: String) {
        if (!::headerBar.isInitialized) return
        if (liveTranslateActive) {
            refreshLiveTranslateBar()
            return
        }
        if (ramblerActive) {
            refreshRamblerBar()
            return
        }
        if (listening) headerBar.showVoiceListening(statusText) else headerBar.showToolbar()
    }

    override fun onVoicePartialResult(text: String) {
        if (!inputActive || isSensitiveField) return
        if (liveTranslateActive) {
            val base = liveTranslateBuffer.toString()
            val preview = if (base.isBlank()) text else "$base $text"
            refreshLiveTranslateBar(overridePreviewSource = preview)
            return
        }
        if (ramblerActive) {
            val base = ramblerBuffer.toString()
            val preview = if (base.isBlank()) text else "$base $text"
            refreshRamblerBar(overridePreview = preview)
            return
        }
        currentInputConnection?.setComposingText(text, 1)
    }

    override fun onVoiceFinalResult(text: String) {
        if (!inputActive || isSensitiveField) return
        if (liveTranslateActive) {
            if (liveTranslateBuffer.isNotEmpty() && !liveTranslateBuffer.endsWith(" ")) {
                liveTranslateBuffer.append(' ')
            }
            liveTranslateBuffer.append(text.trim())
            refreshLiveTranslateBar()
            return
        }
        if (ramblerActive) {
            val cleanSeg = text.trim()
            if (cleanSeg.isNotEmpty()) {
                if (ramblerBuffer.isNotEmpty() && !ramblerBuffer.endsWith(" ")) {
                    ramblerBuffer.append(' ')
                }
                ramblerBuffer.append(cleanSeg)
            }
            refreshRamblerBar()
            return
        }
        val ic = currentInputConnection ?: return
        val formatted = if (shiftState != ShiftState.OFF) {
            text.replaceFirstChar { it.uppercaseChar() }
        } else {
            text
        }
        ic.commitText("$formatted ", 1)
        updateAutoCap()
    }

    override fun onVoicePermissionRequired() {
        Toast.makeText(this, "Разрешите доступ к микрофону в настройках", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
        }
        startActivity(intent)
    }

    override fun onVoiceError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Android Autofill & Inline Suggestions (API 30+ / Android 17)
    // ------------------------------------------------------------------
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val skin = BreenoSkinEngine.resolveSkin(this, prefs.themeMode, prefs.glassTransparency, prefs.glassBlurRadius, prefs.dynamicColorEnabled, prefs.nightScheduleEnabled)
        val density = resources.displayMetrics.density
        val minW = (64f * density).roundToInt()
        val maxW = (240f * density).roundToInt()
        val height = (34f * density).roundToInt()

        // Autofill 1.1.0 exposes these public setters through ViewStyle.Builder, but marks
        // its shared BaseBuilder @RestrictTo. Keep suppression scoped to the style initializer.
        @android.annotation.SuppressLint("RestrictedApi")
        val stylesBundle = UiVersions.newStylesBuilder()
            .addStyle(
                InlineSuggestionUi.newStyleBuilder()
                    .setSingleIconChipStyle(ViewStyle.Builder().setPadding((8 * density).roundToInt(), 0, (8 * density).roundToInt(), 0).build())
                    .setChipStyle(
                        ViewStyle.Builder()
                            .setBackgroundColor(skin.normalKeyBg.normalColor)
                            .setPadding((16 * density).roundToInt(), (4 * density).roundToInt(), (16 * density).roundToInt(), (4 * density).roundToInt())
                            .build()
                    )
                    .setTitleStyle(
                        TextViewStyle.Builder()
                            .setTextColor(skin.keyTextColor)
                            .setTextSize(13f)
                            .build()
                    )
                    .setSubtitleStyle(
                        TextViewStyle.Builder()
                            .setTextColor(skin.keyMarkColor)
                            .setTextSize(11f)
                            .build()
                    )
                    .setStartIconStyle(ImageViewStyle.Builder().build())
                    .setEndIconStyle(ImageViewStyle.Builder().build())
                    .build()
            )
            .build()

        val spec = InlinePresentationSpec.Builder(Size(minW, height), Size(maxW, height))
            .setStyle(stylesBundle)
            .build()

        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(6)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val session = inputSession
        val request = ++autofillRequest
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            if (::headerBar.isInitialized) headerBar.clearAutofillViews()
            return true
        }
        val inflated = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(
                this,
                Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                mainExecutor
            ) { view ->
                if (session != inputSession || request != autofillRequest) return@inflate
                inflated[index] = view
                pending--
                if (pending <= 0 && ::headerBar.isInitialized) {
                    headerBar.showAutofillViews(inflated.filterNotNull())
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // KeyboardView.Listener (with Gboard smart typing features)
    // ------------------------------------------------------------------
    private fun currentCalibrationLayoutKey(): String {
        val orient = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "LAND" else "PORT"
        return "${prefs.activeLanguage}_$orient"
    }

    override fun onLetterTap(key: ComputedKey, tapX: Float, tapY: Float, allKeys: List<ComputedKey>) {
        lastSwipedWordLen = 0
        val allowAdaptive = !isSensitiveField && contextMode == InputContextMode.NORMAL &&
            (currentMode == KeyboardMode.ALPHA_RU || currentMode == KeyboardMode.ALPHA_EN)
        val layoutKey = currentCalibrationLayoutKey()
        val resolved = if (allowAdaptive) {
            touchCalibration.resolveLetterKey(
                tapX = tapX,
                tapY = tapY,
                fallbackKey = key,
                allKeys = allKeys,
                composing = composing.toString(),
                lang = prefs.activeLanguage,
                layoutKey = layoutKey,
                dictEngine = dictEngine
            )
        } else {
            key
        }
        if (allowAdaptive && !isIncognito) {
            touchCalibration.recordTapSample(tapX, tapY, resolved.visualRect, layoutKey)
        }
        val raw = resolved.spec.output
        pendingLetterTapPoint = PointF(tapX, tapY)
        val formatted = if (raw.length == 1 && raw[0].isLetter() && shiftState != ShiftState.OFF) {
            raw.uppercase()
        } else {
            raw
        }
        onCharInput(formatted)
        pendingLetterTapPoint = null
    }

    override fun onSwipeGesture(path: List<PointF>, allKeys: List<ComputedKey>) {
        if (!inputActive || !prefs.swipeTypingEnabled || isSensitiveField || contextMode != InputContextMode.NORMAL) return
        swipeQueue.addLast(PendingSwipe(path.map { PointF(it.x, it.y) }, allKeys.toList(), prefs.activeLanguage, shiftState))
        processNextSwipe()
    }

    private fun processNextSwipe() {
        if (swipeInFlight || swipeQueue.isEmpty()) return
        val gesture = swipeQueue.removeFirst()
        val session = inputSession
        val request = swipeRequest
        val connection = currentInputConnection ?: run { swipeQueue.clear(); return }
        commitComposingIfNeeded(applyAutocorrect = false)
        val beforeSwipe = connection.getTextBeforeCursor(128, 0)?.toString()
        val afterSwipe = connection.getTextAfterCursor(128, 0)?.toString()
        swipeInFlight = true
        autofillExecutor.execute {
            val candidates = runCatching {
                swipeDecoder.decode(gesture.path, gesture.keys, gesture.lang, maxResults = 3)
            }.getOrDefault(emptyList())
            val cased = candidates.map { w ->
                when (gesture.shift) {
                    ShiftState.LOCKED -> w.uppercase()
                    ShiftState.ON -> w.replaceFirstChar { it.uppercaseChar() }
                    ShiftState.OFF -> w
                }
            }
            rootContainer.post {
                if (session != inputSession) return@post
                swipeInFlight = false
                if (!inputActive || request != swipeRequest || isSensitiveField) {
                    processNextSwipe()
                    return@post
                }
                val ic = currentInputConnection
                if (ic !== connection || ic.getTextBeforeCursor(128, 0)?.toString() != beforeSwipe ||
                    ic.getTextAfterCursor(128, 0)?.toString() != afterSwipe || composing.isNotEmpty()) {
                    swipeQueue.clear()
                    return@post
                }
                val best = cased.firstOrNull()
                if (best != null) {
                    val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    val leadSpace = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([\"«") " " else ""
                    if (ic.commitText("$leadSpace$best ", 1)) {
                        lastCommittedSpaceFromSuggestion = true
                        lastSwipedWordLen = best.length + 1
                        lastSwipedText = "$best "
                        if (!isIncognito && prefs.personalLearningEnabled) {
                            prefs.recordUserWordUsage(best)
                            if (lastCommittedWord.isNotEmpty()) dictEngine.learnBigram(lastCommittedWord, best, gesture.lang)
                        }
                        lastCommittedWord = best
                        if (gesture.shift == ShiftState.ON) {
                            shiftState = ShiftState.OFF
                            keyboardView.setShiftState(shiftState)
                        }
                        updateAutoCap()
                        if (prefs.showSuggestions && !isIncognito && ::headerBar.isInitialized) headerBar.showCandidates(cased)
                    }
                }
                processNextSwipe()
            }
        }
    }

    override fun onCharInput(rawText: String) {
        swipeRequest++
        swipeQueue.clear()
        if (currentMode == KeyboardMode.T9_RU && rawText.length > 1) {
            val icT9 = currentInputConnection ?: return
            val now = System.currentTimeMillis()
            if (rawText == t9LastKeyLabel && (now - t9LastTapMs) < 700L) {
                t9CharIdx = (t9CharIdx + 1) % rawText.length
                icT9.deleteSurroundingText(1, 0)
            } else {
                t9LastKeyLabel = rawText
                t9CharIdx = 0
            }
            t9LastTapMs = now
            var ch = rawText[t9CharIdx].toString()
            if (ch[0].isLetter() && shiftState != ShiftState.OFF) ch = ch.uppercase()
            icT9.commitText(ch, 1)
            if (shiftState == ShiftState.ON) {
                shiftState = ShiftState.OFF
                keyboardView.setShiftState(shiftState)
            }
            return
        }
        val text = if (activeUnicodeStyle != UnicodeStyle.NORMAL && !isSensitiveField) {
            TextPowerTools.applyUnicodeStyle(rawText, activeUnicodeStyle)
        } else rawText
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        if (liveTranslateActive) {
            liveTranslateBuffer.append(rawText)
            refreshLiveTranslateBar()
            if (shiftState == ShiftState.ON) {
                shiftState = ShiftState.OFF
                keyboardView.setShiftState(shiftState)
            }
            return
        }
        headerBar.hideQuickClipChip()
        clearAutoCorrectUndo()

        val ic = currentInputConnection ?: return
        val isWordChar = text.length == 1 && (text[0].isLetter() || text[0] == '\'')
        val isSmartPunct = text in setOf(".", ",", "!", "?", ":", ";")

        if (isWordChar && !isSensitiveField && prefs.showSuggestions &&
            (currentMode == KeyboardMode.ALPHA_RU || currentMode == KeyboardMode.ALPHA_EN)
        ) {
            lastCommittedSpaceFromSuggestion = false
            composing.append(text)
            val pt = pendingLetterTapPoint
            if (pt != null) wordTapPoints.add(pt) else wordTapPoints.clear()
            ic.setComposingText(composing.toString(), 1)
            updateSuggestions()
        } else {
            // Gboard-style smart punctuation: removes auto-space before punctuation and adds space after
            if (isSmartPunct && composing.isEmpty() && lastCommittedSpaceFromSuggestion) {
                val before = ic.getTextBeforeCursor(1, 0)?.toString()
                if (before == " ") {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText("$text ", 1)
                    lastCommittedSpaceFromSuggestion = false
                    updateAutoCap()
                    return
                }
            }
            lastCommittedSpaceFromSuggestion = false
            commitComposingIfNeeded(applyAutocorrect = isSmartPunct)
            val pairClose = if (prefs.smartPairsEnabled && !isSensitiveField) {
                when (text) {
                    "«" -> "»"
                    "(" -> ")"
                    "[" -> "]"
                    else -> null
                }
            } else null
            if (pairClose != null) {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrEmpty()) {
                    ic.commitText("$text$sel$pairClose", 1)
                } else {
                    ic.commitText("$text$pairClose", 1)
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                }
            } else {
                val typoHit = if (prefs.autoTypographyEnabled && !isSensitiveField && !isTerminalAppProfile) {
                    val beforeTypo = ic.getTextBeforeCursor(4, 0)?.toString() ?: ""
                    TextPowerTools.applyAutoTypography(beforeTypo, text, prefs.activeLanguage)
                } else null
                if (typoHit != null) {
                    if (typoHit.first > 0) ic.deleteSurroundingText(typoHit.first, 0)
                    ic.commitText(typoHit.second, 1)
                } else {
                    val addPunctSpace = prefs.autoSpaceAfterPunctuation && isSmartPunct &&
                        contextMode == InputContextMode.NORMAL && !isSensitiveField && !isTerminalAppProfile
                    ic.commitText(if (addPunctSpace) "$text " else text, 1)
                }
                if (prefs.autoSwitchAfterApostrophe && text == "'" &&
                    (currentMode == KeyboardMode.SYMBOLS_1 || currentMode == KeyboardMode.SYMBOLS_2)
                ) {
                    currentMode = if (prefs.activeLanguage == "EN") KeyboardMode.ALPHA_EN else if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU
                    refreshThemeAndLayout()
                }
            }
            if (text == "=" && (prefs.inlineCalculatorEnabled || prefs.smartInlineMathEnabled) && !isSensitiveField && ::headerBar.isInitialized) {
                val tail = ic.getTextBeforeCursor(44, 0)?.toString() ?: ""
                val smartRes = if (prefs.smartInlineMathEnabled) {
                    val token = tail.trimEnd().substringAfterLast(' ').substringAfterLast('\n')
                    TextPowerTools.tryEvalSmartMath(token)
                } else null
                if (smartRes != null) {
                    headerBar.showCandidates(listOf(smartRes))
                } else if (prefs.inlineCalculatorEnabled) {
                    val calc = InlineCalculator.evaluateTail(tail)
                if (calc != null) {
                    val cands = if (calc.formattedResult != calc.plainResult) {
                        listOf(calc.formattedResult, calc.plainResult)
                    } else {
                        listOf(calc.plainResult)
                    }
                    headerBar.showCandidates(cands)
                    }
                }
            }
        }

        if (prefs.typingStatsEnabled && !isSensitiveField && !isIncognito) {
            val nowStat = System.currentTimeMillis()
            val dt = if (lastTypingEventMs > 0L) (nowStat - lastTypingEventMs).coerceIn(0L, 4000L) else 0L
            lastTypingEventMs = nowStat
            prefs.recordTypingStat(charsDelta = text.length, wordsDelta = 0, backspacesDelta = 0, elapsedMs = dt)
        }
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            keyboardView.setShiftState(shiftState)
        }
        updateAutoCap()
    }

    override fun onShiftPressed() {
        val ic = currentInputConnection
        // Gboard feature: tap Shift with selected text cycles lowercase -> Title -> UPPERCASE!
        val selected = if (prefs.smartShiftCaseEnabled) ic?.getSelectedText(0)?.toString() else null
        if (!selected.isNullOrEmpty() && selected.any { it.isLetter() }) {
            val transformed = when {
                selected == selected.lowercase() -> selected.replaceFirstChar { it.uppercaseChar() }
                selected == selected.uppercase() -> selected.lowercase()
                else -> selected.uppercase()
            }
            ic.commitText(transformed, 1)
            return
        }

        val now = System.currentTimeMillis()
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> if (now - lastShiftTapMs < 350L) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTapMs = now
        keyboardView.setShiftState(shiftState)
    }

    override fun onDeleteChar() {
        swipeRequest++
        swipeQueue.clear()
        t9LastKeyLabel = ""
        if (voiceHelper.isCurrentlyListening()) voiceHelper.stopListening()
        if (liveTranslateActive) {
            if (liveTranslateBuffer.isNotEmpty()) {
                liveTranslateBuffer.deleteCharAt(liveTranslateBuffer.length - 1)
                refreshLiveTranslateBar()
            } else {
                onTranslateClose()
            }
            return
        }
        lastCommittedSpaceFromSuggestion = false
        val ic = currentInputConnection ?: return

        // Swipe v2: 1-tap Backspace immediately after a swiped word deletes the whole swiped word!
        if (composing.isEmpty() && lastSwipedWordLen > 0 && ic.getTextBeforeCursor(lastSwipedWordLen, 0)?.toString() == lastSwipedText) {
            val delLen = lastSwipedWordLen
            lastSwipedWordLen = 0
            val deleted = ic.getTextBeforeCursor(delLen, 0)?.toString() ?: ""
            ic.deleteSurroundingText(delLen, 0)
            if (prefs.undoDeleteEnabled && !isSensitiveField && deleted.isNotBlank() && ::headerBar.isInitialized) {
                headerBar.showUndoDeleteChip(deleted)
            }
            updateAutoCap()
            return
        }

        // Gboard feature: Backspace immediately after autocorrect reverts to original word!
        val origWord = lastAutoCorrectedFrom
        val corrWord = lastAutoCorrectedTo
        if (prefs.undoAutoCorrectOnBackspaceEnabled && composing.isEmpty() && origWord != null && corrWord != null) {
            val expected = "$corrWord "
            val before = ic.getTextBeforeCursor(expected.length, 0)?.toString()
            if (before == expected) {
                ic.deleteSurroundingText(expected.length, 0)
                ic.commitText("$origWord ", 1)
                if (!isIncognito && !isSensitiveField && prefs.personalLearningEnabled) {
                    personalMemory.recordRejectedAutocorrect(origWord)
                }
                clearAutoCorrectUndo()
                return
            }
            clearAutoCorrectUndo()
        }

        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1); if (wordTapPoints.isNotEmpty()) wordTapPoints.removeAt(wordTapPoints.lastIndex)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
                headerBar.showToolbar()
            } else {
                ic.setComposingText(composing.toString(), 1)
                updateSuggestions()
            }
        } else {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
                if (before.isNotEmpty()) {
                    val boundary = android.icu.text.BreakIterator.getCharacterInstance()
                    boundary.setText(before)
                    val start = boundary.preceding(before.length)
                    if (start >= 0) ic.deleteSurroundingText(before.length - start, 0)
                }
            }
        }
        updateAutoCap()
    }

    override fun onDeleteSwipePreview(steps: Int) {
        if (!::headerBar.isInitialized) return
        if (steps <= 0) {
            updateSuggestions()
        } else {
            headerBar.showCandidates(listOf("⌫ Удалить $steps сл."))
        }
    }

    override fun onDeleteSwipeCommit(steps: Int) {
        if (steps <= 0) {
            updateSuggestions()
            return
        }
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(0); wordTapPoints.clear()
            ic.commitText("", 1)
            headerBar.showToolbar()
            updateAutoCap()
            return
        }
        val before = ic.getTextBeforeCursor(120, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var idx = before.length - 1
        repeat(steps) {
            while (idx >= 0 && before[idx].isWhitespace()) idx--
            while (idx >= 0 && !before[idx].isWhitespace()) idx--
        }
        val delCount = before.length - 1 - idx
        if (delCount > 0) {
            val deleted = before.substring(before.length - delCount)
            ic.deleteSurroundingText(delCount, 0)
            if (prefs.undoDeleteEnabled && !isSensitiveField && deleted.isNotBlank() && ::headerBar.isInitialized) {
                headerBar.showUndoDeleteChip(deleted)
            }
        }
        updateAutoCap()
    }

    override fun onDeleteWord() {
        clearAutoCorrectUndo()
        lastCommittedSpaceFromSuggestion = false
        lastCommittedWord = ""
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(0); wordTapPoints.clear()
            ic.commitText("", 1)
            headerBar.showToolbar()
            updateAutoCap()
            return
        }
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var i = before.length - 1
        while (i >= 0 && before[i].isWhitespace()) i--
        while (i >= 0 && !before[i].isWhitespace()) i--
        val deleteCount = before.length - 1 - i
        if (deleteCount > 0) {
            val deletedChunk = before.substring(before.length - deleteCount)
            ic.deleteSurroundingText(deleteCount, 0)
            if (prefs.undoDeleteEnabled && !isSensitiveField && deletedChunk.isNotBlank() && ::headerBar.isInitialized) {
                headerBar.showUndoDeleteChip(deletedChunk)
            }
        }
        updateAutoCap()
    }

    override fun onSpacePressed() {
        swipeRequest++
        swipeQueue.clear()
        t9LastKeyLabel = ""
        if (liveTranslateActive) {
            liveTranslateBuffer.append(' ')
            refreshLiveTranslateBar()
            return
        }
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        lastCommittedSpaceFromSuggestion = false

        if (prefs.doubleSpacePeriod && composing.isEmpty() && (now - lastSpaceTapMs < 360L)) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTapMs = 0L
                updateAutoCap()
                return
            }
        }

        val lastWord = composing.toString().ifEmpty { extractLastWordBeforeCursor(ic) }
        commitComposingIfNeeded(applyAutocorrect = prefs.autoCorrectOnSpace && contextMode == InputContextMode.NORMAL)
        ic.commitText(" ", 1)
        lastSpaceTapMs = now
        val committedWord = extractLastWordBeforeCursor(ic).ifEmpty { lastWord }
        updateAutoCap()
        showNextWordPredictions(committedWord)
    }

    override fun onEnterPressed() {
        swipeRequest++
        swipeQueue.clear()
        t9LastKeyLabel = ""
        if (liveTranslateActive) {
            onTranslateCommit()
            return
        }
        clearAutoCorrectUndo()
        lastCommittedSpaceFromSuggestion = false
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)

        val editor = currentInputEditorInfo
        val actionId = (editor?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val isMultiLine = ((editor?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((editor?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        if (!isMultiLine && !noEnterAction && actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (prefs.safeEnterSendEnabled && actionId == EditorInfo.IME_ACTION_SEND) {
                val beforeSend = ic.getTextBeforeCursor(80, 0)?.toString()?.trim() ?: ""
                val wordCount = beforeSend.split(Regex("\\s+")).count { it.isNotBlank() }
                val nowTap = System.currentTimeMillis()
                if (wordCount > 1 && (nowTap - lastSafeEnterTapMs) > 950L) {
                    lastSafeEnterTapMs = nowTap
                    Toast.makeText(this, "Нажмите Enter ещё раз для отправки", Toast.LENGTH_SHORT).show()
                    return
                }
                lastSafeEnterTapMs = 0L
            }
            ic.performEditorAction(actionId)
        } else {
            val listAct = if (prefs.smartListsEnabled && !isSensitiveField) {
                val beforeLine = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
                TextPowerTools.computeSmartListContinuation(beforeLine)
            } else null
            if (listAct != null) {
                if (listAct.deleteCharsBefore > 0) ic.deleteSurroundingText(listAct.deleteCharsBefore, 0)
                if (listAct.insertText.isNotEmpty()) ic.commitText(listAct.insertText, 1)
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
        updateAutoCap()
    }

    override fun onOpenEmojiFromKey() = onToggleEmoji()

    override fun onCurrentInputMethodSubtypeChanged(subtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val language = subtype?.languageTag?.ifBlank { subtype.locale }.orEmpty().substringBefore('-').substringBefore('_').lowercase()
        val target = when (language) { "ru" -> "RU"; "en" -> "EN"; else -> return }
        if (!::prefs.isInitialized || prefs.activeLanguage == target) return
        if (inputActive) commitComposingIfNeeded(applyAutocorrect = false)
        swipeRequest++
        swipeQueue.clear()
        prefs.activeLanguage = target
        currentMode = if (target == "RU") {
            if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU
        } else KeyboardMode.ALPHA_EN
        if (::keyboardView.isInitialized) { refreshThemeAndLayout(); updateAutoCap() }
    }

    override fun onLanguageSwitch() {
        swipeRequest++
        swipeQueue.clear()
        commitComposingIfNeeded(applyAutocorrect = false)
        val nextLang = if (prefs.activeLanguage == "RU") "EN" else "RU"
        prefs.activeLanguage = nextLang
        currentMode = if (nextLang == "RU") (if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU) else KeyboardMode.ALPHA_EN
        switchPanel(ActivePanel.KEYBOARD)
        refreshThemeAndLayout()
        updateAutoCap()
    }

    override fun onModeChange(mode: KeyboardMode) {
        swipeRequest++
        swipeQueue.clear()
        commitComposingIfNeeded(applyAutocorrect = false)
        currentMode = if (mode == KeyboardMode.ALPHA_RU) {
            if (prefs.activeLanguage == "EN") KeyboardMode.ALPHA_EN else if (prefs.t9ModeEnabled) KeyboardMode.T9_RU else KeyboardMode.ALPHA_RU
        } else {
            mode
        }
        switchPanel(ActivePanel.KEYBOARD)
        refreshThemeAndLayout()
    }

    override fun onCursorMove(deltaChars: Int) {
        swipeRequest++
        swipeQueue.clear()
        lastSwipedWordLen = 0
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val keyCode = if (deltaChars > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(deltaChars)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    override fun onCursorMoveVertical(deltaLines: Int) {
        swipeRequest++
        swipeQueue.clear()
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(applyAutocorrect = false)
        val keyCode = if (deltaLines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        repeat(kotlin.math.abs(deltaLines)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    override fun onKeyFeedback(role: KeyRole) {
        if (prefs.hapticEnabled) {
            val baseMs = prefs.hapticDurationMs.toLong().coerceIn(5L, 40L)
            val durMs = if (prefs.differentialHapticsEnabled) {
                when (role) {
                    KeyRole.SPACE -> baseMs
                    KeyRole.NORMAL -> (baseMs * 0.72f).roundToInt().toLong().coerceIn(5L, 35L)
                    KeyRole.FUNCTION -> (baseMs * 0.95f).roundToInt().toLong().coerceIn(6L, 38L)
                    KeyRole.ENTER -> (baseMs * 1.25f).roundToInt().toLong().coerceIn(8L, 45L)
                }
            } else baseMs
            val effect = if (!prefs.differentialHapticsEnabled && durMs == 16L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                val amp = if (prefs.differentialHapticsEnabled) {
                    when (role) {
                        KeyRole.SPACE -> 140
                        KeyRole.NORMAL -> 115
                        KeyRole.FUNCTION -> 165
                        KeyRole.ENTER -> 215
                    }
                } else VibrationEffect.DEFAULT_AMPLITUDE
                VibrationEffect.createOneShot(durMs, amp)
            }
            vibrator?.vibrate(effect)
        }
        if (prefs.soundEnabled && !(isSensitiveField && prefs.privacyShieldEnabled)) {
            val sid = when (role) {
                KeyRole.FUNCTION, KeyRole.ENTER -> soundFuncId
                else -> soundStandardId
            }
            val baseVol = (prefs.soundVolumePercent / 100f).coerceIn(0.15f, 1.0f)
            val (volMul, rate) = when (prefs.soundProfile) {
                1 -> 0.55f to 0.78f   // Тихий клик
                2 -> 1.15f to 1.28f   // Механика
                else -> 1.0f to 1.0f  // Классический Breeno
            }
            val finalVol = (baseVol * volMul).coerceIn(0.1f, 1.0f)
            if (sid != 0) soundPool?.play(sid, finalVol, finalVol, 1, 0, rate)
        }
    }

    // ------------------------------------------------------------------
    // HeaderBarView.Listener
    // ------------------------------------------------------------------
    override fun onOpenSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onToggleEmoji() {
        commitComposingIfNeeded(applyAutocorrect = false)
        switchPanel(if (activePanel == ActivePanel.EMOJI) ActivePanel.KEYBOARD else ActivePanel.EMOJI)
    }

    override fun onToggleClipboard() {
        commitComposingIfNeeded(applyAutocorrect = false)
        switchPanel(if (activePanel == ActivePanel.CLIPBOARD) ActivePanel.KEYBOARD else ActivePanel.CLIPBOARD)
    }

    override fun onToggleTextEdit() {
        commitComposingIfNeeded(applyAutocorrect = false)
        switchPanel(if (activePanel == ActivePanel.TEXT_EDIT) ActivePanel.KEYBOARD else ActivePanel.TEXT_EDIT)
    }

    override fun onToggleMenu() {
        commitComposingIfNeeded(applyAutocorrect = false)
        switchPanel(if (activePanel == ActivePanel.MENU) ActivePanel.KEYBOARD else ActivePanel.MENU)
    }

    override fun onMenuClose() = switchPanel(ActivePanel.KEYBOARD)

    override fun onMenuAction(action: BreenoMenuPanelView.MenuAction) {
        onKeyFeedback(KeyRole.FUNCTION)
        when (action) {
            BreenoMenuPanelView.MenuAction.EMOJI -> switchPanel(ActivePanel.EMOJI)
            BreenoMenuPanelView.MenuAction.THEME -> openSettingsExtra(SettingsActivity.EXTRA_OPEN_APPEARANCE_THEME)
            BreenoMenuPanelView.MenuAction.TEXT_EDIT -> switchPanel(ActivePanel.TEXT_EDIT)
            BreenoMenuPanelView.MenuAction.RESIZE -> openSettingsExtra(SettingsActivity.EXTRA_OPEN_APPEARANCE_LAYOUT)
            BreenoMenuPanelView.MenuAction.CLIPBOARD -> switchPanel(ActivePanel.CLIPBOARD)
            BreenoMenuPanelView.MenuAction.TEMPLATES -> openSettingsExtra(SettingsActivity.EXTRA_OPEN_TEMPLATES)
            BreenoMenuPanelView.MenuAction.ONE_HANDED -> {
                onCycleOneHanded()
                switchPanel(ActivePanel.KEYBOARD)
            }
            BreenoMenuPanelView.MenuAction.FLOATING -> {
                onToggleFloating()
                switchPanel(ActivePanel.KEYBOARD)
            }
            BreenoMenuPanelView.MenuAction.NEXT_LANGUAGE -> onLanguageSwitch()
            BreenoMenuPanelView.MenuAction.TRANSLATE -> {
                onQuickTranslateSelected()
                switchPanel(ActivePanel.KEYBOARD)
            }
            BreenoMenuPanelView.MenuAction.INCOGNITO -> {
                onToggleManualIncognito()
                menuPanel.updateStates(
                    incognito = prefs.manualIncognito,
                    oneHanded = prefs.oneHandedMode != OneHandedMode.OFF,
                    floating = prefs.floatingModeEnabled
                )
            }
            BreenoMenuPanelView.MenuAction.AUTOFILL -> openSettingsExtra(SettingsActivity.EXTRA_OPEN_AUTOFILL_MEMORY)
            BreenoMenuPanelView.MenuAction.CUSTOMIZE_TOOLBAR -> openSettingsExtra(SettingsActivity.EXTRA_OPEN_APPEARANCE_TOOLBAR)
        }
    }

    private fun openSettingsExtra(extraKey: String) {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(extraKey, true)
        }
        startActivity(intent)
        switchPanel(ActivePanel.KEYBOARD)
    }

    override fun onQuickClipPaste(text: String) {
        onClipPaste(text)
    }

    override fun onQuickImagePaste(suggestion: QuickImageSuggestion) {
        if (isSensitiveField) {
            headerBar.hideQuickClipChip()
            return
        }
        clipboardManager.dismissImageSuggestion(suggestion)
        val existingEntry = suggestion.clipEntry
        if (existingEntry != null) {
            onClipImagePaste(existingEntry)
            return
        }
        val ssInfo = suggestion.screenshotInfo ?: return
        clipboardManager.importScreenshotAndGetEntryAsync(ssInfo) { entry ->
            if (entry != null) {
                onClipImagePaste(entry)
            } else {
                Toast.makeText(this, "Не удалось прочитать скриншот", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onQuickImageDismiss(suggestion: QuickImageSuggestion) {
        clipboardManager.dismissImageSuggestion(suggestion)
    }

    override fun onQuickSmartPaste(suggestion: QuickSmartSuggestion) {
        clipboardManager.dismissSmartSuggestion(suggestion.contentHash)
        val direct = suggestion.directFragment
        if (direct != null) {
            onClipFragmentPaste(direct)
        } else {
            switchPanel(ActivePanel.CLIPBOARD)
            clipboardPanel.openSmartFragmentsForEntry(suggestion.entry, suggestion.entities)
        }
    }

    override fun onQuickSmartDismiss(suggestion: QuickSmartSuggestion) {
        clipboardManager.dismissSmartSuggestion(suggestion.contentHash)
    }

    override fun onCollapseKeyboard() {
        requestHideSelf(0)
    }

    private fun commitTextWithPipeCursor(ic: android.view.inputmethod.InputConnection, rawText: String) {
        val pipeIdx = rawText.indexOf('|')
        if (pipeIdx < 0) {
            ic.commitText(rawText, 1)
            return
        }
        val beforePipe = rawText.substring(0, pipeIdx)
        val afterPipe = rawText.substring(pipeIdx + 1)
        ic.commitText(beforePipe + afterPipe, 1)
        if (afterPipe.isNotEmpty()) {
            onCursorMove(-afterPipe.length)
        }
    }

    override fun onCandidateClick(word: String) {
        val ic = currentInputConnection ?: return
        if (word.startsWith("⌫ Удалить ")) {
            updateSuggestions()
            return
        }
        val sj = lastSplitJoinCandidate
        if (sj != null && sj.first == word && sj.second && composing.isNotEmpty()) {
            // Space-Join repair: delete previous word + space + composing, then commit joined word
            val prev = extractPreviousTwoWordsBeforeComposing().second
            if (prev.isNotEmpty()) {
                ic.finishComposingText()
                ic.deleteSurroundingText(prev.length + 1 + composing.length, 0)
                composing.setLength(0); wordTapPoints.clear()
                ic.commitText("$word ", 1)
                lastCommittedSpaceFromSuggestion = true
                lastSplitJoinCandidate = null
                updateAutoCap()
                showNextWordPredictions(word)
                return
            }
        }
        lastSplitJoinCandidate = null
        if (composing.isEmpty() && lastSwipedWordLen > 0 && ic.getTextBeforeCursor(lastSwipedWordLen, 0)?.toString() == lastSwipedText) {
            ic.deleteSurroundingText(lastSwipedWordLen, 0)
            lastSwipedWordLen = 0
        }
        val isDomainChip = word.startsWith("@") || word.startsWith(".") || word.startsWith("https://")
        if (word.contains('|')) {
            commitTextWithPipeCursor(ic, word)
        } else {
            ic.commitText(if (isDomainChip) word else "$word ", 1)
        }
        if (prefs.typingStatsEnabled && !isSensitiveField && !isIncognito && !isDomainChip) {
            prefs.recordTypingStat(charsDelta = 0, wordsDelta = 1, backspacesDelta = 0, elapsedMs = 0L)
        }
        lastCommittedSpaceFromSuggestion = !isDomainChip
        if (!isSensitiveField && !isIncognito && prefs.personalLearningEnabled && !isDomainChip) {
            prefs.addUserWord(word)
            personalMemory.recordCommittedPhrase(prevCommittedWord, lastCommittedWord, word)
            if (lastCommittedWord.isNotEmpty()) {
                dictEngine.learnBigram(lastCommittedWord, word, prefs.activeLanguage)
            }
        }
        if (!isDomainChip) {
            prevCommittedWord = lastCommittedWord
            lastCommittedWord = word
        }
        composing.setLength(0); wordTapPoints.clear()
        updateAutoCap()
        showNextWordPredictions(word)
    }

    private fun extractLastWordBeforeCursor(ic: android.view.inputmethod.InputConnection): String {
        val before = ic.getTextBeforeCursor(24, 0)?.toString()?.trimEnd() ?: return ""
        val idx = before.lastIndexOfAny(charArrayOf(' ', '\n', '.', ',', '!', '?'))
        return if (idx >= 0) before.substring(idx + 1) else before
    }

    private fun showNextWordPredictions(previousWord: String) {
        if (isIncognito || !prefs.showSuggestions || !prefs.nextWordPrediction || previousWord.isEmpty()) {
            headerBar.showToolbar()
            return
        }
        val memNext = personalMemory.predictNextWords(prevCommittedWord, previousWord, maxCount = 3)
        val dictNext = dictEngine.getNextWordSuggestions(previousWord, prefs.activeLanguage)
        val merged = (memNext + dictNext).distinct().take(3)
        if (merged.isNotEmpty()) headerBar.showCandidates(merged) else headerBar.showToolbar()
    }

    override fun onCandidateLongClick(word: String) {
        if (isSensitiveField) return
        prefs.blockWord(word)
        Toast.makeText(this, "Слово «$word» скрыто из подсказок", Toast.LENGTH_SHORT).show()
        updateSuggestions()
    }

    override fun onQuickToolbarSlot(slotId: String) {
        when (slotId) {
            "TRANSLATE" -> onQuickTranslateSelected()
            "INCOGNITO" -> onToggleManualIncognito()
            "ONE_HAND" -> onCycleOneHanded()
        }
    }

    override fun onUndoDeleteClick(deletedText: String) {
        currentInputConnection?.commitText(deletedText, 1)
        onKeyFeedback(KeyRole.NORMAL)
    }

    override fun onLocalAutofillClick(value: String) {
        commitComposingIfNeeded(applyAutocorrect = false)
        currentInputConnection?.commitText(value, 1)
        if (OtpMemoryBridge.peekLatest()?.first == value) {
            OtpMemoryBridge.clear()
        }
        onKeyFeedback(KeyRole.NORMAL)
    }

    override fun onPasswordGenClick() {
        val pwd = TextPowerTools.generateSecurePassword(16)
        val ic = currentInputConnection ?: return
        if (!prefs.addVaultItem("Пароль: $pwd")) {
            Toast.makeText(this, "Не удалось зашифровать пароль. Повторите позже", Toast.LENGTH_SHORT).show()
            return
        }
        val inserted = ic.commitText(pwd, 1)
        Toast.makeText(this, if (inserted) "Пароль вставлен и сохранён в сейф" else "Пароль сохранён в сейф. Поле отклонило вставку", Toast.LENGTH_SHORT).show()
        onKeyFeedback(KeyRole.FUNCTION)
    }

    private fun showContextDomainChipsIfRelevant() {
        if (!::headerBar.isInitialized || isSensitiveField || composing.isNotEmpty()) return
        val allDom = prefs.customQuickDomainsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        when (contextMode) {
            InputContextMode.EMAIL -> {
                val emailDoms = allDom.filter { it.startsWith("@") }.ifEmpty { listOf("@gmail.com", "@yandex.ru", "@mail.ru") }
                headerBar.showCandidates(emailDoms.take(3))
            }
            InputContextMode.URI -> {
                val urlDoms = allDom.filter { !it.startsWith("@") }.ifEmpty { listOf(".ru", ".com", ".рф") }
                headerBar.showCandidates(urlDoms.take(3))
            }
            else -> {
                if (prefs.smartClipboardReplyEnabled && !isIncognito) {
                    val lastClip = clipboardManager.getPendingQuickSuggestion() ?: ""
                    if (lastClip.isNotBlank()) {
                        val reply = TextPowerTools.suggestSmartReplyForClipboard(
                            copiedText = lastClip,
                            savedEmail = prefs.autofillEmail,
                            savedPhone = prefs.autofillPhone,
                            savedAddress = prefs.autofillAddress
                        )
                        if (reply != null) {
                            headerBar.showLocalAutofillChip("✨", reply.first, reply.second)
                            return
                        }
                    }
                }
                val pinnedSnippets = prefs.pinnedTopSnippetsCsv.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (pinnedSnippets.isNotEmpty()) {
                    headerBar.showCandidates(pinnedSnippets.take(2))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private class CenterCropWallpaperDrawable(
        private val bitmap: Bitmap,
        private val dimAlpha: Int,
        private val radii: FloatArray,
        private val borderColor: Int,
        private val borderWidthPx: Float
    ) : Drawable() {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(dimAlpha, 12, 14, 18)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidthPx
        }
        private val clipPath = Path()
        private val dstRectF = RectF()
        private val srcRect = Rect()

        // Critical: return -1 so View.onMeasure NEVER expands keyboard height to the photo's pixel height!
        override fun getIntrinsicWidth(): Int = -1
        override fun getIntrinsicHeight(): Int = -1

        override fun draw(canvas: Canvas) {
            val b = bounds
            val vw = b.width()
            val vh = b.height()
            if (vw <= 0 || vh <= 0 || bitmap.isRecycled) return

            dstRectF.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(dstRectF, radii, Path.Direction.CW)

            // True CENTER_CROP source rect (zero stretching or aspect distortion)
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val viewAspect = vw.toFloat() / vh.toFloat()
            val bmpAspect = bw / bh
            if (bmpAspect > viewAspect) {
                val cropW = bh * viewAspect
                val left = ((bw - cropW) / 2f).roundToInt().coerceAtLeast(0)
                srcRect.set(left, 0, (left + cropW.roundToInt()).coerceAtMost(bitmap.width), bitmap.height)
            } else {
                val cropH = bw / viewAspect
                val top = ((bh - cropH) / 2f).roundToInt().coerceAtLeast(0)
                srcRect.set(0, top, bitmap.width, (top + cropH.roundToInt()).coerceAtMost(bitmap.height))
            }

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bitmap, srcRect, b, bmpPaint)
            canvas.drawRect(b, scrimPaint)
            if (borderColor != Color.TRANSPARENT && borderWidthPx > 0f) {
                canvas.drawPath(clipPath, borderPaint)
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            bmpPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bmpPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun loadCachedWallpaperBitmap(): Bitmap? {
        val wp = prefs.wallpaperPath
        if (wp.isBlank()) {
            cachedWallpaperPath = ""
            cachedWallpaperBitmap = null
            return null
        }
        val f = File(wp)
        if (!f.exists()) {
            cachedWallpaperPath = ""
            cachedWallpaperBitmap = null
            return null
        }
        val key = "$wp:${f.lastModified()}"
        if (cachedWallpaperPath == key && cachedWallpaperBitmap?.isRecycled == false) {
            return cachedWallpaperBitmap
        }
        return try {
            val bmp = BitmapFactory.decodeFile(wp)
            cachedWallpaperPath = key
            cachedWallpaperBitmap = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCardWallpaperOrSolidDrawable(cornerRadiusPx: Float): Drawable {
        val d = resources.displayMetrics.density
        val borderColor = if (currentSkin.isDark) Color.argb(64, 255, 255, 255) else Color.argb(45, 0, 0, 0)
        val bmp = loadCachedWallpaperBitmap()
        if (bmp != null) {
            val dimAlpha = ((prefs.wallpaperDimPercent / 100f) * 255f).roundToInt().coerceIn(40, 225)
            val radii = floatArrayOf(
                cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx,
                cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx
            )
            return CenterCropWallpaperDrawable(bmp, dimAlpha, radii, borderColor, 1f * d)
        }
        return GradientDrawable().apply {
            cornerRadius = cornerRadiusPx
            setColor(currentSkin.common.keyboardBackgroundColor)
            setStroke((1f * d).roundToInt().coerceAtLeast(1), borderColor)
        }
    }

    private fun applyWallpaperBackgroundIfSet(): Boolean {
        if (prefs.floatingModeEnabled) return false
        val bmp = loadCachedWallpaperBitmap() ?: return false
        val d = resources.displayMetrics.density
        val dimAlpha = ((prefs.wallpaperDimPercent / 100f) * 255f).roundToInt().coerceIn(40, 225)
        val topR = if (currentSkin.isGlassBackground) 20f * d else 0f
        val radii = floatArrayOf(topR, topR, topR, topR, 0f, 0f, 0f, 0f)
        rootContainer.background = CenterCropWallpaperDrawable(bmp, dimAlpha, radii, Color.TRANSPARENT, 0f)
        return true
    }

    private fun refreshThemeAndLayout() {
        val resolvedSkin = BreenoSkinEngine.resolveSkin(
            this,
            prefs.themeMode,
            prefs.glassTransparency,
            prefs.glassBlurRadius,
            prefs.dynamicColorEnabled,
            prefs.nightScheduleEnabled
        )
        val rawSkin = BreenoSkinEngine.applyCustomKeyColors(
            skin = resolvedSkin,
            normalColor = prefs.customNormalKeyColor,
            shiftColor = prefs.customShiftKeyColor,
            deleteColor = prefs.customDeleteKeyColor,
            langColor = prefs.customLangKeyColor,
            enterColor = prefs.customEnterKeyColor
        )
        val contrasted = if (prefs.wallpaperPath.isNotBlank() && prefs.wallpaperAutoContrast && prefs.wallpaperIsBright && prefs.wallpaperDimPercent < 55) {
            rawSkin.copy(
                keyTextColor = Color.parseColor("#F2101216"),
                funcKeyTextColor = Color.parseColor("#E6101216")
            )
        } else {
            rawSkin
        }
        val radiusOverride = if (prefs.keyCornerRadiusDp == 9) contrasted.keyRadiusDp else prefs.keyCornerRadiusDp.toFloat()
        currentSkin = contrasted.copy(
            keyRadiusDp = radiusOverride,
            keyShadowEnabled = prefs.keyShadowEnabled
        )
        applyWindowGlassEffect(currentSkin)
        val hasWallpaper = applyWallpaperBackgroundIfSet()
        if (!hasWallpaper) {
            if (prefs.floatingModeEnabled || currentSkin.isGlassBackground) {
                rootContainer.background = null
            } else {
                rootContainer.setBackgroundColor(currentSkin.common.keyboardBackgroundColor)
            }
        }
        headerBar.applySkin(currentSkin)
        if (::arrowNavBar.isInitialized) {
            val showArrows = (prefs.arrowBarEnabled || isTerminalAppProfile) && activePanel == ActivePanel.KEYBOARD
            arrowNavBar.visibility = if (showArrows) View.VISIBLE else View.GONE
            for (ci in 0 until arrowNavBar.childCount) {
                (arrowNavBar.getChildAt(ci) as? TextView)?.setTextColor(currentSkin.keyTextColor)
            }
        }
        headerBar.setToolbarSlots(prefs.getToolbarSlots())
        headerBar.setIncognitoActive(prefs.manualIncognito)
        headerBar.visibility = if (prefs.showSuggestionsBar) View.VISIBLE else View.GONE
        headerBar.setVoiceButtonVisible(prefs.voiceInputEnabled && !isSensitiveField)
        if (keyboardView.heightScalePercent != prefs.keyboardHeightPercent) {
            keyboardView.heightScalePercent = prefs.keyboardHeightPercent
            keyboardView.requestLayout()
        }
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val effHeight = if (prefs.floatingModeEnabled) prefs.floatingHeightPercent else if (isLandscape) prefs.landscapeHeightPercent else prefs.keyboardHeightPercent
        if (keyboardView.heightScalePercent != effHeight) {
            keyboardView.heightScalePercent = effHeight
            keyboardView.requestLayout()
        }
        keyboardView.compactLandscape = prefs.landscapeMode == 0
        keyboardView.keyFontScalePercent = prefs.keyFontScalePercent
        keyboardView.longPressSymbolsEnabled = prefs.longPressSymbolsEnabled
        keyboardView.interactiveDeleteSwipeEnabled = prefs.interactiveDeleteSwipeEnabled
        keyboardView.dynamicHitboxEnabled = prefs.dynamicHitboxEnabled
        keyboardView.keyPressHighlightEnabled = prefs.keyPressHighlightEnabled
        keyboardView.keyPressHighlightColor = prefs.keyPressHighlightColor
        keyboardView.quickSymbolSlideEnabled = prefs.quickSymbolSlideEnabled
        keyboardView.flickDownSymbolsEnabled = prefs.flickDownSymbolsEnabled
        keyboardView.touchSensitivity = prefs.touchSensitivity
        keyboardView.longPressDelayMs = prefs.longPressDelayMs
        keyboardView.edgeRejectionDp = prefs.edgeRejectionDp
        keyboardView.applySkin(currentSkin)
        val effectiveNumRow = prefs.showNumberRow || isTerminalAppProfile || (isSensitiveField && prefs.showNumberRowOnPassword)
        val isWideTablet = resources.configuration.smallestScreenWidthDp >= 600
        val splitGap = if ((isLandscape && prefs.landscapeMode == 1) || (isWideTablet && prefs.landscapeMode != 2)) 0.14f else 0f
        keyboardView.setLayout(
            mode = currentMode,
            contextMode = contextMode,
            separateHardSign = prefs.separateHardSign,
            separateYo = prefs.separateYoKey,
            showNumberRow = effectiveNumRow,
            lastAlphaLang = prefs.activeLanguage,
            showEmojiKey = prefs.showEmojiKey,
            showLangKey = prefs.showLangKey,
            showCommaKey = prefs.showCommaKey,
            showPeriodKey = prefs.showPeriodKey,
            splitGapS = splitGap
        )
        emojiPanel.applySkin(currentSkin)
        clipboardPanel.applySkin(currentSkin)
        textEditPanel.setIncognitoState(prefs.manualIncognito)
        textEditPanel.applySkin(currentSkin)
        menuPanel.applySkin(currentSkin)
        menuPanel.updateStates(prefs.manualIncognito, prefs.oneHandedMode != OneHandedMode.OFF, prefs.floatingModeEnabled)
        applyAdaptiveBottomLayout(lastWindowInsets)
        applyErgonomicLayoutMode()
    }

    private fun applyAdaptiveBottomLayout(insets: WindowInsetsCompat?) {
        if (!::rootContainer.isInitialized) return
        if (prefs.floatingModeEnabled) {
            rootContainer.setPadding(0, 0, 0, 0)
            return
        }
        val metrics: AdaptiveBottomMetrics = AdaptiveBottomLayout.compute(
            context = this,
            hostView = rootContainer,
            insetsCompat = insets,
            mode = prefs.bottomPaddingMode
        )
        rootContainer.setPadding(
            metrics.sidePaddingLeftPx,
            0,
            metrics.sidePaddingRightPx,
            metrics.bottomPaddingPx
        )
    }
    private fun buildArrowNavBar(): LinearLayout {
        val d = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding((6f * d).roundToInt(), (2f * d).roundToInt(), (6f * d).roundToInt(), (2f * d).roundToInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (34f * d).roundToInt())
        }
        val items = listOf(
            "⇤" to KeyEvent.KEYCODE_MOVE_HOME,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "⇥" to KeyEvent.KEYCODE_MOVE_END
        )
        items.forEach { (lbl, kc) ->
            bar.addView(TextView(this).apply {
                text = lbl
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    onMoveCursor(kc, false)
                    onKeyFeedback(KeyRole.FUNCTION)
                }
            })
        }
        return bar
    }

    private fun buildFloatingHandleBar(): LinearLayout {
        val d = resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startBottomMargin = 0

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding((10f * d).roundToInt(), (5f * d).roundToInt(), (10f * d).roundToInt(), (7f * d).roundToInt())

            val dragPill = TextView(this@BreenoRuInputMethodService).apply {
                text = "≡  Перетащите"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
                setPadding((12f * d).roundToInt(), (5f * d).roundToInt(), (12f * d).roundToInt(), (5f * d).roundToInt())
                setOnTouchListener { _, ev ->
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels.toFloat()
                    val stageH = rootContainer.height.takeIf { it > 0 } ?: dm.heightPixels
                    val cardW = keyboardCardContainer.width.toFloat().coerceAtLeast(screenW * 0.84f)
                    val cardH = keyboardCardContainer.height.takeIf { it > 0 } ?: (280f * d).roundToInt()
                    val maxTx = ((screenW - cardW) / 2f).coerceAtLeast(0f)
                    val safeTopPx = (84f * d).roundToInt()
                    val minBottomPx = (24f * d).roundToInt()
                    val maxBottomPx = (stageH - cardH - safeTopPx).coerceAtLeast(minBottomPx)
                    val lp = keyboardCardContainer.layoutParams as? LinearLayout.LayoutParams

                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            startTx = keyboardCardContainer.translationX
                            startBottomMargin = lp?.bottomMargin ?: (prefs.floatingOffsetYDp * d).roundToInt()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val nx = (startTx + (ev.rawX - downRawX)).coerceIn(-maxTx, maxTx)
                            val newBottom = (startBottomMargin - (ev.rawY - downRawY).roundToInt())
                                .coerceIn(minBottomPx, maxBottomPx)
                            keyboardCardContainer.translationX = nx
                            if (lp != null) {
                                lp.bottomMargin = newBottom
                                keyboardCardContainer.layoutParams = lp
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            prefs.floatingOffsetX = keyboardCardContainer.translationX.roundToInt()
                            val finalBottom = lp?.bottomMargin ?: startBottomMargin
                            prefs.floatingOffsetYDp = (finalBottom / d).roundToInt().coerceIn(24, 420)
                            rootContainer.requestLayout()
                            true
                        }
                        else -> false
                    }
                }
            }

            val dockBtn = TextView(this@BreenoRuInputMethodService).apply {
                text = "Закрепить ✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
                setPadding((10f * d).roundToInt(), (5f * d).roundToInt(), (8f * d).roundToInt(), (5f * d).roundToInt())
                setOnClickListener {
                    prefs.floatingModeEnabled = false
                    refreshThemeAndLayout()
                }
            }

            var resizeDownX = 0f
            var resizeDownY = 0f
            var startW = prefs.floatingWidthPercent
            var startH = prefs.floatingHeightPercent
            val resizeBtn = TextView(this@BreenoRuInputMethodService).apply {
                text = "↘"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding((10f * d).roundToInt(), (4f * d).roundToInt(), (10f * d).roundToInt(), (4f * d).roundToInt())
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            resizeDownX = ev.rawX
                            resizeDownY = ev.rawY
                            startW = prefs.floatingWidthPercent
                            startH = prefs.floatingHeightPercent
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dw = ((ev.rawX - resizeDownX) / (4f * d)).roundToInt()
                            val dh = ((ev.rawY - resizeDownY) / (4f * d)).roundToInt()
                            prefs.floatingWidthPercent = (startW + dw).coerceIn(68, 96)
                            prefs.floatingHeightPercent = (startH + dh).coerceIn(80, 120)
                            refreshThemeAndLayout()
                            true
                        }
                        else -> false
                    }
                }
            }

            addView(dragPill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dockBtn)
            addView(resizeBtn)
        }
    }

    private fun buildOneHandedRail(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding((4f * d).roundToInt(), (10f * d).roundToInt(), (4f * d).roundToInt(), (10f * d).roundToInt())

            val flipBtn = TextView(this@BreenoRuInputMethodService).apply {
                text = "⇄"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setPadding((8f * d).roundToInt(), (14f * d).roundToInt(), (8f * d).roundToInt(), (14f * d).roundToInt())
                setOnClickListener {
                    prefs.oneHandedMode = if (prefs.oneHandedMode == OneHandedMode.LEFT) OneHandedMode.RIGHT else OneHandedMode.LEFT
                    refreshThemeAndLayout()
                }
            }
            val expandBtn = TextView(this@BreenoRuInputMethodService).apply {
                text = "⤢"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding((8f * d).roundToInt(), (14f * d).roundToInt(), (8f * d).roundToInt(), (14f * d).roundToInt())
                setOnClickListener {
                    prefs.oneHandedMode = OneHandedMode.OFF
                    refreshThemeAndLayout()
                }
            }
            addView(flipBtn)
            addView(expandBtn)
        }
    }

    private fun applyErgonomicLayoutMode() {
        if (!::modeRowContainer.isInitialized) return
        val d = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        modeRowContainer.removeAllViews()

        val textColor = currentSkin.keyTextColor
        for (i in 0 until floatingHandleBar.childCount) {
            (floatingHandleBar.getChildAt(i) as? TextView)?.setTextColor(textColor)
        }
        for (i in 0 until oneHandedRail.childCount) {
            (oneHandedRail.getChildAt(i) as? TextView)?.setTextColor(textColor)
        }

        if (prefs.floatingModeEnabled) {
            floatingHandleBar.visibility = View.VISIBLE
            oneHandedRail.visibility = View.GONE
            val screenH = resources.displayMetrics.heightPixels
            val stageH = (screenH * 0.88f).roundToInt()
            rootContainer.layoutParams = (rootContainer.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, stageH
            )).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = stageH
            }
            val floatW = (screenW * (prefs.floatingWidthPercent / 100f)).roundToInt()
            val approxCardH = (290f * d).roundToInt()
            val safeBottom = (prefs.floatingOffsetYDp * d).roundToInt()
                .coerceIn((24f * d).roundToInt(), (stageH - approxCardH - (72f * d).roundToInt()).coerceAtLeast((24f * d).roundToInt()))
            val lp = LinearLayout.LayoutParams(floatW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = safeBottom
            }
            keyboardCardContainer.clipToOutline = true
            keyboardCardContainer.elevation = 14f * d
            keyboardCardContainer.background = buildCardWallpaperOrSolidDrawable(cornerRadiusPx = 20f * d)
            val maxTx = ((screenW - floatW) / 2f).coerceAtLeast(0f)
            keyboardCardContainer.translationX = prefs.floatingOffsetX.toFloat().coerceIn(-maxTx, maxTx)
            keyboardCardContainer.translationY = 0f
            modeRowContainer.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            modeRowContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            modeRowContainer.addView(keyboardCardContainer, lp)
        } else if (prefs.oneHandedMode != OneHandedMode.OFF) {
            rootContainer.layoutParams = (rootContainer.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            keyboardCardContainer.translationX = 0f
            keyboardCardContainer.translationY = 0f
            keyboardCardContainer.elevation = 0f
            modeRowContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            floatingHandleBar.visibility = View.GONE
            oneHandedRail.visibility = View.VISIBLE
            keyboardCardContainer.background = null
            val kbW = (screenW * 0.82f).roundToInt()
            val railW = (screenW - kbW).coerceAtLeast((44f * d).roundToInt())
            val kbLp = LinearLayout.LayoutParams(kbW, ViewGroup.LayoutParams.WRAP_CONTENT)
            val railLp = LinearLayout.LayoutParams(railW, ViewGroup.LayoutParams.MATCH_PARENT)
            modeRowContainer.gravity = Gravity.BOTTOM
            if (prefs.oneHandedMode == OneHandedMode.LEFT) {
                modeRowContainer.addView(keyboardCardContainer, kbLp)
                modeRowContainer.addView(oneHandedRail, railLp)
            } else {
                modeRowContainer.addView(oneHandedRail, railLp)
                modeRowContainer.addView(keyboardCardContainer, kbLp)
            }
        } else {
            rootContainer.layoutParams = (rootContainer.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            keyboardCardContainer.translationX = 0f
            keyboardCardContainer.translationY = 0f
            keyboardCardContainer.elevation = 0f
            modeRowContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            floatingHandleBar.visibility = View.GONE
            oneHandedRail.visibility = View.GONE
            keyboardCardContainer.background = null
            modeRowContainer.gravity = Gravity.BOTTOM
            modeRowContainer.addView(
                keyboardCardContainer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun applyWindowGlassEffect(skin: KeyboardSkin) {
        val w = window?.window ?: return
        // NEVER use FLAG_BLUR_BEHIND on an IME window (it blurs the entire app screen above the keyboard).
        // Instead, bind window height to WRAP_CONTENT at Gravity.BOTTOM and blur strictly the window background drawable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply {
                blurBehindRadius = 0
            }
        }
        if (prefs.floatingModeEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.setBackgroundBlurRadius(0)
            }
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setGravity(Gravity.BOTTOM)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
            return
        }

        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setGravity(Gravity.BOTTOM)

        if (skin.isGlassBackground) {
            val d = resources.displayMetrics.density
            val topR = 20f * d
            val glassDrawable = LiquidGlassBackdropDrawable(
                baseColor = skin.common.keyboardBackgroundColor,
                isDark = skin.isDark,
                topRadiusPx = topR,
                rimColor = skin.glassBorderTopColor,
                density = d
            )
            w.setBackgroundDrawable(glassDrawable)
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wm = getSystemService(WindowManager::class.java)
                val blurSupported = wm?.isCrossWindowBlurEnabled == true
                w.setBackgroundBlurRadius(if (blurSupported) skin.glassBlurRadiusPx else 0)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                w.setBackgroundBlurRadius(0)
            }
            w.setBackgroundDrawable(ColorDrawable(skin.common.keyboardBackgroundColor))
            w.navigationBarColor = skin.common.keyboardBackgroundColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightNavigationBars = !skin.isDark
    }

    private fun extractPreviousTwoWordsBeforeComposing(): Pair<String, String> {
        val ic = currentInputConnection ?: return "" to ""
        val rawBefore = ic.getTextBeforeCursor(80, 0)?.toString() ?: return "" to ""
        val withoutComposing = if (composing.isNotEmpty() && rawBefore.length >= composing.length) {
            rawBefore.substring(0, rawBefore.length - composing.length)
        } else {
            rawBefore
        }
        val trimmed = withoutComposing.trimEnd()
        if (trimmed.isEmpty()) return "" to ""
        val words = trimmed.split(Regex("[\\s.,!?;:()\\[\\]«»\"]+")).filter { it.isNotBlank() }
        val prev1 = words.lastOrNull() ?: ""
        val prev2 = if (words.size >= 2) words[words.size - 2] else ""
        return prev2 to prev1
    }

    private fun updateSuggestions() {
        if (isIncognito || !prefs.showSuggestions || composing.isEmpty()) {
            keyboardView.likelyNextChars = emptySet()
            headerBar.showToolbar()
            return
        }
        val rawComp = composing.toString()
        val (prev2, prev1) = extractPreviousTwoWordsBeforeComposing()
        val dynToken = PersonalMemoryEngine.resolveDynamicToken(rawComp)
        val rawExp = if (dynToken == null && prefs.textExpandersEnabled) prefs.getTextExpanders()[rawComp.lowercase()] else null
        val exp = rawExp?.let { PersonalMemoryEngine.interpolateVariables(it) }
        val wrongLayout = if (dynToken == null && exp == null && prefs.autoLayoutSwitchEnabled) {
            LayoutSwitcherAndTranslator.detectWrongLayoutWord(rawComp, prefs.activeLanguage, dictEngine)
        } else null
        val memContextMatches = if (prefs.nextWordPrediction && prev1.isNotEmpty()) {
            personalMemory.predictNextWords(prev2, prev1, maxCount = 10)
                .filter { it.startsWith(rawComp, ignoreCase = true) }
                .take(2)
        } else emptyList()
        val memMatches = (memContextMatches + personalMemory.matchPersonalPrefix(rawComp, maxCount = 2)).distinct().take(2)
        val spatialList = if (prefs.adaptiveSmartTouch && wordTapPoints.size == rawComp.length && rawComp.length >= 2 && ::keyboardView.isInitialized) {
            val (centers, kw, kh) = keyboardView.getLetterKeyCenters()
            dictEngine.decodeSpatialWord(
                composing = rawComp,
                tapPoints = wordTapPoints,
                keyCenters = centers,
                keyW = kw,
                keyH = kh,
                lang = prefs.activeLanguage,
                previousWord = if (prefs.nextWordPrediction) prev1 else "",
                maxResults = 2
            )
        } else emptyList()
        val rawDictList = dictEngine.getSuggestions(
            composing = rawComp,
            lang = prefs.activeLanguage,
            maxCount = 3,
            previousWord = if (prefs.nextWordPrediction) prev1 else ""
        )
        val dictList = if (spatialList.isNotEmpty()) {
            (spatialList + rawDictList).distinctBy { it.lowercase() }.take(3)
        } else {
            rawDictList
        }
        val mixedEn = if (prefs.mixedRuEnSuggestions && prefs.activeLanguage == "RU") {
            TextPowerTools.matchMixedEnglishWord(rawComp)
        } else null
        val emojiSug = if (prefs.predictiveEmojiEnabled) {
            TextPowerTools.predictEmojiForWord(rawComp)
        } else null
        val baseList = when {
            dynToken != null -> listOf(dynToken)
            exp != null -> (listOf(exp) + dictList).distinct().take(3)
            wrongLayout != null -> (listOf(wrongLayout) + dictList).distinct().take(3)
            mixedEn != null -> (listOf(mixedEn) + dictList).distinct().take(3)
            memMatches.isNotEmpty() -> (memMatches + dictList).distinct().take(3)
            else -> dictList
        }
        val splitJoin = if (prefs.spaceSplitJoinEnabled && dynToken == null && exp == null) {
            dictEngine.suggestSplitOrJoin(rawComp, prev1, prefs.activeLanguage)
        } else null
        lastSplitJoinCandidate = splitJoin

        val smartMath = if (prefs.smartInlineMathEnabled) TextPowerTools.tryEvalSmartMath(rawComp) else null

        val mergedBase = when {
            smartMath != null -> listOf(smartMath)
            splitJoin != null -> (listOf(splitJoin.first) + baseList).distinct().take(3)
            else -> baseList
        }

        val finalList = if (emojiSug != null && dynToken == null && smartMath == null) {
            (mergedBase.take(2) + emojiSug).distinct()
        } else {
            mergedBase
        }
        if (prefs.dynamicHitboxEnabled && ::keyboardView.isInitialized) {
            keyboardView.dynamicHitboxEnabled = true
            keyboardView.likelyNextChars = dictEngine.getLikelyNextLetters(rawComp, prefs.activeLanguage)
        }
        headerBar.showCandidates(finalList)
    }

    private fun commitComposingIfNeeded(applyAutocorrect: Boolean) {
        if (composing.isEmpty()) return
        val ic = currentInputConnection
        val raw = composing.toString()
        val (p2, p1) = extractPreviousTwoWordsBeforeComposing()
        val dynHit = if (applyAutocorrect && !isSensitiveField) PersonalMemoryEngine.resolveDynamicToken(raw) else null
        val expanderHit = dynHit ?: if (applyAutocorrect && !isSensitiveField && prefs.textExpandersEnabled) {
            prefs.getTextExpanders()[raw.lowercase()]?.let { PersonalMemoryEngine.interpolateVariables(it) }
        } else null
        val corrected = expanderHit ?: if (applyAutocorrect && !isSensitiveField && !isTerminalAppProfile && prefs.autoCorrect && !personalMemory.isProtectedFromAutocorrect(raw)) {
            val prevW = extractPreviousTwoWordsBeforeComposing().second
            // Spatial candidates remain available for explicit selection. Automatic replacement
            // uses the dictionary's exact-word and acronym safeguards.
            dictEngine.getAutoCorrection(raw, prefs.activeLanguage, prevW)
        } else {
            null
        }
        val finalWord = corrected ?: raw
        if (corrected != null && corrected != raw) {
            lastAutoCorrectedFrom = raw
            lastAutoCorrectedTo = corrected
        } else {
            clearAutoCorrectUndo()
        }

        if (ic?.commitText(finalWord, 1) != true) return
        if (!isIncognito && !isSensitiveField && prefs.personalLearningEnabled && finalWord.length >= 2 && finalWord.all { it.isLetter() }) {
            prefs.recordUserWordUsage(finalWord)
            personalMemory.recordCommittedPhrase(p2, p1, finalWord)
            if (p1.isNotEmpty()) dictEngine.learnBigram(p1, finalWord, prefs.activeLanguage)
            prevCommittedWord = p1
            lastCommittedWord = finalWord
        }
        composing.setLength(0); wordTapPoints.clear()
        if (::headerBar.isInitialized) headerBar.showToolbar()
    }

    private fun clearAutoCorrectUndo() {
        lastAutoCorrectedFrom = null
        lastAutoCorrectedTo = null
    }

    private fun updateAutoCap() {
        if (!::keyboardView.isInitialized) return
        if (shiftState == ShiftState.LOCKED) return
        if (!prefs.autoCap || isSensitiveField || isTerminalAppProfile ||
            (currentMode != KeyboardMode.ALPHA_RU && currentMode != KeyboardMode.ALPHA_EN)
        ) {
            return
        }
        val ic = currentInputConnection ?: return
        val editor = currentInputEditorInfo ?: return
        val caps = ic.getCursorCapsMode(editor.inputType)
        val shouldCap = caps != 0 && composing.isEmpty()
        val next = if (shouldCap) ShiftState.ON else ShiftState.OFF
        if (shiftState != next) {
            shiftState = next
            keyboardView.setShiftState(shiftState)
        }
    }

    private fun isPasswordVariation(inputClass: Int, variation: Int): Boolean {
        if (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }
        return inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
    }

    private fun isSensitiveSecurityApp(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val tokens = listOf(
            "sberbank", "sbol", "tinkoff", "tbank", "vtb", "alfabank", "raiffeisen",
            "gazprombank", "openbank", "psbank", "sovcombank", "mtsbank", "ozon.bank",
            "gosuslugi", "nalog", "keepass", "bitwarden", "1password", "lastpass",
            "dashlane", "authenticator", "authy", "protonpass", "wallet"
        )
        return tokens.any { pkg.contains(it) }
    }

    private fun applyImeWindowSecurityFlag(secure: Boolean) {
        val w = window?.window ?: return
        if (secure) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }


    /**
     * Multi-layer Frosted Liquid Glass backdrop:
     * 1) Vertical luminance gradient (brighter frosted top fading softly down) prevents flat muddy grey over black apps
     * 2) Soft 22dp specular top sheen band for realistic glass depth
     * 3) Crisp 1.25dp luminous top rim along the 20dp rounded edge
     */
    private class LiquidGlassBackdropDrawable(
        private val baseColor: Int,
        private val isDark: Boolean,
        private val topRadiusPx: Float,
        private val rimColor: Int,
        private val density: Float
    ) : Drawable() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (isDark) 0.85f else 1.25f) * density
            color = if (isDark) Color.argb(34, 255, 255, 255) else rimColor
        }
        private val clipPath = Path()
        private val rectF = RectF()
        private val radii = floatArrayOf(topRadiusPx, topRadiusPx, topRadiusPx, topRadiusPx, 0f, 0f, 0f, 0f)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            rectF.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rectF, radii, Path.Direction.CW)

            val baseAlpha = Color.alpha(baseColor)
            val topColor = if (isDark) {
                Color.argb(baseAlpha, 17, 19, 24)
            } else {
                Color.argb((baseAlpha + 18).coerceAtMost(248), 252, 253, 255)
            }
            val bottomColor = if (isDark) {
                Color.argb(baseAlpha, 12, 14, 18)
            } else {
                Color.argb((baseAlpha - 6).coerceAtLeast(168), 232, 238, 248)
            }
            bgPaint.shader = LinearGradient(0f, rectF.top, 0f, rectF.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)

            val sheenTop = if (isDark) Color.argb(7, 255, 255, 255) else Color.argb(72, 255, 255, 255)
            val sheenH = (if (isDark) 12f else 24f) * density
            sheenPaint.shader = LinearGradient(0f, rectF.top, 0f, rectF.top + sheenH.coerceAtMost(h * 0.2f), sheenTop, Color.TRANSPARENT, Shader.TileMode.CLAMP)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rectF, bgPaint)
            canvas.drawRect(rectF.left, rectF.top, rectF.right, rectF.top + sheenH, sheenPaint)
            canvas.restoreToCount(save)

            // Crisp luminous top edge rim
            if (rimColor != Color.TRANSPARENT) {
                val rimSave = canvas.save()
                canvas.clipRect(rectF.left, rectF.top, rectF.right, rectF.top + topRadiusPx + 2f * density)
                canvas.drawPath(clipPath, rimPaint)
                canvas.restoreToCount(rimSave)
            }
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

}
