package it.casate.scanner

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lets the camera app write one photo into this app's files (no library, no storage permission): the
 * camera gets a content:// address served by [PhotoProvider] and writes the picture there.
 */
class PhotoProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = Photos.file(context!!)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = Photos.file(context!!)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(if (mode.contains('w')) "rwt" else "r"))
    }
}

object Photos {
    const val REQUEST = 7301
    private const val AUTHORITY = "it.casate.scanner.photo"
    /** Longest side sent to the office (readable label, ~150 KB). */
    private const val SIDE = 1280

    fun file(ctx: android.content.Context): File = File(ctx.cacheDir, "request_photo.jpg")

    /** Opens the camera; false = this device has no camera app. */
    fun take(a: Activity): Boolean {
        val f = file(a)
        f.delete()
        f.createNewFile()
        val uri = Uri.parse("content://$AUTHORITY/photo.jpg")
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        return runCatching { a.startActivityForResult(i, REQUEST); true }.getOrDefault(false)
    }

    /** The picture just taken, made small (JPEG), or null. */
    fun result(a: Activity, ok: Boolean): ByteArray? {
        val f = file(a)
        if (!ok || f.length() < 100) return null
        return runCatching { shrink(f) }.getOrNull().also { f.delete() }
    }

    private fun shrink(f: File): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= SIDE) sample *= 2
        var b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val big = maxOf(b.width, b.height)
        if (big > SIDE) b = Bitmap.createScaledBitmap(b, b.width * SIDE / big, b.height * SIDE / big, true)
        val turn = when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        if (turn != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(turn) }, true)
        var q = 80
        while (true) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= 450_000 || q <= 40) return out.toByteArray()
            q -= 15
        }
    }
}
