package com.claritykey.keyboard.engine

import android.graphics.RectF
import com.claritykey.keyboard.layout.ComputedKey
import com.claritykey.keyboard.layout.KeyAction
import com.claritykey.keyboard.prefs.BreenoPreferences
import kotlin.math.hypot

data class TouchBias(val dxNorm: Float, val dyNorm: Float, val samples: Int)

/**
 * Adaptive Touch Recognition & Personal Calibration Model v2.0:
 * - Learns gentle per-layout, per-orientation finger touch offsets (EMA, strictly clamped).
 * - Applies a subtle -4.5% upward thumb-offset prior before enough samples accumulate
 *   (compensating for natural smartphone thumb landing below visual key center).
 * - Uses anisotropic distance (stricter vertically than horizontally) + top-3 dictionary
 *   prefix continuation scoring to rescue border taps without overriding confident center taps.
 */
class TouchCalibrationModel(private val prefs: BreenoPreferences) {

    companion object {
        private const val EMA_ALPHA = 0.08f
        private const val MAX_BIAS_X_NORM = 0.26f
        private const val MAX_BIAS_Y_NORM = 0.24f
        private const val DEFAULT_THUMB_PRIOR_Y = 0.045f
        private const val CONFIDENT_CENTER_RADIUS = 0.34f
        private const val BORDER_AMBIGUITY_RATIO = 1.42f
        private const val X_WEIGHT = 0.92f
        private const val Y_WEIGHT = 1.18f
    }

    private val biasCache = HashMap<String, TouchBias>()

    fun getBias(layoutKey: String): TouchBias {
        return biasCache.getOrPut(layoutKey) {
            prefs.getTouchBias(layoutKey)
        }
    }

    /**
     * Records an observed tap offset relative to the intended key's center.
     * Ignores outliers (> 1.15x key size) to protect against accidental mis-taps.
     */
    fun recordTapSample(
        tapX: Float,
        tapY: Float,
        keyRect: RectF,
        layoutKey: String,
        manualWeight: Boolean = false
    ) {
        if (!manualWeight && !prefs.adaptiveAutoCalibration) return
        val w = keyRect.width().coerceAtLeast(1f)
        val h = keyRect.height().coerceAtLeast(1f)
        val dxNorm = (tapX - keyRect.centerX()) / w
        val dyNorm = (tapY - keyRect.centerY()) / h
        if (kotlin.math.abs(dxNorm) > 1.15f || kotlin.math.abs(dyNorm) > 1.15f) return

        val cur = getBias(layoutKey)
        val alpha = if (manualWeight) 0.28f else EMA_ALPHA
        val nextDx = (cur.dxNorm * (1f - alpha) + dxNorm * alpha).coerceIn(-MAX_BIAS_X_NORM, MAX_BIAS_X_NORM)
        val nextDy = (cur.dyNorm * (1f - alpha) + dyNorm * alpha).coerceIn(-MAX_BIAS_Y_NORM, MAX_BIAS_Y_NORM)
        val updated = TouchBias(nextDx, nextDy, cur.samples + 1)
        biasCache[layoutKey] = updated
        prefs.saveTouchBias(layoutKey, updated.dxNorm, updated.dyNorm, updated.samples)
    }

    fun resolveLetterKey(
        tapX: Float,
        tapY: Float,
        fallbackKey: ComputedKey,
        allKeys: List<ComputedKey>,
        composing: String,
        lang: String,
        layoutKey: String,
        dictEngine: BreenoDictionaryEngine
    ): ComputedKey {
        if (!prefs.adaptiveSmartTouch) return fallbackKey
        if (fallbackKey.spec.action != KeyAction.CHAR || fallbackKey.spec.output.length != 1) return fallbackKey
        if (!fallbackKey.spec.output[0].isLetter()) return fallbackKey

        val bias = getBias(layoutKey)
        val keyW = fallbackKey.visualRect.width().coerceAtLeast(1f)
        val keyH = fallbackKey.visualRect.height().coerceAtLeast(1f)
        val effDyNorm = if (bias.samples < 8) {
            (bias.dyNorm * (bias.samples / 8f)) + (DEFAULT_THUMB_PRIOR_Y * (1f - bias.samples / 8f))
        } else {
            bias.dyNorm
        }
        val adjX = tapX - (bias.dxNorm * keyW)
        val adjY = tapY - (effDyNorm * keyH)

        // Score nearby letter keys with anisotropic distance (horizontal slips are more common than vertical)
        val scored = ArrayList<Pair<ComputedKey, Float>>(6)
        for (ck in allKeys) {
            if (ck.spec.action != KeyAction.CHAR || ck.spec.output.length != 1) continue
            if (!ck.spec.output[0].isLetter()) continue
            val nx = ((adjX - ck.visualRect.centerX()) / keyW) * X_WEIGHT
            val ny = ((adjY - ck.visualRect.centerY()) / keyH) * Y_WEIGHT
            val dist = hypot(nx.toDouble(), ny.toDouble()).toFloat()
            if (dist <= 1.25f) {
                scored.add(ck to dist)
            }
        }
        if (scored.isEmpty()) return fallbackKey
        scored.sortBy { it.second }

        val best = scored[0]
        // Confident center tap -> never override intentional character
        if (best.second <= CONFIDENT_CENTER_RADIUS || scored.size < 2 || composing.isEmpty()) {
            return best.first
        }

        // Evaluate top 3 close border candidates with dictionary continuation probability
        val c1 = best.first.spec.output[0].lowercaseChar()
        val score1 = dictEngine.scorePrefixContinuation(composing, c1, lang)
        var chosen = best.first
        var chosenScore = score1
        var chosenDist = best.second

        val limit = scored.size.coerceAtMost(3)
        for (i in 1 until limit) {
            val cand = scored[i]
            if (cand.second > best.second * BORDER_AMBIGUITY_RATIO) break
            val ci = cand.first.spec.output[0].lowercaseChar()
            val sc = dictEngine.scorePrefixContinuation(composing, ci, lang)
            val stronglyPreferred = (sc > 0L && chosenScore == 0L) ||
                (sc > chosenScore * 4L && best.second > 0.42f && cand.second <= chosenDist * 1.25f)
            if (stronglyPreferred) {
                chosen = cand.first
                chosenScore = sc
                chosenDist = cand.second
            }
        }
        return chosen
    }

    fun resetAll() {
        biasCache.clear()
        prefs.clearTouchCalibration()
    }
}
