package com.claritykey.keyboard.engine

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

data class CalcSuggestion(
    val expressionWithEquals: String,
    val plainResult: String,
    val formattedResult: String
)

/**
 * Offline, zero-dependency recursive-descent arithmetic evaluator for the IME candidate bar.
 * Triggered when user types an expression ending with '=' (e.g. "1450*12=", "(250+750)/4=").
 * Supports +, -, *, /, ×, ÷, parentheses, decimals ('.' or ','), and unary minus.
 */
object InlineCalculator {

    private val EXPR_REGEX = Regex("([0-9().,+\\-*/×÷\\s]{3,40})=$")

    private val UNIT_REGEX = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(км|km|миль|mi|м|см|cm|дюйм(?:ов|а)?|in|кг|kg|фунт(?:ов|а)?|lb|г|g|°?c|°?с|°?f|гб|gb|мб|mb)=$", RegexOption.IGNORE_CASE)

    private fun evaluateUnitConversion(textBeforeCursor: String): CalcSuggestion? {
        val m = UNIT_REGEX.find(textBeforeCursor) ?: return null
        val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val (res, outUnit) = when (unit) {
            "км", "km" -> (num * 0.621371) to "миль"
            "миль", "mi" -> (num * 1.60934) to "км"
            "м" -> (num * 100.0) to "см"
            "см", "cm" -> (num / 2.54) to "дюйм"
            "дюйм", "дюйма", "дюймов", "in" -> (num * 2.54) to "см"
            "кг", "kg" -> (num * 2.20462) to "lb"
            "фунт", "фунта", "фунтов", "lb" -> (num * 0.453592) to "кг"
            "г", "g" -> (num / 1000.0) to "кг"
            "c", "°c", "с", "°с" -> (num * 9.0 / 5.0 + 32.0) to "°F"
            "f", "°f" -> ((num - 32.0) * 5.0 / 9.0) to "°C"
            "гб", "gb" -> (num * 1024.0) to "МБ"
            "мб", "mb" -> (num / 1024.0) to "ГБ"
            else -> return null
        }
        val rounded = BigDecimal.valueOf(res).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val label = "$rounded $outUnit"
        return CalcSuggestion(m.value, label, label)
    }

    fun evaluateTail(textBeforeCursor: String): CalcSuggestion? {
        if (!textBeforeCursor.endsWith("=")) return null
        evaluateUnitConversion(textBeforeCursor)?.let { return it }
        val match = EXPR_REGEX.find(textBeforeCursor) ?: return null
        val rawWithEq = match.value.trimStart()
        val rawExpr = match.groupValues[1].trim()
        // Must contain at least one arithmetic operator so "123=" is not treated as a formula
        if (rawExpr.none { it in "+-*/×÷" }) return null
        if (rawExpr.none { it.isDigit() }) return null

        val normalized = rawExpr
            .replace('×', '*')
            .replace('÷', '/')
            .replace(',', '.')
            .replace(" ", "")

        val value = try {
            Parser(normalized).parseExpression()
        } catch (_: Exception) {
            return null
        } ?: return null

        if (value.isNaN() || value.isInfinite() || abs(value) > 1e15) return null

        val plain = formatPlain(value)
        val formatted = formatGrouped(plain)
        return CalcSuggestion(
            expressionWithEquals = rawWithEq,
            plainResult = plain,
            formattedResult = formatted
        )
    }

    private fun formatPlain(v: Double): String {
        val bd = BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    private fun formatGrouped(plain: String): String {
        val neg = plain.startsWith("-")
        val body = if (neg) plain.substring(1) else plain
        val parts = body.split(".")
        val intPart = parts[0]
        val sb = StringBuilder()
        for (i in intPart.indices) {
            if (i > 0 && (intPart.length - i) % 3 == 0) sb.append(' ')
            sb.append(intPart[i])
        }
        if (parts.size == 2 && parts[1].isNotEmpty()) {
            sb.append('.').append(parts[1])
        }
        return if (neg) "-$sb" else sb.toString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseExpression(): Double? {
            val v = parseAddSub()
            return if (pos == s.length) v else null
        }

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> { pos++; left += parseMulDiv() }
                    '-' -> { pos++; left -= parseMulDiv() }
                    else -> break
                }
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parseUnary()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> { pos++; left *= parseUnary() }
                    '/' -> {
                        pos++
                        val right = parseUnary()
                        if (abs(right) < 1e-12) throw ArithmeticException("Division by zero")
                        left /= right
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseUnary(): Double {
            if (pos < s.length && s[pos] == '+') { pos++; return parseUnary() }
            if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = parseAddSub()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing )")
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected number")
            return s.substring(start, pos).toDouble()
        }
    }
}
