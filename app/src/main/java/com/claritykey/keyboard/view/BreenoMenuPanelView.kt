package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.PathInterpolator
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Breeno Quick Menu Hub (2-page 2x3 pill-card grid) with smooth interactive 1:1 finger-tracking
 * page swipe animation, rubber-band edge resistance, FastOutSlowInInterpolator settle,
 * and animated pill-dot indicators.
 */
class BreenoMenuPanelView(context: Context) : LinearLayout(context) {

    enum class MenuAction {
        EMOJI,
        THEME,
        TEXT_EDIT,
        RESIZE,
        CLIPBOARD,
        TEMPLATES,
        ONE_HANDED,
        FLOATING,
        NEXT_LANGUAGE,
        TRANSLATE,
        INCOGNITO,
        AUTOFILL,
        CUSTOMIZE_TOOLBAR
    }

    interface Listener {
        fun onMenuAction(action: MenuAction)
        fun onMenuClose()
    }

    var listener: Listener? = null

    private data class MenuItem(
        val action: MenuAction,
        val icon: String,
        val label: String,
        val active: Boolean = false
    )

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var currentPage: Int = 0
    private var incognitoActive: Boolean = false
    private var oneHandedActive: Boolean = false
    private var floatingActive: Boolean = false

    private var swipeDownX: Float = 0f
    private var swipeDownY: Float = 0f
    private var isDraggingPage: Boolean = false
    private var velocityTracker: VelocityTracker? = null

