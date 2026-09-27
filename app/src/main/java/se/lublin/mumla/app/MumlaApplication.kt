package se.lublin.mumla.app

import android.app.Application
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.setApplicationLocales
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.db.MumlaSQLiteDatabase
import se.lublin.mumla.log.AppLog
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.ui.AppMessages
import se.lublin.mumla.util.ApplicationScope
import se.lublin.mumla.util.changes

class MumlaApplication :
    Application(),
    MumlaRepository.Owner,
    ApplicationScope.Owner,
    SessionManager.Owner,
    AppMessages.Owner {

    override val scope: CoroutineScope = MainScope()

    /** Built in [onCreate], before any component of the app runs. */
    lateinit var container: AppContainer
        private set

    /** Replaces the container, e.g. with one whose sessions are fakes. */
    @VisibleForTesting
    internal fun installContainer(container: AppContainer) {
        this.container = container
    }

    override val sessionManager: SessionManager get() = container.sessionManager

    override val appMessages: AppMessages get() = container.appMessages

    @Volatile
    private var installedRepository: MumlaRepository? = null

    /** The single database of the process, opened on first use and never closed. */
    override val repository: MumlaRepository
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
        AppLog.install()
        DebugStrictMode.install()
        container = AppContainer(this, scope)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        applyTheme(preferences)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            preferences.changes(Settings.LANGUAGE.key, Settings.THEME.key)
                .collect { key -> onPreferenceChanged(preferences, key) }
        }
        // Decided per activity creation, so a changed setting applies to recreated activities.
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> Settings.getInstance(this).isDynamicColorEnabled }
                .build(),
        )
    }

    private fun onPreferenceChanged(preferences: SharedPreferences, key: String) {
        when (key) {
            Settings.LANGUAGE.key -> {
                val language = preferences.getString(Settings.LANGUAGE.key, "system")
                setApplicationLocales(
                    if (language == "system") LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(language),
                )
            }
            Settings.THEME.key -> applyTheme(preferences)
        }
    }

    private companion object {
        /** Unknown (older) values fall back to the system theme, which is written back. */
        fun applyTheme(preferences: SharedPreferences) {
            when (preferences.getString(Settings.THEME.key, "system")) {
                "forceLight" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "forceDark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    preferences.edit { putString(Settings.THEME.key, "system") }
                }
            }
        }
    }
}
