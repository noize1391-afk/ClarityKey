package com.claritykey.keyboard.engine

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.claritykey.keyboard.clipboard.ClipEntry
import com.claritykey.keyboard.clipboard.ClipboardCrypto
import com.claritykey.keyboard.clipboard.ClipboardRepository
import com.claritykey.keyboard.clipboard.MediaClipboardStorage
import com.claritykey.keyboard.clipboard.MediaStorageStats
import com.claritykey.keyboard.clipboard.RecentScreenshotDetector
import com.claritykey.keyboard.clipboard.RecentScreenshotInfo
import com.claritykey.keyboard.clipboard.SmartEntity
import com.claritykey.keyboard.clipboard.SmartEntityType
import com.claritykey.keyboard.clipboard.SmartPasteAnalyzer
import com.claritykey.keyboard.clipboard.SmartPasteVisionOcr
import com.claritykey.keyboard.clipboard.OcrVisionResult
import com.claritykey.keyboard.prefs.BreenoPreferences
import java.util.concurrent.Executors

data class QuickSmartSuggestion(
    val contentHash: String,
    val label: String,
    val iconGlyph: String,
    val directFragment: String?,
    val entry: ClipEntry,
    val entities: List<SmartEntity>
)

data class QuickImageSuggestion(
    val idKey: String,
    val label: String,
    val thumbPath: String?,
    val clipEntry: ClipEntry? = null,
    val screenshotInfo: RecentScreenshotInfo? = null
)

/**
 * Universal Smart Clipboard 2.0 Coordinator:
 * - Official Android ClipboardManager + OnPrimaryClipChangedListener + IME lifecycle sync
 * - Handles multi-item ClipData preserving order: text/plain, text/html, and image MIME types (PNG/JPEG/WebP/GIF/etc.)
 * - Never converts image URIs into text strings; safely streams local copies via MediaClipboardStorage
 * - Respects ClipDescription.EXTRA_IS_SENSITIVE (API 33+) and legacy IS_SENSITIVE extra (API 28-32)
 * - Coordinates independent optional MediaStore recent screenshot suggestions (2-minute freshness)
 */
