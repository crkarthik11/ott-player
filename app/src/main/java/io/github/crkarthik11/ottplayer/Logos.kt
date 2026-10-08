package io.github.crkarthik11.ottplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Channel logos, fetched only when a tile showing them is on screen. Each logo is
 * shrunk to tile size, kept in memory, and cached on disk, so it is downloaded once.
 */
object Logos {
    private const val MAX_PX = 160
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.cacheDir, "logos").apply { mkdirs() }
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    /** Calls [done] on the main thread with the logo, or not at all if it can't be had. */
    fun load(url: String, done: (Bitmap) -> Unit) {
        memory.get(url)?.let { done(it); return }
        if (url in failed) return
        pool.execute {
            val bmp = runCatching { fromDisk(url) ?: download(url) }.getOrNull()
            main.post {
                if (bmp == null) failed.add(url)
                else { memory.put(url, bmp); done(bmp) }
            }
        }
    }

    private fun file(url: String) = dir?.let { File(it, Integer.toHexString(url.hashCode()) + ".png") }

    private fun fromDisk(url: String): Bitmap? = file(url)?.takeIf { it.isFile }?.let { decode(it.readBytes()) }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bmp = decode(bytes) ?: return null
            // Store the shrunk copy, so the disk cache stays small too.
            file(url)?.outputStream()?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bmp
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * A channel's square tile: its logo on a dark tile once loaded, coloured initials
 * until then (or for channels without a logo).
 */
class LogoTile @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val initials = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        paint.isFakeBoldText = true
    }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private var url: String? = null

    init {
        addView(initials, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initials.textSize = h * 0.34f / resources.displayMetrics.scaledDensity
        val pad = (h * 0.1f).toInt()
        image.setPadding(pad, pad, pad, pad)
    }

    fun bind(ch: Channel, tint: Int, radiusDp: Float) {
        val radius = radiusDp * resources.displayMetrics.density
        initials.text = ch.initials
        url = ch.logo
        val logo = ch.logo?.let { Logos.cached(it) }
        show(logo, tint, radius)
        if (logo == null) ch.logo?.let { u ->
            Logos.load(u) { bmp -> if (url == u) show(bmp, tint, radius) }
        }
    }

    private fun show(logo: Bitmap?, tint: Int, radius: Float) {
        image.setImageBitmap(logo)
        initials.visibility = if (logo == null) VISIBLE else INVISIBLE
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (logo == null) tint else LOGO_BG)
        }
    }

    private companion object {
        const val LOGO_BG = 0xFF242A33.toInt()
    }
}
