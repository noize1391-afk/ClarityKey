package com.claritykey.keyboard.clipboard

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import com.claritykey.keyboard.prefs.BreenoPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Lightweight transparent trampoline Activity to launch Android's official Photo Picker
 * (API 33+ MediaStore.ACTION_PICK_IMAGES, fallback ACTION_GET_CONTENT on API 28-32)
 * without requesting broad gallery permissions. Supports both:
 * 1) Smart Clipboard image import (default)
 * 2) Custom keyboard wallpaper selection (EXTRA_PICK_WALLPAPER = true)
 */
class ClipboardImagePickerActivity : Activity() {

    companion object {
        const val EXTRA_PICK_WALLPAPER = "pick_wallpaper"
        const val EXTRA_PICK_STICKER = "pick_sticker"
        private const val REQ_PICK_IMAGE = 2041
    }

    private var pickWallpaperMode: Boolean = false
    private var pickStickerMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickWallpaperMode = intent?.getBooleanExtra(EXTRA_PICK_WALLPAPER, false) == true
        pickStickerMode = intent?.getBooleanExtra(EXTRA_PICK_STICKER, false) == true
        launchPicker()
    }

    private fun launchPicker() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                }
            } else {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            }
            startActivityForResult(intent, REQ_PICK_IMAGE)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(fallback, REQ_PICK_IMAGE)
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось открыть выбор изображений", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                if (pickWallpaperMode) {
                    saveWallpaperAsync(uri)
                    return
                }
                if (pickStickerMode) {
                    saveStickerAsync(uri)
                    return
                }
                val prefs = BreenoPreferences(this)
                val repo = ClipboardRepository(this, prefs)
                repo.saveImageFromUriAsync(
                    uri = uri,
                    hintedMime = contentResolver.getType(uri),
                    sourcePackage = null,
                    sourceLabel = "Выбрано вручную"
                ) { entry, _ ->
                    repo.close()
                    val msg = if (entry != null) {
                        "Изображение добавлено в буфер обмена"
                    } else {
                        "Не удалось прочитать выбранное изображение"
                    }
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                    finish()
                }
                return
            }
        }
        finish()
    }

    private fun saveWallpaperAsync(uri: Uri) {
        val exec = Executors.newSingleThreadExecutor()
        exec.execute {
            val ok = try {
                val mime = contentResolver.getType(uri)?.lowercase() ?: ""
                val isGif = mime.contains("gif") || uri.toString().lowercase().endsWith(".gif")
                if (isGif) {
                    val dir = File(filesDir, "breeno_stickers").apply { mkdirs() }
                    val outFile = File(dir, "sticker_${System.currentTimeMillis()}.gif")
                    contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(outFile).use { fos ->
                            ins.copyTo(fos)
                            fos.flush()
                        }
                    }
                    if (outFile.exists() && outFile.length() > 0L) {
                        BreenoPreferences(applicationContext).addStickerPath(outFile.absolutePath)
                        true
                    } else false
                } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while ((bounds.outWidth / (sample * 2)) >= 1280 || (bounds.outHeight / (sample * 2)) >= 1280) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                if (bmp != null) {
                    val outFile = File(filesDir, "keyboard_wallpaper.jpg")
                    FileOutputStream(outFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                        fos.flush()
                    }
                    var lumaSum = 0L
                    var samples = 0
                    val stepX = (bmp.width / 24).coerceAtLeast(1)
                    val stepY = (bmp.height / 24).coerceAtLeast(1)
                    var y = 0
                    while (y < bmp.height) {
                        var x = 0
                        while (x < bmp.width) {
                            val px = bmp.getPixel(x, y)
                            val r = (px shr 16) and 0xFF
                            val g = (px shr 8) and 0xFF
                            val b = px and 0xFF
                            lumaSum += (299 * r + 587 * g + 114 * b) / 1000
                            samples++
                            x += stepX
                        }
                        y += stepY
                    }
                    val avgLuma = if (samples > 0) (lumaSum / samples).toInt() else 60
                    bmp.recycle()
                    val bp = BreenoPreferences(applicationContext)
                    bp.wallpaperPath = outFile.absolutePath
                    bp.wallpaperIsBright = avgLuma > 138
                    true
                } else {
                    false
                }
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                val msg = if (ok) "Обои клавиатуры применены" else "Не удалось сохранить обои"
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                exec.shutdown()
                finish()
            }
        }
    }

    private fun saveStickerAsync(uri: Uri) {
        val exec = Executors.newSingleThreadExecutor()
        exec.execute {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while ((bounds.outWidth / (sample * 2)) >= 512 || (bounds.outHeight / (sample * 2)) >= 512) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                if (bmp != null) {
                    val dir = File(filesDir, "breeno_stickers").apply { mkdirs() }
                    val outFile = File(dir, "sticker_${System.currentTimeMillis()}.png")
                    FileOutputStream(outFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
                        fos.flush()
                    }
                    bmp.recycle()
                    BreenoPreferences(applicationContext).addStickerPath(outFile.absolutePath)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    applicationContext,
                    if (ok) "Статический стикер добавлен во вкладку ★" else "Не удалось добавить стикер",
                    Toast.LENGTH_SHORT
                ).show()
                exec.shutdown()
                finish()
            }
        }
    }

}