class BreenoClipboardManager(
    private val context: Context,
    private val prefs: BreenoPreferences
) {
    private val sysClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val repository = ClipboardRepository(context, prefs)
    val screenshotDetector = RecentScreenshotDetector(context, prefs)
    val smartPasteAnalyzer = SmartPasteAnalyzer(prefs)
    val visionOcr = SmartPasteVisionOcr(context, prefs, repository.mediaStorage, smartPasteAnalyzer)

    private var observedGeneration = ClipboardRepository.dataGeneration
    private fun syncDataGeneration() {
        if (observedGeneration != ClipboardRepository.dataGeneration) {
            observedGeneration = ClipboardRepository.dataGeneration
            clearPendingSuggestions()
        }
    }

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastCapturedTextHash: String? = null
    private var lastCapturedUriKey: String? = null
    private var lastSuggestedHash: String? = null
    private var latestSuggestionText: String? = null
    private var latestSuggestionTimeMs: Long = 0L
    private var latestTextEntry: ClipEntry? = null
    private var ephemeralOtpSuggestion: QuickSmartSuggestion? = null
    private var ephemeralOtpTimeMs: Long = 0L
    private var dismissedSmartHashes = HashSet<String>()
    private var onNewSmartClipCallback: ((QuickSmartSuggestion) -> Unit)? = null

    private var latestImageSuggestion: QuickImageSuggestion? = null
    private var latestImageSuggestionTimeMs: Long = 0L
    private var dismissedImageKeys = HashSet<String>()

    private var onNewClipCallback: ((String) -> Unit)? = null
    private var onNewImageClipCallback: ((QuickImageSuggestion) -> Unit)? = null
    private var onHistoryChangedCallback: (() -> Unit)? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncFromSystemClipboard(notifyQuickChip = true)
    }

    init {
        try {
            sysClipboard.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        val legacyPinned = prefs.getPinnedClips()
        if (legacyPinned.isNotEmpty()) {
            legacyPinned.forEach { text ->
                repository.saveOrPromoteAsync(text) { entry, _ ->
                    if (!entry.pinned) repository.togglePinAsync(entry.id)
                }
            }
            prefs.savePinnedClips(emptyList())
        }
        syncFromSystemClipboard(notifyQuickChip = false)
        screenshotDetector.startObserving {
            onMediaStoreScreenshotChanged()
        }
    }

    fun setOnNewClipListener(listener: ((String) -> Unit)?) {
        onNewClipCallback = listener
    }

    fun setOnNewSmartClipListener(listener: ((QuickSmartSuggestion) -> Unit)?) {
        onNewSmartClipCallback = listener
    }

    fun setOnNewImageClipListener(listener: ((QuickImageSuggestion) -> Unit)?) {
        onNewImageClipCallback = listener
    }

    fun setOnHistoryChangedListener(listener: (() -> Unit)?) {
        onHistoryChangedCallback = listener
    }

    fun release() {
        try {
            sysClipboard.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        onNewClipCallback = null
        onNewImageClipCallback = null
        onNewSmartClipCallback = null
        visionOcr.release()
        smartPasteAnalyzer.release()
        onHistoryChangedCallback = null
        screenshotDetector.stopObserving()
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
        repository.close()
    }

    /**
     * Synchronizes with the current system clipboard across ALL ClipData items.
     * Supports text/plain, text/html, and image MIME types (PNG, JPEG, WebP, GIF, etc.).
     */
    @Volatile
    private var initialClipSynced: Boolean = false

    fun syncFromSystemClipboard(notifyQuickChip: Boolean = false) {
        syncDataGeneration()
        if (!prefs.clipboardEnabled || prefs.clipboardCapturePaused) return
        try {
            if (!sysClipboard.hasPrimaryClip()) return
            val clip = sysClipboard.primaryClip ?: return
            val count = clip.itemCount
            if (count <= 0) return
            val isSilentInitialSync = !initialClipSynced && !notifyQuickChip
            if (!initialClipSynced) initialClipSynced = true
            val desc = clip.description

            // Privacy guard: ignore system-marked sensitive content (API 33+ and API 28-32)
            if (prefs.clipboardIgnoreSensitive && isClipSensitive(desc)) {
                return
            }

            val descImageMime = findImageMimeInDescription(desc)

            for (i in 0 until count) {
                val item = clip.getItemAt(i) ?: continue
                val uri = item.uri

                // 1. Check for image URI payload first
                if (uri != null) {
                    val resolvedMime = resolveUriImageMime(uri, descImageMime)
                    if (resolvedMime != null) {
                        if (prefs.clipboardSaveImages) {
                            if (!isSilentInitialSync) handleImageItem(uri, resolvedMime, item, notifyQuickChip && i == 0)
                        }
                        // Never fall through and save a content:// image URI as plain text!
                        continue
                    }
                }

                // 2. Text payload (text/plain or text/html)
                val textSeq = item.text ?: run {
                    val isTextMime = desc != null && (
                        desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                            desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                        )
                    if (isTextMime && uri == null) item.coerceToText(context) else null
                } ?: continue

                val rawText = textSeq.toString()
                if (rawText.isEmpty() || rawText.length > 100_000) continue
                // Never treat a bare content:// URI string as text if it came alongside an image description
                if (descImageMime != null && rawText.startsWith("content://")) continue

                val hash = ClipboardCrypto.sha256Exact(rawText)
                if (count == 1 && hash == lastCapturedTextHash) continue
                lastCapturedTextHash = hash

                // Operational-only OTP handling: recognize confirmation code in memory for 60s,
                // NEVER save OTP message to persistent history and NEVER display digits in open chip!
                val otpCode = SmartPasteAnalyzer.extractEphemeralOtp(rawText)
                if (otpCode != null) {
                    val epEntry = ClipEntry(
                        id = -1L,
                        text = "",
                        contentHash = hash,
                        pinned = false,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    val otpEntity = SmartEntity(SmartEntityType.OTP_CODE, otpCode, 0, otpCode.length, true, -1L)
                    val otpSug = QuickSmartSuggestion(
                        contentHash = hash,
                        label = "Вставить код",
                        iconGlyph = "🔐",
                        directFragment = otpCode,
                        entry = epEntry,
                        entities = listOf(otpEntity)
                    )
                    ephemeralOtpSuggestion = otpSug
                    ephemeralOtpTimeMs = System.currentTimeMillis()
                    if (notifyQuickChip && prefs.smartPasteQuickSuggestions && !dismissedSmartHashes.contains(hash)) {
                        onNewSmartClipCallback?.invoke(otpSug)
                    }
                    continue
                }

                val verifiedSourcePkg = resolveVerifiedSourcePackage(item)
                val verifiedSourceLabel = verifiedSourcePkg?.let { resolveAppLabel(it) }
                if (isSilentInitialSync) {
                    // The first sync commonly happens only after the keyboard becomes visible.
                    // Persist the current system clip so copies made while the IME view was
                    // hidden are not lost, but suppress both smart and plain quick-chips on startup.
                    dismissedSmartHashes.add(hash)
                    lastSuggestedHash = hash
                }
                repository.saveOrPromoteAsync(rawText, verifiedSourcePkg, verifiedSourceLabel, automaticCapture = true) { entry, _ ->
                    if (prefs.clipboardCapturePaused) return@saveOrPromoteAsync
                    if (!isSilentInitialSync) {
                        latestSuggestionText = entry.text
                        latestSuggestionTimeMs = entry.updatedAt
                        latestTextEntry = entry
                    }
                    onHistoryChangedCallback?.invoke()
                    if (isSilentInitialSync) return@saveOrPromoteAsync
                    if (prefs.smartPasteEnabled) {
                        smartPasteAnalyzer.analyzeAsync(entry) { res ->
                            if (prefs.clipboardCapturePaused || (prefs.smartPasteHideSensitive && res.hasSensitiveData)) return@analyzeAsync
                            val smartSug = buildQuickSmartSuggestion(entry, res.entities, res.hasSensitiveData)
                            if (notifyQuickChip && i == 0 && smartSug != null && !dismissedSmartHashes.contains(entry.contentHash)) {
                                lastSuggestedHash = hash
                                onNewSmartClipCallback?.invoke(smartSug)
                            } else if (notifyQuickChip && i == 0 && prefs.clipboardQuickChipEnabled && hash != lastSuggestedHash) {
                                lastSuggestedHash = hash
                                onNewClipCallback?.invoke(entry.text)
                            }
                        }
                    } else if (notifyQuickChip && i == 0 && prefs.clipboardQuickChipEnabled && hash != lastSuggestedHash) {
                        lastSuggestedHash = hash
                        onNewClipCallback?.invoke(entry.text)
                    }
                }
            }
        } catch (_: Exception) {
            // Never crash on OEM clipboard restrictions or revoked temporary URI permissions
        }
    }

    private fun handleImageItem(uri: Uri, mimeType: String, item: ClipData.Item, notifyQuickChip: Boolean) {
        val uriKey = "${uri}_$mimeType"
        if (uriKey == lastCapturedUriKey) return

        val verifiedSourcePkg = resolveVerifiedSourcePackage(item)
        val verifiedSourceLabel = verifiedSourcePkg?.let { resolveAppLabel(it) }

        repository.saveImageFromUriAsync(
            uri = uri,
            hintedMime = mimeType,
            sourcePackage = verifiedSourcePkg,
            sourceLabel = verifiedSourceLabel,
            automaticCapture = true
        ) { entry, _ ->
            if (entry != null) {
                lastCapturedUriKey = uriKey
                val isScreenshot = isLikelyScreenshotName(uri.lastPathSegment ?: "")
                val label = if (isScreenshot) "Недавний скриншот" else "Скопированное фото"
                val suggestion = QuickImageSuggestion(
                    idKey = entry.contentHash,
                    label = label,
                    thumbPath = entry.thumbPath ?: entry.filePath,
                    clipEntry = entry
                )
                latestImageSuggestion = suggestion
                latestImageSuggestionTimeMs = entry.updatedAt
                onHistoryChangedCallback?.invoke()
                if (prefs.visionOcrEnabled && prefs.visionAutoOcr) {
                    recognizeImageOcrAsync(entry) { onHistoryChangedCallback?.invoke() }
                }
                if (notifyQuickChip && prefs.clipboardShowImageSuggestions && !dismissedImageKeys.contains(suggestion.idKey)) {
                    onNewImageClipCallback?.invoke(suggestion)
                }
            }
        }
    }

    private fun onMediaStoreScreenshotChanged() {
        if (prefs.clipboardCapturePaused || !prefs.clipboardEnabled || !prefs.clipboardSuggestScreenshots || !screenshotDetector.hasPermission()) return
        bgExecutor.execute {
            if (prefs.clipboardCapturePaused) return@execute
            val info = screenshotDetector.findRecentScreenshot(ignoreDismissed = true) ?: return@execute
            if (info.key == prefs.lastAutoImportedScreenshotKey) return@execute
            prefs.lastAutoImportedScreenshotKey = info.key
            if (!prefs.clipboardSaveImages) return@execute
            repository.saveImageFromUriAsync(
                uri = info.contentUri,
                hintedMime = info.mimeType,
                sourcePackage = null,
                sourceLabel = "Скриншот",
                automaticCapture = true
            ) { entry, _ ->
                if (entry != null) {
                    val suggestion = QuickImageSuggestion(
                        idKey = info.key,
                        label = "Недавний скриншот",
                        thumbPath = entry.thumbPath ?: entry.filePath,
                        clipEntry = entry
                    )
                    latestImageSuggestion = suggestion
                    latestImageSuggestionTimeMs = entry.updatedAt
                    onHistoryChangedCallback?.invoke()
                    if (prefs.clipboardShowImageSuggestions && !dismissedImageKeys.contains(info.key)) {
                        onNewImageClipCallback?.invoke(suggestion)
                    }
                }
            }
        }
    }

    /**
     * Checks for a quick image suggestion to display when the keyboard opens:
     * 1. Recently copied image from system clipboard (if enabled & <= 2 min old)
     * 2. Optional MediaStore recent screenshot (if user enabled & granted media permission & <= 2 min old)
     */
    fun checkPendingImageOrScreenshotAsync(onResult: (QuickImageSuggestion?) -> Unit) {
        syncDataGeneration()
        if (!prefs.clipboardEnabled || prefs.clipboardCapturePaused) {
            onResult(null)
            return
        }
        val now = System.currentTimeMillis()
        val recentClipImg = latestImageSuggestion
        if (prefs.clipboardShowImageSuggestions &&
            recentClipImg != null &&
            (now - latestImageSuggestionTimeMs) < RecentScreenshotDetector.SCREENSHOT_FRESHNESS_MS &&
            !dismissedImageKeys.contains(recentClipImg.idKey)
        ) {
            onResult(recentClipImg)
            return
        }

        if (!prefs.clipboardSuggestScreenshots || !screenshotDetector.hasPermission()) {
            onResult(null)
            return
        }

        bgExecutor.execute {
            if (prefs.clipboardCapturePaused) return@execute
            val info = screenshotDetector.findRecentScreenshot()
            if (info == null) {
                mainHandler.post { onResult(null) }
                return@execute
            }

            val shouldAutoImport = prefs.clipboardSaveImages && info.key != prefs.lastAutoImportedScreenshotKey
            if (shouldAutoImport) {
                prefs.lastAutoImportedScreenshotKey = info.key
                repository.saveImageFromUriAsync(
                    uri = info.contentUri,
                    hintedMime = info.mimeType,
                    sourcePackage = null,
                    sourceLabel = "Скриншот",
                automaticCapture = true
                ) { entry, _ ->
                    if (entry != null) {
                        onHistoryChangedCallback?.invoke()
                    }
                    val suggestion = if (!dismissedImageKeys.contains(info.key)) {
                        QuickImageSuggestion(
                            idKey = info.key,
                            label = "Недавний скриншот",
                            thumbPath = entry?.thumbPath ?: entry?.filePath,
                            clipEntry = entry,
                            screenshotInfo = if (entry == null) info else null
                        )
                    } else null
                    onResult(suggestion)
                }
            } else {
                val suggestion = if (!dismissedImageKeys.contains(info.key)) {
                    QuickImageSuggestion(
                        idKey = info.key,
                        label = "Недавний скриншот",
                        thumbPath = null,
                        clipEntry = null,
                        screenshotInfo = info
                    )
                } else null
                mainHandler.post { onResult(suggestion) }
            }
        }
    }

    fun dismissImageSuggestion(suggestion: QuickImageSuggestion) {
        dismissedImageKeys.add(suggestion.idKey)
        if (latestImageSuggestion?.idKey == suggestion.idKey) {
            latestImageSuggestion = null
        }
        suggestion.screenshotInfo?.let { screenshotDetector.dismiss(it) }
    }

    /**
     * Imports a MediaStore recent screenshot into local Smart Clipboard storage before inserting it.
     */
    fun importScreenshotAndGetEntryAsync(
        info: RecentScreenshotInfo,
        onReady: (ClipEntry?) -> Unit
    ) {
        repository.saveImageFromUriAsync(
            uri = info.contentUri,
            hintedMime = info.mimeType,
            sourcePackage = null,
            sourceLabel = "Скриншот",
                automaticCapture = true
        ) { entry, _ ->
            if (entry != null) {
                screenshotDetector.dismiss(info)
                dismissedImageKeys.add(info.key)
                dismissedImageKeys.add(entry.contentHash)
                onHistoryChangedCallback?.invoke()
            }
            onReady(entry)
        }
    }

    private fun buildQuickSmartSuggestion(
        entry: ClipEntry,
        entities: List<SmartEntity>,
        hasSensitiveData: Boolean
    ): QuickSmartSuggestion? {
        if (!prefs.smartPasteEnabled || !prefs.smartPasteQuickSuggestions) return null
        if (prefs.smartPasteHideSensitive && hasSensitiveData) return null
        if (entities.isEmpty()) return null
        if (entities.size == 1) {
            val e = entities[0]
            val label = when (e.type) {
                SmartEntityType.URL -> "Вставить ссылку"
                SmartEntityType.PHONE -> "Вставить телефон"
                SmartEntityType.EMAIL -> "Вставить email"
                else -> return null
            }
            return QuickSmartSuggestion(entry.contentHash, label, e.type.iconGlyph, e.rawText, entry, entities)
        }
        return QuickSmartSuggestion(
            contentHash = entry.contentHash,
            label = "Умная вставка (${entities.size})",
            iconGlyph = "✦",
            directFragment = null,
            entry = entry,
            entities = entities
        )
    }

    fun checkPendingSmartSuggestionAsync(onResult: (QuickSmartSuggestion?) -> Unit) {
        syncDataGeneration()
        if (prefs.clipboardCapturePaused || !prefs.clipboardEnabled || !prefs.smartPasteEnabled || !prefs.smartPasteQuickSuggestions) {
            onResult(null)
            return
        }
        val otp = ephemeralOtpSuggestion
        if (otp != null) {
            if ((System.currentTimeMillis() - ephemeralOtpTimeMs) < 60_000L && !dismissedSmartHashes.contains(otp.contentHash)) {
                onResult(otp)
                return
            } else {
                ephemeralOtpSuggestion = null
            }
        }
        val entry = latestTextEntry ?: run { onResult(null); return }
        if ((System.currentTimeMillis() - latestSuggestionTimeMs) >= 120_000L || dismissedSmartHashes.contains(entry.contentHash)) {
            onResult(null)
            return
        }
        smartPasteAnalyzer.analyzeAsync(entry) { res ->
            onResult(buildQuickSmartSuggestion(entry, res.entities, res.hasSensitiveData))
        }
    }

    fun dismissSmartSuggestion(contentHash: String) {
        dismissedSmartHashes.add(contentHash)
        if (ephemeralOtpSuggestion?.contentHash == contentHash) ephemeralOtpSuggestion = null
        lastSuggestedHash = contentHash
    }

    fun getPendingQuickSuggestion(): String? {
        syncDataGeneration()
        if (prefs.clipboardCapturePaused || !prefs.clipboardEnabled || !prefs.clipboardQuickChipEnabled) return null
        val text = latestSuggestionText ?: return null
        val ageMs = System.currentTimeMillis() - latestSuggestionTimeMs
        val hash = ClipboardCrypto.sha256Exact(text)
        return if (ageMs < 120_000L && hash != lastSuggestedHash) text else null
    }

    fun markSuggestionConsumed(text: String) {
        lastSuggestedHash = ClipboardCrypto.sha256Exact(text)
    }

    fun recognizeImageOcrAsync(entry: ClipEntry, onResult: (OcrVisionResult) -> Unit) {
        visionOcr.recognizeAsync(
            entry = entry,
            onPersistOcrText = { id, text -> repository.updateOcrTextAsync(id, text) },
            onResult = onResult
        )
    }

    fun loadHistoryAsync(callback: (List<ClipEntry>) -> Unit) {
        syncDataGeneration()
        if (!prefs.clipboardCapturePaused && prefs.clipboardEnabled && prefs.clipboardSuggestScreenshots && prefs.clipboardSaveImages && screenshotDetector.hasPermission()) {
            bgExecutor.execute {
                val info = screenshotDetector.findRecentScreenshot(ignoreDismissed = true)
                if (info != null && info.key != prefs.lastAutoImportedScreenshotKey) {
                    prefs.lastAutoImportedScreenshotKey = info.key
                    repository.saveImageFromUriAsync(
                        uri = info.contentUri,
                        hintedMime = info.mimeType,
                        sourcePackage = null,
                        sourceLabel = "Скриншот",
                automaticCapture = true
                    ) { _, _ ->
                        repository.loadAllAsync(callback)
                    }
                } else {
                    repository.loadAllAsync(callback)
                }
            }
        } else {
            repository.loadAllAsync(callback)
        }
    }

    fun getMediaStorageStatsAsync(callback: (MediaStorageStats) -> Unit) {
        repository.getMediaStorageStatsAsync(callback)
    }

    fun togglePinAsync(id: Long, onDone: (() -> Unit)? = null) {
        repository.togglePinAsync(id) {
            onHistoryChangedCallback?.invoke()
            onDone?.invoke()
        }
    }

    fun deleteEntryAsync(id: Long, onDone: (() -> Unit)? = null) {
        repository.deleteByIdAsync(id) {
            onHistoryChangedCallback?.invoke()
            onDone?.invoke()
        }
    }

    fun clearUnpinnedAsync(onDone: (() -> Unit)? = null) {
        latestSuggestionText = null
        latestImageSuggestion = null
        repository.clearUnpinnedAsync {
            onHistoryChangedCallback?.invoke()
            onDone?.invoke()
        }
    }

    fun clearPendingSuggestions() {
        latestSuggestionText = null
        latestTextEntry = null
        latestImageSuggestion = null
        ephemeralOtpSuggestion = null
        lastSuggestedHash = null
        dismissedSmartHashes.clear()
        dismissedImageKeys.clear()
    }

    fun clearAllAsync(onDone: (() -> Unit)? = null) {
        clearPendingSuggestions()
        latestSuggestionText = null
        latestImageSuggestion = null
        lastCapturedTextHash = null
        lastCapturedUriKey = null
        repository.clearAllAsync {
            onHistoryChangedCallback?.invoke()
            onDone?.invoke()
        }
    }

    private fun findImageMimeInDescription(desc: ClipDescription?): String? {
        if (desc == null) return null
        for (i in 0 until desc.mimeTypeCount) {
            val mt = desc.getMimeType(i)
            if (MediaClipboardStorage.isSupportedImageMime(mt)) return mt.lowercase()
            if (mt.equals("image/*", ignoreCase = true)) return "image/*"
        }
        return null
    }

    private fun resolveUriImageMime(uri: Uri, descMime: String?): String? {
        return try {
            val crType = context.contentResolver.getType(uri)
            if (MediaClipboardStorage.isSupportedImageMime(crType)) return crType!!.lowercase()
            if (MediaClipboardStorage.isSupportedImageMime(descMime)) return descMime!!.lowercase()
            if (descMime == "image/*") return "image/png"
            null
        } catch (_: Exception) {
            if (MediaClipboardStorage.isSupportedImageMime(descMime)) descMime!!.lowercase() else null
        }
    }

    private fun isLikelyScreenshotName(name: String): Boolean {
        val low = name.lowercase()
        return low.contains("screenshot") || low.contains("screen_shot") || low.contains("скриншот")
    }

    private fun resolveVerifiedSourcePackage(item: ClipData.Item): String? {
        return try {
            val fromIntent = item.intent?.component?.packageName
            if (!fromIntent.isNullOrBlank()) return fromIntent

            val authority = item.uri?.authority
            if (!authority.isNullOrBlank()) {
                val provider = context.packageManager.resolveContentProvider(authority, 0)
                if (!provider?.packageName.isNullOrBlank()) return provider?.packageName
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isClipSensitive(desc: ClipDescription?): Boolean {
        val extras = desc?.extras ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) return true
        }
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }
}
