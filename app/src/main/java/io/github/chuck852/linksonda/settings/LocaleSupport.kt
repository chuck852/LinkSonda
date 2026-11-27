package io.github.chuck852.linksonda.settings

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the saved language to an Activity base context.
 *
 * On API 33+ [applySystemAppLanguage] keeps the platform per-app language picker in sync with the
 * same preference. On API 26–32 the configuration context is the only mechanism.
 */
@SuppressLint("AppBundleLocaleChanges") // LinkSonda is distributed as a sideloaded APK, not an App Bundle.
fun Context.withAppLanguage(language: AppLanguage): Context {
    val locale = language.languageTag
        ?.let(Locale::forLanguageTag)
        ?: resources.configuration.locales[0]
    Locale.setDefault(locale)
    if (language.languageTag == null) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}

/**
 * Synchronizes the Android per-app language picker with [language].
 *
 * The stored setting remains the source of truth. [AppLanguage.SYSTEM] clears the platform override
 * so Android follows the device locale again.
 */
@SuppressLint("AppBundleLocaleChanges") // LinkSonda is distributed as a sideloaded APK, not an App Bundle.
fun Context.applySystemAppLanguage(language: AppLanguage) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = getSystemService(LocaleManager::class.java) ?: return
    val desired = language.languageTag
        ?.let { tag -> LocaleList.forLanguageTags(tag) }
        ?: LocaleList.getEmptyLocaleList()
    if (manager.applicationLocales != desired) {
        manager.applicationLocales = desired
    }
}
