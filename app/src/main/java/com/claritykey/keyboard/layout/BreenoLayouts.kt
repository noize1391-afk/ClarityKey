package com.claritykey.keyboard.layout

import android.graphics.RectF

enum class KeyboardMode {
    ALPHA_RU,
    ALPHA_EN,
    SYMBOLS_1,
    SYMBOLS_2,
    NUMBER_PAD,
    T9_RU
}

enum class InputContextMode {
    NORMAL,
    EMAIL,
    URI
}

enum class ShiftState {
    OFF,
    ON,
    LOCKED
}

enum class KeyRole {
    NORMAL,
    FUNCTION,
    ENTER,
    SPACE
}

enum class KeyAction {
    CHAR,
    SHIFT,
    DELETE,
    SPACE,
    ENTER,
    LANG_SWITCH,
    EMOJI,
    MODE_SYMBOLS_1,
    MODE_SYMBOLS_2,
    MODE_NUMBER,
    MODE_ALPHA
}

data class KeySpec(
    val label: String,
    val output: String = label,
    val mark: String? = null,
    val alternates: List<String> = emptyList(),
    val action: KeyAction = KeyAction.CHAR,
    val role: KeyRole = KeyRole.NORMAL,
    val widthS: Float? = null,
    val xMarginBeforeS: Float? = null,
    val textSizeS: Float? = null,
    val leftTouchExpandS: Float = 0f,
    val rightTouchExpandS: Float = 0f
)

data class RowSpec(
    val startMarginS: Float,
    val keys: List<KeySpec>,
    val heightScale: Float = 1.0f
)

data class KeyboardLayoutSpec(
    val mode: KeyboardMode,
    val normalKeyWidthS: Float,
    val normalKeyHeightS: Float = 0.21212122f,
    val keyXMarginS: Float,
    val keyYMarginS: Float = 0.038f,
    val normalKeyTextSizeS: Float = 0.095f,
    val keyMarkTextSizeS: Float = 0.04545455f,
    val rows: List<RowSpec>
)

data class ComputedKey(
    val spec: KeySpec,
    val visualRect: RectF,
    val touchRect: RectF,
    val textSizePx: Float,
    val markSizePx: Float
)

object BreenoLayouts {

    private const val START_MARGIN = 0.014f

    fun buildLayout(
        mode: KeyboardMode,
        contextMode: InputContextMode = InputContextMode.NORMAL,
        separateHardSign: Boolean = false,
        separateYo: Boolean = false,
        showNumberRow: Boolean = false,
        lastAlphaLang: String = "RU",
        showEmojiKey: Boolean = false,
        showLangKey: Boolean = true,
        showCommaKey: Boolean = true,
        showPeriodKey: Boolean = true,
        splitGapS: Float = 0f
    ): KeyboardLayoutSpec {
        val raw = when (mode) {
            KeyboardMode.ALPHA_RU -> buildRussianLayout(contextMode, separateHardSign, separateYo, showNumberRow, showEmojiKey, showLangKey, showCommaKey, showPeriodKey)
            KeyboardMode.ALPHA_EN -> buildEnglishLayout(contextMode, showNumberRow, showEmojiKey, showLangKey, showCommaKey, showPeriodKey)
            KeyboardMode.SYMBOLS_1 -> buildSymbols1Layout(lastAlphaLang)
            KeyboardMode.SYMBOLS_2 -> buildSymbols2Layout(lastAlphaLang)
            KeyboardMode.NUMBER_PAD -> buildNumberPadLayout(lastAlphaLang)
            KeyboardMode.T9_RU -> buildT9RussianLayout(showEmojiKey, showLangKey)
        }
        return if (splitGapS > 0.01f && mode != KeyboardMode.NUMBER_PAD && mode != KeyboardMode.T9_RU) {
            applySplitGap(raw, splitGapS)
        } else {
            raw
        }
    }

