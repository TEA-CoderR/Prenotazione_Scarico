package it.casate.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Product photos from the company's image server (the same as the purchasing program:
 * https://img.casaete.top/prodotti/<ean>/01.webp, template sent with the catalog). Downloaded in the
 * background, kept on the device (so they show offline afterwards), decoded at the size shown.
 * A photo the server does not have is not asked again for a day.
 */
class Images(ctx: Context) {
    var template = DEFAULT
    private val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val loading = HashSet<String>()
    private val waiting = HashMap<String, MutableList<() -> Unit>>()

    /** Ways the server may name the folder: as in the catalog, then with leading zeros to 13 digits. */
    fun candidates(ean: String): List<String> {
        val e = ean.filter { it.isDigit() }.trimStart('0')
        if (e.length < 6) return emptyList()
        return listOf(e, e.padStart(13, '0')).distinct()
    }

    private fun file(e: String) = File(dir, "$e.img")
    private fun miss(e: String) = File(dir, "$e.none")

    /** Shows the photo in `view` (sized `px` pixels); `done(true)` when there is one. */
    fun into(view: ImageView, ean: String, px: Int, done: (Boolean) -> Unit) {
        val e = candidates(ean).firstOrNull() ?: return done(false)
        val key = "$e@$px"
        view.tag = key
        mem.get(key)?.let { view.setImageBitmap(it); return done(true) }
        val show = {
            if (view.tag == key) {
                val b = mem.get(key)
                if (b != null) { view.setImageBitmap(b); done(true) } else done(false)
            }
        }
        synchronized(loading) {
            waiting.getOrPut(key) { ArrayList() }.add(show)
            if (!loading.add(key)) return
        }
        pool.execute {
            val bmp = runCatching { load(e, px) }.getOrNull()
            if (bmp != null) mem.put(key, bmp)
            main.post {
                val list = synchronized(loading) { loading.remove(key); waiting.remove(key).orEmpty() }
                for (f in list) f()
            }
        }
    }

    private fun load(e: String, px: Int): Bitmap? {
        val f = file(e)
        if (!f.exists()) {
            val m = miss(e)
            if (m.exists() && System.currentTimeMillis() - m.lastModified() < 24 * 3600_000L) return null
            var found = false
            var notFound = true
            for (c in candidates(e)) {
                val url = template.replace("{ean}", c)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CasaTeScanner")
                    setRequestProperty("Accept", "image/webp,image/*")
                }
                try {
                    val code = conn.responseCode
                    if (code == 200) {
                        val tmp = File(dir, "$e.tmp")
                        conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        tmp.renameTo(f)
                        found = true
                        break
                    }
                    if (code != 404) notFound = false
                } finally {
                    conn.disconnect()
                }
            }
            if (!found) {
                if (notFound) runCatching { m.writeText("") }
                return null
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    companion object {
        const val DEFAULT = "https://img.casaete.top/prodotti/{ean}/01.webp"
    }
}
