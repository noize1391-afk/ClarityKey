package com.claritykey.keyboard.clipboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.claritykey.keyboard.prefs.BreenoPreferences

data class RecentScreenshotInfo(
    val key: String,
    val contentUri: Uri,
    val mimeType: String,
    val displayName: String,
    val addedAtMs: Long
)

/**
 * Gboard-style real-time + on-open recent screenshot detector using official Android MediaStore API.
 * - Uses ContentObserver on MediaStore.Images.Media.EXTERNAL_CONTENT_URI with 750ms debounce
 *   so IS_PENDING=1 writes finish flushing before reading.
 * - Checks both DATE_ADDED and DATE_MODIFIED (within 3 minutes) and skips IS_PENDING=1 rows (API 29+).
 * - Matches universal OEM screenshot directories (Pictures/Screenshots, DCIM/Screenshots, ColorOS,
 *   HyperOS, One UI, Pixel, OxygenOS, realme UI, MagicOS, vivo, Motorola).
 * - Strictly requires user consent + READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE (API 28-32).
 * - Zero Accessibility Service, zero root, zero hidden OEM APIs.
 */
class RecentScreenshotDetector(
    context: Context,
    private val prefs: BreenoPreferences
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var pendingDebounceRunnable: Runnable? = null

    companion object {
        const val SCREENSHOT_FRESHNESS_MS = 180_000L // 3 minutes
        private const val OBSERVER_DEBOUNCE_MS = 750L

        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasMediaPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPermission(): Boolean = hasMediaPermission(appContext)

    fun dismiss(info: RecentScreenshotInfo) {
        prefs.lastDismissedScreenshotKey = info.key
    }

    /**
     * Registers a lightweight ContentObserver on MediaStore.Images.Media to react to new screenshots
     * in real time (just like Gboard), debounced to wait for MediaStore IS_PENDING -> 0 completion.
     */
    fun startObserving(onMediaChanged: () -> Unit) {
        if (observer != null) return
        val obs = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!prefs.clipboardEnabled || !prefs.clipboardSuggestScreenshots || !hasPermission()) return
                pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable { onMediaChanged() }
                pendingDebounceRunnable = r
                mainHandler.postDelayed(r, OBSERVER_DEBOUNCE_MS)
            }
        }
        try {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                obs
            )
            observer = obs
        } catch (_: Exception) {
        }
    }

    fun stopObserving() {
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDebounceRunnable = null
        observer?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        observer = null
    }

    /**
     * Queries MediaStore for the newest completed screenshot created within the last 3 minutes.
     * Call on a background thread.
     */
    fun findRecentScreenshot(ignoreDismissed: Boolean = false): RecentScreenshotInfo? {
        if (!prefs.clipboardEnabled || !prefs.clipboardSuggestScreenshots) return null
        if (!hasPermission()) return null

        val nowMs = System.currentTimeMillis()
        val minSec = (nowMs - SCREENSHOT_FRESHNESS_MS) / 1000L

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.IS_PENDING,
                MediaStore.Images.Media.SIZE
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
            )
        }

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?) AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            "(${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?)"
        }
        val selectionArgs = arrayOf(minSec.toString(), minSec.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        return try {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                var checked = 0
                while (cursor.moveToNext() && checked < 15) {
                    checked++
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 1L
                    if (size <= 0L) continue

                    val name = cursor.getString(nameCol) ?: ""
                    val relPath = if (relCol >= 0) (cursor.getString(relCol) ?: "") else ""
                    val bucket = if (bucketCol >= 0) (cursor.getString(bucketCol) ?: "") else ""

                    if (!isScreenshotPath(name, relPath, bucket)) continue

                    val id = cursor.getLong(idCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val dateModSec = if (modCol >= 0) cursor.getLong(modCol) else dateAddedSec
                    val bestSec = maxOf(dateAddedSec, dateModSec)
                    val key = "ms_$id"
                    if (!ignoreDismissed && key == prefs.lastDismissedScreenshotKey) return null

                    val mime = cursor.getString(mimeCol)?.takeIf { MediaClipboardStorage.isSupportedImageMime(it) }
                        ?: "image/png"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    return RecentScreenshotInfo(
                        key = key,
                        contentUri = uri,
                        mimeType = mime,
                        displayName = name.ifBlank { "Screenshot" },
                        addedAtMs = bestSec * 1000L
                    )
                }
                null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isScreenshotPath(name: String, relPath: String, bucket: String): Boolean {
        val combined = "$relPath $bucket $name".lowercase()
        if (combined.contains("screenrecord") || combined.contains("screen_record")) return false
        return combined.contains("screenshot") ||
            combined.contains("screen_shot") ||
            combined.contains("screenshots") ||
            combined.contains("скриншот") ||
            combined.contains("снимок")
    }
}
