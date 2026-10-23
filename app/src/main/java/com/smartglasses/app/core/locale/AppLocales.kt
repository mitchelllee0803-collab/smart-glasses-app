package com.smartglasses.app.core.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Languages of the app's own screens. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    CHINESE("zh-CN"),
}

/**
 * The app's language, independent of the phone's. Android 13+ has per-app languages built in
 * (the system stores the choice, applies it to every part of the app and lists it in the
 * phone's settings). Older versions: the choice is stored here and applied to each Activity,
 * Service and the Application through [wrap].
 */
object AppLocales {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "language_tag"

    /** The language in use: the user's choice, or the phone's language when it is Chinese. */
    fun current(context: Context): AppLanguage {
        val tag = storedTag(context)
            ?: context.resources.configuration.locales[0].toLanguageTag()
        return if (tag.startsWith("zh")) AppLanguage.CHINESE else AppLanguage.ENGLISH
    }

    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system restarts the activities in the new language.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
            return
        }
        prefs(activity).edit().putString(KEY_TAG, language.tag).apply()
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        // The Application context (notifications, messages) follows without a restart.
        val app = activity.applicationContext
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) }
        @Suppress("DEPRECATION") // The only way to update an existing context's resources before API 33.
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        activity.recreate()
    }

    /** For attachBaseContext of the Application, Activities and Services (only acts before API 33). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    private fun storedTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            prefs(context).getString(KEY_TAG, null)
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
