package com.iurika.videoflows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Video thumbnails: one frame at 10 % in, memory LRU in front of filesDir/thumbs (cacheDir gets emptied when storage runs low). */
object Thumbs {
    private const val PX = 320
    private lateinit var dir: File
    private lateinit var app: Context
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet()) // unreadable this session: not retried on every redraw
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) { app = c.applicationContext; dir = File(c.filesDir, "thumbs").apply { mkdirs() } }

    private fun disk(key: String) = File(dir, MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    // ponytail: keyed on the uri only, an edited file keeps its old frame (add size/mtime to the key if that matters)
    fun load(view: ImageView, uri: Uri, durationMs: Long) {
        val k = uri.toString()
        view.tag = k
        val hit = mem.get(k)
        view.setImageBitmap(hit)
        if (hit != null || k in failed) return
        pool.execute {
            if (view.tag != k) return@execute // scrolled away before its turn
            val bmp = get(k, uri, durationMs)
            main.post { if (view.tag == k && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    private fun get(k: String, uri: Uri, durationMs: Long): Bitmap? {
        mem.get(k)?.let { return it }
        val d = disk(k)
        BitmapFactory.decodeFile(d.path)?.let { mem.put(k, it); return it }
        val bmp = runCatching { frame(uri, durationMs) }.getOrNull()
        if (bmp == null) { failed += k; return null }
        runCatching { d.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        mem.put(k, bmp)
        return bmp
    }

    private fun frame(uri: Uri, durationMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, uri)
            val at = durationMs * 100 // µs: 10 % in, past black intros
            if (Build.VERSION.SDK_INT >= 27) return r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PX, PX)
            val f = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = PX.toFloat() / maxOf(f.width, f.height)
            return if (s >= 1) f else Bitmap.createScaledBitmap(f, (f.width * s).toInt(), (f.height * s).toInt(), true).also { f.recycle() }
        } finally {
            r.release()
        }
    }
}
