package com.claritykey.keyboard.skin

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import java.util.Calendar
import kotlin.math.roundToInt

enum class DayNightMode {
    SYSTEM,
    LIGHT,
    DARK,
    GLASS_BG_SYSTEM,
    GLASS_BG_LIGHT,
    GLASS_BG_DARK,
    GLASS_SYSTEM,
    GLASS_LIGHT,
    GLASS_DARK
}

data class KeyBackgroundConfig(
    val normalColor: Int,
    val pressedColor: Int,
    val pressedOverlayColor: Int,
    val shadowColor: Int
)

data class KeyPopConfig(
    val backgroundColor: Int,
    val strokeColor: Int,
    val shadowColor: Int,
    val itemSelectBackgroundColor: Int,
    val textColor: Int,
    val textColorSelected: Int
)

data class CommonSkin(
    val keyboardBackgroundColor: Int,
    val topBarBackgroundColor: Int,
    val candidateWordColor: Int,
    val candidateTargetColor: Int,
    val candidateDividerColor: Int,
    val deleteSwipeDangerColor: Int
)

data class KeyboardSkin(
    val isDark: Boolean,
    val isGlassBackground: Boolean = false,
    val isGlassKeys: Boolean = false,
    val glassBlurRadiusPx: Int = 0,
    val glassBorderTopColor: Int = Color.TRANSPARENT,
    val glassBorderBottomColor: Int = Color.TRANSPARENT,
    val glassSheenTopColor: Int = Color.TRANSPARENT,
    val common: CommonSkin,
    val normalKeyBg: KeyBackgroundConfig,
    val funcKeyBg: KeyBackgroundConfig,
    val enterActionBg: KeyBackgroundConfig,
    val keyTextColor: Int,
    val keyMarkColor: Int,
    val funcKeyTextColor: Int,
    val enterActionTextColor: Int,
    val keyPop: KeyPopConfig,
    val keyRadiusDp: Float = 8.5f,
    val keyShadowRadiusDp: Float = 8.5f,
    val keyShadowDyPx: Float = 3.0f,
    val toolBarHeightDp: Float = 44.0f,
    val keyboardRefWidthDp: Float = 360.0f,
    val keyboardRefHeightDp: Float = 224.0f, // Enlarged Gboard-proportioned key height
    val customShiftBgColor: Int = 0,
    val customDeleteBgColor: Int = 0,
    val customLangBgColor: Int = 0,
    val keyShadowEnabled: Boolean = true
)

object BreenoSkinEngine {

