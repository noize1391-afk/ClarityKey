package com.claritykey.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Native Android SpeechRecognizer controller for Breeno Keyboard RU.
 * Uses the device's configured system speech service (e.g. Google Speech Services)
 * with live partial results and clean lifecycle management.
 */
class BreenoVoiceHelper(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onVoiceStateChanged(listening: Boolean, statusText: String)
        fun onVoicePartialResult(text: String)
        fun onVoiceFinalResult(text: String)
        fun onVoicePermissionRequired()
        fun onVoiceError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isCurrentlyListening(): Boolean = isListening

    fun toggleListening(lang: String) {
        if (isListening) {
            stopListening()
        } else {
            startListening(lang)
        }
    }

    fun startListening(lang: String) {
        if (!hasMicPermission()) {
            callback.onVoicePermissionRequired()
            return
        }
        if (!isAvailable()) {
            callback.onVoiceError("Сервис распознавания речи не найден")
            return
        }

        destroyRecognizer()
        val sr = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Exception) {
            callback.onVoiceError("Не удалось запустить распознавание речи")
            return
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== sr) return
                isListening = true
                val label = if (lang == "RU") "Говорите (RU)…" else "Speak (EN)…"
                callback.onVoiceStateChanged(true, label)
            }

            override fun onBeginningOfSpeech() {
                if (recognizer !== sr) return
                callback.onVoiceStateChanged(true, "Слушаю…")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (recognizer !== sr) return
                callback.onVoiceStateChanged(true, "Распознавание…")
            }

            override fun onError(error: Int) {
                if (recognizer !== sr) return
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Нет сигнала с микрофона"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        callback.onVoicePermissionRequired()
                        return
                    }
                    SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания"
                    else -> "Голосовой ввод остановлен"
                }
                callback.onVoiceStateChanged(false, "")
                callback.onVoiceError(msg)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== sr) return
                isListening = false
                callback.onVoiceStateChanged(false, "")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim()
                if (!best.isNullOrEmpty()) {
                    callback.onVoiceFinalResult(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== sr) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrEmpty()) {
                    callback.onVoicePartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val locale = if (lang == "RU") "ru-RU" else "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        isListening = true
        callback.onVoiceStateChanged(true, "Запуск микрофона…")
        try {
            sr.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            callback.onVoiceStateChanged(false, "")
            callback.onVoiceError("Не удалось запустить микрофон")
        }
    }

    fun stopListening() {
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        callback.onVoiceStateChanged(false, "")
    }

    fun destroyRecognizer() {
        isListening = false
        val previous = recognizer
        recognizer = null
        try {
            previous?.cancel()
            previous?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
