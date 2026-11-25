package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.claritykey.keyboard.R
import com.claritykey.keyboard.engine.QuickImageSuggestion
import com.claritykey.keyboard.engine.QuickSmartSuggestion
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 44.0dp Breeno top header bar:
 * - Toolbar mode: 5 evenly spaced optically equal icons ([Edit] [Clip] [Emoji] [Settings] [Mic])
 * - Candidate mode: [Menu Icon] | Word 1 | Word 2 | Word 3 | [Mic Icon]
 * - Quick Text Clip mode & Quick Image/Screenshot Suggestion mode (compact thumbnail + dismissible)
 * - Voice Listening mode & Android Inline Autofill mode
 */
class BreenoHeaderBarView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onOpenSettings()
        fun onToggleEmoji()
        fun onToggleClipboard()
        fun onToggleTextEdit()
        fun onToggleMenu()
        fun onQuickClipPaste(text: String)
        fun onQuickImagePaste(suggestion: QuickImageSuggestion)
        fun onQuickImageDismiss(suggestion: QuickImageSuggestion)
        fun onQuickSmartPaste(suggestion: QuickSmartSuggestion)
        fun onQuickSmartDismiss(suggestion: QuickSmartSuggestion)
        fun onVoiceClick()
        fun onCollapseKeyboard()
        fun onCandidateClick(word: String)
        fun onCandidateLongClick(word: String)
        fun onQuickToolbarSlot(slotId: String)
        fun onUndoDeleteClick(deletedText: String)
        fun onLocalAutofillClick(value: String)
        fun onPasswordGenClick()
        fun onTranslateToggleDirection()
        fun onTranslateCommit()
        fun onTranslateVoiceClick()
        fun onTranslateClose()
        fun onRamblerCycleStyle()
        fun onRamblerToggleMic()
        fun onRamblerCommit()
        fun onRamblerCancel()
    }

    var listener: Listener? = null

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private val density = context.resources.displayMetrics.density
    private var voiceVisible: Boolean = true
    private var incognitoActive: Boolean = false
    private var menuOpen: Boolean = false
    private var toolbarSlots: List<String> = listOf("MENU", "CLIP", "TRANSLATE", "SETTINGS", "VOICE")

    private val thumbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val toolbarRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val candidateContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private lateinit var btnCandMenu: ImageButton
    private val candidateSlotsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private lateinit var btnCandVoice: ImageButton

    private val quickClipBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private lateinit var quickClipChip: TextView
    private lateinit var quickClipClose: TextView

    // Compact dismissible pill for Recent Screenshot / Copied Image (occupies exact 44dp top bar)
    private val quickImageBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private lateinit var quickImagePill: LinearLayout
    private lateinit var quickImageThumb: ImageView
    private lateinit var quickImageClose: TextView

    private val voiceBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private lateinit var voiceStatusText: TextView

    private var ramblerIconActive: Boolean = true
    private val ramblerBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private lateinit var ramblerStyleBtn: TextView
    private lateinit var ramblerPreviewTv: TextView
    private lateinit var ramblerMicBtn: ImageButton
    private lateinit var ramblerCommitBtn: TextView
    private lateinit var ramblerCloseBtn: TextView

    private val translateBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private lateinit var translateDirBtn: TextView
    private lateinit var translatePreviewTv: TextView
    private lateinit var translateVoiceBtn: ImageButton
    private lateinit var translateCommitBtn: TextView
    private lateinit var translateCloseBtn: TextView

    private val autofillScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = GONE
    }
    private val autofillRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private lateinit var btnEdit: ImageButton
    private lateinit var btnClip: ImageButton
    private lateinit var btnEmoji: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnVoice: ImageButton
    private lateinit var btnTranslate: ImageButton

    private var quickClipText: String? = null
    private var undoDeleteText: String? = null
    private var localAutofillValue: String? = null
    private var passwordGenMode: Boolean = false
    private var activeImageSuggestion: QuickImageSuggestion? = null
    private var activeSmartSuggestion: QuickSmartSuggestion? = null

    init {
        val barHeightPx = (44f * density).roundToInt()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx)

        buildToolbar()
        buildCandidateBar()
        buildQuickClipBar()
        buildQuickImageBar()
        buildVoiceBar()
        buildTranslateBar()
        autofillScroll.addView(autofillRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        addView(toolbarRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(candidateContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(quickClipBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(quickImageBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(voiceBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(translateBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ramblerBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(autofillScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildToolbar() {
        val padH = (6f * density).roundToInt()
        toolbarRow.setPadding(padH, 0, padH, 0)

        btnEdit = createWeightedIconButton { listener?.onToggleMenu() }
        btnClip = createWeightedIconButton { listener?.onToggleClipboard() }
        btnEmoji = createWeightedIconButton { listener?.onToggleEmoji() }
        btnSettings = createWeightedIconButton { listener?.onOpenSettings() }
        btnVoice = createWeightedIconButton { listener?.onVoiceClick() }
        btnTranslate = createWeightedIconButton { listener?.onQuickToolbarSlot("TRANSLATE") }

        rebuildToolbarSlots()
    }

    fun setToolbarSlots(slots: List<String>) {
        val cleaned = slots.take(5).ifEmpty { listOf("EDIT", "CLIP", "EMOJI", "SETTINGS", "VOICE") }
        if (toolbarSlots != cleaned) {
            toolbarSlots = cleaned
            rebuildToolbarSlots()
        }
    }

    fun setIncognitoActive(active: Boolean) {
        if (incognitoActive != active) {
            incognitoActive = active
            rebuildToolbarSlots()
        }
    }

    fun setMenuOpen(open: Boolean) {
        if (menuOpen != open) {
            menuOpen = open
            rebuildToolbarSlots()
        }
    }

    private fun createWeightedBadgeButton(glyph: String, accent: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        val iconTint = if (accent) skin.common.candidateTargetColor else if (skin.isDark) Color.parseColor("#E6FFFFFF") else Color.parseColor("#F014161A")
        setTextColor(iconTint)
        setOnClickListener { onClick() }
    }

    private fun createMenuClosePill(): View {
        val wrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { listener?.onToggleMenu() }
        }
        val sz = (32f * density).roundToInt()
        val closeTv = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.keyTextColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(skin.funcKeyBg.normalColor)
            }
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
        }
        wrap.addView(closeTv)
        return wrap
    }

    private fun rebuildToolbarSlots() {
        toolbarRow.removeAllViews()
        toolbarSlots.forEach { slot ->
            when (slot) {
                "MENU", "EDIT" -> {
                    if (menuOpen) toolbarRow.addView(createMenuClosePill())
                    else toolbarRow.addView(btnEdit)
                }
                "CLIP" -> toolbarRow.addView(btnClip)
                "EMOJI" -> toolbarRow.addView(btnEmoji)
                "SETTINGS" -> toolbarRow.addView(btnSettings)
                "VOICE" -> {
                    btnVoice.visibility = if (voiceVisible) VISIBLE else GONE
                    toolbarRow.addView(btnVoice)
                }
                "TRANSLATE" -> toolbarRow.addView(btnTranslate)
                "INCOGNITO" -> toolbarRow.addView(createWeightedBadgeButton(if (incognitoActive) "🕶●" else "🕶", incognitoActive) {
                    listener?.onQuickToolbarSlot("INCOGNITO")
                })
                "ONE_HAND" -> toolbarRow.addView(createWeightedBadgeButton("◧", false) {
                    listener?.onQuickToolbarSlot("ONE_HAND")
                })
            }
        }
    }

    private fun buildCandidateBar() {
        val padH = (6f * density).roundToInt()
        candidateContainer.setPadding(padH, 0, padH, 0)

        val iconW = (42f * density).roundToInt()
        btnCandMenu = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconW, LayoutParams.MATCH_PARENT)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = (11f * density).roundToInt()
            setPadding(p, p, p, p)
            setOnClickListener { showToolbar() }
        }
        btnCandVoice = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconW, LayoutParams.MATCH_PARENT)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = (11f * density).roundToInt()
            setPadding(p, p, p, p)
            setOnClickListener { listener?.onVoiceClick() }
        }

        candidateContainer.addView(btnCandMenu)
        candidateContainer.addView(candidateSlotsRow, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        candidateContainer.addView(btnCandVoice)
    }

    private fun buildQuickClipBar() {
        val padH = (10f * density).roundToInt()
        quickClipBar.setPadding(padH, (6f * density).roundToInt(), padH, (6f * density).roundToInt())

        quickClipChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14f * density).roundToInt(), 0, (14f * density).roundToInt(), 0)
            setOnClickListener {
                val smart = activeSmartSuggestion
                if (smart != null) {
                    hideQuickClipChip()
                    listener?.onQuickSmartPaste(smart)
                    return@setOnClickListener
                }
                undoDeleteText?.let { deleted ->
                    hideQuickClipChip()
                    listener?.onUndoDeleteClick(deleted)
                    return@setOnClickListener
                }
                if (passwordGenMode) {
                    hideQuickClipChip()
                    listener?.onPasswordGenClick()
                    return@setOnClickListener
                }
                localAutofillValue?.let { afVal ->
                    hideQuickClipChip()
                    listener?.onLocalAutofillClick(afVal)
                    return@setOnClickListener
                }
                quickClipText?.let { txt ->
                    listener?.onQuickClipPaste(txt)
                    hideQuickClipChip()
                }
            }
        }
        quickClipClose = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding((12f * density).roundToInt(), 0, (6f * density).roundToInt(), 0)
            setOnClickListener {
                activeSmartSuggestion?.let { listener?.onQuickSmartDismiss(it) }
                hideQuickClipChip()
            }
        }
        quickClipBar.addView(quickClipChip, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        quickClipBar.addView(quickClipClose, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildQuickImageBar() {
        quickImageBar.gravity = Gravity.CENTER
        quickImageBar.setPadding((10f * density).roundToInt(), (4f * density).roundToInt(), (10f * density).roundToInt(), (4f * density).roundToInt())

        quickImagePill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((5f * density).roundToInt(), (3f * density).roundToInt(), (4f * density).roundToInt(), (3f * density).roundToInt())
        }

        val thumbW = (44f * density).roundToInt()
        val thumbH = (30f * density).roundToInt()
        quickImageThumb = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            contentDescription = "Вставить изображение"
            setOnClickListener {
                activeImageSuggestion?.let { suggestion ->
                    hideQuickClipChip()
                    listener?.onQuickImagePaste(suggestion)
                }
            }
        }

        quickImageClose = TextView(context).apply {
            text = "✕"
            contentDescription = "Скрыть предложение"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding((10f * density).roundToInt(), 0, (6f * density).roundToInt(), 0)
            setOnClickListener {
                activeImageSuggestion?.let { listener?.onQuickImageDismiss(it) }
                hideQuickClipChip()
            }
        }

        quickImagePill.addView(quickImageThumb)
        quickImagePill.addView(quickImageClose, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        quickImageBar.addView(quickImagePill, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildVoiceBar() {
        val padH = (12f * density).roundToInt()
        voiceBar.setPadding(padH, (5f * density).roundToInt(), padH, (5f * density).roundToInt())
        voiceStatusText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((18f * density).roundToInt(), 0, (18f * density).roundToInt(), 0)
            setOnClickListener { listener?.onVoiceClick() }
        }
        voiceBar.addView(
            voiceStatusText,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private fun buildTranslateBar() {
        val padH = (6f * density).roundToInt()
        translateBar.setPadding(padH, (5f * density).roundToInt(), padH, (5f * density).roundToInt())

        translateDirBtn = TextView(context).apply {
            text = "RU→EN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding((9f * density).roundToInt(), 0, (9f * density).roundToInt(), 0)
            setOnClickListener { listener?.onTranslateToggleDirection() }
        }
        translatePreviewTv = TextView(context).apply {
            text = "Печатайте для перевода…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10f * density).roundToInt(), 0, (10f * density).roundToInt(), 0)
            setOnClickListener { listener?.onTranslateCommit() }
        }
        translateVoiceBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_toolbar_voice)
            contentDescription = "Голосовой ввод в переводчике"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = (7f * density).roundToInt()
            setPadding(p, p, p, p)
            setOnClickListener { listener?.onTranslateVoiceClick() }
        }
        translateCommitBtn = TextView(context).apply {
            text = "✓"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((12f * density).roundToInt(), 0, (12f * density).roundToInt(), 0)
            setOnClickListener { listener?.onTranslateCommit() }
        }
        translateCloseBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding((9f * density).roundToInt(), 0, (6f * density).roundToInt(), 0)
            setOnClickListener { listener?.onTranslateClose() }
        }

        val gap = (5f * density).roundToInt()
        translateBar.addView(translateDirBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        translateBar.addView(translatePreviewTv, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = gap
            rightMargin = gap
        })
        val micSz = (34f * density).roundToInt()
        translateBar.addView(translateVoiceBtn, LinearLayout.LayoutParams(micSz, LayoutParams.MATCH_PARENT).apply {
            rightMargin = gap
        })
        translateBar.addView(translateCommitBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        translateBar.addView(translateCloseBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun showTranslateBar(ruToEn: Boolean, sourceText: String, translatedText: String, voiceListening: Boolean = false) {
        translateDirBtn.text = if (ruToEn) "RU→EN" else "EN→RU"
        translatePreviewTv.text = when {
            voiceListening && sourceText.isBlank() -> if (ruToEn) "Слушаю (RU)…" else "Listening (EN)…"
            sourceText.isBlank() -> if (ruToEn) "Печатайте или диктуйте…" else "Type or speak…"
            else -> "$sourceText → $translatedText"
        }
        translatePreviewTv.setTextColor(if (sourceText.isBlank() && !voiceListening) skin.keyMarkColor else skin.keyTextColor)
        translateVoiceBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(if (voiceListening) skin.enterActionBg.normalColor else skin.funcKeyBg.normalColor)
        }
        translateVoiceBtn.setColorFilter(if (voiceListening) Color.WHITE else skin.keyTextColor)
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        translateBar.visibility = VISIBLE
    }

    fun hideTranslateBar() {
        translateBar.visibility = GONE
        ramblerBar.visibility = GONE
        showToolbar()
    }

    private fun buildRamblerBarViews() {
        if (::ramblerStyleBtn.isInitialized) return
        val padH = (8f * density).roundToInt()
        val gap = (6f * density).roundToInt()
        ramblerBar.setPadding(padH, (5f * density).roundToInt(), padH, (5f * density).roundToInt())

        ramblerStyleBtn = TextView(context).apply {
            text = "Обычный"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding((9f * density).roundToInt(), 0, (9f * density).roundToInt(), 0)
            setOnClickListener { listener?.onRamblerCycleStyle() }
        }
        ramblerPreviewTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            setPadding((8f * density).roundToInt(), 0, (8f * density).roundToInt(), 0)
        }
        ramblerMicBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_toolbar_voice_rambler)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = (6f * density).roundToInt()
            setPadding(p, p, p, p)
            setOnClickListener { listener?.onRamblerToggleMic() }
        }
        ramblerCommitBtn = TextView(context).apply {
            text = "✓"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding((12f * density).roundToInt(), 0, (12f * density).roundToInt(), 0)
            setOnClickListener { listener?.onRamblerCommit() }
        }
        ramblerCloseBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding((8f * density).roundToInt(), 0, (6f * density).roundToInt(), 0)
            setOnClickListener { listener?.onRamblerCancel() }
        }

        ramblerBar.addView(ramblerStyleBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        ramblerBar.addView(ramblerPreviewTv, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = gap
            rightMargin = gap
        })
        val micSz = (34f * density).roundToInt()
        ramblerBar.addView(ramblerMicBtn, LinearLayout.LayoutParams(micSz, LayoutParams.MATCH_PARENT).apply {
            rightMargin = gap
        })
        ramblerBar.addView(ramblerCommitBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        ramblerBar.addView(ramblerCloseBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setRamblerVoiceIcon(enabled: Boolean) {
        ramblerIconActive = enabled
        val resId = if (enabled) R.drawable.ic_toolbar_voice_rambler else R.drawable.ic_toolbar_voice
        btnVoice.setImageResource(resId)
        btnCandVoice.setImageResource(resId)
    }

    fun showRamblerBar(previewText: String, styleLabel: String, voiceListening: Boolean) {
        buildRamblerBarViews()
        ramblerStyleBtn.text = styleLabel
        ramblerStyleBtn.setTextColor(skin.keyTextColor)
        ramblerStyleBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.funcKeyBg.normalColor)
        }
        ramblerPreviewTv.text = when {
            voiceListening && previewText.isBlank() -> "✦ Говорите свободно…"
            previewText.isBlank() -> "✦ Нажмите микрофон и диктуйте…"
            else -> previewText
        }
        ramblerPreviewTv.setTextColor(if (previewText.isBlank()) skin.keyMarkColor else skin.keyTextColor)
        ramblerPreviewTv.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.normalKeyBg.normalColor)
        }
        ramblerMicBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(if (voiceListening) skin.enterActionBg.normalColor else skin.funcKeyBg.normalColor)
        }
        ramblerMicBtn.setColorFilter(if (voiceListening) Color.WHITE else skin.keyTextColor)
        ramblerCommitBtn.setTextColor(Color.WHITE)
        ramblerCommitBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.enterActionBg.normalColor)
        }
        ramblerCloseBtn.setTextColor(skin.keyMarkColor)

        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        translateBar.visibility = GONE
        ramblerBar.visibility = GONE
        ramblerBar.visibility = VISIBLE
    }

    fun hideRamblerBar() {
        ramblerBar.visibility = GONE
        showToolbar()
    }

    private fun createWeightedIconButton(onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        background = null
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (11f * density).roundToInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onClick() }
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(skin.common.topBarBackgroundColor)
        val iconTint = if (skin.isDark) Color.parseColor("#E6FFFFFF") else Color.parseColor("#F014161A")
        btnEdit.setImageResource(R.drawable.ic_toolbar_edit)
        btnCandMenu.setImageResource(R.drawable.ic_toolbar_edit)
        btnClip.setImageResource(R.drawable.ic_toolbar_clip)
        btnEmoji.setImageResource(R.drawable.ic_toolbar_emoji)
        btnSettings.setImageResource(R.drawable.ic_toolbar_settings)
        btnVoice.setImageResource(if (ramblerIconActive) R.drawable.ic_toolbar_voice_rambler else R.drawable.ic_toolbar_voice)
        btnTranslate.setImageResource(R.drawable.ic_toolbar_translate)
        btnCandVoice.setImageResource(if (ramblerIconActive) R.drawable.ic_toolbar_voice_rambler else R.drawable.ic_toolbar_voice)

        listOf(btnEdit, btnCandMenu, btnClip, btnEmoji, btnSettings, btnVoice, btnCandVoice, btnTranslate).forEach { btn ->
            btn.setColorFilter(iconTint)
        }
        rebuildToolbarSlots()

        quickClipChip.setTextColor(skin.keyTextColor)
        quickClipChip.background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(skin.normalKeyBg.normalColor)
        }
        quickClipClose.setTextColor(skin.keyMarkColor)

        quickImagePill.background = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(skin.normalKeyBg.normalColor)
            if (skin.isGlassKeys) {
                setStroke((0.75f * density).roundToInt().coerceAtLeast(1), skin.glassBorderTopColor)
            }
        }
        quickImageThumb.background = GradientDrawable().apply {
            cornerRadius = 6f * density
            setColor(skin.funcKeyBg.normalColor)
        }
        quickImageClose.setTextColor(skin.keyMarkColor)

        voiceStatusText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(skin.enterActionBg.normalColor)
        }
        translateDirBtn.setTextColor(skin.common.candidateTargetColor)
        translateDirBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.funcKeyBg.normalColor)
        }
        translatePreviewTv.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.normalKeyBg.normalColor)
        }
        translateCommitBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.enterActionBg.normalColor)
        }
        translateVoiceBtn.setColorFilter(skin.keyTextColor)
        translateVoiceBtn.background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(skin.funcKeyBg.normalColor)
        }
        translateCloseBtn.setTextColor(skin.keyMarkColor)
    }

    fun setVoiceButtonVisible(visible: Boolean) {
        voiceVisible = visible
        btnVoice.visibility = if (visible) VISIBLE else GONE
        btnCandVoice.visibility = if (visible) VISIBLE else GONE
    }

    fun showQuickSmartSuggestion(suggestion: QuickSmartSuggestion) {
        activeImageSuggestion = null
        quickClipText = null
        activeSmartSuggestion = suggestion
        quickClipChip.text = "${suggestion.iconGlyph}  ${suggestion.label}"
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickImageBar.visibility = GONE
        quickClipBar.visibility = VISIBLE
    }

    fun showUndoDeleteChip(deletedText: String) {
        activeImageSuggestion = null
        activeSmartSuggestion = null
        quickClipText = null
        localAutofillValue = null
        undoDeleteText = deletedText
        quickClipChip.text = "↩  Вернуть: ${deletedText.replace('\n', ' ')}"
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickImageBar.visibility = GONE
        quickClipBar.visibility = VISIBLE
    }

    fun showPasswordGenChip() {
        activeImageSuggestion = null
        activeSmartSuggestion = null
        quickClipText = null
        undoDeleteText = null
        localAutofillValue = null
        passwordGenMode = true
        quickClipChip.text = "🔑  Создать надёжный пароль (16 симв.)"
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickImageBar.visibility = GONE
        quickClipBar.visibility = VISIBLE
    }

    fun showLocalAutofillChip(icon: String, displayLabel: String, value: String) {
        passwordGenMode = false
        activeImageSuggestion = null
        activeSmartSuggestion = null
        quickClipText = null
        undoDeleteText = null
        localAutofillValue = value
        quickClipChip.text = "$icon  $displayLabel"
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickImageBar.visibility = GONE
        quickClipBar.visibility = VISIBLE
    }

    fun showQuickClipChip(text: String, preview: String = text) {
        activeImageSuggestion = null
        activeSmartSuggestion = null
        undoDeleteText = null
        localAutofillValue = null
        quickClipText = text
        quickClipChip.text = "📋  ${preview.replace('\n', ' ')}"
        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickImageBar.visibility = GONE
        quickClipBar.visibility = VISIBLE
    }

    fun showQuickImageSuggestion(suggestion: QuickImageSuggestion) {
        quickClipText = null
        activeImageSuggestion = suggestion
        quickImageThumb.setImageResource(R.drawable.ic_toolbar_clip)

        val requestKey = suggestion.idKey
        thumbExecutor.execute {
            val bmp = loadSmallPreview(suggestion)
            mainHandler.post {
                if (activeImageSuggestion?.idKey == requestKey && bmp != null) {
                    quickImageThumb.clearColorFilter()
                    quickImageThumb.setImageBitmap(bmp)
                }
            }
        }

        toolbarRow.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = VISIBLE
    }

    private fun loadSmallPreview(suggestion: QuickImageSuggestion): Bitmap? {
        return try {
            val thumbPath = suggestion.thumbPath
            if (!thumbPath.isNullOrBlank() && File(thumbPath).exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                return BitmapFactory.decodeFile(thumbPath, opts)
            }
            val uri = suggestion.screenshotInfo?.contentUri ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((bounds.outWidth / (sample * 2)) >= 96 && (bounds.outHeight / (sample * 2)) >= 96) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (_: Exception) {
            null
        }
    }

    fun hideQuickClipChip() {
        quickClipText = null
        undoDeleteText = null
        localAutofillValue = null
        activeImageSuggestion = null
        activeSmartSuggestion = null
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        if (candidateContainer.visibility != VISIBLE && autofillScroll.visibility != VISIBLE && voiceBar.visibility != VISIBLE) {
            toolbarRow.visibility = VISIBLE
        }
    }

    fun showVoiceListening(statusText: String) {
        voiceStatusText.text = "$statusText  •  Стоп"
        toolbarRow.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = VISIBLE
    }

    fun showToolbar() {
        voiceBar.visibility = GONE
        translateBar.visibility = GONE
        ramblerBar.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        toolbarRow.visibility = VISIBLE
        candidateContainer.visibility = GONE
        autofillScroll.visibility = GONE
    }

    fun showCandidates(words: List<String>) {
        if (voiceBar.visibility == VISIBLE || translateBar.visibility == VISIBLE || ramblerBar.visibility == VISIBLE) return
        if (words.isEmpty()) {
            if (autofillRow.childCount > 0) {
                toolbarRow.visibility = GONE
                quickClipBar.visibility = GONE
                quickImageBar.visibility = GONE
                candidateContainer.visibility = GONE
                autofillScroll.visibility = VISIBLE
            } else {
                showToolbar()
            }
            return
        }

        candidateSlotsRow.removeAllViews()
        val top3 = words.take(3)
        val dividerW = (0.7f * density).roundToInt().coerceAtLeast(1)
        val dividerH = (18f * density).roundToInt()

        top3.forEachIndexed { idx, word ->
            if (idx > 0) {
                val div = View(context).apply {
                    setBackgroundColor(skin.common.candidateDividerColor)
                    alpha = 0.28f
                    layoutParams = LinearLayout.LayoutParams(dividerW, dividerH).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                }
                candidateSlotsRow.addView(div)
            }
            val isPrimary = false // Candidate order does not imply automatic replacement.
            val tv = TextView(context).apply {
                text = word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = if (isPrimary) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
                setTextColor(if (isPrimary) skin.common.candidateTargetColor else skin.common.candidateWordColor)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding((6f * density).roundToInt(), 0, (6f * density).roundToInt(), 0)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { listener?.onCandidateClick(word) }
                setOnLongClickListener {
                    listener?.onCandidateLongClick(word)
                    true
                }
            }
            candidateSlotsRow.addView(tv)
        }

        btnCandVoice.visibility = if (voiceVisible) VISIBLE else GONE
        toolbarRow.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        autofillScroll.visibility = GONE
        voiceBar.visibility = GONE
        candidateContainer.visibility = VISIBLE
    }

    fun showAutofillViews(views: List<View>) {
        autofillRow.removeAllViews()
        if (views.isEmpty()) {
            showToolbar()
            return
        }
        val gap = (6f * density).roundToInt()
        views.forEach { v ->
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(gap, 0, gap, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            autofillRow.addView(v, lp)
        }
        toolbarRow.visibility = GONE
        quickClipBar.visibility = GONE
        quickImageBar.visibility = GONE
        candidateContainer.visibility = GONE
        voiceBar.visibility = GONE
        autofillScroll.visibility = VISIBLE
    }

    fun clearAutofillViews() {
        autofillRow.removeAllViews()
        if (candidateContainer.visibility != VISIBLE &&
            voiceBar.visibility != VISIBLE &&
            quickClipBar.visibility != VISIBLE &&
            quickImageBar.visibility != VISIBLE
        ) {
            showToolbar()
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!thumbExecutor.isShutdown) {
            thumbExecutor.shutdownNow()
        }
    }
}