    private val density = context.resources.displayMetrics.density
    private val smoothInterp = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val pagesViewport = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
    }
    private val page0Grid = GridLayout(context).apply {
        columnCount = 2
        rowCount = 3
    }
    private val page1Grid = GridLayout(context).apply {
        columnCount = 2
        rowCount = 3
    }
    private val footerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = VERTICAL
        pagesViewport.addView(page0Grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pagesViewport.addView(page1Grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(pagesViewport, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footerRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (w * (skin.keyboardRefHeightDp / skin.keyboardRefWidthDp)).roundToInt()
            .coerceIn(dp(212f), dp(296f)) + dp(8f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        snapPagesImmediate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                isDraggingPage = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = abs(ev.x - swipeDownX)
                val dy = abs(ev.y - swipeDownY)
                if (dx > dp(14f) && dx > dy * 1.25f) {
                    isDraggingPage = true
                    page0Grid.animate().cancel()
                    page1Grid.animate().cancel()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingPage = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val vw = pagesViewport.width.coerceAtLeast(1).toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                var dx = ev.x - swipeDownX
                // Gentle rubber-band resistance past page bounds
                if ((currentPage == 0 && dx > 0f) || (currentPage == 1 && dx < 0f)) {
                    dx *= 0.28f
                }
                val baseOffset = -currentPage * vw
                page0Grid.translationX = baseOffset + dx
                page1Grid.translationX = baseOffset + vw + dx
                val progress = (-(baseOffset + dx) / vw).coerceIn(0f, 1f)
                page0Grid.alpha = 1f - progress * 0.25f
                page1Grid.alpha = 0.75f + progress * 0.25f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(ev)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                isDraggingPage = false

                val dx = ev.x - swipeDownX
                val targetPage = when {
                    currentPage == 0 && (dx < -vw * 0.22f || vx < -420f) -> 1
                    currentPage == 1 && (dx > vw * 0.22f || vx > 420f) -> 0
                    else -> currentPage
                }
                animateToPage(targetPage)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun snapPagesImmediate() {
        val vw = pagesViewport.width.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
        page0Grid.animate().cancel()
        page1Grid.animate().cancel()
        page0Grid.translationX = if (currentPage == 0) 0f else -vw
        page1Grid.translationX = if (currentPage == 0) vw else 0f
        page0Grid.alpha = if (currentPage == 0) 1f else 0.75f
        page1Grid.alpha = if (currentPage == 1) 1f else 0.75f
    }

    private fun animateToPage(target: Int) {
        val next = target.coerceIn(0, 1)
        val changed = next != currentPage
        currentPage = next
        val vw = pagesViewport.width.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
        val p0Target = if (currentPage == 0) 0f else -vw
        val p1Target = if (currentPage == 0) vw else 0f

        page0Grid.animate()
            .translationX(p0Target)
            .alpha(if (currentPage == 0) 1f else 0.75f)
            .setDuration(230L)
            .setInterpolator(smoothInterp)
            .start()
        page1Grid.animate()
            .translationX(p1Target)
            .alpha(if (currentPage == 1) 1f else 0.75f)
            .setDuration(230L)
            .setInterpolator(smoothInterp)
            .start()

        if (changed) renderFooter()
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(if (skin.isGlassBackground) Color.TRANSPARENT else skin.common.keyboardBackgroundColor)
        renderAllPages()
    }

    fun updateStates(incognito: Boolean, oneHanded: Boolean, floating: Boolean) {
        if (incognitoActive != incognito || oneHandedActive != oneHanded || floatingActive != floating) {
            incognitoActive = incognito
            oneHandedActive = oneHanded
            floatingActive = floating
            renderAllPages()
        }
    }

    fun resetToFirstPage() {
        currentPage = 0
        renderFooter()
        snapPagesImmediate()
    }

    private fun buildPages(): List<List<MenuItem>> = listOf(
        listOf(
            MenuItem(MenuAction.EMOJI, "☺", "Эмодзи"),
            MenuItem(MenuAction.THEME, "◐", "Тема"),
            MenuItem(MenuAction.TEXT_EDIT, "⇕", "Редактор"),
            MenuItem(MenuAction.RESIZE, "⛶", "Размер"),
            MenuItem(MenuAction.CLIPBOARD, "📋", "Буфер"),
            MenuItem(MenuAction.TEMPLATES, "⚡", "Шаблоны")
        ),
        listOf(
            MenuItem(MenuAction.ONE_HANDED, "◧", "Одной рукой", active = oneHandedActive),
            MenuItem(MenuAction.FLOATING, "▣", "Плавающий", active = floatingActive),
            MenuItem(MenuAction.NEXT_LANGUAGE, "🌐", "Язык RU/EN"),
            MenuItem(MenuAction.TRANSLATE, "⇄", "Перевод"),
            MenuItem(MenuAction.INCOGNITO, "🕶", "Инкогнито", active = incognitoActive),
            MenuItem(MenuAction.AUTOFILL, "✦", "Автозаполн.")
        )
    )

    private fun renderAllPages() {
        val pages = buildPages()
        populateGrid(page0Grid, pages[0])
        populateGrid(page1Grid, pages[1])
        renderFooter()
        snapPagesImmediate()
    }

    private fun populateGrid(targetGrid: GridLayout, items: List<MenuItem>) {
        targetGrid.removeAllViews()
        targetGrid.setPadding(dp(10f), dp(8f), dp(10f), dp(4f))
        val pillRadius = dp(22f).toFloat()

        items.forEach { item ->
            val pill = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(10f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = pillRadius
                    setColor(if (item.active) skin.enterActionBg.normalColor else skin.normalKeyBg.normalColor)
                    if (!item.active && skin.isGlassKeys) {
                        setStroke(dp(0.75f).coerceAtLeast(1), skin.glassBorderTopColor)
                    }
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
                }
                setOnClickListener { listener?.onMenuAction(item.action) }
            }

            val txtColor = if (item.active) skin.enterActionTextColor else skin.keyTextColor
            val iconTv = TextView(context).apply {
                text = item.icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                setTextColor(txtColor)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val labelTv = TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(txtColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(7f), 0, dp(3f), 0)
            }
            val arrowTv = TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(if (item.active) skin.enterActionTextColor else skin.keyMarkColor)
            }

            pill.addView(iconTv)
            pill.addView(labelTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pill.addView(arrowTv)
            targetGrid.addView(pill)
        }
    }

    private fun renderFooter() {
        footerRow.removeAllViews()
        footerRow.setPadding(dp(14f), dp(2f), dp(14f), dp(6f))

        footerRow.addView(View(context), LayoutParams(dp(56f), 1))

        val dotsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setOnClickListener {
                animateToPage(if (currentPage == 0) 1 else 0)
            }
        }
        for (i in 0..1) {
            val active = i == currentPage
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(if (active) skin.common.candidateTargetColor else skin.keyMarkColor)
                }
                layoutParams = LayoutParams(dp(if (active) 16f else 7f), dp(7f)).apply {
                    setMargins(dp(4f), dp(6f), dp(4f), dp(6f))
                }
            }
            dotsRow.addView(dot)
        }
        footerRow.addView(dotsRow, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val editBtn = TextView(context).apply {
            text = "✎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(skin.keyTextColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(skin.funcKeyBg.normalColor)
                setStroke(dp(1f), skin.keyMarkColor)
            }
            layoutParams = LayoutParams(dp(56f), dp(30f))
            setOnClickListener { listener?.onMenuAction(MenuAction.CUSTOMIZE_TOOLBAR) }
        }
        footerRow.addView(editBtn)
    }
}
