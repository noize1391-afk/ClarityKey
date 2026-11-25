package com.claritykey.keyboard.prefs

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local JSON Backup & Restore for Breeno Keyboard RU settings, personal dictionary,
 * text expanders, and quick reply templates via Android Storage Access Framework (SAF).
 * Never exports sensitive clipboard items or passwords.
 */
object BackupExportHelper {

    fun exportToUri(context: Context, prefs: BreenoPreferences, uri: Uri): Boolean {
        return try {
            val root = JSONObject().apply {
                put("format", "claritykey_backup")
                put("version", 1)
                put("themeMode", prefs.themeMode.name)
                put("glassTransparency", prefs.glassTransparency)
                put("glassBlurRadius", prefs.glassBlurRadius)
                put("keyboardHeightPercent", prefs.keyboardHeightPercent)
                put("showNumberRow", prefs.showNumberRow)
                put("separateHardSign", prefs.separateHardSign)
                put("autoCorrect", prefs.autoCorrect)
                put("autoCorrectStrength", prefs.autoCorrectStrength.name)
                put("swipeTypingEnabled", prefs.swipeTypingEnabled)
                put("hapticEnabled", prefs.hapticEnabled)
                put("hapticDurationMs", prefs.hapticDurationMs)
                put("soundEnabled", prefs.soundEnabled)
                put("soundProfile", prefs.soundProfile)
                put("dynamicColorEnabled", prefs.dynamicColorEnabled)
                put("nightScheduleEnabled", prefs.nightScheduleEnabled)
                put("toolbarSlots", prefs.toolbarSlotsCsv)

                val wordsArr = JSONArray()
                prefs.getUserWords().forEach { wordsArr.put(it) }
                put("userWords", wordsArr)

                val expObj = JSONObject()
                prefs.getTextExpanders().forEach { (k, v) -> expObj.put(k, v) }
                put("textExpanders", expObj)

                val tplArr = JSONArray()
                prefs.getReplyTemplates().forEach { (folder, body) ->
                    tplArr.put(JSONObject().put("folder", folder).put("text", body))
                }
                put("replyTemplates", tplArr)
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun importFromUri(context: Context, prefs: BreenoPreferences, uri: Uri): Boolean {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return false
            val root = JSONObject(raw)
            val fmt = root.optString("format"); if (fmt != "claritykey_backup" && fmt != "breeno_keyboard_ru_backup") return false

            root.optString("themeMode").let { mode ->
                com.claritykey.keyboard.skin.DayNightMode.entries.firstOrNull { it.name == mode }?.let { prefs.themeMode = it }
            }
            root.optString("autoCorrectStrength").let { strength ->
                AutoCorrectStrength.entries.firstOrNull { it.name == strength }?.let { prefs.autoCorrectStrength = it }
            }
            if (root.has("glassTransparency")) prefs.glassTransparency = root.optInt("glassTransparency", 55)
            if (root.has("glassBlurRadius")) prefs.glassBlurRadius = root.optInt("glassBlurRadius", 48)
            if (root.has("keyboardHeightPercent")) prefs.keyboardHeightPercent = root.optInt("keyboardHeightPercent", 100)
            if (root.has("showNumberRow")) prefs.showNumberRow = root.optBoolean("showNumberRow", false)
            if (root.has("separateHardSign")) prefs.separateHardSign = root.optBoolean("separateHardSign", false)
            if (root.has("autoCorrect")) prefs.autoCorrect = root.optBoolean("autoCorrect", true)
            if (root.has("swipeTypingEnabled")) prefs.swipeTypingEnabled = root.optBoolean("swipeTypingEnabled", true)
            if (root.has("hapticEnabled")) prefs.hapticEnabled = root.optBoolean("hapticEnabled", true)
            if (root.has("hapticDurationMs")) prefs.hapticDurationMs = root.optInt("hapticDurationMs", 16)
            if (root.has("soundEnabled")) prefs.soundEnabled = root.optBoolean("soundEnabled", false)
            if (root.has("soundProfile")) prefs.soundProfile = root.optInt("soundProfile", 0)
            if (root.has("dynamicColorEnabled")) prefs.dynamicColorEnabled = root.optBoolean("dynamicColorEnabled", false)
            if (root.has("nightScheduleEnabled")) prefs.nightScheduleEnabled = root.optBoolean("nightScheduleEnabled", false)
            if (root.has("toolbarSlots")) prefs.toolbarSlotsCsv = root.optString("toolbarSlots", prefs.toolbarSlotsCsv)

            root.optJSONArray("userWords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i)
                    if (w.isNotBlank()) prefs.addUserWord(w)
                }
            }
            root.optJSONObject("textExpanders")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) prefs.putTextExpander(k, v)
                }
            }
            root.optJSONArray("replyTemplates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val folder = item.optString("folder", "Работа")
                    val text = item.optString("text", "")
                    if (text.isNotBlank()) prefs.addReplyTemplate(folder, text)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