    private fun applySplitGap(spec: KeyboardLayoutSpec, splitGapS: Float): KeyboardLayoutSpec {
        val scale = (1f - splitGapS).coerceIn(0.74f, 0.96f)
        val splitRows = spec.rows.map { row ->
            val mid = row.keys.size / 2
            val newKeys = row.keys.mapIndexed { idx, k ->
                val baseW = k.widthS ?: spec.normalKeyWidthS
                val scaledGap = k.xMarginBeforeS?.times(scale)
                val finalGap = if (idx == mid) (scaledGap ?: (spec.keyXMarginS * scale)) + splitGapS else scaledGap
                k.copy(widthS = baseW * scale, xMarginBeforeS = finalGap)
            }
            row.copy(startMarginS = row.startMarginS * scale, keys = newKeys)
        }
        return spec.copy(
            normalKeyWidthS = spec.normalKeyWidthS * scale,
            keyXMarginS = spec.keyXMarginS * scale,
            rows = splitRows
        )
    }

    private fun buildT9RussianLayout(showEmojiKey: Boolean, showLangKey: Boolean): KeyboardLayoutSpec {
        val gap = 0.018f
        val w = 0.228f
        val row0 = listOf(
            KeySpec("1 .,!", output = ".,!?", mark = "1", widthS = w),
            KeySpec("2 АБВГ", output = "абвг", mark = "2", widthS = w, xMarginBeforeS = gap),
            KeySpec("3 ДЕЖЗ", output = "дежз", mark = "3", widthS = w, xMarginBeforeS = gap),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = w, xMarginBeforeS = gap)
        )
        val row1 = listOf(
            KeySpec("4 ИЙКЛ", output = "ийкл", mark = "4", widthS = w),
            KeySpec("5 МНОП", output = "мноп", mark = "5", widthS = w, xMarginBeforeS = gap),
            KeySpec("6 РСТУ", output = "рсту", mark = "6", widthS = w, xMarginBeforeS = gap),
            KeySpec("SHIFT", action = KeyAction.SHIFT, role = KeyRole.FUNCTION, widthS = w, xMarginBeforeS = gap)
        )
        val row2 = listOf(
            KeySpec("7 ФХЦЧ", output = "фхцч", mark = "7", widthS = w),
            KeySpec("8 ШЩЪЫ", output = "шщъы", mark = "8", widthS = w, xMarginBeforeS = gap),
            KeySpec("9 ЬЭЮЯ", output = "ьэюя", mark = "9", widthS = w, xMarginBeforeS = gap),
            KeySpec("?123", action = KeyAction.MODE_SYMBOLS_1, role = KeyRole.FUNCTION, widthS = w, xMarginBeforeS = gap, textSizeS = 0.068f)
        )
        val langOrEmoji = if (showEmojiKey) {
            KeySpec("☺", action = KeyAction.EMOJI, role = KeyRole.FUNCTION, widthS = w, textSizeS = 0.088f)
        } else if (showLangKey) {
            KeySpec("RU", action = KeyAction.LANG_SWITCH, role = KeyRole.NORMAL, widthS = w)
        } else {
            KeySpec(",", widthS = w)
        }
        val row3 = listOf(
            langOrEmoji,
            KeySpec("Пробел", output = " ", action = KeyAction.SPACE, role = KeyRole.SPACE, widthS = w * 2f + gap, xMarginBeforeS = gap, textSizeS = 0.072f),
            KeySpec("ENTER", action = KeyAction.ENTER, role = KeyRole.ENTER, widthS = w, xMarginBeforeS = gap)
        )
        return KeyboardLayoutSpec(
            mode = KeyboardMode.T9_RU,
            normalKeyWidthS = w,
            keyXMarginS = gap,
            rows = listOf(
                RowSpec(0.017f, row0),
                RowSpec(0.017f, row1),
                RowSpec(0.017f, row2),
                RowSpec(0.017f, row3)
            )
        )
    }

    private fun buildNumberRow(): RowSpec {
        // Exact flush fit for START_MARGIN=0.014: 2*0.014 + 10*0.08865 + 9*0.0095 = 1.0000
        val nw = 0.08865f
        val gap = 0.0095f
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val keys = digits.mapIndexed { idx, d ->
            KeySpec(
                label = d,
                widthS = nw,
                xMarginBeforeS = if (idx == 0) null else gap,
                textSizeS = 0.078f
            )
        }
        return RowSpec(START_MARGIN, keys, heightScale = 0.78f)
    }

    private fun buildRussianLayout(
        contextMode: InputContextMode,
        separateHardSign: Boolean,
        separateYo: Boolean,
        showNumberRow: Boolean,
        showEmojiKey: Boolean,
        showLangKey: Boolean,
        showCommaKey: Boolean,
        showPeriodKey: Boolean
    ): KeyboardLayoutSpec {
        val topRowWidth = if (separateHardSign) 0.0728f else 0.0806f
        val topRowGap = if (separateHardSign) 0.0089f else 0.0085f

        val row0Keys = mutableListOf(
            KeySpec("й", mark = if (showNumberRow) null else "1", alternates = listOf("1"), widthS = topRowWidth),
            KeySpec("ц", mark = if (showNumberRow) null else "2", alternates = listOf("2"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("у", mark = if (showNumberRow) null else "3", alternates = listOf("3"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("к", mark = if (showNumberRow) null else "4", alternates = listOf("4"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("е", mark = "ё", alternates = listOf("ё", "5"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("н", mark = if (showNumberRow) null else "6", alternates = listOf("6"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("г", mark = if (showNumberRow) null else "7", alternates = listOf("7"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("ш", mark = if (showNumberRow) null else "8", alternates = listOf("8"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("щ", mark = if (showNumberRow) null else "9", alternates = listOf("9"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec("з", mark = if (showNumberRow) null else "0", alternates = listOf("0"), widthS = topRowWidth, xMarginBeforeS = topRowGap),
            KeySpec(
                "х",
                mark = if (separateHardSign) null else "ъ",
                alternates = if (separateHardSign) listOf("[", "{") else listOf("ъ", "[", "{"),
                widthS = topRowWidth,
                xMarginBeforeS = topRowGap
            )
        )
        if (separateHardSign) {
            row0Keys.add(KeySpec("ъ", alternates = listOf("]", "}"), widthS = topRowWidth, xMarginBeforeS = topRowGap))
        }

        val row1Keys = listOf(
            KeySpec("ф", alternates = listOf("@", "#")),
            KeySpec("ы", alternates = listOf("₽", "$")),
            KeySpec("в", alternates = listOf("€", "%")),
            KeySpec("а", alternates = listOf("&", "№")),
            KeySpec("п", alternates = listOf("-", "—")),
            KeySpec("р", alternates = listOf("+", "=")),
            KeySpec("о", alternates = listOf("(", "[")),
            KeySpec("л", alternates = listOf(")", "]")),
            KeySpec("д", alternates = listOf("\"", "«", "»")),
            KeySpec("ж", alternates = listOf(":", ";")),
            KeySpec("э", alternates = listOf("'", "«", "»"))
        )

        val shiftDelWidth = 0.0808f
        val row2Gap = 0.0085f
        val row2Keys = listOf(
            KeySpec("SHIFT", action = KeyAction.SHIFT, role = KeyRole.FUNCTION, widthS = shiftDelWidth, rightTouchExpandS = 0.006f),
            KeySpec("я", alternates = listOf("!", "¡"), xMarginBeforeS = row2Gap),
            KeySpec("ч", alternates = listOf("?", "¿"), xMarginBeforeS = row2Gap),
            KeySpec("с", alternates = listOf("/", "\\"), xMarginBeforeS = row2Gap),
            KeySpec("м", alternates = listOf("*", "~"), xMarginBeforeS = row2Gap),
            KeySpec("и", alternates = listOf("_", "^"), xMarginBeforeS = row2Gap),
            KeySpec("т", alternates = listOf(",", ";"), xMarginBeforeS = row2Gap),
            KeySpec("ь", mark = "ъ", alternates = listOf("ъ"), xMarginBeforeS = row2Gap),
            KeySpec("б", alternates = listOf("<", "«"), xMarginBeforeS = row2Gap),
            KeySpec("ю", alternates = listOf(">", "»"), xMarginBeforeS = row2Gap),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = shiftDelWidth, xMarginBeforeS = row2Gap, leftTouchExpandS = 0.006f)
        )

        val row3Keys = buildBottomRow(
            langLabel = "RU",
            spaceLabel = "Русский",
            contextMode = contextMode,
            keyXMarginS = 0.010f,
            showEmojiKey = showEmojiKey,
            showLangKey = showLangKey,
            showCommaKey = showCommaKey,
            showPeriodKey = showPeriodKey
        )

        if (separateYo) {
            row0Keys.add(0, KeySpec("ё", alternates = listOf("Ё")))
            val gap = 0.0085f
            val width = (1f - START_MARGIN * 2 - gap * (row0Keys.size - 1)) / row0Keys.size
            row0Keys.indices.forEach { i -> row0Keys[i] = row0Keys[i].copy(widthS = width, xMarginBeforeS = gap) }
        }
        val rows = ArrayList<RowSpec>(5)
        if (showNumberRow) rows.add(buildNumberRow())
        rows.add(RowSpec(START_MARGIN, row0Keys))
        rows.add(RowSpec(START_MARGIN, row1Keys))
        rows.add(RowSpec(START_MARGIN, row2Keys))
        rows.add(RowSpec(START_MARGIN, row3Keys))

        return KeyboardLayoutSpec(
            mode = KeyboardMode.ALPHA_RU,
            normalKeyWidthS = 0.0806f,
            keyXMarginS = 0.0085f,
            rows = rows
        )
    }

    private fun buildEnglishLayout(
        contextMode: InputContextMode,
        showNumberRow: Boolean,
        showEmojiKey: Boolean,
        showLangKey: Boolean,
        showCommaKey: Boolean,
        showPeriodKey: Boolean
    ): KeyboardLayoutSpec {
        val row0 = listOf(
            KeySpec("q", mark = if (showNumberRow) null else "1", alternates = listOf("1")),
            KeySpec("w", mark = if (showNumberRow) null else "2", alternates = listOf("2")),
            KeySpec("e", mark = if (showNumberRow) null else "3", alternates = listOf("3", "é", "è", "ê")),
            KeySpec("r", mark = if (showNumberRow) null else "4", alternates = listOf("4")),
            KeySpec("t", mark = if (showNumberRow) null else "5", alternates = listOf("5")),
            KeySpec("y", mark = if (showNumberRow) null else "6", alternates = listOf("6")),
            KeySpec("u", mark = if (showNumberRow) null else "7", alternates = listOf("7", "ü", "ú")),
            KeySpec("i", mark = if (showNumberRow) null else "8", alternates = listOf("8", "í", "ï")),
            KeySpec("o", mark = if (showNumberRow) null else "9", alternates = listOf("9", "ö", "ó")),
            KeySpec("p", mark = if (showNumberRow) null else "0", alternates = listOf("0"))
        )

        val row1 = listOf(
            KeySpec("a", alternates = listOf("@", "á", "ä"), leftTouchExpandS = 0.05611112f),
            KeySpec("s", alternates = listOf("#", "$", "ß")),
            KeySpec("d", alternates = listOf("%", "&")),
            KeySpec("f", alternates = listOf("_", "-")),
            KeySpec("g", alternates = listOf("+", "=")),
            KeySpec("h", alternates = listOf("(", "[")),
            KeySpec("j", alternates = listOf(")", "]")),
            KeySpec("k", alternates = listOf("\"", "'")),
            KeySpec("l", alternates = listOf(":", ";"), rightTouchExpandS = 0.05611112f)
        )

        val row2 = listOf(
            KeySpec("SHIFT", action = KeyAction.SHIFT, role = KeyRole.FUNCTION, widthS = 0.1166667f, rightTouchExpandS = 0.014375f),
            KeySpec("z", alternates = listOf("!"), xMarginBeforeS = 0.02875f, leftTouchExpandS = 0.014375f),
            KeySpec("x", alternates = listOf("?")),
            KeySpec("c", alternates = listOf("/", "ç")),
            KeySpec("v", alternates = listOf("*")),
            KeySpec("b", alternates = listOf("~")),
            KeySpec("n", alternates = listOf("ñ")),
            KeySpec("m", alternates = listOf("`"), rightTouchExpandS = 0.014375f),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = 0.1166667f, xMarginBeforeS = 0.02875f, leftTouchExpandS = 0.014375f)
        )

        val row3 = buildBottomRow(
            langLabel = "EN",
            spaceLabel = "English",
            contextMode = contextMode,
            keyXMarginS = 0.010f,
            showEmojiKey = showEmojiKey,
            showLangKey = showLangKey,
            showCommaKey = showCommaKey,
            showPeriodKey = showPeriodKey
        )

        val rows = ArrayList<RowSpec>(5)
        if (showNumberRow) rows.add(buildNumberRow())
        rows.add(RowSpec(START_MARGIN, row0))
        rows.add(RowSpec(0.063f, row1))
        rows.add(RowSpec(START_MARGIN, row2))
        rows.add(RowSpec(START_MARGIN, row3))

        return KeyboardLayoutSpec(
            mode = KeyboardMode.ALPHA_EN,
            normalKeyWidthS = 0.08865f,
            keyXMarginS = 0.0095f,
            rows = rows
        )
    }

    /**
     * Clean 6-key bottom row:
     * ?123 | LANG (globe+RU/EN) | , | WIDE SPACE | . | ENTER
     * Exact sum: 2*0.0222222 + 0.130 + 0.105 + 0.085 + 0.340 + 0.085 + 0.141 + 5*0.013888889 = 1.000
     */
    private fun buildBottomRow(
        langLabel: String,
        spaceLabel: String,
        contextMode: InputContextMode,
        keyXMarginS: Float,
        showEmojiKey: Boolean = false,
        showLangKey: Boolean = true,
        showCommaKey: Boolean = true,
        showPeriodKey: Boolean = true
    ): List<KeySpec> {
        val keys = ArrayList<KeySpec>(6)
        keys.add(KeySpec("?123", action = KeyAction.MODE_SYMBOLS_1, role = KeyRole.FUNCTION, widthS = 0.135f, textSizeS = 0.068f))

        var spaceW = 0.368f

        val includeLeftPunct = showCommaKey || contextMode != InputContextMode.NORMAL
        if (includeLeftPunct) {
            val leftPunct = when (contextMode) {
                InputContextMode.EMAIL -> KeySpec("@", alternates = listOf(",", "_", "-"), role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
                InputContextMode.URI -> KeySpec("/", alternates = listOf(",", ":", "?"), role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
                InputContextMode.NORMAL -> KeySpec(",", mark = "☺", role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
            }
            keys.add(leftPunct)
        } else {
            spaceW += 0.092f + keyXMarginS
        }

        if (showEmojiKey) {
            keys.add(KeySpec("☺", action = KeyAction.EMOJI, role = KeyRole.FUNCTION, widthS = 0.095f, xMarginBeforeS = keyXMarginS, textSizeS = 0.088f))
        } else if (showLangKey) {
            keys.add(KeySpec(langLabel, action = KeyAction.LANG_SWITCH, role = KeyRole.NORMAL, widthS = 0.095f, xMarginBeforeS = keyXMarginS))
        } else {
            spaceW += 0.095f + keyXMarginS
        }

        keys.add(KeySpec(spaceLabel, output = " ", action = KeyAction.SPACE, role = KeyRole.SPACE, widthS = spaceW, xMarginBeforeS = keyXMarginS, textSizeS = 0.07070707f))

        val includeRightPunct = showPeriodKey || contextMode != InputContextMode.NORMAL
        if (includeRightPunct) {
            val rightPunct = when (contextMode) {
                InputContextMode.EMAIL -> KeySpec(".", alternates = listOf(".ru", ".com", ".net", ".org"), role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
                InputContextMode.URI -> KeySpec(".", alternates = listOf(".ru", ".com", ".org", "https://"), role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
                InputContextMode.NORMAL -> KeySpec(".", alternates = listOf(",", "?", "!", "…", "—", ":"), role = KeyRole.FUNCTION, widthS = 0.092f, xMarginBeforeS = keyXMarginS)
            }
            keys.add(rightPunct)
        } else {
            val spaceIdx = keys.size - 1
            keys[spaceIdx] = keys[spaceIdx].copy(widthS = spaceW + 0.092f + keyXMarginS)
        }

        keys.add(KeySpec("ENTER", action = KeyAction.ENTER, role = KeyRole.ENTER, widthS = 0.140f, xMarginBeforeS = keyXMarginS, textSizeS = 0.070f))
        return keys
    }

    private fun buildSymbols1Layout(lastAlphaLang: String): KeyboardLayoutSpec {
        val gap = 0.013888889f
        val row0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { KeySpec(it) }
        val row1 = listOf("@", "#", "₽", "_", "&", "-", "+", "(", ")", "/").map { KeySpec(it) }
        val row2 = listOf(
            KeySpec("=\\<", action = KeyAction.MODE_SYMBOLS_2, role = KeyRole.FUNCTION, widthS = 0.1166667f, textSizeS = 0.068f),
            KeySpec("*", xMarginBeforeS = 0.02875f),
            KeySpec("\""),
            KeySpec("'"),
            KeySpec(":"),
            KeySpec(";"),
            KeySpec("!"),
            KeySpec("?"),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = 0.1166667f, xMarginBeforeS = 0.02875f)
        )
        val backLabel = if (lastAlphaLang == "RU") "АБВ" else "ABC"
        val row3 = listOf(
            KeySpec(backLabel, action = KeyAction.MODE_ALPHA, role = KeyRole.FUNCTION, widthS = 0.135f, textSizeS = 0.070f),
            KeySpec("123", action = KeyAction.MODE_NUMBER, role = KeyRole.FUNCTION, widthS = 0.100f, xMarginBeforeS = gap, textSizeS = 0.068f),
            KeySpec(",", widthS = 0.085f, xMarginBeforeS = gap),
            KeySpec(" ", output = " ", action = KeyAction.SPACE, role = KeyRole.SPACE, widthS = 0.340f, xMarginBeforeS = gap),
            KeySpec(".", widthS = 0.085f, xMarginBeforeS = gap),
            KeySpec("ENTER", action = KeyAction.ENTER, role = KeyRole.ENTER, widthS = 0.141f, xMarginBeforeS = gap)
        )
        return KeyboardLayoutSpec(
            mode = KeyboardMode.SYMBOLS_1,
            normalKeyWidthS = 0.083055556f,
            keyXMarginS = gap,
            rows = listOf(
                RowSpec(START_MARGIN, row0),
                RowSpec(START_MARGIN, row1),
                RowSpec(START_MARGIN, row2),
                RowSpec(START_MARGIN, row3)
            )
        )
    }

    private fun buildSymbols2Layout(lastAlphaLang: String): KeyboardLayoutSpec {
        val gap = 0.013888889f
        val row0 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "№").map { KeySpec(it) }
        val row1 = listOf("€", "$", "£", "¥", "^", "°", "=", "{", "}", "\\").map { KeySpec(it) }
        val row2 = listOf(
            KeySpec("?123", action = KeyAction.MODE_SYMBOLS_1, role = KeyRole.FUNCTION, widthS = 0.1166667f, textSizeS = 0.065f),
            KeySpec("%", xMarginBeforeS = 0.02875f),
            KeySpec("©"),
            KeySpec("®"),
            KeySpec("™"),
            KeySpec("«"),
            KeySpec("»"),
            KeySpec("—"),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = 0.1166667f, xMarginBeforeS = 0.02875f)
        )
        val backLabel = if (lastAlphaLang == "RU") "АБВ" else "ABC"
        val row3 = listOf(
            KeySpec(backLabel, action = KeyAction.MODE_ALPHA, role = KeyRole.FUNCTION, widthS = 0.135f, textSizeS = 0.070f),
            KeySpec("[", widthS = 0.100f, xMarginBeforeS = gap),
            KeySpec("]", widthS = 0.085f, xMarginBeforeS = gap),
            KeySpec(" ", output = " ", action = KeyAction.SPACE, role = KeyRole.SPACE, widthS = 0.340f, xMarginBeforeS = gap),
            KeySpec("…", widthS = 0.085f, xMarginBeforeS = gap),
            KeySpec("ENTER", action = KeyAction.ENTER, role = KeyRole.ENTER, widthS = 0.141f, xMarginBeforeS = gap)
        )
        return KeyboardLayoutSpec(
            mode = KeyboardMode.SYMBOLS_2,
            normalKeyWidthS = 0.083055556f,
            keyXMarginS = gap,
            rows = listOf(
                RowSpec(START_MARGIN, row0),
                RowSpec(START_MARGIN, row1),
                RowSpec(START_MARGIN, row2),
                RowSpec(START_MARGIN, row3)
            )
        )
    }

    private fun buildNumberPadLayout(lastAlphaLang: String): KeyboardLayoutSpec {
        val gap = 0.016f
        val numW = 0.225f
        val sideW = 0.215f
        val backLabel = if (lastAlphaLang == "RU") "АБВ" else "ABC"
        val row0 = listOf(
            KeySpec("1", widthS = numW),
            KeySpec("2", widthS = numW),
            KeySpec("3", widthS = numW),
            KeySpec("-", role = KeyRole.FUNCTION, widthS = sideW)
        )
        val row1 = listOf(
            KeySpec("4", widthS = numW),
            KeySpec("5", widthS = numW),
            KeySpec("6", widthS = numW),
            KeySpec("+", role = KeyRole.FUNCTION, widthS = sideW)
        )
        val row2 = listOf(
            KeySpec("7", widthS = numW),
            KeySpec("8", widthS = numW),
            KeySpec("9", widthS = numW),
            KeySpec("DEL", action = KeyAction.DELETE, role = KeyRole.FUNCTION, widthS = sideW)
        )
        val row3 = listOf(
            KeySpec(backLabel, action = KeyAction.MODE_ALPHA, role = KeyRole.FUNCTION, widthS = numW, textSizeS = 0.075f),
            KeySpec("0", alternates = listOf("+"), widthS = numW),
            KeySpec(".", alternates = listOf(","), widthS = numW),
            KeySpec("ENTER", action = KeyAction.ENTER, role = KeyRole.ENTER, widthS = sideW)
        )
        return KeyboardLayoutSpec(
            mode = KeyboardMode.NUMBER_PAD,
            normalKeyWidthS = numW,
            keyXMarginS = gap,
            rows = listOf(
                RowSpec(0.031f, row0),
                RowSpec(0.031f, row1),
                RowSpec(0.031f, row2),
                RowSpec(0.031f, row3)
            )
        )
    }

    fun computeGeometry(
        spec: KeyboardLayoutSpec,
        totalWidthPx: Float,
        totalHeightPx: Float
    ): List<ComputedKey> {
        val result = ArrayList<ComputedKey>(52)
        val totalUnits = spec.rows.sumOf { it.heightScale.toDouble() }.toFloat()
        val yGap = (if (spec.rows.size > 4) 0.036f else spec.keyYMarginS) * totalHeightPx
        val availH = totalHeightPx - (spec.rows.size - 1) * yGap - totalHeightPx * 0.03f
        val unitKeyH = availH / totalUnits
        val refH = totalHeightPx * (4f / totalUnits)

        var currentTop = totalHeightPx * 0.015f

        spec.rows.forEach { row ->
            val keyH = unitKeyH * row.heightScale
            val bottom = currentTop + keyH
            var cursorX = row.startMarginS * totalWidthPx

            row.keys.forEachIndexed { keyIdx, key ->
                if (keyIdx > 0) {
                    val gapS = key.xMarginBeforeS ?: spec.keyXMarginS
                    cursorX += gapS * totalWidthPx
                }
                val kw = (key.widthS ?: spec.normalKeyWidthS) * totalWidthPx
                val visual = RectF(cursorX, currentTop, cursorX + kw, bottom)
                val touch = RectF(
                    visual.left - key.leftTouchExpandS * totalWidthPx,
                    visual.top - yGap * 0.45f,
                    visual.right + key.rightTouchExpandS * totalWidthPx,
                    visual.bottom + yGap * 0.45f
                )
                val textSz = (key.textSizeS ?: spec.normalKeyTextSizeS) * refH
                val markSz = spec.keyMarkTextSizeS * refH
                if (keyIdx > 0) {
                    val previous = result.last()
                    val gap = visual.left - previous.visualRect.right
                    // Split keyboard's deliberate center gap stays untouchable.
                    if (gap >= 0f && gap <= totalWidthPx * 0.04f) {
                        val boundary = (previous.visualRect.right + visual.left) / 2f
                        previous.touchRect.right = maxOf(previous.touchRect.right, boundary)
                        touch.left = minOf(touch.left, boundary)
                    }
                }
                result.add(ComputedKey(key, visual, touch, textSz, markSz))
                cursorX += kw
            }
            currentTop = bottom + yGap
        }
        return result
    }
}