    val LIGHT_SKIN = KeyboardSkin(
        isDark = false,
        common = CommonSkin(
            keyboardBackgroundColor = Color.parseColor("#FFE2E4E7"),
            topBarBackgroundColor = Color.parseColor("#FFE2E4E7"),
            candidateWordColor = Color.parseColor("#D9000000"),
            candidateTargetColor = Color.parseColor("#FF0066FF"),
            candidateDividerColor = Color.parseColor("#66000000"),
            deleteSwipeDangerColor = Color.parseColor("#FFDB382C")
        ),
        normalKeyBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FFFFFFFF"),
            pressedColor = Color.parseColor("#FFBBBEC2"),
            pressedOverlayColor = Color.TRANSPARENT,
            shadowColor = Color.parseColor("#FFB1B4BE")
        ),
        funcKeyBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FFC6CBCF"),
            pressedColor = Color.parseColor("#FFBBBEC2"),
            pressedOverlayColor = Color.TRANSPARENT,
            shadowColor = Color.parseColor("#FF9A9EAD")
        ),
        enterActionBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FF0066FF"),
            pressedColor = Color.parseColor("#FF0066FF"),
            pressedOverlayColor = Color.parseColor("#14000000"),
            shadowColor = Color.parseColor("#FF004ECC")
        ),
        keyTextColor = Color.parseColor("#E6000000"),
        keyMarkColor = Color.parseColor("#66000000"),
        funcKeyTextColor = Color.parseColor("#E6000000"),
        enterActionTextColor = Color.parseColor("#FFFFFFFF"),
        keyPop = KeyPopConfig(
            backgroundColor = Color.parseColor("#FFFFFFFF"),
            strokeColor = Color.parseColor("#26000000"),
            shadowColor = Color.parseColor("#14000000"),
            itemSelectBackgroundColor = Color.parseColor("#FF0066FF"),
            textColor = Color.parseColor("#FF0066FF"),
            textColorSelected = Color.parseColor("#FFFFFFFF")
        )
    )

    val DARK_SKIN = KeyboardSkin(
        isDark = true,
        common = CommonSkin(
            keyboardBackgroundColor = Color.parseColor("#FF2C2D2E"),
            topBarBackgroundColor = Color.parseColor("#FF2C2D2E"),
            candidateWordColor = Color.parseColor("#E6FFFFFF"),
            candidateTargetColor = Color.parseColor("#FF5C9DFF"),
            candidateDividerColor = Color.parseColor("#66FFFFFF"),
            deleteSwipeDangerColor = Color.parseColor("#FFDB382C")
        ),
        normalKeyBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FF5C5D5E"),
            pressedColor = Color.parseColor("#FF3B3C3E"),
            pressedOverlayColor = Color.parseColor("#14000000"),
            shadowColor = Color.parseColor("#FF1A1B1C")
        ),
        funcKeyBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FF3B3C3E"),
            pressedColor = Color.parseColor("#FF3B3C3E"),
            pressedOverlayColor = Color.parseColor("#14FFFFFF"),
            shadowColor = Color.parseColor("#FF171819")
        ),
        enterActionBg = KeyBackgroundConfig(
            normalColor = Color.parseColor("#FF247CFF"),
            pressedColor = Color.parseColor("#FF247CFF"),
            pressedOverlayColor = Color.parseColor("#14000000"),
            shadowColor = Color.parseColor("#FF1656B8")
        ),
        keyTextColor = Color.parseColor("#E6FFFFFF"),
        keyMarkColor = Color.parseColor("#66FFFFFF"),
        funcKeyTextColor = Color.parseColor("#E6FFFFFF"),
        enterActionTextColor = Color.parseColor("#FFFFFFFF"),
        keyPop = KeyPopConfig(
            backgroundColor = Color.parseColor("#FF5C5D5E"),
            strokeColor = Color.parseColor("#26FFFFFF"),
            shadowColor = Color.parseColor("#14000000"),
            itemSelectBackgroundColor = Color.parseColor("#FF247CFF"),
            textColor = Color.parseColor("#FF5C9DFF"),
            textColorSelected = Color.parseColor("#FFFFFFFF")
        )
    )

    /**
     * Glass Backdrop ONLY: translucent blurred glass panel behind classic solid Breeno keys.
     */
    private fun buildGlassBackdropOnlySkin(isDark: Boolean, transparencyPercent: Int, blurRadiusPx: Int): KeyboardSkin {
        val base = if (isDark) DARK_SKIN else LIGHT_SKIN
        val t = transparencyPercent.coerceIn(15, 85) / 100f
        val opacity = 1f - t
        // Light Glass uses a bright frosted-white floor so it stays airy & high-contrast
        // even when opened over pitch-black dark-mode apps (e.g. Termux, dark Telegram)
        val bgAlpha = if (isDark) {
            (opacity * 0.82f * 255f).roundToInt().coerceIn(48, 228)
        } else {
            // Pearlescent bright frosted-white floor so Light Glass stays airy & luminous even over #000000 dark apps
            ((0.60f + opacity * 0.32f) * 255f).roundToInt().coerceIn(186, 242)
        }
        val glassBg = if (isDark) Color.argb(bgAlpha, 14, 16, 22) else Color.argb(bgAlpha, 244, 247, 252)
        val rimTop = if (isDark) Color.argb(54, 255, 255, 255) else Color.argb(220, 255, 255, 255)
        // Seamless glass: never draw a separate solid/tinted band behind the top toolbar
        val topBarBg = Color.TRANSPARENT

        return base.copy(
            isGlassBackground = true,
            isGlassKeys = false,
            glassBlurRadiusPx = blurRadiusPx.coerceIn(0, 80),
            glassBorderTopColor = rimTop,
            common = base.common.copy(
                keyboardBackgroundColor = glassBg,
                topBarBackgroundColor = topBarBg
            )
        )
    }

    /**
     * Full Liquid Glass: translucent blurred glass backdrop + specular frosted glass keys.
     */
    private fun buildFullGlassSkin(isDark: Boolean, transparencyPercent: Int, blurRadiusPx: Int): KeyboardSkin {
        val t = transparencyPercent.coerceIn(15, 85) / 100f
        val opacity = 1f - t

        val bgAlpha = if (isDark) {
            (opacity * 0.78f * 255f).roundToInt().coerceIn(42, 220)
        } else {
            // Airy pearlescent frosted floor prevents muddy grey when opened over pitch-black apps (e.g. Termux)
            ((0.58f + opacity * 0.34f) * 255f).roundToInt().coerceIn(180, 240)
        }
        val keyAlpha = if (isDark) {
            (opacity * 0.34f * 255f).roundToInt().coerceIn(24, 130)
        } else {
            ((0.72f + opacity * 0.24f) * 255f).roundToInt().coerceIn(194, 246)
        }
        val funcAlpha = if (isDark) {
            (opacity * 0.22f * 255f).roundToInt().coerceIn(16, 95)
        } else {
            ((0.56f + opacity * 0.30f) * 255f).roundToInt().coerceIn(158, 222)
        }
        val pressedAlpha = (keyAlpha + 48).coerceAtMost(230)
        val enterAlpha = (opacity * 0.92f * 255f).roundToInt().coerceIn(165, 245)

        return if (isDark) {
            KeyboardSkin(
                isDark = true,
                isGlassBackground = true,
                isGlassKeys = true,
                glassBlurRadiusPx = blurRadiusPx.coerceIn(0, 80),
                glassBorderTopColor = Color.argb(56, 255, 255, 255),
                glassBorderBottomColor = Color.argb(14, 255, 255, 255),
                glassSheenTopColor = Color.argb(22, 255, 255, 255),
                common = CommonSkin(
                    keyboardBackgroundColor = Color.argb(bgAlpha, 14, 16, 22),
                    topBarBackgroundColor = Color.TRANSPARENT,
                    candidateWordColor = Color.parseColor("#F2FFFFFF"),
                    candidateTargetColor = Color.parseColor("#FF6EB0FF"),
                    candidateDividerColor = Color.parseColor("#55FFFFFF"),
                    deleteSwipeDangerColor = Color.argb(215, 219, 56, 44)
                ),
                normalKeyBg = KeyBackgroundConfig(
                    normalColor = Color.argb(keyAlpha, 255, 255, 255),
                    pressedColor = Color.argb(pressedAlpha, 255, 255, 255),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(45, 0, 0, 0)
                ),
                funcKeyBg = KeyBackgroundConfig(
                    normalColor = Color.argb(funcAlpha, 180, 190, 210),
                    pressedColor = Color.argb(pressedAlpha, 200, 210, 230),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(40, 0, 0, 0)
                ),
                enterActionBg = KeyBackgroundConfig(
                    normalColor = Color.argb(enterAlpha, 36, 124, 255),
                    pressedColor = Color.argb(245, 24, 102, 230),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(60, 0, 40, 120)
                ),
                keyTextColor = Color.parseColor("#F5FFFFFF"),
                keyMarkColor = Color.parseColor("#99FFFFFF"),
                funcKeyTextColor = Color.parseColor("#F0FFFFFF"),
                enterActionTextColor = Color.WHITE,
                keyPop = KeyPopConfig(
                    backgroundColor = Color.argb(225, 44, 47, 56),
                    strokeColor = Color.argb(110, 255, 255, 255),
                    shadowColor = Color.parseColor("#26000000"),
                    itemSelectBackgroundColor = Color.parseColor("#FF247CFF"),
                    textColor = Color.parseColor("#FF78B6FF"),
                    textColorSelected = Color.WHITE
                ),
                keyRadiusDp = 10.0f
            )
        } else {
            KeyboardSkin(
                isDark = false,
                isGlassBackground = true,
                isGlassKeys = true,
                glassBlurRadiusPx = blurRadiusPx.coerceIn(0, 80),
                glassBorderTopColor = Color.argb(225, 255, 255, 255),
                glassBorderBottomColor = Color.argb(46, 90, 106, 134),
                glassSheenTopColor = Color.argb(82, 255, 255, 255),
                common = CommonSkin(
                    keyboardBackgroundColor = Color.argb(bgAlpha, 244, 247, 252),
                    topBarBackgroundColor = Color.TRANSPARENT,
                    candidateWordColor = Color.parseColor("#E614161A"),
                    candidateTargetColor = Color.parseColor("#FF0066FF"),
                    candidateDividerColor = Color.parseColor("#44000000"),
                    deleteSwipeDangerColor = Color.argb(215, 219, 56, 44)
                ),
                normalKeyBg = KeyBackgroundConfig(
                    normalColor = Color.argb(keyAlpha, 255, 255, 255),
                    pressedColor = Color.argb(pressedAlpha, 215, 222, 234),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(38, 60, 72, 95)
                ),
                funcKeyBg = KeyBackgroundConfig(
                    normalColor = Color.argb(funcAlpha, 212, 220, 234),
                    pressedColor = Color.argb(pressedAlpha, 186, 196, 214),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(42, 58, 70, 96)
                ),
                enterActionBg = KeyBackgroundConfig(
                    normalColor = Color.argb(enterAlpha, 0, 102, 255),
                    pressedColor = Color.argb(245, 0, 84, 215),
                    pressedOverlayColor = Color.TRANSPARENT,
                    shadowColor = Color.argb(55, 0, 64, 180)
                ),
                keyTextColor = Color.parseColor("#EB0E1014"),
                keyMarkColor = Color.parseColor("#80141820"),
                funcKeyTextColor = Color.parseColor("#E60E1014"),
                enterActionTextColor = Color.WHITE,
                keyPop = KeyPopConfig(
                    backgroundColor = Color.argb(235, 252, 253, 255),
                    strokeColor = Color.argb(180, 255, 255, 255),
                    shadowColor = Color.parseColor("#1F000000"),
                    itemSelectBackgroundColor = Color.parseColor("#FF0066FF"),
                    textColor = Color.parseColor("#FF0066FF"),
                    textColorSelected = Color.WHITE
                ),
                keyRadiusDp = 10.0f
            )
        }
    }

    fun isNightScheduleActive(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    private fun applyDynamicColorAccent(context: Context, skin: KeyboardSkin): KeyboardSkin {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return skin
        return try {
            val resId = if (skin.isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
            val accent = context.getColor(resId)
            val enterAlpha = Color.alpha(skin.enterActionBg.normalColor)
            val enterCol = Color.argb(enterAlpha, Color.red(accent), Color.green(accent), Color.blue(accent))
            val enterText = if (skin.isDark) Color.parseColor("#FF101318") else Color.WHITE
            skin.copy(
                common = skin.common.copy(candidateTargetColor = accent),
                enterActionBg = skin.enterActionBg.copy(normalColor = enterCol, pressedColor = enterCol),
                enterActionTextColor = enterText,
                keyPop = skin.keyPop.copy(
                    itemSelectBackgroundColor = accent,
                    textColor = accent
                )
            )
        } catch (_: Exception) {
            skin
        }
    }

    fun resolveSkin(
        context: Context,
        mode: DayNightMode,
        glassTransparencyPercent: Int = 55,
        glassBlurRadiusPx: Int = 48,
        dynamicColorEnabled: Boolean = false,
        nightScheduleEnabled: Boolean = false
    ): KeyboardSkin {
        val sysDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val forceNight = nightScheduleEnabled && isNightScheduleActive()
        val effectiveDark = sysDark || forceNight
        val base = when (mode) {
            DayNightMode.LIGHT -> if (forceNight) DARK_SKIN else LIGHT_SKIN
            DayNightMode.DARK -> DARK_SKIN
            DayNightMode.SYSTEM -> if (effectiveDark) DARK_SKIN else LIGHT_SKIN
            DayNightMode.GLASS_BG_LIGHT -> buildGlassBackdropOnlySkin(forceNight, glassTransparencyPercent, glassBlurRadiusPx)
            DayNightMode.GLASS_BG_DARK -> buildGlassBackdropOnlySkin(true, glassTransparencyPercent, glassBlurRadiusPx)
            DayNightMode.GLASS_BG_SYSTEM -> buildGlassBackdropOnlySkin(effectiveDark, glassTransparencyPercent, glassBlurRadiusPx)
            DayNightMode.GLASS_LIGHT -> buildFullGlassSkin(forceNight, glassTransparencyPercent, glassBlurRadiusPx)
            DayNightMode.GLASS_DARK -> buildFullGlassSkin(true, glassTransparencyPercent, glassBlurRadiusPx)
            DayNightMode.GLASS_SYSTEM -> buildFullGlassSkin(effectiveDark, glassTransparencyPercent, glassBlurRadiusPx)
        }
        return if (dynamicColorEnabled) applyDynamicColorAccent(context, base) else base
    }

    fun isColorLight(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (299 * r + 587 * g + 114 * b) / 1000 > 145
    }

    fun blendAlphaForSkin(baseColor: Int, targetAlpha: Int): Int =
        Color.argb(targetAlpha.coerceIn(48, 255), Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

    fun applyCustomKeyColors(
        skin: KeyboardSkin,
        normalColor: Int = 0,
        shiftColor: Int = 0,
        deleteColor: Int = 0,
        langColor: Int = 0,
        enterColor: Int = 0
    ): KeyboardSkin {
        var out = skin
        if (normalColor != 0) {
            val alpha = if (skin.isGlassKeys) Color.alpha(skin.normalKeyBg.normalColor).coerceAtLeast(125) else 255
            val norm = blendAlphaForSkin(normalColor, alpha)
            val pressed = blendAlphaForSkin(normalColor, (alpha + 40).coerceAtMost(255))
            val txt = if (isColorLight(normalColor)) Color.parseColor("#EB14161A") else Color.parseColor("#F5FFFFFF")
            val mark = if (isColorLight(normalColor)) Color.parseColor("#8014161A") else Color.parseColor("#99FFFFFF")
            out = out.copy(
                normalKeyBg = out.normalKeyBg.copy(normalColor = norm, pressedColor = pressed),
                keyTextColor = txt,
                keyMarkColor = mark
            )
        }
        if (enterColor != 0) {
            val alpha = if (skin.isGlassKeys) Color.alpha(skin.enterActionBg.normalColor).coerceAtLeast(185) else 255
            val ent = blendAlphaForSkin(enterColor, alpha)
            val entTxt = if (isColorLight(enterColor)) Color.parseColor("#FF14161A") else Color.WHITE
            out = out.copy(
                enterActionBg = out.enterActionBg.copy(normalColor = ent, pressedColor = ent),
                enterActionTextColor = entTxt,
                common = out.common.copy(candidateTargetColor = enterColor)
            )
        }
        val funcAlpha = if (skin.isGlassKeys) Color.alpha(skin.funcKeyBg.normalColor).coerceAtLeast(145) else 255
        out = out.copy(
            customShiftBgColor = if (shiftColor != 0) blendAlphaForSkin(shiftColor, funcAlpha) else 0,
            customDeleteBgColor = if (deleteColor != 0) blendAlphaForSkin(deleteColor, funcAlpha) else 0,
            customLangBgColor = if (langColor != 0) blendAlphaForSkin(langColor, funcAlpha) else 0
        )
        return out
    }

}
