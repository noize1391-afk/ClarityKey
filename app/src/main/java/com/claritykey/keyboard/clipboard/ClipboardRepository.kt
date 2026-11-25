package com.claritykey.keyboard.clipboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.claritykey.keyboard.prefs.BreenoPreferences
import java.io.File
import java.util.concurrent.Executors

enum class ClipItemType {
    TEXT,
    IMAGE
}

data class MediaStorageStats(
    val unpinnedCount: Int,
    val unpinnedBytes: Long,
    val pinnedCount: Int,
    val pinnedBytes: Long
) {
    val totalBytes: Long get() = unpinnedBytes + pinnedBytes
}

data class ClipEntry(
    val id: Long,
    val text: String,
    val contentHash: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val type: ClipItemType = ClipItemType.TEXT,
    val mimeType: String? = null,
    val filePath: String? = null,
    val thumbPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0L,
    val ocrText: String? = null
) {
    val isImage: Boolean get() = type == ClipItemType.IMAGE
}

/**
 * Persistent local SQLite repository for Smart Clipboard 2.0 (text + media).
 * - Non-destructive DB v3 -> v4 migration preserves 100% of existing text items.
 * - Text items remain AES-256-GCM encrypted.
 * - Image items store local copies via MediaClipboardStorage (originals + downsampled thumbs)
 *   and enforce independent count & MB quotas for unpinned images while never auto-deleting pinned images.
 */
