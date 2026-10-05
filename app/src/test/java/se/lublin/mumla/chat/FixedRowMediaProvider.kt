package se.lublin.mumla.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A MediaStore that answers every insert with [ROW] and logs each call in [calls], so a test can
 * register the stream the save writes into before the save runs, and see the order of the steps.
 * Robolectric's own `FakeMediaProvider` numbers rows itself and writes real files, which leaves no
 * way to make a write fail.
 */
class FixedRowMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        calls += Call(Op.INSERT, uri, values?.let(::ContentValues))
        return ROW
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        calls += Call(Op.UPDATE, uri, values?.let(::ContentValues))
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        calls += Call(Op.DELETE, uri, null)
        return 1
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    enum class Op { INSERT, WRITE, UPDATE, DELETE }

    data class Call(val op: Op, val uri: Uri, val values: ContentValues?)

    companion object {
        val ROW: Uri = Uri.withAppendedPath(ImageGallerySaver.COLLECTION, "7")

        /** Every call since the last [install], writes to [ROW]'s stream included. */
        val calls = mutableListOf<Call>()

        /** Puts this provider in place of Robolectric's and has the next save write into [into]. */
        fun install(into: OutputStream) {
            calls.clear()
            Robolectric.setupContentProvider(FixedRowMediaProvider::class.java, MediaStore.AUTHORITY)
            val logged = object : FilterOutputStream(into) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    calls += Call(Op.WRITE, ROW, null)
                    out.write(b, off, len)
                }
            }
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStream(ROW, logged)
        }
    }
}

/** An output stream for a full disk. */
class FailingOutputStream : OutputStream() {
    override fun write(b: Int) = throw IOException("disk full")
    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
}
