package com.claritykey.keyboard.ime

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.claritykey.keyboard.clipboard.SmartPasteAnalyzer
import com.claritykey.keyboard.prefs.BreenoPreferences

/**
 * Volatile RAM-only bridge for one-time SMS & Push verification codes (OTP).
 * - Zero disk persistence, zero clipboard writes, zero logging.
 * - Automatic 90-second TTL + immediate wipe upon single-tap paste.
 */
object OtpMemoryBridge {
    private const val OTP_TTL_MS = 90_000L

    @Volatile private var latestCode: String? = null
    @Volatile private var latestSource: String = ""
    @Volatile private var latestTimeMs: Long = 0L
    @Volatile private var listener: ((code: String, source: String) -> Unit)? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val OTP_KEYWORD_REGEX = Regex(
        "(?iu)(код|парол[ья]|подтвержд|одноразов|смс|sms|otp|code|passcode|pin|verif|auth|логин|вход|оплат|списан)"
    )
    private val OTP_CODE_REGEX = Regex("(?<!\\d)(\\d{3}[-\\s]\\d{3}|\\d{4,8})(?!\\d)")

    fun setListener(cb: ((code: String, source: String) -> Unit)?) {
        listener = cb
    }

    fun publish(code: String, source: String) {
        val clean = code.filter { it.isDigit() }
        if (clean.length !in 4..8) return
        latestCode = clean
        latestSource = source
        latestTimeMs = System.currentTimeMillis()
        val cb = listener ?: return
        mainHandler.post { cb(clean, source) }
    }

    fun peekLatest(): Pair<String, String>? {
        val code = latestCode ?: return null
        if (System.currentTimeMillis() - latestTimeMs > OTP_TTL_MS) {
            clear()
            return null
        }
        return code to latestSource
    }

    fun clear() {
        latestCode = null
        latestSource = ""
        latestTimeMs = 0L
    }

    /**
     * Extracts a 4..8 digit OTP code from SMS/Push notification title + body.
     * Supports Russian & English banks, messengers, Gosuslugi, and hyphenated formats (e.g. 549-201).
     */
    fun extractOtpFromNotification(title: String, body: String): String? {
        val combined = "$title\n$body".trim()
        if (combined.length < 4 || combined.length > 600) return null

        // 1. Primary extractor from SmartPasteAnalyzer
        SmartPasteAnalyzer.extractEphemeralOtp(combined)?.let { found ->
            val digits = found.filter { it.isDigit() }
            if (digits.length in 4..8) return digits
        }

        return extractKeywordGuardedOtp(combined)
    }

    internal fun extractKeywordGuardedOtp(combined: String): String? {
        // Keyword-guarded extraction shared with clipboard; no call back into its primary parser.
        if (!OTP_KEYWORD_REGEX.containsMatchIn(combined)) return null
        val match = OTP_CODE_REGEX.find(combined) ?: return null
        val digits = match.groupValues[1].filter { it.isDigit() }
        if (digits.length !in 4..8) return null
        // Avoid matching bare years like 2025/2026 unless 5+ digits
        if (digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20"))) {
            val nextMatch = OTP_CODE_REGEX.find(combined, match.range.last + 1)
            if (nextMatch != null) {
                val alt = nextMatch.groupValues[1].filter { it.isDigit() }
                if (alt.length in 4..8) return alt
            }
        }
        return digits
    }
}

class OtpNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        val prefs = BreenoPreferences(applicationContext)
        if (!prefs.otpNotificationAutofillEnabled || prefs.manualIncognito) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = if (bigText.length > text.length) bigText else text

        val code = OtpMemoryBridge.extractOtpFromNotification(title, body) ?: return
        val sourceLabel = title.take(18).ifBlank { "SMS / Push" }
        OtpMemoryBridge.publish(code, sourceLabel)
    }
}
