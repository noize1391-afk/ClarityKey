package com.claritykey.keyboard.clipboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

data class OcrVisionResult(
    val entryId: Long,
    val contentHash: String,
    val fullText: String,
    val paragraphs: List<String>,
    val lines: List<String>,
    val entities: List<SmartEntity>,
    val hasSensitiveData: Boolean,
    val errorMessage: String? = null
) {
    val hasText: Boolean get() = fullText.isNotBlank()
}

/**
 * Smart Paste Vision 3.0 — On-Device Russian & English OCR Engine.
 * - Powered by Tesseract4Android (Tesseract 5.5 + Leptonica, Apache 2.0, zero Google Play Services).
 * - 100% local on-device inference using tessdata_fast (rus.traineddata + eng.traineddata).
 * - Lazy-loaded only on demand (never slows keyboard startup or typing).
 * - Operates on bounded readable copies (max 1600px edge) to protect RAM on high-res photos.
 * - Feeds recognized text directly into existing SmartPasteAnalyzer without duplicating logic.
 * - Never silently alters OCR characters; caches results by image contentHash.
 */
class SmartPasteVisionOcr(
    context: Context,
    private val prefs: BreenoPreferences,
    private val mediaStorage: MediaClipboardStorage,
    private val smartPasteAnalyzer: SmartPasteAnalyzer
) {
    private val appContext = context.applicationContext
    private val tessRootDir = File(appContext.filesDir, "ocr_tess")
    private val tessDataDir = File(tessRootDir, "tessdata")
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = LruCache<String, OcrVisionResult>(60)

    private var observedGeneration = ClipboardRepository.dataGeneration
    private var tessApi: TessBaseAPI? = null
    private var initializedLang: String? = null

    companion object {
        private const val MAX_OCR_IMAGE_EDGE_PX = 1600
    }

    fun getCached(entry: ClipEntry): OcrVisionResult? {
        if (observedGeneration != ClipboardRepository.dataGeneration) {
            memoryCache.evictAll()
            observedGeneration = ClipboardRepository.dataGeneration
        }
        if (!entry.isImage) return null
        memoryCache.get(entry.contentHash)?.let { return buildResult(entry, it.fullText) }
        val persisted = entry.ocrText
        if (persisted != null) {
            val built = buildResult(entry, persisted)
            memoryCache.put(entry.contentHash, built)
            return built
        }
        return null
    }

    fun recognizeAsync(
        entry: ClipEntry,
        onPersistOcrText: ((Long, String) -> Unit)? = null,
        onResult: (OcrVisionResult) -> Unit
    ) {
        if (!entry.isImage || !prefs.visionOcrEnabled) {
            onResult(OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false))
            return
        }

        getCached(entry)?.let { cached ->
            onResult(cached)
            return
        }

        val generation = ClipboardRepository.dataGeneration
        ocrExecutor.execute {
            if (generation != ClipboardRepository.dataGeneration) return@execute
            val result = try {
                recognizeSync(entry)
            } catch (_: Throwable) {
                OcrVisionResult(
                    entryId = entry.id,
                    contentHash = entry.contentHash,
                    fullText = "",
                    paragraphs = emptyList(),
                    lines = emptyList(),
                    entities = emptyList(),
                    hasSensitiveData = false,
                    errorMessage = "Не удалось выполнить распознавание текста"
                )
            }

            if (generation != ClipboardRepository.dataGeneration) return@execute
            if (result.errorMessage == null) {
                memoryCache.put(entry.contentHash, result)
                // Privacy guard: never persist OCR text containing detected secrets when hideSensitive is ON
                val canPersist = prefs.visionSaveResults && !(prefs.smartPasteHideSensitive && result.hasSensitiveData)
                if (canPersist && onPersistOcrText != null) {
                    onPersistOcrText(entry.id, result.fullText)
                }
            }

            mainHandler.post { if (generation == ClipboardRepository.dataGeneration) onResult(result) }
        }
    }

    private fun recognizeSync(entry: ClipEntry): OcrVisionResult {
        val filePath = entry.filePath
        if (filePath.isNullOrBlank() || !File(filePath).exists()) {
            return OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false, "Файл недоступен")
        }

        val targetLang = if (prefs.visionOcrRusEng) "rus+eng" else "rus"
        if (!ensureTessDataExtracted(targetLang)) {
            return OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false, "Языковые пакеты OCR не найдены")
        }

        val api = ensureEngineInitialized(targetLang)
            ?: return OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false, "Ошибка инициализации OCR")

        val bmp = mediaStorage.decodeBoundedBitmap(filePath, MAX_OCR_IMAGE_EDGE_PX)
            ?: return OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false, "Не удалось декодировать изображение")

        val rawText = try {
            api.setImage(bmp)
            api.utF8Text ?: ""
        } finally {
            try {
                api.clear()
            } catch (_: Exception) {
            }
            if (!bmp.isRecycled) bmp.recycle()
        }

        // Preserve exact recognized text; only strip outer blank whitespace and reject noise-only output
        val cleaned = rawText.trim()
        val alnumCount = cleaned.count { it.isLetterOrDigit() }
        val finalText = if (alnumCount >= 2) cleaned else ""
        return buildResult(entry, finalText)
    }

    private fun buildResult(entry: ClipEntry, fullText: String): OcrVisionResult {
        if (fullText.isBlank()) {
            return OcrVisionResult(entry.id, entry.contentHash, "", emptyList(), emptyList(), emptyList(), false)
        }

        val paragraphs = fullText
            .split(Regex("\\r?\\n\\s*\\r?\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val lines = fullText
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Reuse existing SmartPasteAnalyzer directly (zero duplicated logic!)
        val syntheticTextEntry = entry.copy(text = fullText, type = ClipItemType.TEXT)
        val smartRes = smartPasteAnalyzer.analyzeSync(syntheticTextEntry)

        return OcrVisionResult(
            entryId = entry.id,
            contentHash = entry.contentHash,
            fullText = fullText,
            paragraphs = paragraphs,
            lines = lines,
            entities = if (prefs.visionShowFragments) smartRes.entities else emptyList(),
            hasSensitiveData = smartRes.hasSensitiveData
        )
    }

    private fun ensureTessDataExtracted(langSpec: String): Boolean {
        if (!tessDataDir.exists()) tessDataDir.mkdirs()
        val langs = langSpec.split("+").filter { it.isNotBlank() }
        for (lang in langs) {
            val fileName = "$lang.traineddata"
            val outFile = File(tessDataDir, fileName)
            if (outFile.exists() && outFile.length() > 100_000L) continue
            try {
                appContext.assets.open("tessdata/$fileName").use { ins ->
                    FileOutputStream(outFile).use { outs ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = ins.read(buf)
                            if (r <= 0) break
                            outs.write(buf, 0, r)
                        }
                        outs.flush()
                    }
                }
            } catch (_: Exception) {
                outFile.delete()
                return false
            }
        }
        return true
    }

    private fun ensureEngineInitialized(langSpec: String): TessBaseAPI? {
        if (tessApi != null && initializedLang == langSpec) return tessApi
        try {
            tessApi?.recycle()
        } catch (_: Exception) {
        }
        tessApi = null
        initializedLang = null

        return try {
            val api = TessBaseAPI()
            val ok = api.init(tessRootDir.absolutePath, langSpec)
            if (!ok) {
                api.recycle()
                null
            } else {
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tessApi = api
                initializedLang = langSpec
                api
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        memoryCache.evictAll()
        ocrExecutor.execute {
            try {
                tessApi?.recycle()
            } catch (_: Exception) {
            }
            tessApi = null
            initializedLang = null
        }
        ocrExecutor.shutdown()
    }
}
