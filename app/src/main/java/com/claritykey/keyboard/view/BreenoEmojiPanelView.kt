package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.claritykey.keyboard.R
import com.claritykey.keyboard.engine.LayoutSwitcherAndTranslator
import com.claritykey.keyboard.engine.TextPowerTools
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import kotlin.math.roundToInt

/**
 * Full Emoji & Typography Panel styled in Breeno's Light & Dark palette:
 * - Bilingual RU/EN Emoji search bar ("огонь" / "fire" -> 🔥)
 * - Recents + 6 Emoji categories + Typography/Symbols tab (§ « » — ₽ €)
 * - ABC back + Backspace
 */
class BreenoEmojiPanelView(
    context: Context,
    private val prefs: BreenoPreferences
) : LinearLayout(context) {

    interface Listener {
        fun onEmojiSelected(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiClose()
        fun onStickerSelected(path: String)
        fun onAddStickerClick()
    }

    var listener: Listener? = null

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var selectedCategory: Int = 1 // Smileys by default (0 = Recents, 7 = Typography)
    private var searchQuery: String = ""
    private val density = context.resources.displayMetrics.density

    private val searchBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private lateinit var searchInput: EditText
    private val scrollView = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val grid = GridLayout(context).apply { columnCount = 8 }
    private val bottomBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private data class Category(val icon: String, val emojis: List<String>)

    private val categories = listOf(
        Category("🕒", emptyList()),
        Category("😀", listOf(
            "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩",
            "😘","😗","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😐",
            "😑","😶","😏","😒","🙄","😬","😮‍💨","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕",
            "🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🤠","🥳","🥸","😎","🤓","🧐","😕","😟",
            "🙁","😮","😯","😲","😳","🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖","😣",
            "😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈","👿","💀","🤡","👻","👽","🤖"
        )),
        Category("👍", listOf(
            "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙","👈","👉","👆",
            "👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🤝","🙏","💪","🦾",
            "🫶","🫰","🫵","👀","🧠","🫀","👤","👥","🫂","🔥","⚡","💥","✨","🎉","🎊","💯"
        )),
        Category("❤️", listOf(
            "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❤️‍🔥","❤️‍🩹","💕","💞","💓","💗",
            "💖","💘","💝","💟","❣️","💋","💌","💐","🌹","🥀","🌺","🌸","🌼","🌻","☀️","🌙",
            "⭐","🌟","💫","🌈","☁️","🌧","⛈","❄️","☃️","🌊","🍀","🌿","🍁","🍂","🌍","🚀"
        )),
        Category("🍕", listOf(
            "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥝","🍅",
            "🥑","🥦","🥕","🌽","🌶","🥔","🥐","🍞","🧀","🥚","🍳","🥓","🥩","🍗","🍖","🌭",
            "🍔","🍟","🍕","🌮","🌯","🥗","🍜","🍝","🍣","🍤","🥟","🍦","🍩","🍪","🎂","🍰",
            "🍫","☕","🍵","🥤","🍺","🍻","🥂","🍷","🥃","🍸","🍹","🍾"
        )),
        Category("⚽", listOf(
            "⚽","🏀","🏈","⚾","🎾","🏐","🏉","🎱","🏓","🏸","🏒","🥊","🥋","🏋️","🚴","🏊",
            "🏃","🎮","🕹","🎲","🎯","🎳","🎸","🎹","🥁","🎷","🎺","🎻","🎧","🎤","🎬","🎨",
            "🚗","🚕","🚌","🏎","🚓","🚑","🚒","✈️","🚀","🛸","🚢","⚓","🏠","🏢","🏖","🏔"
        )),
        Category("✅", listOf(
            "✅","☑️","✔️","❌","❎","➕","➖","➗","✖️","❓","❔","❕","❗","‼️","⁉️","⚠️",
            "🚫","⛔","🔴","🟠","🟡","🟢","🔵","🟣","⚫","⚪","🔶","🔷","▪️","▫️","▶️","⏸",
            "⏹","🔄","🔔","🔕","📌","📍","📎","🔗","🔒","🔓","🔑","💡","📱","💻","⌚","📷"
        )),
        Category("§", LayoutSwitcherAndTranslator.TYPOGRAPHY_SYMBOLS),
        Category("^_^", TextPowerTools.KAOMOJI_LIST),
        Category("★", emptyList())
    )

    private val searchKeywords: List<Pair<String, List<String>>> = listOf(
        "ржач смех хаха лол угар lol laugh" to listOf("😂", "🤣", "😆", "😹", "💀"),
        "привет пока рука ладонь hello bye wave" to listOf("👋", "🤝", "✌️", "🤙", "🙋"),
        "работа офис комп код ноутбук work code" to listOf("💻", "🖥️", "📱", "⌨️", "📊", "📈", "💼"),
        "деньги бабки зарплата рубль доллар money" to listOf("💰", "💵", "💳", "🪙", "💎", "🤑"),
        "машина авто такси дорога car" to listOf("🚗", "🚕", "🚙", "🏎️", "🚌", "✈️", "🚆"),
        "еда обед ужин пицца бургер food" to listOf("🍕", "🍔", "🍟", "🌮", "🍣", "🥗", "🍜"),
        "погода солнце дождь снег weather" to listOf("☀️", "🌤️", "🌧️", "❄️", "⚡", "🌈"),
        "спорт зал тренировка футбол sport gym" to listOf("🏋️", "💪", "⚽", "🏀", "🏃", "🚴", "🏆"),
        "музыка песня рок гитара music" to listOf("🎵", "🎶", "🎸", "🎧", "🎤", "🥁"),
        "время часы будильник срочно time" to listOf("⏰", "⌚", "⏳", "🕐", "📅"),
        "огонь пожар круто fire hot lit" to listOf("🔥", "❤️‍🔥", "⚡", "💥"),
        "сердце любовь люблю heart love" to listOf("❤️", "🥰", "😍", "💕", "💖", "🫶", "💔"),
        "смех ржу хаха laugh lol haha funny" to listOf("😂", "🤣", "😆", "😁", "😅"),
        "улыбка радость счастлив smile happy" to listOf("😀", "😊", "🙂", "😄", "😇", "🥳"),
        "привет пока рука hi hello bye wave" to listOf("👋", "🤝", "🙌", "✌️"),
        "ок хорошо класс супер лайк ok good like thumbs" to listOf("👍", "👌", "💯", "✅", "🔥"),
        "плохо дизлайк нет bad no dislike" to listOf("👎", "❌", "🚫", "😒"),
        "спасибо пожалуйста молитва thanks please pray" to listOf("🙏", "🤝", "🫶", "💐"),
        "грусть плач слёзы sad cry tears" to listOf("😢", "😭", "😔", "🥺", "💔"),
        "злость бесит злой angry mad" to listOf("😡", "😠", "🤬", "😤"),
        "думаю вопрос think hmm question" to listOf("🤔", "🧐", "❓", "💡"),
        "праздник торт др party birthday cake" to listOf("🎉", "🎊", "🥳", "🎂", "🍰", "🍾"),
        "деньги бабки зарплата money cash dollar" to listOf("🤑", "₽", "$", "€", "💯"),
        "еда пицца бургер кофе food pizza burger coffee" to listOf("🍕", "🍔", "☕", "🍣", "🍺", "🍷"),
        "машина авто такси самолёт car taxi plane" to listOf("🚗", "🚕", "🏎", "✈️", "🚀"),
        "спорт футбол зал тренировка sport gym football" to listOf("⚽", "🏀", "🏋️", "🥊", "💪", "🏃"),
        "работа телефон ноутбук замок work phone laptop lock" to listOf("📱", "💻", "📌", "📍", "🔒", "🔑"),
        "погода солнце снег дождь weather sun snow rain" to listOf("☀️", "🌙", "⭐", "🌧", "❄️", "🌈"),
        "музыка рок гитара игры music rock guitar game" to listOf("🎸", "🎧", "🎤", "🎮", "🤘")
    )

    init {
        orientation = VERTICAL
        buildSearchBar()
        addView(searchBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))
        scrollView.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun buildSearchBar() {
        searchBar.setPadding(dp(8f), dp(4f), dp(8f), dp(2f))
        searchInput = EditText(context).apply {
            hint = "Поиск эмодзи (огонь, сердце, ok…)"
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                    renderContent()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val clearBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(4f), 0)
            setOnClickListener {
                searchInput.setText("")
                searchQuery = ""
                renderContent()
            }
        }
        searchBar.addView(searchInput, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        searchBar.addView(clearBtn)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (w * (skin.keyboardRefHeightDp / skin.keyboardRefWidthDp)).roundToInt()
            .coerceIn(dp(212f), dp(296f)) + dp(8f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(if (skin.isGlassBackground) Color.TRANSPARENT else skin.common.keyboardBackgroundColor)
        searchInput.setTextColor(skin.keyTextColor)
        searchInput.setHintTextColor(skin.keyMarkColor)
        searchInput.background = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(skin.normalKeyBg.normalColor)
        }
        renderContent()
        renderBottomBar()
    }

    fun refreshRecents() {
        if (selectedCategory == 0 && searchQuery.isEmpty()) renderContent()
    }

    private fun renderContent() {
        grid.removeAllViews()
        val list = if (searchQuery.isNotEmpty()) {
            val found = LinkedHashSet<String>()
            searchKeywords.forEach { (keys, emojis) ->
                if (keys.contains(searchQuery)) found.addAll(emojis)
            }
            found.toList()
        } else if (selectedCategory == 0) {
            val recents = prefs.getRecentEmojis()
            if (recents.isEmpty()) categories[1].emojis.take(24) else recents
        } else {
            categories[selectedCategory].emojis
        }

        val isStickers = searchQuery.isEmpty() && selectedCategory == 9
        if (isStickers) {
            renderStickersTab()
            return
        }

        if (list.isEmpty()) {
            val empty = TextView(context).apply {
                text = "Ничего не найдено по запросу «$searchQuery»"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(24f), dp(12f), dp(12f))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(0, 8, 1f)
                }
            }
            grid.addView(empty)
            return
        }

        val isTypography = searchQuery.isEmpty() && selectedCategory == 7
        val isKaomoji = searchQuery.isEmpty() && selectedCategory == 8
        val (cols, emojiSp, cellDp) = when (prefs.emojiSizeLevel) {
            0 -> Triple(10, 19f, 36f)
            1 -> Triple(9, 21f, 39f)
            3 -> Triple(7, 26f, 46f)
            4 -> Triple(6, 29f, 52f)
            else -> Triple(8, 23f, 42f)
        }
        grid.columnCount = when {
            isKaomoji -> 3
            isTypography -> 8
            else -> cols
        }
        val cellH = dp(when {
            isKaomoji -> 42f
            isTypography -> 42f
            else -> cellDp
        })
        list.forEach { item ->
            val tv = TextView(context).apply {
                text = item
                setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
                    isKaomoji -> 13f
                    isTypography -> 18f
                    else -> emojiSp
                })
                setTextColor(skin.keyTextColor)
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = cellH
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setOnClickListener {
                    prefs.recordRecentEmoji(item)
                    listener?.onEmojiSelected(item)
                }
                if (item in SKIN_TONE_BASE_EMOJIS) {
                    setOnLongClickListener { anchor ->
                        showSkinTonePopup(anchor, item)
                        true
                    }
                }
            }
            grid.addView(tv)
        }
    }

    private fun renderBottomBar() {
        bottomBar.removeAllViews()
        bottomBar.setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        bottomBar.setBackgroundColor(skin.common.topBarBackgroundColor)

        val abc = TextView(context).apply {
            text = "АБВ"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.funcKeyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { listener?.onEmojiClose() }
        }
        bottomBar.addView(abc, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        categories.forEachIndexed { idx, cat ->
            val selected = idx == selectedCategory && searchQuery.isEmpty()
            val tab = TextView(context).apply {
                text = cat.icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (idx == 7) 16f else 17f)
                setTextColor(if (selected) skin.common.candidateTargetColor else skin.keyTextColor)
                gravity = Gravity.CENTER
                alpha = if (selected) 1.0f else 0.52f
                if (selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(skin.normalKeyBg.normalColor)
                    }
                }
                setOnClickListener {
                    searchInput.setText("")
                    searchQuery = ""
                    selectedCategory = idx
                    renderContent()
                    renderBottomBar()
                }
            }
            bottomBar.addView(tab, LayoutParams(0, dp(32f), 1f))
        }

        val delBtn = ImageButton(context).apply {
            setImageResource(if (skin.isDark) R.drawable.ic_breeno_delete_dark else R.drawable.ic_breeno_delete)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { listener?.onEmojiBackspace() }
        }
        bottomBar.addView(delBtn, LayoutParams(dp(40f), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderStickersTab() {
        grid.columnCount = 4
        val stickers = prefs.getStickerPaths()
        val addBtn = TextView(context).apply {
            text = "+ Стикер"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(skin.funcKeyBg.normalColor)
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(64f)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            setOnClickListener { listener?.onAddStickerClick() }
        }
        grid.addView(addBtn)

        stickers.forEach { path ->
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(skin.normalKeyBg.normalColor)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(64f)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                }
                try {
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val cached = stickerThumbCache.get(path) ?: BitmapFactory.decodeFile(path, o)?.also { stickerThumbCache.put(path, it) }
                    if (cached != null) setImageBitmap(cached)
                } catch (_: Exception) {
                }
                setOnClickListener { listener?.onStickerSelected(path) }
                setOnLongClickListener {
                    prefs.removeStickerPath(path)
                    renderContent()
                    true
                }
            }
            grid.addView(iv)
        }
    }


    private val stickerThumbCache = android.util.LruCache<String, android.graphics.Bitmap>(24)

    fun release() {
        stickerThumbCache.evictAll()
    }

    private val SKIN_TONE_BASE_EMOJIS = setOf(
        "👍", "👎", "👏", "🙏", "👋", "👌", "✌️", "💪", "☝️", "✋", "🤙", "🤘", "🤞", "🫶"
    )

    private fun showSkinTonePopup(anchor: View, baseEmoji: String) {
        val tones = listOf("", "🏻", "🏼", "🏽", "🏾", "🏿")
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(skin.normalKeyBg.normalColor)
                setStroke(dp(1f), skin.common.candidateDividerColor)
            }
        }
        val pw = android.widget.PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(6f).toFloat()
        }
        tones.forEach { mod ->
            val composed = baseEmoji.replace("️", "") + mod
            row.addView(TextView(context).apply {
                text = composed
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                setOnClickListener {
                    prefs.recordRecentEmoji(composed)
                    listener?.onEmojiSelected(composed)
                    pw.dismiss()
                }
            })
        }
        pw.showAsDropDown(anchor, -dp(48f), -anchor.height - dp(46f))
    }
}