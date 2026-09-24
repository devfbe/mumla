package se.lublin.mumla.util

import android.content.Context
import android.content.pm.PackageManager

/** Detects the Orbot Tor proxy app. Its package is declared in the manifest's `<queries>`. */
object Orbot {
    const val PACKAGE_NAME = "org.torproject.android"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