class ClipboardRepository(
    context: Context,
    private val prefs: BreenoPreferences
) {
    private val appContext = context.applicationContext
    private val dbHelper = ClipboardDbHelper(appContext)
    val mediaStorage = MediaClipboardStorage(appContext)
    private val ioExecutor get() = sharedExecutor
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveOrPromoteAsync(
        rawText: String,
        sourcePackage: String? = null,
        sourceLabel: String? = null,
        automaticCapture: Boolean = false,
        onSaved: ((ClipEntry, Boolean) -> Unit)? = null
    ) {
        if (rawText.isEmpty() || rawText.length > 100_000) return
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            try {
                if (automaticCapture && prefs.clipboardCapturePaused) return@execute
                val now = System.currentTimeMillis()
                val hash = ClipboardCrypto.sha256Exact(rawText)
                val db = dbHelper.writableDatabase
                var entry: ClipEntry? = null
                var isNew = false

                db.beginTransaction()
                try {
                    val existing = findByHashLocked(db, hash)
                    if (existing != null) {
                        val effectivePkg = sourcePackage ?: existing.sourcePackage
                        val effectiveLbl = sourceLabel ?: existing.sourceLabel
                        val cv = ContentValues().apply {
                            put("updated_at", now)
                            if (effectivePkg != null) put("source_pkg", effectivePkg)
                            if (effectiveLbl != null) put("source_label", effectiveLbl)
                        }
                        db.update(TABLE, cv, "id = ?", arrayOf(existing.id.toString()))
                        entry = existing.copy(updatedAt = now, sourcePackage = effectivePkg, sourceLabel = effectiveLbl)
                        isNew = false
                    } else {
                        val enc = ClipboardCrypto.encrypt(rawText)
                        val cv = ContentValues().apply {
                            put("content_hash", hash)
                            put("content_enc", enc)
                            put("is_pinned", 0)
                            put("created_at", now)
                            put("updated_at", now)
                            put("source_pkg", sourcePackage)
                            put("source_label", sourceLabel)
                            put("item_type", "TEXT")
                            put("mime_type", "text/plain")
                        }
                        val rowId = db.insert(TABLE, null, cv)
                        if (rowId > 0) {
                            entry = ClipEntry(
                                id = rowId,
                                text = rawText,
                                contentHash = hash,
                                pinned = false,
                                createdAt = now,
                                updatedAt = now,
                                sourcePackage = sourcePackage,
                                sourceLabel = sourceLabel,
                                type = ClipItemType.TEXT,
                                mimeType = "text/plain"
                            )
                            isNew = true
                        }
                    }
                    pruneLocked(db, now)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                val finalEntry = entry
                if (finalEntry != null && onSaved != null) {
                    mainHandler.post { if (generation == dataGeneration) onSaved(finalEntry, isNew) }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streams an image from ContentResolver URI into private storage and inserts/promotes it in DB.
     * Preserves original format (including animated GIF) and creates a downsampled thumbnail.
     */
    fun saveImageFromUriAsync(
        uri: Uri,
        hintedMime: String? = null,
        sourcePackage: String? = null,
        sourceLabel: String? = null,
        automaticCapture: Boolean = false,
        onSaved: ((ClipEntry?, Boolean) -> Unit)? = null
    ) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            var entry: ClipEntry? = null
            var isNew = false
            try {
                if (automaticCapture && prefs.clipboardCapturePaused) return@execute
                val saved = mediaStorage.importFromUri(uri, hintedMime)
                if (saved != null) {
                    if (generation != dataGeneration || (automaticCapture && prefs.clipboardCapturePaused)) {
                        return@execute
                    }
                    val now = System.currentTimeMillis()
                    val db = dbHelper.writableDatabase
                    db.beginTransaction()
                    try {
                        val existing = findByHashLocked(db, saved.contentHash)
                        if (existing != null) {
                            val effectivePkg = sourcePackage ?: existing.sourcePackage
                            val effectiveLbl = sourceLabel ?: existing.sourceLabel
                            val cv = ContentValues().apply {
                                put("updated_at", now)
                                put("file_path", saved.filePath)
                                put("thumb_path", saved.thumbPath)
                                put("mime_type", saved.mimeType)
                                put("width", saved.width)
                                put("height", saved.height)
                                put("file_size", saved.fileSize)
                                if (effectivePkg != null) put("source_pkg", effectivePkg)
                                if (effectiveLbl != null) put("source_label", effectiveLbl)
                            }
                            db.update(TABLE, cv, "id = ?", arrayOf(existing.id.toString()))
                            entry = existing.copy(
                                updatedAt = now,
                                sourcePackage = effectivePkg,
                                sourceLabel = effectiveLbl,
                                mimeType = saved.mimeType,
                                filePath = saved.filePath,
                                thumbPath = saved.thumbPath,
                                width = saved.width,
                                height = saved.height,
                                fileSize = saved.fileSize
                            )
                            isNew = false
                        } else {
                            val labelEnc = ClipboardCrypto.encrypt("")
                            val cv = ContentValues().apply {
                                put("content_hash", saved.contentHash)
                                put("content_enc", labelEnc)
                                put("is_pinned", 0)
                                put("created_at", now)
                                put("updated_at", now)
                                put("source_pkg", sourcePackage)
                                put("source_label", sourceLabel)
                                put("item_type", "IMAGE")
                                put("mime_type", saved.mimeType)
                                put("file_path", saved.filePath)
                                put("thumb_path", saved.thumbPath)
                                put("width", saved.width)
                                put("height", saved.height)
                                put("file_size", saved.fileSize)
                            }
                            val rowId = db.insert(TABLE, null, cv)
                            if (rowId > 0) {
                                entry = ClipEntry(
                                    id = rowId,
                                    text = "",
                                    contentHash = saved.contentHash,
                                    pinned = false,
                                    createdAt = now,
                                    updatedAt = now,
                                    sourcePackage = sourcePackage,
                                    sourceLabel = sourceLabel,
                                    type = ClipItemType.IMAGE,
                                    mimeType = saved.mimeType,
                                    filePath = saved.filePath,
                                    thumbPath = saved.thumbPath,
                                    width = saved.width,
                                    height = saved.height,
                                    fileSize = saved.fileSize
                                )
                                isNew = true
                            }
                        }
                        pruneLocked(db, now)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            } catch (_: Exception) {
            }
            if (onSaved != null) {
                val res = entry
                mainHandler.post { if (generation == dataGeneration) onSaved(res, isNew) }
            }
        }
    }

    fun loadAllAsync(onLoaded: (List<ClipEntry>) -> Unit) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            val list = ArrayList<ClipEntry>()
            try {
                val now = System.currentTimeMillis()
                val db = dbHelper.writableDatabase
                pruneLocked(db, now)
                db.query(
                    TABLE,
                    COLUMNS,
                    null, null, null, null,
                    "is_pinned DESC, updated_at DESC"
                ).use { c ->
                    while (c.moveToNext()) {
                        val item = readCursorRow(c)
                        if (item != null) {
                            if (item.isImage) {
                                val fp = item.filePath
                                if (!fp.isNullOrBlank() && File(fp).exists()) {
                                    list.add(item)
                                } else {
                                    // Clean stale DB row whose local file was removed
                                    db.delete(TABLE, "id = ?", arrayOf(item.id.toString()))
                                }
                            } else {
                                list.add(item)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            mainHandler.post { onLoaded(if (generation == dataGeneration) list else emptyList()) }
        }
    }

    fun getMediaStorageStatsAsync(onLoaded: (MediaStorageStats) -> Unit) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            var unpinnedCount = 0
            var unpinnedBytes = 0L
            var pinnedCount = 0
            var pinnedBytes = 0L
            try {
                val db = dbHelper.readableDatabase
                db.query(
                    TABLE,
                    arrayOf("is_pinned", "file_size"),
                    "item_type = 'IMAGE'",
                    null, null, null, null
                ).use { c ->
                    while (c.moveToNext()) {
                        val pinned = c.getInt(0) == 1
                        val size = c.getLong(1).coerceAtLeast(0L)
                        if (pinned) {
                            pinnedCount++
                            pinnedBytes += size
                        } else {
                            unpinnedCount++
                            unpinnedBytes += size
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val stats = MediaStorageStats(unpinnedCount, unpinnedBytes, pinnedCount, pinnedBytes)
            mainHandler.post { onLoaded(stats) }
        }
    }

    fun updateOcrTextAsync(id: Long, plainOcrText: String) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            try {
                val enc = ClipboardCrypto.encrypt(plainOcrText)
                val cv = ContentValues().apply { put("ocr_text", enc) }
                dbHelper.writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
            } catch (_: Exception) {
            }
        }
    }

    fun togglePinAsync(id: Long, onDone: (() -> Unit)? = null) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            try {
                val db = dbHelper.writableDatabase
                var currentlyPinned = false
                db.query(TABLE, arrayOf("is_pinned"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
                    if (c.moveToFirst()) currentlyPinned = c.getInt(0) == 1
                }
                val cv = ContentValues().apply {
                    put("is_pinned", if (currentlyPinned) 0 else 1)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
                pruneLocked(db, System.currentTimeMillis())
            } catch (_: Exception) {
            }
            if (onDone != null) mainHandler.post { onDone() }
        }
    }

    fun deleteByIdAsync(id: Long, onDone: (() -> Unit)? = null) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            try {
                val db = dbHelper.writableDatabase
                var filePath: String? = null
                var thumbPath: String? = null
                db.query(TABLE, arrayOf("file_path", "thumb_path"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
                    if (c.moveToFirst()) {
                        filePath = if (c.isNull(0)) null else c.getString(0)
                        thumbPath = if (c.isNull(1)) null else c.getString(1)
                    }
                }
                db.delete(TABLE, "id = ?", arrayOf(id.toString()))
                mediaStorage.deleteMediaFiles(filePath, thumbPath)
            } catch (_: Exception) {
            }
            if (onDone != null) mainHandler.post { onDone() }
        }
    }

    fun clearUnpinnedAsync(onDone: (() -> Unit)? = null) {
        val generation = dataGeneration
        ioExecutor.execute {
            if (generation != dataGeneration) return@execute
            try {
                val db = dbHelper.writableDatabase
                val filesToDelete = ArrayList<Pair<String?, String?>>()
                db.query(TABLE, arrayOf("file_path", "thumb_path"), "is_pinned = 0 AND item_type = 'IMAGE'", null, null, null, null).use { c ->
                    while (c.moveToNext()) {
                        val fp = if (c.isNull(0)) null else c.getString(0)
                        val tp = if (c.isNull(1)) null else c.getString(1)
                        filesToDelete.add(fp to tp)
                    }
                }
                db.delete(TABLE, "is_pinned = 0", null)
                filesToDelete.forEach { (fp, tp) -> mediaStorage.deleteMediaFiles(fp, tp) }
            } catch (_: Exception) {
            }
            if (onDone != null) mainHandler.post { onDone() }
        }
    }

    fun clearAllAsync(onDone: ((Boolean) -> Unit)? = null) {
        dataGeneration++
        ioExecutor.execute {
            val ok = try {
                dbHelper.writableDatabase.delete(TABLE, null, null)
                mediaStorage.deleteAllMediaFiles()
                true
            } catch (_: Exception) { false }
            if (onDone != null) mainHandler.post { onDone(ok) }
        }
    }

    fun close() {
        ioExecutor.execute {
            try { dbHelper.close() } catch (_: Exception) {}
        }
    }

    private fun readCursorRow(c: android.database.Cursor): ClipEntry? {
        val id = c.getLong(0)
        val hash = c.getString(1)
        val enc = c.getString(2)
        val pinned = c.getInt(3) == 1
        val created = c.getLong(4)
        val updated = c.getLong(5)
        val srcPkg = if (c.isNull(6)) null else c.getString(6)
        val srcLbl = if (c.isNull(7)) null else c.getString(7)
        val typeStr = if (c.isNull(8)) "TEXT" else c.getString(8)
        val mime = if (c.isNull(9)) null else c.getString(9)
        val filePath = if (c.isNull(10)) null else c.getString(10)
        val thumbPath = if (c.isNull(11)) null else c.getString(11)
        val width = if (c.isNull(12)) 0 else c.getInt(12)
        val height = if (c.isNull(13)) 0 else c.getInt(13)
        val fileSize = if (c.isNull(14)) 0L else c.getLong(14)
        val ocrTextEnc = if (c.columnCount > 15 && !c.isNull(15)) c.getString(15) else null
        val ocrText = ocrTextEnc?.let { ClipboardCrypto.decrypt(it) }

        val itemType = if (typeStr == "IMAGE") ClipItemType.IMAGE else ClipItemType.TEXT
        val plain = if (itemType == ClipItemType.TEXT) {
            ClipboardCrypto.decrypt(enc) ?: return null
        } else {
            ""
        }
        // Upgrade legacy plaintext/Base64 only after successful encryption; keep unreadable records intact.
        val upgrades = ContentValues()
        if (itemType == ClipItemType.TEXT && !enc.startsWith("enc1:")) {
            runCatching { upgrades.put("content_enc", ClipboardCrypto.encrypt(plain)) }
        }
        if (ocrTextEnc != null && ocrText != null && !ocrTextEnc.startsWith("enc1:")) {
            runCatching { upgrades.put("ocr_text", ClipboardCrypto.encrypt(ocrText)) }
        }
        if (upgrades.size() > 0) dbHelper.writableDatabase.update(TABLE, upgrades, "id = ?", arrayOf(id.toString()))
        return ClipEntry(
            id = id,
            text = plain,
            contentHash = hash,
            pinned = pinned,
            createdAt = created,
            updatedAt = updated,
            sourcePackage = srcPkg,
            sourceLabel = srcLbl,
            type = itemType,
            mimeType = mime,
            filePath = filePath,
            thumbPath = thumbPath,
            width = width,
            height = height,
            fileSize = fileSize,
            ocrText = ocrText
        )
    }

    private fun findByHashLocked(db: SQLiteDatabase, hash: String): ClipEntry? {
        db.query(
            TABLE,
            COLUMNS,
            "content_hash = ?",
            arrayOf(hash),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return readCursorRow(c)
        }
        return null
    }

    private fun pruneLocked(db: SQLiteDatabase, nowMs: Long) {
        // 1. Prune unpinned TEXT items
        val textTtlMs = prefs.clipboardRetentionHours * 3_600_000L
        if (textTtlMs > 0L) {
            val cutoff = nowMs - textTtlMs
            db.delete(TABLE, "is_pinned = 0 AND item_type = 'TEXT' AND updated_at < ?", arrayOf(cutoff.toString()))
        }
        val maxTextItems = prefs.clipboardMaxItems.coerceIn(50, 200)
        db.execSQL(
            "DELETE FROM $TABLE WHERE is_pinned = 0 AND item_type = 'TEXT' AND id NOT IN " +
                "(SELECT id FROM $TABLE WHERE is_pinned = 0 AND item_type = 'TEXT' ORDER BY updated_at DESC LIMIT $maxTextItems)"
        )

        // 2. Prune unpinned IMAGE items (TTL + max count + max MB; pinned images are NEVER auto-deleted)
        val imgTtlMs = prefs.clipboardImageRetentionHours * 3_600_000L
        if (imgTtlMs > 0L) {
            val cutoff = nowMs - imgTtlMs
            deleteMatchingImagesLocked(db, "is_pinned = 0 AND item_type = 'IMAGE' AND updated_at < ?", arrayOf(cutoff.toString()))
        }

        if (prefs.clipboardImageAutoClean) {
            val maxImages = prefs.clipboardMaxImages.coerceIn(5, 100)
            val maxBytes = prefs.clipboardMaxImageMb.coerceIn(10, 250).toLong() * 1024L * 1024L
            val toDelete = ArrayList<Triple<Long, String?, String?>>()
            var keptCount = 0
            var keptBytes = 0L

            db.query(
                TABLE,
                arrayOf("id", "file_path", "thumb_path", "file_size"),
                "is_pinned = 0 AND item_type = 'IMAGE'",
                null, null, null,
                "updated_at DESC"
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val fp = if (c.isNull(1)) null else c.getString(1)
                    val tp = if (c.isNull(2)) null else c.getString(2)
                    val sz = c.getLong(3).coerceAtLeast(0L)
                    val withinCount = keptCount < maxImages
                    val withinBytes = (keptCount == 0) || (keptBytes + sz <= maxBytes)
                    if (withinCount && withinBytes) {
                        keptCount++
                        keptBytes += sz
                    } else {
                        toDelete.add(Triple(id, fp, tp))
                    }
                }
            }
            toDelete.forEach { (id, fp, tp) ->
                db.delete(TABLE, "id = ?", arrayOf(id.toString()))
                mediaStorage.deleteMediaFiles(fp, tp)
            }
        }
    }

    private fun deleteMatchingImagesLocked(db: SQLiteDatabase, whereClause: String, whereArgs: Array<String>) {
        val files = ArrayList<Pair<String?, String?>>()
        db.query(TABLE, arrayOf("file_path", "thumb_path"), whereClause, whereArgs, null, null, null).use { c ->
            while (c.moveToNext()) {
                val fp = if (c.isNull(0)) null else c.getString(0)
                val tp = if (c.isNull(1)) null else c.getString(1)
                files.add(fp to tp)
            }
        }
        db.delete(TABLE, whereClause, whereArgs)
        files.forEach { (fp, tp) -> mediaStorage.deleteMediaFiles(fp, tp) }
    }

    private class ClipboardDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "content_hash TEXT NOT NULL UNIQUE, " +
                    "content_enc TEXT NOT NULL, " +
                    "is_pinned INTEGER NOT NULL DEFAULT 0, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "source_pkg TEXT, " +
                    "source_label TEXT, " +
                    "item_type TEXT NOT NULL DEFAULT 'TEXT', " +
                    "mime_type TEXT, " +
                    "file_path TEXT, " +
                    "thumb_path TEXT, " +
                    "width INTEGER NOT NULL DEFAULT 0, " +
                    "height INTEGER NOT NULL DEFAULT 0, " +
                    "file_size INTEGER NOT NULL DEFAULT 0, " +
                    "ocr_text TEXT)"
            )
            db.execSQL("CREATE INDEX idx_clip_pinned_updated ON $TABLE (is_pinned, updated_at DESC)")
            db.execSQL("CREATE INDEX idx_clip_type_pinned ON $TABLE (item_type, is_pinned, updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_pkg TEXT") } catch (_: Exception) {}
            }
            if (oldVersion < 3) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_label TEXT") } catch (_: Exception) {}
            }
            if (oldVersion < 4) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN item_type TEXT NOT NULL DEFAULT 'TEXT'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN mime_type TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_path TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN thumb_path TEXT") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN width INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN height INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_clip_type_pinned ON $TABLE (item_type, is_pinned, updated_at DESC)") } catch (_: Exception) {}
            }
            if (oldVersion < 5) {
                try { db.execSQL("ALTER TABLE $TABLE ADD COLUMN ocr_text TEXT") } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private val sharedExecutor = Executors.newSingleThreadExecutor()
        @Volatile var dataGeneration: Long = 0L
            private set
        private const val DB_NAME = "breeno_clipboard.db"
        private const val DB_VERSION = 5
        private const val TABLE = "clip_items"
        private val COLUMNS = arrayOf(
            "id", "content_hash", "content_enc", "is_pinned", "created_at", "updated_at",
            "source_pkg", "source_label", "item_type", "mime_type", "file_path",
            "thumb_path", "width", "height", "file_size", "ocr_text"
        )
    }
}
