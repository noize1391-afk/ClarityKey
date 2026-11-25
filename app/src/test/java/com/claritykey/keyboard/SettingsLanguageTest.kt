package com.claritykey.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.claritykey.keyboard.settings.SettingsActivity
import com.claritykey.keyboard.settings.SettingsLocale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru")
class SettingsLanguageTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("breeno_keyboard_ru_prefs", 0).edit().clear().commit()
    }

    @Test fun defaultLanguageUsesRussianOrEnglishFallback() {
        fun configured(tag: String): Context = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        )
        assertEquals("ru", SettingsLocale.language(configured("ru-RU")))
        assertEquals("en", SettingsLocale.language(configured("en-US")))
        assertEquals("en", SettingsLocale.language(configured("de-DE")))
    }

    @Test fun languageSelectionPersistsWithoutChangingTypingLanguageOrTemplates() {
        val prefs = BreenoPreferences(context)
        prefs.activeLanguage = "RU"
        prefs.addReplyTemplate("Работа", "Original template")
        val templatesBefore = prefs.getReplyTemplates().toList()
        SettingsLocale.select(context, "en")
        assertEquals("en", BreenoPreferences(context).settingsUiLanguage)
        assertEquals("RU", prefs.activeLanguage)
        assertEquals(templatesBefore, prefs.getReplyTemplates())
        assertEquals("Interface language", SettingsLocale.wrap(context).getString(R.string.settings_ui_language_title))
        SettingsLocale.select(context, "ru")
        assertEquals("Язык интерфейса", SettingsLocale.wrap(context).getString(R.string.settings_ui_language_title))
        assertEquals("RU", prefs.activeLanguage)
    }

    @Test fun allSettingsMessagesResolveAndFormattedArgumentsAreValid() {
        val fields = R.string::class.java.fields.filter { it.name.startsWith("settings_") }
        assertTrue(fields.size > 580)
        for (language in listOf("ru", "en")) {
            SettingsLocale.select(context, language)
            val localized = SettingsLocale.wrap(context)
            for (field in fields) {
                val id = field.getInt(null)
                val raw = localized.getString(id)
                assertTrue(field.name, raw.isNotBlank())
                val placeholder = Regex("%(\\d+)" + Regex.escape("$") + "([.\\d]*[sdf])")
                val matches = placeholder.findAll(raw).toList()
                val count = matches.maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                if (count > 0) {
                    val args = Array<Any>(count) { "ARG${it + 1}" }
                    matches.forEach { match ->
                        val index = match.groupValues[1].toInt() - 1
                        args[index] = when (match.groupValues[2].last()) {
                            'd' -> 1
                            'f' -> 1.25
                            else -> "ARG${index + 1}"
                        }
                    }
                    val formatted = localized.getString(id, *args)
                    assertFalse(field.name, placeholder.containsMatchIn(formatted))
                }
            }
        }
    }

    @Test fun everySettingsScreenRendersInBothLanguages() {
        val screenField = SettingsActivity::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
        val render = SettingsActivity::class.java.getDeclaredMethod("renderScreen").apply { isAccessible = true }
        for (language in listOf("ru", "en")) {
            SettingsLocale.select(context, language)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            assertEquals(language, activity.resources.configuration.locales[0].language)
            for (screen in screenField.type.enumConstants!!) {
                screenField.set(activity, screen)
                render.invoke(activity)
                val texts = allTexts(activity.window.decorView)
                assertTrue("$language/$screen", texts.isNotEmpty())
                if (screen.toString() == "LANGUAGES") {
                    assertTrue(texts.contains(if (language == "ru") "Язык интерфейса" else "Interface language"))
                    assertTrue(texts.contains("Русский"))
                    assertTrue(texts.contains("English"))
                }
            }
            controller.pause().stop().destroy()
        }
    }

    @Test fun recreationKeepsTheCurrentScreenAndSelectedLanguage() {
        SettingsLocale.select(context, "en")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val field = SettingsActivity::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
        field.set(controller.get(), field.type.enumConstants!!.first { it.toString() == "LANGUAGES" })
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(SettingsActivity::class.java).create(state).start().resume().visible()
        assertEquals("LANGUAGES", field.get(recreated.get())?.toString())
        assertEquals("en", recreated.get().resources.configuration.locales[0].language)
        recreated.pause().stop().destroy()
    }

    @Test fun publicComponentsUseTheNewApplicationId() {
        assertEquals("com.claritykey.keyboard", context.packageName)
        val manager = context.packageManager
        val activity = manager.getActivityInfo(android.content.ComponentName(context, SettingsActivity::class.java), 0)
        assertEquals("com.claritykey.keyboard.settings.SettingsActivity", activity.name)
        val provider = manager.resolveContentProvider("com.claritykey.keyboard.clipboard.fileprovider", 0)
        assertNotNull(provider)
        assertEquals(context.packageName, provider!!.packageName)
        assertNull(manager.resolveContentProvider("com.breeno.keyboard.ru.clipboard.fileprovider", 0))
    }

    private fun allTexts(view: View): List<String> {
        val own = if (view is TextView) listOf(view.text.toString(), view.hint?.toString().orEmpty()) else emptyList()
        return own + if (view is ViewGroup) (0 until view.childCount).flatMap { allTexts(view.getChildAt(it)) } else emptyList()
    }
}
