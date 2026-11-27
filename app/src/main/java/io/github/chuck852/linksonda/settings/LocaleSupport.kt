package io.github.chuck852.linksonda.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Applies the saved language to an Activity base context.
 *
 * The app does not depend on AppCompat: on API 26+ a configuration context is enough, and the system
 * per-app language picker still sees the same `res/xml/locales_config.xml`.
 */
@SuppressLint("AppBundleLocaleChanges") // LinkSonda is distributed as a sideloaded APK, not an App Bundle.
fun Context.withAppLanguage(language: AppLanguage): Context {
    val locale = language.languageTag
        ?.let(Locale::forLanguageTag)
        ?: Resources.getSystem().configuration.locales[0]
    Locale.setDefault(locale)
    if (language.languageTag == null) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}
