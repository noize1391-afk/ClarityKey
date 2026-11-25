package com.claritykey.keyboard.layout

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BottomPaddingMode {
    AUTO,
    COMPACT,
    STANDARD,
    LARGE
}

enum class SystemNavMode {
    GESTURE,
    THREE_BUTTON,
    UNKNOWN
}

data class AdaptiveBottomMetrics(
    val bottomPaddingPx: Int,
    val sidePaddingLeftPx: Int,
    val sidePaddingRightPx: Int,
    val navMode: SystemNavMode,
    val detectedCornerRadiusPx: Int,
    val systemNavBarHeightPx: Int,
    val mandatoryGestureBottomPx: Int
)

/**
 * Universal Adaptive Bottom Layout calculator for Android IME (minSdk 28, targetSdk 35).
 * - Uses official WindowInsetsCompat, DisplayCutout, and RoundedCorner (API 31+) APIs only.
 * - Zero OEM-specific APIs or Build.MODEL checks.
 * - Prevents double-application of system navigation bar insets in InputMethodService.
 * - Never intercepts or excludes system gesture areas, preserving Circle to Search and all Android nav gestures.
 */
object AdaptiveBottomLayout {

    fun compute(
        context: Context,
        hostView: View?,
        insetsCompat: WindowInsetsCompat?,
        mode: BottomPaddingMode
    ): AdaptiveBottomMetrics {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun dp(v: Float): Int = (v * density).roundToInt()

        val navInsets = insetsCompat?.getInsets(WindowInsetsCompat.Type.navigationBars())
        val mandGestures = insetsCompat?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val cutout = insetsCompat?.displayCutout

        val navBottomPx = navInsets?.bottom ?: 0
        val mandBottomPx = mandGestures?.bottom ?: 0
        val cutoutLeftPx = cutout?.safeInsetLeft ?: 0
        val cutoutRightPx = cutout?.safeInsetRight ?: 0
        val cutoutBottomPx = cutout?.safeInsetBottom ?: 0

        // Detect navigation mode from actual system inset geometry (universal across OEMs)
        val navMode = when {
            navBottomPx >= dp(36f) -> SystemNavMode.THREE_BUTTON
            mandBottomPx > 0 || (navBottomPx in 1 until dp(36f)) -> SystemNavMode.GESTURE
            else -> SystemNavMode.UNKNOWN
        }

        // Read actual bottom display corner radii on Android 12+ (API 31+) when exposed to IME window
        var cornerLeftPx = 0
        var cornerRightPx = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rawInsets: WindowInsets? = hostView?.rootWindowInsets ?: insetsCompat?.toWindowInsets()
            cornerLeftPx = rawInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            cornerRightPx = rawInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        }
        val maxCornerPx = max(cornerLeftPx, cornerRightPx)

        // Note: Android's InputMethodService decor frame already positions the IME input view above
        // the system navigation bar. Adding navBottomPx again causes double bottom padding!
        // Therefore our Adaptive Safety Zone adds ONLY the minimal breathing/clearance space
        // above the system navigation boundary (zero touch-intercepting views).
        val baseSafePx = when (navMode) {
            SystemNavMode.THREE_BUTTON -> dp(2f)
            SystemNavMode.GESTURE -> if (isLandscape) dp(2f) else dp(3f)
            SystemNavMode.UNKNOWN -> if (isLandscape) dp(2f) else dp(4f)
        }

        // Subtle extra clearance only if a display has a very steep bottom corner arc (> 28dp)
        // and the system navigation area underneath is very short (< 18dp).
        val cornerBottomAssistPx = if (!isLandscape && maxCornerPx > dp(28f) && navBottomPx < dp(18f)) {
            ((maxCornerPx - dp(28f)) * 0.22f).roundToInt().coerceIn(0, dp(6f))
        } else {
            0
        }

        val minSafeBottomPx = max(baseSafePx + cornerBottomAssistPx, cutoutBottomPx)

        val userExtraPx = when (mode) {
            BottomPaddingMode.COMPACT -> 0
            BottomPaddingMode.AUTO -> if (isLandscape) dp(1f) else dp(2f)
            BottomPaddingMode.STANDARD -> dp(7f)
            BottomPaddingMode.LARGE -> dp(14f)
        }

        val finalBottomPx = minSafeBottomPx + userExtraPx

        // Adaptive horizontal side safe inset so outer bottom keys (?123 / Enter) never clip
        // against curved OLED bottom corners or landscape display cutouts.
        val effectiveBottomElevationPx = navBottomPx + finalBottomPx
        val cornerSideLeftPx = computeCornerSideInset(cornerLeftPx, effectiveBottomElevationPx, dp(6f))
        val cornerSideRightPx = computeCornerSideInset(cornerRightPx, effectiveBottomElevationPx, dp(6f))

        val sideLeftPx = max(cutoutLeftPx, cornerSideLeftPx)
        val sideRightPx = max(cutoutRightPx, cornerSideRightPx)

        return AdaptiveBottomMetrics(
            bottomPaddingPx = finalBottomPx,
            sidePaddingLeftPx = sideLeftPx,
            sidePaddingRightPx = sideRightPx,
            navMode = navMode,
            detectedCornerRadiusPx = maxCornerPx,
            systemNavBarHeightPx = navBottomPx,
            mandatoryGestureBottomPx = mandBottomPx
        )
    }

    /**
     * Computes exact horizontal chord inset needed at vertical distance y from screen bottom
     * for a circular corner arc of radius R: dx = R - sqrt(R^2 - (R - y)^2), capped gently
     * so letter key widths remain comfortable.
     */
    private fun computeCornerSideInset(radiusPx: Int, yFromBottomPx: Int, maxCapPx: Int): Int {
        if (radiusPx <= 0 || yFromBottomPx >= radiusPx) return 0
        val dy = (radiusPx - yFromBottomPx).toDouble()
        val r = radiusPx.toDouble()
        val dx = r - sqrt((r * r - dy * dy).coerceAtLeast(0.0))
        return (dx * 0.45).roundToInt().coerceIn(0, maxCapPx)
    }

    fun attachListener(view: View, onInsetsChanged: (WindowInsetsCompat) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            onInsetsChanged(insets)
            insets
        }
        if (view.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(view)
        }
    }
}
