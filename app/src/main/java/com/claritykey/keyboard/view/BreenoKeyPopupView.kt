package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import com.claritykey.keyboard.skin.KeyboardSkin
import kotlin.math.roundToInt

/**
 * Reconstructs Breeno's KeyPopView (KeyPopConfig / KeyPopSkin):
 * - Single-key tap preview bubble
 * - Long-press alternate character strip with finger-slide selection
 */
class BreenoKeyPopupView(private val context: Context) {

    private var popupWindow: PopupWindow? = null
    private var contentView: PopupCanvasView? = null
    private var items: List<String> = emptyList()
    private var selectedIndex: Int = 0
    private var isAlternatesMode: Boolean = false
    private var anchorX: Float = 0f
    private var itemWidthPx: Float = 0f

    fun showKeyPreview(
        anchor: View,
        skin: KeyboardSkin,
        label: String,
        keyCenterX: Float,
        keyTopY: Float,
        keyWidthPx: Float,
        keyHeightPx: Float
    ) {
        val density = context.resources.displayMetrics.density
        isAlternatesMode = false
        items = listOf(label)
        selectedIndex = -1

        val w = (keyWidthPx * 1.45f).coerceAtLeast(46f * density).roundToInt()
        val h = (keyHeightPx * 1.32f).coerceAtLeast(56f * density).roundToInt()
        showPopup(anchor, skin, w, h, keyCenterX, keyTopY, isAlternates = false)
    }

    fun showAlternates(
        anchor: View,
        skin: KeyboardSkin,
        alternates: List<String>,
        keyCenterX: Float,
        keyTopY: Float,
        keyHeightPx: Float
    ) {
        if (alternates.isEmpty()) return
        val density = context.resources.displayMetrics.density
        isAlternatesMode = true
        items = alternates
        selectedIndex = 0
        itemWidthPx = 42f * density
        anchorX = keyCenterX

        val pad = (8f * density).roundToInt()
        val w = (items.size * itemWidthPx + pad * 2).roundToInt()
        val h = (keyHeightPx * 1.15f).coerceAtLeast(48f * density).roundToInt()
        showPopup(anchor, skin, w, h, keyCenterX, keyTopY, isAlternates = true)
    }

    fun updateSelectionByX(touchX: Float) {
        if (!isAlternatesMode || items.isEmpty() || itemWidthPx <= 0f) return
        val delta = touchX - anchorX
        val offsetIdx = (delta / itemWidthPx).roundToInt()
        val newIdx = offsetIdx.coerceIn(0, items.lastIndex)
        if (newIdx != selectedIndex) {
            selectedIndex = newIdx
            contentView?.setState(items, selectedIndex, isAlternatesMode)
        }
    }

    fun getSelectedAlternate(): String? {
        if (!isAlternatesMode || selectedIndex !in items.indices) return null
        return items[selectedIndex]
    }

    fun isShowingAlternates(): Boolean = isAlternatesMode && popupWindow?.isShowing == true

    fun dismiss() {
        isAlternatesMode = false
        popupWindow?.dismiss()
    }

    private fun showPopup(
        anchor: View,
        skin: KeyboardSkin,
        widthPx: Int,
        heightPx: Int,
        keyCenterX: Float,
        keyTopY: Float,
        isAlternates: Boolean
    ) {
        val density = context.resources.displayMetrics.density
        val view = contentView ?: PopupCanvasView(context).also { contentView = it }
        view.skin = skin
        view.setState(items, selectedIndex, isAlternates)

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = context.resources.displayMetrics.widthPixels

        val rawX = (loc[0] + keyCenterX - widthPx / 2f).roundToInt()
        val clampedX = rawX.coerceIn((4f * density).roundToInt(), (screenW - widthPx - 4f * density).roundToInt())
        val y = (loc[1] + keyTopY - heightPx - 6f * density).roundToInt()

        val pw = popupWindow ?: PopupWindow(view, widthPx, heightPx, false).apply {
            isTouchable = false
            isClippingEnabled = false
            elevation = 6f * density
            popupWindow = this
        }

        if (pw.isShowing) {
            pw.update(clampedX, y, widthPx, heightPx)
        } else {
            pw.width = widthPx
            pw.height = heightPx
            pw.showAtLocation(anchor, Gravity.NO_GRAVITY, clampedX, y)
        }
    }

    private class PopupCanvasView(context: Context) : View(context) {
        var skin: KeyboardSkin = com.claritykey.keyboard.skin.BreenoSkinEngine.LIGHT_SKIN
        private var labels: List<String> = emptyList()
        private var selected: Int = -1
        private var alternatesMode: Boolean = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val rect = RectF()
        private val pillRect = RectF()

        fun setState(items: List<String>, selectedIdx: Int, isAlternates: Boolean) {
            labels = items
            selected = selectedIdx
            alternatesMode = isAlternates
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val radius = 10f * d
            val inset = 1.5f * d
            rect.set(inset, inset, width - inset, height - inset)

            bgPaint.color = skin.keyPop.backgroundColor
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            strokePaint.color = skin.keyPop.strokeColor
            strokePaint.strokeWidth = 1f * d
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            if (labels.isEmpty()) return
            val padX = if (alternatesMode) 6f * d else 0f
            val cellW = (width - padX * 2f) / labels.size
            textPaint.textSize = if (alternatesMode) 20f * d else 26f * d
            val fm = textPaint.fontMetrics
            val baseline = height / 2f - (fm.ascent + fm.descent) / 2f

            labels.forEachIndexed { idx, txt ->
                val cx = padX + cellW * (idx + 0.5f)
                if (alternatesMode && idx == selected) {
                    pillPaint.color = skin.keyPop.itemSelectBackgroundColor
                    pillRect.set(
                        padX + cellW * idx + 2f * d,
                        5f * d,
                        padX + cellW * (idx + 1) - 2f * d,
                        height - 5f * d
                    )
                    canvas.drawRoundRect(pillRect, 7f * d, 7f * d, pillPaint)
                    textPaint.color = skin.keyPop.textColorSelected
                } else {
                    textPaint.color = if (alternatesMode) skin.keyPop.textColor else skin.keyTextColor
                }
                canvas.drawText(txt, cx, baseline, textPaint)
            }
        }
    }
}
