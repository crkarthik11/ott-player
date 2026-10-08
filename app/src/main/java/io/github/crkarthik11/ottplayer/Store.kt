package io.github.crkarthik11.ottplayer

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the playlist and guide on disk so the app starts from them instantly,
 * and refreshes them with conditional GETs so an unchanged file costs one 304.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("http", Context.MODE_PRIVATE)

    fun cached(name: String): File? = File(dir, name).takeIf { it.isFile && it.length() > 0 }

    /**
     * Downloads [url] into [name] if it changed. A download that fails [valid] is
     * discarded, so a bad response never replaces a good cached copy.
     * Returns true when the file was replaced.
     */
    @Throws(IOException::class)
    fun refresh(url: String, name: String, valid: (File) -> Boolean): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "OTTPlayer/${BuildConfig.VERSION_NAME}")
            if (cached(name) != null) {
                prefs.getString("$name.etag", null)?.let { conn.setRequestProperty("If-None-Match", it) }
                prefs.getString("$name.modified", null)?.let { conn.setRequestProperty("If-Modified-Since", it) }
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return false
                HttpURLConnection.HTTP_OK -> {
                    val tmp = File(dir, "$name.tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() == 0L || !runCatching { valid(tmp) }.getOrDefault(false)) {
                        tmp.delete()
                        throw IOException("invalid $name")
                    }
                    // Servers without ETag/Last-Modified send the whole file every time;
                    // report "unchanged" when it is byte-for-byte what we already have.
                    val old = cached(name)
                    if (old != null && old.length() == tmp.length() && old.readBytes().contentEquals(tmp.readBytes())) {
                        tmp.delete()
                        return false
                    }
                    if (!tmp.renameTo(File(dir, name))) throw IOException("could not save $name")
                    prefs.edit()
                        .putString("$name.etag", conn.getHeaderField("ETag"))
                        .putString("$name.modified", conn.getHeaderField("Last-Modified"))
                        .apply()
                    return true
                }
                else -> throw IOException("HTTP $code for $name")
            }
        } finally {
            conn.disconnect()
        }
    }
}
