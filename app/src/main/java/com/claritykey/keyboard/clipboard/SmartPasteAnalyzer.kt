package com.claritykey.keyboard.clipboard

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Patterns
import com.claritykey.keyboard.prefs.BreenoPreferences
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.regex.Pattern

enum class SmartEntityType(
    val titleRu: String,
    val shortSingular: String,
    val shortPlural: String,
    val iconGlyph: String,
    val priority: Int
) {
    URL("Ссылка", "ссылка", "ссылки", "🔗", 100),
    EMAIL("Email", "email", "email", "✉", 96),
    COORDINATES("Координаты", "коорд.", "коорд.", "📍", 92),
    BANK_DETAILS("Реквизиты", "реквизит", "реквизиты", "🏦", 90),
    TRACKING_NUMBER("Трек-номер", "трек", "треки", "🚚", 88),
    PHONE("Телефон", "тел.", "тел.", "📞", 85),
    FLIGHT_NUMBER("Рейс", "рейс", "рейсы", "✈", 82),
    CAR_PLATE("Госномер", "авто", "авто", "🚗", 80),
    PROMO_CODE("Промокод", "промокод", "промокоды", "🏷", 78),
    SKU_ARTICLE("Артикул", "артикул", "артикулы", "🔖", 76),
    CURRENCY("Сумма", "сумма", "суммы", "💳", 74),
    DATE("Дата", "дата", "даты", "📅", 70),
    TIME("Время", "время", "время", "⏰", 66),
    ORDER_NUMBER("Номер заказа", "заказ", "заказы", "📦", 62),
    ORGANIZATION("Организация", "орг.", "орг.", "🏢", 58),
    PERSON_NAME("ФИО", "ФИО", "ФИО", "👤", 56),
    ADDRESS("Адрес", "адрес", "адреса", "🏠", 52),
    OTP_CODE("Код подтверждения", "код", "коды", "🔐", 110)
}

data class SmartEntity(
    val type: SmartEntityType,
    val rawText: String,
    val start: Int,
    val end: Int,
    val verified: Boolean,
    val parentEntryId: Long
)

data class SmartPasteResult(
    val parentEntryId: Long,
    val contentHash: String,
    val entities: List<SmartEntity>,
    val hasSensitiveData: Boolean
) {
    val isEmpty: Boolean get() = entities.isEmpty()
}

/**
 * Smart Paste 2.0 — Fully local, asynchronous, high-precision structured data analyzer.
 * - Supports 15 entity types with real format/checksum validators (IBAN Mod-97, Russian INN checksum,
 *   Luhn card guard, UPU S10 tracking, context-verified flights/plates/orders/promos/SKUs/FIO/orgs).
 * - Operational-only OTP extraction: confirmation codes are NEVER stored in permanent Smart Paste history
 *   and never displayed in open plaintext suggestions.
 * - Zero network calls, zero cloud AI, zero diagnostic logging of clipboard contents.
 */
