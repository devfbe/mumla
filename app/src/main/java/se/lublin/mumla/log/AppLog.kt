/*
 * Copyright (C) 2026 The Mumla authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import se.lublin.humla.util.AndroidLogSink
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.chat.ImageShareExporter
import java.io.File

private const val CAPACITY = 2000
private const val DIRECTORY = "shared_logs"
private const val FILE_NAME = "mumla-log.txt"

/** The process's developer log: logcat, plus the recent lines the user can share. */
object AppLog {
    val buffer = LogBuffer(CAPACITY)

    /** Idempotent. */
    fun install() {
        HumlaLog.addSink(AndroidLogSink)
        HumlaLog.addSink(buffer)
    }

    /** What "Share log" sends: the app and device, then the buffer, redacted. */
    fun report(): String = LogRedaction.redact(
        "Mumla ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR}), " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            buffer.dump(),
    )

    /**
     * Writes [report] to the one file the FileProvider shares for logs, replacing the previous one.
     * Blocking I/O.
     */
    fun export(context: Context, report: String): Uri {
        val file = File(File(context.cacheDir, DIRECTORY).apply { mkdirs() }, FILE_NAME)
        file.writeText(report)
        val authority = context.packageName + ImageShareExporter.AUTHORITY_SUFFIX
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun shareIntent(uri: Uri, subject: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the read grant on only for URIs in the clip data.
        .apply { clipData = ClipData.newRawUri(subject, uri) }
}
