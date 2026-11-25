package com.claritykey.keyboard.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class SavedMediaResult(
    val contentHash: String,
    val mimeType: String,
    val filePath: String,
    val thumbPath: String,
    val width: Int,
    val height: Int,
    val fileSize: Long
)

/**
 * Local persistent media storage for Smart Clipboard 2.0.
 * - Streams original bytes via ContentResolver (preserves PNG/JPEG/WebP quality and animated GIFs)
 * - Computes SHA-256 on the fly for exact duplicate detection
 * - Validates image integrity & dimensions without decoding full-resolution bitmaps into RAM
 * - Generates memory-safe downsampled thumbnails (max 320px edge)
 * - Exposes safe FileProvider content:// URIs for InputConnectionCompat.commitContent() and Share
 */
class MediaClipboardStorage(context: Context) {

    private val appContext = context.applicationContext
    private val mediaRoot = File(appContext.filesDir, "clipboard_media").apply { mkdirs() }
    private val originalsDir = File(mediaRoot, "originals").apply { mkdirs() }
    private val thumbsDir = File(mediaRoot, "thumbs").apply { mkdirs() }

    companion object {
        private const val MAX_SINGLE_FILE_BYTES = 25L * 1024L * 1024L // 25 MB safety cap
        private const val THUMB_MAX_DIM_PX = 320
        private const val BUFFER_SIZE = 16 * 1024

        fun isSupportedImageMime(mime: String?): Boolean {
            if (mime.isNullOrBlank()) return false
            val m = mime.lowercase().trim()
            return m.startsWith("image/") && m != "image/*"
        }

        fun extensionForMime(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            else -> "img"
        }

        fun shortBadgeForMime(mime: String?): String = when (mime?.lowercase()) {
            "image/gif" -> "GIF"
            "image/png" -> "PNG"
            "image/webp" -> "WEBP"
            "image/jpeg", "image/jpg" -> "JPG"
            "image/heic", "image/heif" -> "HEIC"
            else -> "IMG"
        }
    }

    /**
     * Streams image data from a ContentResolver URI into private app storage.
     * Returns null if the URI is inaccessible, exceeds the safety size cap, or is not a valid image.
     */
    fun importFromUri(uri: Uri, hintedMime: String? = null): SavedMediaResult? {
        val tempFile = File(originalsDir, "tmp_${System.nanoTime()}.bin")
        try {
            val resolver = appContext.contentResolver
            val resolvedMime = resolver.getType(uri)?.takeIf { isSupportedImageMime(it) }
                ?: hintedMime?.takeIf { isSupportedImageMime(it) }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val header = ByteArray(16)
            var headerFilled = 0

            val input = resolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(tempFile).use { outs ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = ins.read(buf)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MAX_SINGLE_FILE_BYTES) {
                            tempFile.delete()
                            return null
                        }
                        if (headerFilled < header.size) {
                            val toCopy = minOf(read, header.size - headerFilled)
                            System.arraycopy(buf, 0, header, headerFilled, toCopy)
                            headerFilled += toCopy
                        }
                        digest.update(buf, 0, read)
                        outs.write(buf, 0, read)
                    }
                    outs.flush()
                }
            }

            if (totalBytes <= 0L) {
                tempFile.delete()
                return null
            }

            // Detect real MIME from magic bytes + BitmapFactory bounds check (zero full bitmap decode!)
            val sniffedMime = sniffImageMime(header, headerFilled)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)

            val finalMime = sniffedMime
                ?: boundsOpts.outMimeType?.takeIf { isSupportedImageMime(it) }
                ?: resolvedMime
            if (finalMime == null || boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                tempFile.delete()
                return null
            }

            val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
            val ext = extensionForMime(finalMime)
            val finalFile = File(originalsDir, "img_${hashHex.take(24)}.$ext")
            val thumbFile = File(thumbsDir, "th_${hashHex.take(24)}.jpg")

            if (finalFile.exists() && finalFile.length() == totalBytes) {
                tempFile.delete()
            } else {
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
            }

            if (!thumbFile.exists() || thumbFile.length() == 0L) {
                generateThumbnail(finalFile, thumbFile, boundsOpts.outWidth, boundsOpts.outHeight)
            }

            return SavedMediaResult(
                contentHash = "img:$hashHex",
                mimeType = finalMime,
                filePath = finalFile.absolutePath,
                thumbPath = if (thumbFile.exists()) thumbFile.absolutePath else finalFile.absolutePath,
                width = boundsOpts.outWidth,
                height = boundsOpts.outHeight,
                fileSize = finalFile.length()
            )
        } catch (_: SecurityException) {
            tempFile.delete()
            return null
        } catch (_: Exception) {
            tempFile.delete()
            return null
        }
    }

    private fun generateThumbnail(sourceFile: File, destThumb: File, origW: Int, origH: Int) {
        try {
            var sample = 1
            while ((origW / (sample * 2)) >= THUMB_MAX_DIM_PX && (origH / (sample * 2)) >= THUMB_MAX_DIM_PX) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts) ?: return
            val scale = minOf(
                THUMB_MAX_DIM_PX.toFloat() / decoded.width.coerceAtLeast(1),
                THUMB_MAX_DIM_PX.toFloat() / decoded.height.coerceAtLeast(1),
                1f
            )
            val thumbBmp = if (scale < 0.95f) {
                val tw = (decoded.width * scale).toInt().coerceAtLeast(1)
                val th = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, tw, th, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            FileOutputStream(destThumb).use { out ->
                thumbBmp.compress(Bitmap.CompressFormat.JPEG, 84, out)
                out.flush()
            }
            thumbBmp.recycle()
        } catch (_: Exception) {
            destThumb.delete()
        }
    }

    private fun sniffImageMime(b: ByteArray, len: Int): String? {
        if (len >= 6 &&
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == '8'.code.toByte() && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) &&
            b[5] == 'a'.code.toByte()
        ) {
            return "image/gif"
        }
        if (len >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (len >= 3 &&
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (len >= 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    fun getShareableUri(filePath: String?): Uri? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.clipboard.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteMediaFiles(filePath: String?, thumbPath: String?) {
        try {
            if (!filePath.isNullOrBlank()) File(filePath).delete()
        } catch (_: Exception) {
        }
        try {
            if (!thumbPath.isNullOrBlank() && thumbPath != filePath) File(thumbPath).delete()
        } catch (_: Exception) {
        }
    }

    fun deleteAllMediaFiles() {
        mediaRoot.listFiles()?.forEach { file ->
            if (file == originalsDir || file == thumbsDir) {
                file.listFiles()?.forEach { check(it.deleteRecursively()) { "Media deletion failed" } }
            } else {
                check(file.deleteRecursively()) { "Media deletion failed" }
            }
        }
    }

    fun decodeBoundedBitmap(filePath: String?, maxEdgePx: Int): Bitmap? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while ((opts.outWidth / (sample * 2)) >= maxEdgePx && (opts.outHeight / (sample * 2)) >= maxEdgePx) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }
}
