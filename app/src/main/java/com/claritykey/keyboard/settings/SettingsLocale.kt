package com.claritykey.keyboard.settings

import android.content.Context
import android.content.res.Configuration
import com.claritykey.keyboard.prefs.BreenoPreferences
import java.util.Locale

/** Settings-only locale: deliberately independent of the IME's RU/EN input layout. */
object SettingsLocale {
    fun language(context: Context): String {
        val selected = BreenoPreferences(context).settingsUiLanguage
        if (selected == "ru" || selected == "en") return selected
        return if (context.resources.configuration.locales[0].language == "ru") "ru" else "en"
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(language(context))
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    fun select(context: Context, language: String) {
        require(language == "ru" || language == "en")
        BreenoPreferences(context).settingsUiLanguage = language
    }
}
