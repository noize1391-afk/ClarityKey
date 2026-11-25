package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.claritykey.keyboard.engine.UnicodeStyle
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import kotlin.math.roundToInt

/**
 * Breeno Text Editing & Quick Power-Tools Panel:
 * - Live character & word counter strip
 * - Power tools row: Punto Switcher (RU⇄EN), Translate, Transliterate, Clean Text, Unicode Style
 * - Directional cursor pad, Select, Select All, Cut, Copy, Paste, Home/End
 */
class BreenoTextEditPanelView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onMoveCursor(keyCode: Int, selecting: Boolean)
        fun onEditAction(actionId: Int)
        fun onPuntoSwitchSelected()
        fun onQuickTranslateSelected()
        fun onTransliterateSelected()
        fun onCleanTextSelected()
        fun onCycleUnicodeStyle()
        fun onToggleManualIncognito()
        fun onCycleOneHanded()
        fun onToggleFloating()
        fun onUndoEdit()
        fun onRedoEdit()
        fun onSelectCurrentWord()
        fun onDeleteToLineStart()
        fun onCopyAllText()
        fun onEditClose()
    }

    var listener: Listener? = null

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var selecting: Boolean = false
    private var incognitoActive: Boolean = false
    private var activeStyle: UnicodeStyle = UnicodeStyle.NORMAL
    private var charCount: Int = 0
    private var wordCount: Int = 0
    private val density = context.resources.displayMetrics.density

    init {
        orientation = VERTICAL
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (w * (skin.keyboardRefHeightDp / skin.keyboardRefWidthDp)).roundToInt()
            .coerceIn(dp(224f), dp(306f)) + dp(8f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(if (skin.isGlassBackground) Color.TRANSPARENT else skin.common.keyboardBackgroundColor)
        render()
    }

    fun setIncognitoState(active: Boolean) {
        if (incognitoActive != active) {
            incognitoActive = active
            render()
        }
    }

    fun setUnicodeStyle(style: UnicodeStyle) {
        if (activeStyle != style) {
            activeStyle = style
            render()
        }
    }

    fun setTextStats(chars: Int, words: Int) {
        if (charCount != chars || wordCount != words) {
            charCount = chars
            wordCount = words
            render()
        }
    }

    private fun render() {
        removeAllViews()
        setPadding(dp(8f), dp(4f), dp(8f), dp(6f))

        // Compact live counter + quick Incognito indicator bar
        val statsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(3f))
        }
        statsRow.addView(TextView(context).apply {
            text = "Симв: $charCount • Слов: $wordCount"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(skin.keyMarkColor)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(TextView(context).apply {
            text = "↶ Отм."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { listener?.onUndoEdit() }
        })
        statsRow.addView(TextView(context).apply {
            text = "↷ Повт."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            setPadding(dp(4f), 0, dp(10f), 0)
            setOnClickListener { listener?.onRedoEdit() }
        })
        statsRow.addView(TextView(context).apply {
            text = if (incognitoActive) "🕶 Инкогнито ВКЛ" else "🕶 Инкогнито"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (incognitoActive) skin.common.candidateTargetColor else skin.keyMarkColor)
            setOnClickListener { listener?.onToggleManualIncognito() }
        })
        addView(statsRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Row 1: Power Tools (RU⇄EN, Перевод, Транслит, Очистить, Стиль Unicode)
        addView(makeRow(
            makeBtn("RU⇄EN", func = true, small = true) { listener?.onPuntoSwitchSelected() },
            makeBtn("Перевод", func = true, small = true) { listener?.onQuickTranslateSelected() },
            makeBtn("Транслит", func = true, small = true) { listener?.onTransliterateSelected() },
            makeBtn("Очистить", func = true, small = true) { listener?.onCleanTextSelected() },
            makeBtn(activeStyle.shortLabel, accent = activeStyle != UnicodeStyle.NORMAL, func = activeStyle == UnicodeStyle.NORMAL, small = true) {
                listener?.onCycleUnicodeStyle()
            }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.88f))

        // Row 2: Select All | Cut | Copy | Paste
        addView(makeRow(
            makeBtn("Всё", func = true) { listener?.onEditAction(android.R.id.selectAll) },
            makeBtn("Вырезать", func = true) { listener?.onEditAction(android.R.id.cut) },
            makeBtn("Копировать", func = true) { listener?.onEditAction(android.R.id.copy) },
            makeBtn("Вставить", func = true) { listener?.onEditAction(android.R.id.paste) }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.90f))

        // Row 2.5: Select Word | Delete to Line Start | Copy All
        addView(makeRow(
            makeBtn("Выд. слово", func = true, small = true) { listener?.onSelectCurrentWord() },
            makeBtn("Уд. строку", func = true, small = true) { listener?.onDeleteToLineStart() },
            makeBtn("Копир. всё", func = true, small = true) { listener?.onCopyAllText() }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.80f))

        // Row 3: Home | ↑ | End
        addView(makeRow(
            makeBtn("⇤ В начало", func = true) { listener?.onMoveCursor(KeyEvent.KEYCODE_MOVE_HOME, selecting) },
            makeBtn("↑", func = false) { listener?.onMoveCursor(KeyEvent.KEYCODE_DPAD_UP, selecting) },
            makeBtn("В конец ⇥", func = true) { listener?.onMoveCursor(KeyEvent.KEYCODE_MOVE_END, selecting) }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.96f))

        // Row 4: ← | Выделение (toggle) | →
        addView(makeRow(
            makeBtn("←", func = false) { listener?.onMoveCursor(KeyEvent.KEYCODE_DPAD_LEFT, selecting) },
            makeBtn(if (selecting) "Выделение ВКЛ" else "Выделить", accent = selecting, func = !selecting) {
                selecting = !selecting
                render()
            },
            makeBtn("→", func = false) { listener?.onMoveCursor(KeyEvent.KEYCODE_DPAD_RIGHT, selecting) }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.96f))

        // Row 5: АБВ | ↓ | Готово
        addView(makeRow(
            makeBtn("АБВ", func = true) { listener?.onEditClose() },
            makeBtn("↓", func = false) { listener?.onMoveCursor(KeyEvent.KEYCODE_DPAD_DOWN, selecting) },
            makeBtn("Готово", accent = true) { listener?.onEditClose() }
        ), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.96f))
    }

    private fun makeRow(vararg views: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        views.forEach { v ->
            addView(v, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f))
            })
        }
    }

    private fun makeBtn(label: String, func: Boolean = false, accent: Boolean = false, small: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
                label.length <= 2 -> 18f
                small -> 11f
                else -> 12.5f
            })
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(
                when {
                    accent -> Color.WHITE
                    func -> skin.funcKeyTextColor
                    else -> skin.keyTextColor
                }
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(
                    when {
                        accent -> skin.enterActionBg.normalColor
                        func -> skin.funcKeyBg.normalColor
                        else -> skin.normalKeyBg.normalColor
                    }
                )
            }
            setOnClickListener { onClick() }
        }
}
