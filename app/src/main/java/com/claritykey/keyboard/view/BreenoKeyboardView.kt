package com.claritykey.keyboard.view

import android.content.Context
import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.claritykey.keyboard.R
import com.claritykey.keyboard.layout.BreenoLayouts
import com.claritykey.keyboard.layout.ComputedKey
import com.claritykey.keyboard.layout.InputContextMode
import com.claritykey.keyboard.layout.KeyAction
import com.claritykey.keyboard.layout.KeyRole
import com.claritykey.keyboard.layout.KeyboardLayoutSpec
import com.claritykey.keyboard.layout.KeyboardMode
import com.claritykey.keyboard.layout.ShiftState
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-precision Canvas renderer faithfully reconstructing Breeno Keyboard 1.9.18.17-mkt:
 * - Proportional geometry from res/XW.json (10-col EN) & res/R2.json (11-col RU)
 * - Exact 8.0dp corner radius, 3.0px bottom shadow, normal/pressed color states
 * - Original decoded Breeno VectorDrawables for Shift, Delete, Enter, Space, Globe
 * - Long-press alternate popup, Backspace word-delete swipe, Spacebar cursor swipe
 */
class BreenoKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onCharInput(text: String)
        fun onLetterTap(key: ComputedKey, tapX: Float, tapY: Float, allKeys: List<ComputedKey>)
        fun onSwipeGesture(path: List<PointF>, allKeys: List<ComputedKey>)
        fun onShiftPressed()
        fun onDeleteChar()
        fun onDeleteWord()
        fun onDeleteSwipePreview(steps: Int) {}
        fun onDeleteSwipeCommit(steps: Int) { if (steps > 0) onDeleteWord() }
        fun onSpacePressed()
        fun onEnterPressed()
        fun onLanguageSwitch()
        fun onShowInputMethodPicker()
        fun onOpenEmojiFromKey()
        fun onModeChange(mode: KeyboardMode)
        fun onCursorMove(deltaChars: Int)
        fun onCursorMoveVertical(deltaLines: Int)
        fun onKeyFeedback(role: KeyRole)
    }

    var listener: Listener? = null
    var popupEnabled: Boolean = true
    var swipeTypingEnabled: Boolean = true
    var quickFlickEnabled: Boolean = true
    var heightScalePercent: Int = 100
    var compactLandscape: Boolean = true
    var keyFontScalePercent: Int = 100
    var longPressSymbolsEnabled: Boolean = true
    var interactiveDeleteSwipeEnabled: Boolean = true
    var dynamicHitboxEnabled: Boolean = true
    var keyPressHighlightEnabled: Boolean = false
    var keyPressHighlightColor: Int = 0
    var quickSymbolSlideEnabled: Boolean = true
    private var quickSymbolSlideActive: Boolean = false
    var likelyNextChars: Set<Char> = emptySet()
    private var deleteSwipeSteps: Int = 0
    var flickDownSymbolsEnabled: Boolean = false
    var touchSensitivity: Int = 1
    var longPressDelayMs: Int = 300
    var edgeRejectionDp: Int = 0

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var layoutSpec: KeyboardLayoutSpec = BreenoLayouts.buildLayout(KeyboardMode.ALPHA_RU)
    private var computedKeys: List<ComputedKey> = emptyList()
    private var shiftState: ShiftState = ShiftState.OFF
    private var imeAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED

    private val popupView = BreenoKeyPopupView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var activeKey: ComputedKey? = null
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastReleasedKey: ComputedKey? = null
    private var lastReleaseTimeMs: Long = 0L
    private var lastDispatchedSpecLabel: String = ""
    private var lastDispatchedTimeMs: Long = 0L
    private val releaseTrailClearRunnable = Runnable {
        lastReleasedKey = null
        invalidate()
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var deleteSwipeTriggered: Boolean = false
    private var spaceSwipeActive: Boolean = false
    private var spaceSwipeLastX: Float = 0f
    private var spaceSwipeLastY: Float = 0f
    private var longPressFired: Boolean = false
    private var swipeActive: Boolean = false
    private val swipePoints = ArrayList<PointF>(64)
    private val swipePath = Path()
    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val shadowRect = RectF()

    // Cached original Breeno VectorDrawables
    private val iconCache = HashMap<Int, Drawable?>()

    private val longPressRunnable = Runnable {
        val key = activeKey ?: return@Runnable
        if (key.spec.action == KeyAction.DELETE) {
            longPressFired = true
            startDeleteRepeat()
            return@Runnable
        }
        if (key.spec.label == "," && key.spec.mark == "☺") {
            longPressFired = true
            popupView.dismiss()
            listener?.onOpenEmojiFromKey()
            return@Runnable
        }
        if (key.spec.action == KeyAction.LANG_SWITCH) {
            longPressFired = true
            popupView.dismiss()
            listener?.onShowInputMethodPicker()
            return@Runnable
        }
        val alts = buildEffectiveAlternates(key)
        if (alts.isNotEmpty()) {
            longPressFired = true
            popupView.showAlternates(
                anchor = this,
                skin = skin,
                alternates = alts,
                keyCenterX = key.visualRect.centerX(),
                keyTopY = key.visualRect.top,
                keyHeightPx = key.visualRect.height()
            )
        }
    }

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (activeKey?.spec?.action == KeyAction.DELETE && !deleteSwipeTriggered) {
                listener?.onDeleteChar()
                handler.postDelayed(this, 55L)
            }
        }
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(if (skin.isGlassBackground) Color.TRANSPARENT else skin.common.keyboardBackgroundColor)
        invalidate()
    }

    fun setLayout(
        mode: KeyboardMode,
        contextMode: InputContextMode,
        separateHardSign: Boolean,
        separateYo: Boolean = false,
        showNumberRow: Boolean,
        lastAlphaLang: String,
        showEmojiKey: Boolean = false,
        showLangKey: Boolean = true,
        showCommaKey: Boolean = true,
        showPeriodKey: Boolean = true,
        splitGapS: Float = 0f
    ) {
        val prevRows = layoutSpec.rows.size
        layoutSpec = BreenoLayouts.buildLayout(
            mode = mode,
            contextMode = contextMode,
            separateHardSign = separateHardSign,
            separateYo = separateYo,
            showNumberRow = showNumberRow,
            lastAlphaLang = lastAlphaLang,
            showEmojiKey = showEmojiKey,
            showLangKey = showLangKey,
            showCommaKey = showCommaKey,
            showPeriodKey = showPeriodKey,
            splitGapS = splitGapS
        )
        if (prevRows != layoutSpec.rows.size) requestLayout()
        recomputeGeometry()
        invalidate()
    }

    fun setShiftState(state: ShiftState) {
        if (shiftState != state) {
            shiftState = state
        accessibilityHelper.invalidateRoot()
            invalidate()
        }
    }

    fun setImeAction(action: Int) {
        if (imeAction != action) {
            imeAction = action
            invalidate()
        }
    }

    fun dismissPopup() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)
        popupView.dismiss()
        activeKey = null
        deleteSwipeTriggered = false
        deleteSwipeSteps = 0
        swipeActive = false
        quickSymbolSlideActive = false
        swipePoints.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        // Exact Breeno aspect ratio: 198dp / 360dp, clamped cleanly for landscape/tablets
        val extraRowScale = if (layoutSpec.rows.size > 4) 1.20f else 1.0f
        val userScale = heightScalePercent.coerceIn(80, 120) / 100f
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orientScale = if (isLandscape && compactLandscape) 0.50f else if (isLandscape) 0.68f else 1.0f
        val aspectHeight = (width * (skin.keyboardRefHeightDp / skin.keyboardRefWidthDp) * extraRowScale * userScale * orientScale).roundToInt()
        val minH = ((if (isLandscape) 142f else 182f) * density * extraRowScale).roundToInt()
        val maxH = ((if (isLandscape) 216f else 348f) * density * extraRowScale).roundToInt()
        val desiredH = aspectHeight.coerceIn(minH, maxH) + (2f * density).roundToInt()
        setMeasuredDimension(width, desiredH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry()
    }

    private val accessibilityHelper = object : androidx.customview.widget.ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = findKeyAt(x, y) ?: return INVALID_ID
            return computedKeys.indexOf(key).takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            computedKeys.indices.forEach { ids.add(it) }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
            val key = computedKeys.getOrNull(id)
            node.contentDescription = if (key == null) "Клавиша" else when (key.spec.action) {
                KeyAction.SPACE -> "Пробел"
                KeyAction.DELETE -> "Удалить символ"
                KeyAction.SHIFT -> if (shiftState == ShiftState.LOCKED) "Заглавные буквы, включены" else "Сменить регистр"
                KeyAction.ENTER -> when (imeAction) {
                    EditorInfo.IME_ACTION_SEND -> "Отправить"
                    EditorInfo.IME_ACTION_SEARCH -> "Поиск"
                    EditorInfo.IME_ACTION_NEXT -> "Далее"
                    EditorInfo.IME_ACTION_DONE -> "Готово"
                    else -> "Ввод"
                }
                KeyAction.LANG_SWITCH -> "Сменить язык"
                KeyAction.EMOJI -> "Эмодзи"
                KeyAction.MODE_ALPHA -> "Буквы"
                KeyAction.MODE_SYMBOLS_1, KeyAction.MODE_SYMBOLS_2 -> "Символы"
                KeyAction.MODE_NUMBER -> "Цифры"
                else -> formatDisplayLabel(key.spec.label)
            }
            node.className = "android.widget.Button"
            node.isClickable = true
            node.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
            val bounds = android.graphics.Rect()
            key?.visualRect?.roundOut(bounds)
            node.setBoundsInParent(bounds)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val key = computedKeys.getOrNull(id) ?: return false
            listener?.onKeyFeedback(key.spec.role)
            dispatchKeyTap(key, key.visualRect.centerX(), key.visualRect.centerY())
            sendEventForVirtualView(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        isFocusable = true
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun recomputeGeometry() {
        if (width <= 0 || height <= 0) return
        val bottomPad = 2f * resources.displayMetrics.density
        computedKeys = BreenoLayouts.computeGeometry(layoutSpec, width.toFloat(), height - bottomPad)
        accessibilityHelper.invalidateRoot()
    }

    private fun getIcon(resId: Int): Drawable? =
        iconCache.getOrPut(resId) { ContextCompat.getDrawable(context, resId)?.mutate() }

    private fun isAccentEnter(): Boolean = when (imeAction) {
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_NEXT -> true
        else -> false
    }

    private fun enterActionLabel(): String? = when (imeAction) {
        EditorInfo.IME_ACTION_SEARCH -> "Поиск"
        EditorInfo.IME_ACTION_SEND -> "Отпр."
        EditorInfo.IME_ACTION_GO -> "Перейти"
        EditorInfo.IME_ACTION_DONE -> "Готово"
        EditorInfo.IME_ACTION_NEXT -> "Далее"
        else -> null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radiusPx = skin.keyRadiusDp * density
        val shadowDy = skin.keyShadowDyPx

        for (ck in computedKeys) {
            val isPressed = ck == activeKey
            val rect = ck.visualRect
            val isActionEnter = ck.spec.role == KeyRole.ENTER && isAccentEnter()
            val isDeleteSwipe = isPressed && deleteSwipeTriggered && ck.spec.action == KeyAction.DELETE

            val bgCfg = when {
                isActionEnter -> skin.enterActionBg
                ck.spec.role == KeyRole.FUNCTION || ck.spec.role == KeyRole.ENTER -> skin.funcKeyBg
                else -> skin.normalKeyBg
            }

            // 1. Shadow (3px solid Breeno shadow in classic mode, soft depth shadow in Glass mode)
            if (skin.keyShadowEnabled) {
                val effectiveShadowDy = if (skin.isGlassKeys) (1.2f * density) else shadowDy
                shadowRect.set(rect.left, rect.top + effectiveShadowDy, rect.right, rect.bottom + effectiveShadowDy)
                shadowPaint.color = bgCfg.shadowColor
                canvas.drawRoundRect(shadowRect, radiusPx, radiusPx, shadowPaint)
            }

            // 2. Main key rounded card
            val perKeyCustomBg = when (ck.spec.action) {
                KeyAction.SHIFT -> skin.customShiftBgColor
                KeyAction.DELETE -> skin.customDeleteBgColor
                KeyAction.LANG_SWITCH -> skin.customLangBgColor
                else -> 0
            }
            keyPaint.color = when {
                isDeleteSwipe -> skin.common.deleteSwipeDangerColor
                perKeyCustomBg != 0 -> if (isPressed) {
                    Color.argb((Color.alpha(perKeyCustomBg) + 35).coerceAtMost(255),
                        (Color.red(perKeyCustomBg) * 0.86f).roundToInt(),
                        (Color.green(perKeyCustomBg) * 0.86f).roundToInt(),
                        (Color.blue(perKeyCustomBg) * 0.86f).roundToInt())
                } else perKeyCustomBg
                isPressed -> bgCfg.pressedColor
                else -> bgCfg.normalColor
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, keyPaint)

            // 3. Liquid Glass specular sheen & luminous rim border (only in Glass themes)
            if (skin.isGlassKeys && !isDeleteSwipe) {
                glassSheenPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.centerY(),
                    skin.glassSheenTopColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, glassSheenPaint)

                glassBorderPaint.strokeWidth = 0.75f * density
                glassBorderPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    skin.glassBorderTopColor, skin.glassBorderBottomColor, Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, glassBorderPaint)
            }

            // 4. Pressed state overlay + crisp tactile focus ring & 65ms release trail for unmistakable hit clarity
            val isTrail = !isPressed && ck === lastReleasedKey && (System.currentTimeMillis() - lastReleaseTimeMs) < 70L
            if (isPressed && !isDeleteSwipe && bgCfg.pressedOverlayColor != Color.TRANSPARENT) {
                overlayPaint.color = bgCfg.pressedOverlayColor
                canvas.drawRoundRect(rect, radiusPx, radiusPx, overlayPaint)
            }
            if (keyPressHighlightEnabled && (isPressed || isTrail) && !isDeleteSwipe) {
                val accent = if (keyPressHighlightColor != 0) keyPressHighlightColor else skin.common.candidateTargetColor
                focusRingPaint.strokeWidth = (if (isPressed) 1.6f else 1.1f) * density
                focusRingPaint.color = Color.argb(
                    if (isPressed) 150 else 65,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent)
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, focusRingPaint)
            }

            // 4. Key content (Original Breeno vector icons or label text)
            drawKeyContent(canvas, ck, isActionEnter, isDeleteSwipe, density)
        }
        if (swipeActive && swipePoints.size >= 2) {
            swipePath.reset()
            swipePath.moveTo(swipePoints[0].x, swipePoints[0].y)
            for (i in 1 until swipePoints.size) {
                swipePath.lineTo(swipePoints[i].x, swipePoints[i].y)
            }
            swipePaint.color = skin.common.candidateTargetColor
            swipePaint.alpha = 185
            swipePaint.strokeWidth = 6.5f * density
            canvas.drawPath(swipePath, swipePaint)
        }
    }

    private fun drawKeyContent(
        canvas: Canvas,
        ck: ComputedKey,
        isActionEnter: Boolean,
        isDeleteSwipe: Boolean,
        density: Float
    ) {
        val rect = ck.visualRect
        val spec = ck.spec
        val cx = rect.centerX()
        val cy = rect.centerY()

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true && am.isTouchExplorationEnabled) {
            val spoken = when (spec.action) {
                KeyAction.SPACE -> "Пробел"
                KeyAction.DELETE -> "Удалить"
                KeyAction.ENTER -> "Ввод"
                KeyAction.SHIFT -> "Регистр"
                KeyAction.LANG_SWITCH -> "Смена языка"
                else -> formatDisplayLabel(spec.label)
            }
            announceForAccessibility(spoken)
        }
        when (spec.action) {
            KeyAction.SHIFT -> {
                val resId = when {
                    shiftState == ShiftState.LOCKED && skin.isDark -> R.drawable.ic_breeno_shift_locked_dark
                    shiftState == ShiftState.LOCKED -> R.drawable.ic_breeno_shift_locked
                    shiftState == ShiftState.ON && skin.isDark -> R.drawable.ic_breeno_shift_on_dark
                    shiftState == ShiftState.ON -> R.drawable.ic_breeno_shift_on
                    skin.isDark -> R.drawable.ic_breeno_shift_off_dark
                    else -> R.drawable.ic_breeno_shift_off
                }
                val sTint = if (skin.customShiftBgColor != 0) {
                    if (BreenoSkinEngine.isColorLight(skin.customShiftBgColor)) Color.parseColor("#EB14161A") else Color.WHITE
                } else null
                drawCenteredIcon(canvas, resId, cx, cy, (22f * density).roundToInt(), tint = sTint)
                return
            }
            KeyAction.DELETE -> {
                val resId = if (skin.isDark || isDeleteSwipe) R.drawable.ic_breeno_delete_dark else R.drawable.ic_breeno_delete
                val dTint = when {
                    isDeleteSwipe -> Color.WHITE
                    skin.customDeleteBgColor != 0 -> if (BreenoSkinEngine.isColorLight(skin.customDeleteBgColor)) Color.parseColor("#EB14161A") else Color.WHITE
                    else -> null
                }
                drawCenteredIcon(canvas, resId, cx, cy, (22f * density).roundToInt(), tint = dTint)
                return
            }
            KeyAction.ENTER -> {
                val resId = when (imeAction) {
                    EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_breeno_action_search
                    EditorInfo.IME_ACTION_SEND -> R.drawable.ic_breeno_action_send
                    EditorInfo.IME_ACTION_DONE -> R.drawable.ic_breeno_action_done
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_breeno_action_go
                    else -> if (skin.isDark || isActionEnter) R.drawable.ic_breeno_enter_dark else R.drawable.ic_breeno_enter
                }
                val iconTint = if (isActionEnter) Color.WHITE else null
                drawCenteredIcon(canvas, resId, cx, cy, (23f * density).roundToInt(), tint = iconTint)
                return
            }
            KeyAction.LANG_SWITCH -> {
                val globeRes = if (skin.isDark) R.drawable.ic_breeno_globe_dark else R.drawable.ic_breeno_globe
                val lTint = if (skin.customLangBgColor != 0) {
                    if (BreenoSkinEngine.isColorLight(skin.customLangBgColor)) Color.parseColor("#EB14161A") else Color.WHITE
                } else null
                drawCenteredIcon(canvas, globeRes, cx, cy, (20f * density).roundToInt(), tint = lTint)
                return
            }
            KeyAction.SPACE -> {
                textPaint.color = skin.keyMarkColor
                textPaint.textSize = 13f * density
                val fm = textPaint.fontMetrics
                canvas.drawText(spec.label, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
                return
            }
            else -> Unit
        }

        // Sub-label mark in top-right corner (monochrome vector smiley for ',' key)
        spec.mark?.let { mark ->
            if (mark == "☺") {
                val sz = (11f * density).roundToInt()
                drawCenteredIcon(
                    canvas,
                    R.drawable.ic_toolbar_emoji,
                    rect.right - 8f * density,
                    rect.top + 8f * density,
                    sz,
                    tint = skin.keyMarkColor
                )
            } else {
                markPaint.color = skin.keyMarkColor
                markPaint.textSize = ck.markSizePx
                canvas.drawText(mark, rect.right - 3.5f * density, rect.top + ck.markSizePx + 1.5f * density, markPaint)
            }
        }

        // Main character / function label
        val displayLabel = formatDisplayLabel(spec.label)
        textPaint.color = if (spec.role == KeyRole.FUNCTION) skin.funcKeyTextColor else skin.keyTextColor
        textPaint.textSize = ck.textSizePx * (keyFontScalePercent / 100f)
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(displayLabel, cx, baseline, textPaint)
    }

    private fun drawCenteredIcon(canvas: Canvas, resId: Int, cx: Float, cy: Float, sizePx: Int, tint: Int?) {
        val d = getIcon(resId) ?: return
        val half = sizePx / 2
        d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        if (tint != null) {
            d.setTint(tint)
        } else {
            d.setTintList(null)
        }
        d.draw(canvas)
    }

    private fun formatDisplayLabel(raw: String): String {
        if (raw.length == 1 && raw[0].isLetter() && shiftState != ShiftState.OFF) {
            return raw.uppercase()
        }
        return raw
    }

    private fun buildEffectiveAlternates(ck: ComputedKey): List<String> {
        if (ck.spec.alternates.isEmpty()) return emptyList()
        return ck.spec.alternates.map { alt ->
            if (alt.length == 1 && alt[0].isLetter() && shiftState != ShiftState.OFF) alt.uppercase() else alt
        }
    }

    private fun findKeyAt(x: Float, y: Float): ComputedKey? {
        computedKeys.firstOrNull { it.visualRect.contains(x, y) }?.let { return it }
        var best: ComputedKey? = null
        var bestDistSq = Float.MAX_VALUE
        for (ck in computedKeys) {
            if (ck.touchRect.contains(x, y)) {
                val dx = x - ck.visualRect.centerX()
                val dy = y - ck.visualRect.centerY()
                var dSq = dx * dx + dy * dy
                if (dynamicHitboxEnabled && likelyNextChars.isNotEmpty() && ck.spec.action == KeyAction.CHAR) {
                    val ch = ck.spec.output.firstOrNull()?.lowercaseChar()
                    if (ch != null && ch in likelyNextChars) dSq *= 0.72f
                }
                if (dSq < bestDistSq) {
                    bestDistSq = dSq
                    best = ck
                }
            }
        }
        return best
    }

    private fun triggerReleaseTrail(ck: ComputedKey?) {
        if (ck == null) return
        lastReleasedKey = ck
        lastReleaseTimeMs = System.currentTimeMillis()
        handler.removeCallbacks(releaseTrailClearRunnable)
        handler.postDelayed(releaseTrailClearRunnable, 68L)
    }

    private var downTimeMs: Long = 0L

    private fun beginKeyPress(key: ComputedKey, x: Float, y: Float, pointerId: Int, density: Float) {
        activeKey = key
        activePointerId = pointerId
        downX = x
        downY = y
        downTimeMs = System.currentTimeMillis()
        deleteSwipeTriggered = false
        spaceSwipeActive = false
        spaceSwipeLastX = x
        spaceSwipeLastY = y
        longPressFired = false
        swipeActive = false
        swipePoints.clear()
        if (swipeTypingEnabled && key.spec.action == KeyAction.CHAR && key.spec.output.length == 1 && key.spec.output[0].isLetter()) {
            swipePoints.add(PointF(x, y))
        }
        listener?.onKeyFeedback(key.spec.role)

        if (popupEnabled && key.spec.role == KeyRole.NORMAL) {
            popupView.showKeyPreview(
                anchor = this,
                skin = skin,
                label = formatDisplayLabel(key.spec.label),
                keyCenterX = key.visualRect.centerX(),
                keyTopY = key.visualRect.top,
                keyWidthPx = key.visualRect.width(),
                keyHeightPx = key.visualRect.height()
            )
        }

        val allowAltLp = longPressSymbolsEnabled && key.spec.alternates.isNotEmpty()
        if (key.spec.action == KeyAction.DELETE || key.spec.action == KeyAction.LANG_SWITCH || allowAltLp || (key.spec.label == "," && key.spec.mark == "☺")) {
            handler.postDelayed(longPressRunnable, longPressDelayMs.toLong().coerceIn(150L, 700L))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (edgeRejectionDp > 0) {
                    val edgePx = edgeRejectionDp * density
                    if (x < edgePx || x > width - edgePx) return false
                    if (event.getTouchMajor(0) > 38f * density) return false
                }
                val key = findKeyAt(x, y) ?: return false
                beginKeyPress(key, x, y, event.getPointerId(0), density)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch fast two-thumb rollover: commit previous thumb's tap immediately and hand off to new thumb!
                val idx = event.actionIndex
                val nx = event.getX(idx)
                val ny = event.getY(idx)
                if (edgeRejectionDp > 0) {
                    val edgePx = edgeRejectionDp * density
                    if (nx < edgePx || nx > width - edgePx) return true
                    if (event.getTouchMajor(idx) > 38f * density) return true
                }
                val nextKey = findKeyAt(nx, ny) ?: return true
                val prevKey = activeKey
                if (prevKey != null && !swipeActive && !popupView.isShowingAlternates() && !deleteSwipeTriggered && !spaceSwipeActive && !longPressFired) {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(deleteRepeatRunnable)
                    dispatchKeyTap(prevKey, downX, downY)
                    triggerReleaseTrail(prevKey)
                }
                popupView.dismiss()
                beginKeyPress(nextKey, nx, ny, event.getPointerId(idx), density)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val key = activeKey ?: return false
                val pIdx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                val x = event.getX(pIdx)
                val y = event.getY(pIdx)

                // 0. Quick symbol slide from ?123 key: drag onto any symbol and release to insert & return to letters!
                if (quickSymbolSlideEnabled && (quickSymbolSlideActive || key.spec.action == KeyAction.MODE_SYMBOLS_1)) {
                    val dist = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                    if (!quickSymbolSlideActive && dist > 18f * density) {
                        quickSymbolSlideActive = true
                        handler.removeCallbacks(longPressRunnable)
                        listener?.onModeChange(KeyboardMode.SYMBOLS_1)
                    }
                    if (quickSymbolSlideActive) {
                        val hovered = findKeyAt(x, y)
                        if (hovered != null && hovered !== activeKey) {
                            activeKey = hovered
                            if (popupEnabled && hovered.spec.role == KeyRole.NORMAL) {
                                popupView.showKeyPreview(
                                    anchor = this,
                                    skin = skin,
                                    label = formatDisplayLabel(hovered.spec.label),
                                    keyCenterX = hovered.visualRect.centerX(),
                                    keyTopY = hovered.visualRect.top,
                                    keyWidthPx = hovered.visualRect.width(),
                                    keyHeightPx = hovered.visualRect.height()
                                )
                            } else {
                                popupView.dismiss()
                            }
                            invalidate()
                        }
                        return true
                    }
                }

                // 1. Long-press alternates selection slide
                if (popupView.isShowingAlternates()) {
                    popupView.updateSelectionByX(x)
                    return true
                }

                // 2. Backspace swipe-left-to-delete-word gesture (with interactive step preview)
                if (key.spec.action == KeyAction.DELETE) {
                    val dx = downX - x
                    if (dx > 30f * density) {
                        if (!deleteSwipeTriggered) {
                            deleteSwipeTriggered = true
                            handler.removeCallbacks(longPressRunnable)
                            handler.removeCallbacks(deleteRepeatRunnable)
                            invalidate()
                        }
                        if (interactiveDeleteSwipeEnabled) {
                            val steps = (dx / (32f * density)).toInt().coerceIn(0, 4)
                            if (steps != deleteSwipeSteps) {
                                deleteSwipeSteps = steps
                                listener?.onDeleteSwipePreview(steps)
                            }
                        }
                    } else if (deleteSwipeTriggered && interactiveDeleteSwipeEnabled && deleteSwipeSteps != 0) {
                        deleteSwipeSteps = 0
                        listener?.onDeleteSwipePreview(0)
                    }
                    return true
                }

                // 3. Spacebar 2D trackpad cursor control (horizontal + vertical lines)
                if (key.spec.action == KeyAction.SPACE) {
                    val stepPx = 14f * density
                    val stepPy = 26f * density
                    val deltaX = x - spaceSwipeLastX
                    val deltaY = y - spaceSwipeLastY
                    if (abs(x - downX) > 18f * density || abs(y - downY) > 24f * density) {
                        spaceSwipeActive = true
                    }
                    if (spaceSwipeActive && abs(deltaX) >= stepPx) {
                        val steps = (deltaX / stepPx).toInt()
                        if (steps != 0) {
                            listener?.onCursorMove(steps)
                            spaceSwipeLastX += steps * stepPx
                        }
                    }
                    if (spaceSwipeActive && abs(deltaY) >= stepPy) {
                        val vSteps = (deltaY / stepPy).toInt()
                        if (vSteps != 0) {
                            listener?.onCursorMoveVertical(vSteps)
                            spaceSwipeLastY += vSteps * stepPy
                        }
                    }
                    return true
                }

                // 4. Continuous Swipe Typing (only when started on a letter key)
                if (swipeTypingEnabled && swipePoints.isNotEmpty() && !longPressFired) {
                    val distFromDown = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                    val threshold = (key.visualRect.width() * 1.15f).coerceAtLeast(34f * density)
                    if (!swipeActive && distFromDown >= threshold) {
                        swipeActive = true
                        handler.removeCallbacks(longPressRunnable)
                        popupView.dismiss()
                    }
                    if (swipeActive) {
                        val lastPt = swipePoints.last()
                        val seg = kotlin.math.hypot((x - lastPt.x).toDouble(), (y - lastPt.y).toDouble()).toFloat()
                        if (seg >= 5f * density) {
                            swipePoints.add(PointF(x, y))
                            if (swipePoints.size > 120) swipePoints.removeAt(1)
                            invalidate()
                        }
                        return true
                    }
                }

                // 5. Live Finger-Slide Key Tracking (when not swiping: highlight & popup track exact key under thumb, never sliding into Enter/Delete!)
                if (!swipeActive && !longPressFired && !swipeTypingEnabled && key.spec.action == KeyAction.CHAR) {
                    val hovered = findKeyAt(x, y)
                    if (hovered != null && hovered !== key && hovered.spec.action == KeyAction.CHAR) {
                        activeKey = hovered
                        downX = x
                        downY = y
                        handler.removeCallbacks(longPressRunnable)
                        if (popupEnabled && hovered.spec.role == KeyRole.NORMAL) {
                            popupView.showKeyPreview(
                                anchor = this,
                                skin = skin,
                                label = formatDisplayLabel(hovered.spec.label),
                                keyCenterX = hovered.visualRect.centerX(),
                                keyTopY = hovered.visualRect.top,
                                keyWidthPx = hovered.visualRect.width(),
                                keyHeightPx = hovered.visualRect.height()
                            )
                        }
                        invalidate()
                        return true
                    }
                }

                // Cancel long press if finger slides far away
                if (abs(x - downX) > 22f * density || abs(y - downY) > 22f * density) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upPointerId = event.getPointerId(event.actionIndex)
                if (upPointerId != activePointerId) {
                    // Previous thumb lifted after rollover already committed its key
                    return true
                }
                // Fall through to finish active pointer cleanly
                val idx = event.actionIndex
                finishActiveTouch(event.getX(idx), event.getY(idx), density)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pIdx = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                finishActiveTouch(event.getX(pIdx), event.getY(pIdx), density)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dismissPopup()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishActiveTouch(x: Float, y: Float, density: Float) {
        val key = activeKey
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)

        if (key != null) {
            val sensMul = when (touchSensitivity) { 0 -> 1.25f; 2 -> 0.78f; else -> 1.0f }
            when {
                quickSymbolSlideActive -> {
                    val target = findKeyAt(x, y) ?: key
                    if (target.spec.action == KeyAction.CHAR) {
                        dispatchKeyTap(target, x, y)
                    }
                    listener?.onModeChange(KeyboardMode.ALPHA_RU)
                }
                popupView.isShowingAlternates() -> {
                    popupView.getSelectedAlternate()?.let { listener?.onCharInput(it) }
                }
                deleteSwipeTriggered && key.spec.action == KeyAction.DELETE -> {
                    if (interactiveDeleteSwipeEnabled) {
                        listener?.onDeleteSwipeCommit(deleteSwipeSteps)
                    } else {
                        listener?.onDeleteWord()
                    }
                }
                spaceSwipeActive && key.spec.action == KeyAction.SPACE -> {
                    // Cursor swipe already handled
                }
                swipeActive && swipePoints.size >= 4 -> {
                    listener?.onSwipeGesture(ArrayList(swipePoints), computedKeys)
                }
                longPressFired -> {
                    // Long press action already handled (delete repeat or emoji)
                }
                key.spec.action == KeyAction.MODE_SYMBOLS_1 && (downY - y) > 26f * density -> {
                    listener?.onModeChange(KeyboardMode.NUMBER_PAD)
                }
                quickFlickEnabled && !swipeActive && key.spec.action == KeyAction.CHAR &&
                    !key.spec.mark.isNullOrEmpty() &&
                    (downY - y) > 22f * density * sensMul &&
                    (downY - y) > abs(x - downX) * 1.35f -> {
                    val flickChar = key.spec.mark
                    if (flickChar == "☺") listener?.onOpenEmojiFromKey()
                    else listener?.onCharInput(flickChar)
                }
                flickDownSymbolsEnabled && !swipeActive && key.spec.action == KeyAction.CHAR &&
                    (!key.spec.mark.isNullOrEmpty() || key.spec.alternates.isNotEmpty()) &&
                    (y - downY) > 20f * density * sensMul &&
                    (y - downY) > abs(x - downX) * 1.3f -> {
                    val symChar = key.spec.mark ?: key.spec.alternates.firstOrNull()
                    if (symChar == "☺") listener?.onOpenEmojiFromKey()
                    else if (symChar != null) listener?.onCharInput(symChar)
                }
                else -> {
                    val dt = System.currentTimeMillis() - downTimeMs
                    val moveDist = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                    if (key.spec.action == KeyAction.CHAR && dt < 14L && moveDist < 4f * density) {
                        // Ignore ultra-brief capacitive ghost/palm spike
                    } else if (key.spec.action == KeyAction.CHAR && moveDist >= 8f * density) {
                        val releaseKey = findKeyAt(x, y)
                        if (releaseKey != null && releaseKey.spec.action == KeyAction.CHAR && releaseKey.visualRect.contains(x, y)) {
                            dispatchKeyTap(releaseKey, x, y)
                        } else {
                            dispatchKeyTap(key, downX, downY)
                        }
                    } else {
                        dispatchKeyTap(key, downX, downY)
                    }
                }
            }
            triggerReleaseTrail(key)
        }
        popupView.dismiss()
        activeKey = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        deleteSwipeTriggered = false
        spaceSwipeActive = false
        swipeActive = false
        swipePoints.clear()
        invalidate()
    }

    private fun startDeleteRepeat() {
        listener?.onDeleteChar()
        handler.postDelayed(deleteRepeatRunnable, 75L)
    }

    private fun dispatchKeyTap(ck: ComputedKey, tapX: Float = downX, tapY: Float = downY) {
        val spec = ck.spec
        val now = System.currentTimeMillis()
        val bounceMs = when (spec.action) {
            KeyAction.CHAR -> 34L
            KeyAction.SPACE, KeyAction.ENTER, KeyAction.LANG_SWITCH -> 68L
            else -> 0L
        }
        if (bounceMs > 0L && spec.label == lastDispatchedSpecLabel && (now - lastDispatchedTimeMs) < bounceMs) {
            return
        }
        lastDispatchedSpecLabel = spec.label
        lastDispatchedTimeMs = now
        when (spec.action) {
            KeyAction.CHAR -> {
                if (spec.output.length == 1 && spec.output[0].isLetter()) {
                    listener?.onLetterTap(ck, tapX, tapY, computedKeys)
                } else {
                    listener?.onCharInput(formatDisplayLabel(spec.output))
                }
            }
            KeyAction.SHIFT -> listener?.onShiftPressed()
            KeyAction.DELETE -> listener?.onDeleteChar()
            KeyAction.SPACE -> listener?.onSpacePressed()
            KeyAction.ENTER -> listener?.onEnterPressed()
            KeyAction.LANG_SWITCH -> listener?.onLanguageSwitch()
            KeyAction.EMOJI -> listener?.onOpenEmojiFromKey()
            KeyAction.MODE_SYMBOLS_1 -> listener?.onModeChange(KeyboardMode.SYMBOLS_1)
            KeyAction.MODE_SYMBOLS_2 -> listener?.onModeChange(KeyboardMode.SYMBOLS_2)
            KeyAction.MODE_NUMBER -> listener?.onModeChange(KeyboardMode.NUMBER_PAD)
            KeyAction.MODE_ALPHA -> listener?.onModeChange(KeyboardMode.ALPHA_RU) // Service resolves active alpha lang
        }
    }

    fun getLetterKeyCenters(): Triple<Map<Char, PointF>, Float, Float> {
        val map = HashMap<Char, PointF>(36)
        var sumW = 0f
        var sumH = 0f
        var count = 0
        for (ck in computedKeys) {
            if (ck.spec.action == KeyAction.CHAR && ck.spec.output.length == 1 && ck.spec.output[0].isLetter()) {
                val ch = ck.spec.output[0].lowercaseChar()
                map[ch] = PointF(ck.visualRect.centerX(), ck.visualRect.centerY())
                sumW += ck.visualRect.width()
                sumH += ck.visualRect.height()
                count++
            }
        }
        val avgW = if (count > 0) sumW / count else 40f * resources.displayMetrics.density
        val avgH = if (count > 0) sumH / count else 50f * resources.displayMetrics.density
        return Triple(map, avgW, avgH)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        popupView.dismiss()
        super.onDetachedFromWindow()
    }
}