class SmartPasteAnalyzer(private val prefs: BreenoPreferences) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, SmartPasteResult>(160)

    companion object {
        private const val MAX_INPUT_LENGTH = 8_000

        // Operational-only OTP / verification code detector (never persisted to history!)
        private val RX_OTP = Pattern.compile(
            "(?iu)(?:код(?:\\s+подтверждения|\\s+доступа|\\s+входа|\\s+из\\s+смс)?|пароль|pin|пин|otp|cvv|cvc|verification\\s+code|confirmation\\s+code|security\\s+code|one[- ]time\\s+(?:code|passcode))[^\\n\\r\\d]{0,18}(\\d{4,8})\\b"
        )
        private val RX_OTP_REVERSE = Pattern.compile(
            "(?iu)\\b(\\d{4,8})\\b[^\\n\\r]{0,24}(?:код\\s+подтверждения|для\\s+входа|никому\\s+не\\s+сообщайте|verification\\s+code|confirmation\\s+code)"
        )
        private val RX_CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
        private val RX_BEARER_SECRET = Pattern.compile("(?i)\\b(?:sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,})\\b")

        // 1. URL & Phone
        private val RX_EXPLICIT_URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'«»]+")
        private val RX_PHONE_CANDIDATE = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[\\s.-]?(?:\\(\\d{2,4}\\)|\\d{2,4})(?:[\\s.-]?\\d{2,4}){2,4}|8[\\s.-]?\\(\\d{3,4}\\)[\\s.-]?\\d{2,3}[\\s.-]?\\d{2}[\\s.-]?\\d{2}|8[\\s.-]\\d{3}[\\s.-]\\d{3}[\\s.-]\\d{2}[\\s.-]\\d{2}|\\b89\\d{9}\\b)(?!\\d)"
        )

        // 2. Dates & Time
        private val RX_DATE_RU_TEXT = Pattern.compile(
            "(?iu)(?<!\\d)([0-3]?\\d)\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|янв\\.?|фев\\.?|мар\\.?|апр\\.?|июн\\.?|июл\\.?|авг\\.?|сен\\.?|окт\\.?|ноя\\.?|дек\\.?)(?:\\s+(20\\d{2}|19\\d{2})(?:\\s*г\\.?)?)?(?!\\p{L})"
        )
        private val RX_DATE_NUMERIC = Pattern.compile(
            "(?<!\\d)((?:0?[1-9]|[12]\\d|3[01])\\.(?:0?[1-9]|1[0-2])\\.(?:20\\d{2}|19\\d{2}|\\d{2})|(?:20\\d{2}|19\\d{2})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01]))(?!\\d)"
        )
        private val RX_TIME = Pattern.compile("(?<![\\d.:/])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d.:])")

        // 3. Addresses & Coordinates
        private val RX_COORDINATES = Pattern.compile("(?<!\\d)(-?\\d{1,2}\\.\\d{3,8})\\s*,\\s*(-?1?\\d{1,2}\\.\\d{3,8})(?!\\d)")
        private val RX_ADDRESS_RU = Pattern.compile(
            "(?iu)(?:(?:г\\.\\s*[А-ЯЁ][а-яё-]+(?:\\s+[А-ЯЁ][а-яё-]+)?,\\s*)?(?:ул\\.|улица|пр-т|пр\\.|проспект|бул\\.|бульвар|пер\\.|переулок|ш\\.|шоссе|наб\\.|набережная|пл\\.|площадь)\\s+[А-ЯЁ0-9][а-яёА-ЯЁ0-9\\s.-]{2,34}?,?\\s*(?:д\\.?\\s*)?\\d+[а-яА-Я]?(?:/\\d+)?(?:,?\\s*(?:корп\\.?|к\\.?|стр\\.?)\\s*\\d+)?(?:,?\\s*(?:кв\\.?|оф\\.?)\\s*\\d+)?)"
        )

        // 4. Currency & Bank Details (IBAN, INN, BIK, Russian 20-digit account)
        private val RX_CURRENCY = Pattern.compile(
            "(?iu)(?<!\\d)(?:(?:\\$|€|£|₽)\\s?\\d{1,3}(?:[\\s,\\u00A0]\\d{3})*(?:[.,]\\d{1,2})?|\\d{1,3}(?:[\\s\\u00A0]\\d{3})*(?:[.,]\\d{1,2})?\\s?(?:₽|руб\\.?|р\\.|USD|EUR|RUB|BYN|KZT|\\$|€|£))(?!\\p{L})"
        )
        private val RX_IBAN = Pattern.compile("(?<![A-Z0-9])([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,4})?)(?![A-Z0-9])")
        private val RX_INN = Pattern.compile("(?iu)\\bИНН\\s*[:№#-]?\\s*(\\d{10}|\\d{12})(?!\\d)")
        private val RX_BIK = Pattern.compile("(?iu)\\bБИК\\s*[:№#-]?\\s*(04\\d{7})(?!\\d)")
        private val RX_ACCOUNT_RU = Pattern.compile("(?iu)\\b(?:р/с|к/с|р\\.с\\.|к\\.с\\.|сч[её]т(?:\\s+№)?)\\s*[:№#-]?\\s*([34]0\\d{18})(?!\\d)")

        // 5. Orders, Parcel Tracking, Flights
        private val RX_ORDER_NUMBER = Pattern.compile(
            "(?iu)(?:заказ[а-я]*|order)\\s*(?:№|#|номер\\s*)\\s*([A-ZА-Я0-9][A-ZА-Я0-9-]{3,22})(?![\\p{L}\\d-])"
        )
        private val RX_TRACK_UPU = Pattern.compile("(?<![A-Z0-9])([A-Z]{2}\\d{9}[A-Z]{2})(?![A-Z0-9])")
        private val RX_TRACK_UPS = Pattern.compile("(?<![A-Z0-9])(1Z[0-9A-Z]{16})(?![A-Z0-9])")
        private val RX_TRACK_CONTEXT = Pattern.compile(
            "(?iu)(?:трек(?:-?номер)?|посылк[аи]|отправлени[еяю]|tracking(?:\\s+number)?|сдэк|cdek|boxberry)\\s*(?:№|#|:)?\\s*([A-Z0-9][A-Z0-9-]{7,22})(?![A-Z0-9-])"
        )
        private val RX_FLIGHT_CONTEXT = Pattern.compile(
            "(?iu)(?:рейс[аеу]?|вылет|посадк[аи]|flight|авиабилет)\\s*(?:№|#|:)?\\s*([A-Z0-9]{2}\\s?\\d{2,4})(?!\\d)"
        )

        // 6. Promo Codes, SKUs, Car Plates
        private val RX_PROMO = Pattern.compile(
            "(?iu)(?:промокод|промо-?код|купон|promo\\s*code|coupon)\\s*[:№#-]?\\s*([A-ZА-Я0-9][A-ZА-Я0-9_-]{3,24})(?![\\p{L}\\d_-])"
        )
        private val RX_SKU = Pattern.compile(
            "(?iu)(?:артикул|арт\\.|sku)\\s*[:№#-]?\\s*([A-ZА-Я0-9][A-ZА-Я0-9-]{3,20})(?![\\p{L}\\d-])"
        )
        // Standard Russian license plates (strictly valid 12 Cyrillic/Latin lookalike letters + 3 digits + 2 letters + 2-3 digit region)
        private val RX_CAR_PLATE_RU = Pattern.compile(
            "(?<![\\p{L}\\d])([АВЕКМНОРСТУХABEKMHOPCTYX]\\s?\\d{3}\\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\\s?(?:[0-9]{2}|[179][0-9]{2}))(?![\\p{L}\\d])"
        )

        // 7. People (FIO with Russian patronymic suffix or explicit label) & Organizations (explicit legal form)
        private val RX_FIO_PATRONYMIC = Pattern.compile(
            "(?<![\\p{L}])([А-ЯЁ][а-яё-]{1,24}\\s+[А-ЯЁ][а-яё-]{1,20}\\s+[А-ЯЁ][а-яё]{3,24}(?:ович|евич|ич|овна|евна|ична|инична))(?![\\p{L}])"
        )
        private val RX_FIO_LABELED = Pattern.compile(
            "(?u)(?:ФИО|Получатель|Контактное лицо|Отправитель)\\s*[:—-]\\s*([А-ЯЁ][а-яё-]+\\s+[А-ЯЁ][а-яё-]+(?:\\s+[А-ЯЁ][а-яё-]+)?)"
        )
        private val RX_ORG_RU = Pattern.compile(
            "(?u)\\b((?:ООО|АО|ПАО|ЗАО|ОАО|НКО|ГБУ|МБУ|ФГБУ)\\s+(?:«[^»\\n]{2,40}»|\"[^\"\\n]{2,40}\"|[А-ЯЁA-Z][а-яёА-ЯЁa-zA-Z0-9-]{2,28})|ИП\\s+[А-ЯЁ][а-яё-]+(?:\\s+[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.|\\s+[А-ЯЁ][а-яё-]+(?:\\s+[А-ЯЁ][а-яё-]+)?))"
        )
        private val RX_ORG_EN = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9&.-]{1,24}(?:\\s+[A-Z][A-Za-z0-9&.-]{1,24}){0,2}\\s+(?:LLC|Inc\\.?|Ltd\\.?|Corp\\.?|GmbH))(?![A-Za-z])"
        )

        private val TRAILING_URL_PUNCT = setOf('.', ',', ';', ':', '!', '?', '»', '"', '\'', '…')

        /**
         * Operational-only confirmation code check (in-memory, never saved to persistent history).
         * Returns the extracted 4-8 digit OTP code if clearly present with OTP context, else null.
         */
        fun extractEphemeralOtp(rawText: String): String? {
            if (rawText.length > 100_000) return null
            val m1 = RX_OTP.matcher(rawText)
            if (m1.find()) return m1.group(1)
            val m2 = RX_OTP_REVERSE.matcher(rawText)
            if (m2.find()) return m2.group(1)
            return com.claritykey.keyboard.ime.OtpMemoryBridge.extractKeywordGuardedOtp(rawText)
        }
    }

    private fun settingsSignature(): String = buildString {
        append(if (prefs.smartPasteEnabled) '1' else '0')
        append(if (prefs.smartPasteDetectUrls) '1' else '0')
        append(if (prefs.smartPasteDetectPhones) '1' else '0')
        append(if (prefs.smartPasteDetectEmails) '1' else '0')
        append(if (prefs.smartPasteDetectDatesAndTime) '1' else '0')
        append(if (prefs.smartPasteDetectAddressesAndCoords) '1' else '0')
        append(if (prefs.smartPasteDetectFinance) '1' else '0')
        append(if (prefs.smartPasteDetectOrdersAndTracking) '1' else '0')
        append(if (prefs.smartPasteDetectPromosAndSkus) '1' else '0')
        append(if (prefs.smartPasteDetectPeopleAndOrgs) '1' else '0')
        append(if (prefs.smartPasteHideSensitive) '1' else '0')
    }

    fun getCached(entry: ClipEntry): SmartPasteResult? {
        if (entry.isImage || !prefs.smartPasteEnabled) return null
        return cache.get("${entry.contentHash}_${settingsSignature()}")
    }

    fun analyzeAsync(entry: ClipEntry, onResult: (SmartPasteResult) -> Unit) {
        if (entry.isImage || !prefs.smartPasteEnabled || entry.text.isBlank()) {
            onResult(SmartPasteResult(entry.id, entry.contentHash, emptyList(), false))
            return
        }
        val key = "${entry.contentHash}_${settingsSignature()}"
        cache.get(key)?.let { cached ->
            onResult(cached)
            return
        }
        executor.execute {
            val result = try {
                analyzeSync(entry)
            } catch (_: Exception) {
                SmartPasteResult(entry.id, entry.contentHash, emptyList(), false)
            }
            cache.put(key, result)
            mainHandler.post { onResult(result) }
        }
    }

    fun analyzeSync(entry: ClipEntry): SmartPasteResult {
        if (entry.isImage) {
            return SmartPasteResult(entry.id, entry.contentHash, emptyList(), false)
        }
        val source = if (entry.text.length > MAX_INPUT_LENGTH) {
            entry.text.substring(0, MAX_INPUT_LENGTH)
        } else {
            entry.text
        }

        val sensitiveSpans = ArrayList<IntRange>()
        val sensitiveSource = entry.text.take(100_000)
        var hasSensitive = entry.text.length > 100_000 || extractEphemeralOtp(sensitiveSource) != null

        // Detect bank cards (Luhn-valid 13..19 digits), OTPs, and API keys
        val cardMatcher = RX_CARD_CANDIDATE.matcher(sensitiveSource)
        while (cardMatcher.find()) {
            val digits = cardMatcher.group().filter { it.isDigit() }
            if (digits.length in 13..19 && isValidLuhn(digits)) {
                hasSensitive = true
                sensitiveSpans.add(cardMatcher.start() until cardMatcher.end())
            }
        }
        listOf(RX_OTP, RX_OTP_REVERSE).forEach { rx ->
            val om = rx.matcher(sensitiveSource)
            while (om.find()) {
                hasSensitive = true
                sensitiveSpans.add(om.start() until om.end())
            }
        }
        if (RX_BEARER_SECRET.matcher(sensitiveSource).find()) {
            hasSensitive = true
        }

        if (!prefs.smartPasteEnabled) return SmartPasteResult(entry.id, entry.contentHash, emptyList(), hasSensitive)

        val candidates = ArrayList<SmartEntity>()

        // 1. URLs
        if (prefs.smartPasteDetectUrls) {
            extractUrls(source, entry.id, candidates)
        }

        // 2. Emails
        if (prefs.smartPasteDetectEmails) {
            val em = Patterns.EMAIL_ADDRESS.matcher(source)
            while (em.find()) {
                val raw = em.group()
                if (raw.contains('.') && !raw.startsWith(".") && !raw.endsWith(".")) {
                    candidates.add(SmartEntity(SmartEntityType.EMAIL, raw, em.start(), em.end(), true, entry.id))
                }
            }
        }

        // 3. Phones
        if (prefs.smartPasteDetectPhones) {
            val pm = RX_PHONE_CANDIDATE.matcher(source)
            while (pm.find()) {
                val raw = pm.group().trim()
                if (overlapsAny(pm.start(), pm.end(), sensitiveSpans)) continue
                if (isLikelyValidPhone(raw)) {
                    candidates.add(SmartEntity(SmartEntityType.PHONE, raw, pm.start(), pm.end(), true, entry.id))
                }
            }
        }

        // 4. Dates & Time
        if (prefs.smartPasteDetectDatesAndTime) {
            val dm1 = RX_DATE_RU_TEXT.matcher(source)
            while (dm1.find()) {
                val day = dm1.group(1)?.toIntOrNull() ?: continue
                if (day in 1..31) {
                    candidates.add(SmartEntity(SmartEntityType.DATE, dm1.group().trim(), dm1.start(), dm1.end(), true, entry.id))
                }
            }
            val dm2 = RX_DATE_NUMERIC.matcher(source)
            while (dm2.find()) {
                candidates.add(SmartEntity(SmartEntityType.DATE, dm2.group(1) ?: dm2.group(), dm2.start(), dm2.end(), true, entry.id))
            }
            val tm = RX_TIME.matcher(source)
            while (tm.find()) {
                candidates.add(SmartEntity(SmartEntityType.TIME, tm.group().trim(), tm.start(), tm.end(), true, entry.id))
            }
        }

        // 5. Addresses & Coordinates
        if (prefs.smartPasteDetectAddressesAndCoords) {
            val cm = RX_COORDINATES.matcher(source)
            while (cm.find()) {
                val lat = cm.group(1)?.toDoubleOrNull()
                val lon = cm.group(2)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    candidates.add(SmartEntity(SmartEntityType.COORDINATES, cm.group().trim(), cm.start(), cm.end(), true, entry.id))
                }
            }
            val am = RX_ADDRESS_RU.matcher(source)
            while (am.find()) {
                val raw = am.group().trim().trimEnd(',', '.', ';')
                if (raw.length >= 8 && raw.any { it.isDigit() }) {
                    candidates.add(SmartEntity(SmartEntityType.ADDRESS, raw, am.start(), am.start() + raw.length, true, entry.id))
                }
            }
        }

        // 6. Currency & Bank Details (IBAN Mod-97, INN checksum, BIK, Russian 20-digit account)
        if (prefs.smartPasteDetectFinance) {
            val cur = RX_CURRENCY.matcher(source)
            while (cur.find()) {
                val raw = cur.group().trim()
                if (raw.any { it.isDigit() }) {
                    candidates.add(SmartEntity(SmartEntityType.CURRENCY, raw, cur.start(), cur.end(), true, entry.id))
                }
            }
            val ibanM = RX_IBAN.matcher(source)
            while (ibanM.find()) {
                val raw = ibanM.group(1)?.trim() ?: continue
                if (isValidIban(raw)) {
                    candidates.add(SmartEntity(SmartEntityType.BANK_DETAILS, raw, ibanM.start(1), ibanM.end(1), true, entry.id))
                }
            }
            val innM = RX_INN.matcher(source)
            while (innM.find()) {
                val inn = innM.group(1) ?: continue
                if (isValidRussianInn(inn)) {
                    candidates.add(SmartEntity(SmartEntityType.BANK_DETAILS, inn, innM.start(1), innM.end(1), true, entry.id))
                }
            }
            val bikM = RX_BIK.matcher(source)
            while (bikM.find()) {
                val bik = bikM.group(1) ?: continue
                candidates.add(SmartEntity(SmartEntityType.BANK_DETAILS, bik, bikM.start(1), bikM.end(1), true, entry.id))
            }
            val accM = RX_ACCOUNT_RU.matcher(source)
            while (accM.find()) {
                val acc = accM.group(1) ?: continue
                candidates.add(SmartEntity(SmartEntityType.BANK_DETAILS, acc, accM.start(1), accM.end(1), true, entry.id))
            }
        }

        // 7. Orders, Tracking Numbers & Flights
        if (prefs.smartPasteDetectOrdersAndTracking) {
            val upu = RX_TRACK_UPU.matcher(source)
            while (upu.find()) {
                candidates.add(SmartEntity(SmartEntityType.TRACKING_NUMBER, upu.group(1)!!, upu.start(1), upu.end(1), true, entry.id))
            }
            val ups = RX_TRACK_UPS.matcher(source)
            while (ups.find()) {
                candidates.add(SmartEntity(SmartEntityType.TRACKING_NUMBER, ups.group(1)!!, ups.start(1), ups.end(1), true, entry.id))
            }
            val trk = RX_TRACK_CONTEXT.matcher(source)
            while (trk.find()) {
                val v = trk.group(1)?.trim() ?: continue
                if (v.count { it.isDigit() } >= 5) {
                    candidates.add(SmartEntity(SmartEntityType.TRACKING_NUMBER, v, trk.start(1), trk.end(1), true, entry.id))
                }
            }
            val om = RX_ORDER_NUMBER.matcher(source)
            while (om.find()) {
                val idVal = om.group(1)?.trim() ?: continue
                if (!idVal.any { it.isDigit() }) continue
                if (overlapsAny(om.start(1), om.end(1), sensitiveSpans)) continue
                candidates.add(SmartEntity(SmartEntityType.ORDER_NUMBER, idVal, om.start(1), om.end(1), true, entry.id))
            }
            val fl = RX_FLIGHT_CONTEXT.matcher(source)
            while (fl.find()) {
                val code = fl.group(1)?.trim() ?: continue
                if (code.any { it.isLetter() } && code.any { it.isDigit() }) {
                    candidates.add(SmartEntity(SmartEntityType.FLIGHT_NUMBER, code, fl.start(1), fl.end(1), true, entry.id))
                }
            }
        }

        // 8. Promo Codes, SKUs & Car Plates
        if (prefs.smartPasteDetectPromosAndSkus) {
            val pr = RX_PROMO.matcher(source)
            while (pr.find()) {
                val v = pr.group(1)?.trim() ?: continue
                if (v.any { it.isLetter() }) {
                    candidates.add(SmartEntity(SmartEntityType.PROMO_CODE, v, pr.start(1), pr.end(1), true, entry.id))
                }
            }
            val sku = RX_SKU.matcher(source)
            while (sku.find()) {
                val v = sku.group(1)?.trim() ?: continue
                if (v.any { it.isDigit() }) {
                    candidates.add(SmartEntity(SmartEntityType.SKU_ARTICLE, v, sku.start(1), sku.end(1), true, entry.id))
                }
            }
            val plate = RX_CAR_PLATE_RU.matcher(source)
            while (plate.find()) {
                val v = plate.group(1)?.trim() ?: continue
                candidates.add(SmartEntity(SmartEntityType.CAR_PLATE, v, plate.start(1), plate.end(1), true, entry.id))
            }
        }

        // 9. People (FIO) & Organizations
        if (prefs.smartPasteDetectPeopleAndOrgs) {
            val orgRu = RX_ORG_RU.matcher(source)
            while (orgRu.find()) {
                val v = orgRu.group(1)?.trim() ?: continue
                candidates.add(SmartEntity(SmartEntityType.ORGANIZATION, v, orgRu.start(1), orgRu.end(1), true, entry.id))
            }
            val orgEn = RX_ORG_EN.matcher(source)
            while (orgEn.find()) {
                val v = orgEn.group(1)?.trim() ?: continue
                candidates.add(SmartEntity(SmartEntityType.ORGANIZATION, v, orgEn.start(1), orgEn.end(1), true, entry.id))
            }
            val fio1 = RX_FIO_PATRONYMIC.matcher(source)
            while (fio1.find()) {
                val v = fio1.group(1)?.trim() ?: continue
                candidates.add(SmartEntity(SmartEntityType.PERSON_NAME, v, fio1.start(1), fio1.end(1), true, entry.id))
            }
            val fio2 = RX_FIO_LABELED.matcher(source)
            while (fio2.find()) {
                val v = fio2.group(1)?.trim() ?: continue
                candidates.add(SmartEntity(SmartEntityType.PERSON_NAME, v, fio2.start(1), fio2.end(1), true, entry.id))
            }
        }

        // Never expose OTP/card/secret spans in persistent Smart Paste results
        val filtered = if (prefs.smartPasteHideSensitive && sensitiveSpans.isNotEmpty()) {
            candidates.filterNot { overlapsAny(it.start, it.end, sensitiveSpans) }
        } else {
            candidates
        }

        val resolved = resolveOverlapsAndDeduplicate(filtered)
        return SmartPasteResult(
            parentEntryId = entry.id,
            contentHash = entry.contentHash,
            entities = resolved,
            hasSensitiveData = hasSensitive
        )
    }

    private fun extractUrls(source: String, entryId: Long, out: MutableList<SmartEntity>) {
        val m = RX_EXPLICIT_URL.matcher(source)
        while (m.find()) {
            val cleaned = trimTrailingUrlPunctuation(m.group())
            if (cleaned.length >= 7 && cleaned.contains('.')) {
                val end = m.start() + cleaned.length
                out.add(SmartEntity(SmartEntityType.URL, cleaned, m.start(), end, true, entryId))
            }
        }
    }

    private fun trimTrailingUrlPunctuation(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val last = s.last()
            if (last in TRAILING_URL_PUNCT) {
                s = s.dropLast(1)
            } else if (last == ')' && s.count { it == ')' } > s.count { it == '(' }) {
                s = s.dropLast(1)
            } else if (last == ']' && s.count { it == ']' } > s.count { it == '[' }) {
                s = s.dropLast(1)
            } else if (last == '}' && s.count { it == '}' } > s.count { it == '{' }) {
                s = s.dropLast(1)
            } else {
                break
            }
        }
        return s
    }

    private fun isLikelyValidPhone(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 10..15) return false
        if (digits.all { it == digits[0] }) return false
        if ((raw.startsWith("+7") || raw.startsWith("8")) && digits.length != 11) return false
        if (digits.length in 13..16 && isValidLuhn(digits)) return false
        return true
    }

    /** ISO 13616 Mod-97 IBAN checksum validation */
    private fun isValidIban(raw: String): Boolean {
        val clean = raw.replace(" ", "").uppercase()
        if (clean.length !in 15..34) return false
        val rearranged = clean.substring(4) + clean.substring(0, 4)
        val sb = StringBuilder(rearranged.length * 2)
        for (ch in rearranged) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in 'A'..'Z' -> sb.append((ch - 'A' + 10))
                else -> return false
            }
        }
        return try {
            BigInteger(sb.toString()).mod(BigInteger.valueOf(97L)) == BigInteger.ONE
        } catch (_: Exception) {
            false
        }
    }

    /** Official Russian FNS 10-digit (org) and 12-digit (person) INN control-digit validation */
    private fun isValidRussianInn(inn: String): Boolean {
        if (!inn.all { it.isDigit() }) return false
        val d = inn.map { it - '0' }
        return when (d.size) {
            10 -> {
                val w = intArrayOf(2, 4, 10, 3, 5, 9, 4, 6, 8)
                val sum = w.indices.sumOf { d[it] * w[it] }
                ((sum % 11) % 10) == d[9]
            }
            12 -> {
                val w1 = intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8)
                val w2 = intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8)
                val c1 = (w1.indices.sumOf { d[it] * w1[it] } % 11) % 10
                val c2 = (w2.indices.sumOf { d[it] * w2[it] } % 11) % 10
                c1 == d[10] && c2 == d[11]
            }
            else -> false
        }
    }

    private fun resolveOverlapsAndDeduplicate(candidates: List<SmartEntity>): List<SmartEntity> {
        if (candidates.isEmpty()) return emptyList()
        val prioritized = candidates.sortedWith(
            compareByDescending<SmartEntity> { it.type.priority }
                .thenByDescending { it.end - it.start }
                .thenBy { it.start }
        )
        val accepted = ArrayList<SmartEntity>()
        val seenKeys = HashSet<String>()
        for (item in prioritized) {
            val overlaps = accepted.any { it.start < item.end && item.start < it.end }
            if (overlaps) continue
            val dedupKey = "${item.type.name}:${item.rawText}"
            if (seenKeys.add(dedupKey)) {
                accepted.add(item)
            }
        }
        return accepted.sortedBy { it.start }
    }

    private fun overlapsAny(start: Int, end: Int, spans: List<IntRange>): Boolean =
        spans.any { r -> start <= r.last && r.first < end }

    private fun isValidLuhn(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }

    fun formatCompactSummary(entities: List<SmartEntity>): String {
        if (entities.isEmpty()) return ""
        val grouped = LinkedHashMap<SmartEntityType, Int>()
        entities.forEach { grouped[it.type] = (grouped[it.type] ?: 0) + 1 }
        val parts = grouped.entries.map { (type, cnt) ->
            val label = if (cnt == 1) type.shortSingular else type.shortPlural
            "${type.iconGlyph} $cnt $label"
        }
        return if (parts.size <= 2) {
            parts.joinToString(" · ")
        } else {
            val shownCount = grouped.entries.take(2).sumOf { it.value }
            val rest = entities.size - shownCount
            "${parts[0]} · ${parts[1]} · +$rest"
        }
    }

    fun release() {
        cache.evictAll()
        executor.shutdown()
    }
}
