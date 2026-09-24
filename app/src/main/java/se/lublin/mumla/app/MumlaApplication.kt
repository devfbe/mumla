package se.lublin.mumla.app

import android.app.Application
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.setApplicationLocales
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import se.lublin.mumla.Settings.Companion.PREF_LANGUAGE
import se.lublin.mumla.Settings.Companion.PREF_THEME
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.MumlaSQLiteDatabase

class MumlaApplication : Application(), SharedPreferences.OnSharedPreferenceChangeListener {

    @Volatile
    private var installedRepository: MumlaRepository? = null

    /** The single database of the process, opened on first use and never closed. */
    val repository: MumlaRepository
        get() = installedRepository ?: synchronized(this) {
            installedRepository ?: MumlaRepository(MumlaSQLiteDatabase(this)).also { installedRepository = it }
        }

    /** Replaces the repository, before anything used it. */
    @VisibleForTesting
    fun installRepository(repository: MumlaRepository) {
        installedRepository = repository
    }

    override fun onCreate() {
        super.onCreate()
        DebugStrictMode.install()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        applyTheme(preferences)
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        when (key) {
            PREF_LANGUAGE -> {
                val language = preferences.getString(PREF_LANGUAGE, "system")
                setApplicationLocales(
                    if (language == "system") LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(language),
                )
            }
            PREF_THEME -> applyTheme(preferences)
        }
    }

    private companion object {
        /** Unknown (older) values fall back to the system theme, which is written back. */
        fun applyTheme(preferences: SharedPreferences) {
            when (preferences.getString(PREF_THEME, "system")) {
                "forceLight" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "forceDark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    preferences.edit { putString(PREF_THEME, "system") }
                }
            }
        }
    }
}
