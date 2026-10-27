package com.iurika.videoflows

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import android.util.AtomicFile
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.text.DateFormat
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val EXPORT = 2
private const val ADD_FOLDER = 3
private const val EXCLUDE_FOLDER = 4
// Palette of the design mockup
private const val AC = 0xfffe2c55.toInt()
private const val AC2 = 0xff25f4ee.toInt()
private const val WHITE = 0xffffffff.toInt()
private const val SH = 0xff161618.toInt()
private const val S1 = 0xff1f1f23.toInt()
private const val S2 = 0xff2a2a30.toInt()
private const val LINE = 0xff2c2c33.toInt()
private const val MU = 0xffa8a8b3.toInt()
private const val PINK_TX = 0xffff8fa6.toInt()
private const val RED = 0xffff6b85.toInt()
private const val HOUR = 3_600_000L
private val RATES = listOf(1f, 1.5f, 2f, 0.5f)
private val RATIOS = listOf("9:16" to 9 / 16.0, "3:4" to 3 / 4.0, "1:1" to 1.0, "4:3" to 4 / 3.0, "16:9" to 16 / 9.0, "21:9" to 21 / 9.0)

private class Order(val label: String, val desc: String, val icon: Int)

private val ORDERS = linkedMapOf(
    "smart" to Order("Mix intelligent", "Non vues d'abord, puis les moins récemment vues. Jamais la même au démarrage.", R.drawable.ic_smart),
    "random" to Order("Aléatoire", "Ordre totalement mélangé à chaque fois.", R.drawable.ic_shuffle),
    "unseen" to Order("Non vues seulement", "Cache tout ce que tu as déjà regardé.", R.drawable.ic_eye),
    "newest" to Order("Plus récentes", "Par date du fichier, les dernières d'abord.", R.drawable.ic_clock),
    "oldest" to Order("Plus anciennes", "Redécouvre tes vieux fichiers.", R.drawable.ic_old),
    "name" to Order("Nom A → Z", "Ordre alphabétique, comme un explorateur.", R.drawable.ic_az),
)

// What the feed is narrowed to (folder, tags, search, preset); the pill under the top bar shows it
private class Scope(val key: String, val label: String, val icon: Int, val test: (MainActivity.Clip) -> Boolean)

// Wrapping row of chips
private class Flow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
    private fun place(width: Int, layout: Boolean): Int {
        var x = 0; var y = 0; var row = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (!layout) c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += row + gap; row = 0 }
            if (layout) c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            row = max(row, c.measuredHeight)
        }
        return y + row
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        setMeasuredDimension(w, place(w, false))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { place(r - l, true) }
}

class MainActivity : Activity() {
    class Note(val t: Long, val text: String) // t = position in the video (ms), -1 for notes from before V0.0.5

    class Clip(val uri: Uri, val title: String, var folder: String) {
        var favorite = false
        val tags = sortedSetOf<String>()
        val notes = mutableListOf<Note>()
        // Probed once and cached in feed.json, so auto tags work without playing the video
        var width = 0
        var height = 0
        var duration = 0L
        var size = 0L
        var lastPlayed = 0L
        var pos = 0L          // where playback stopped, ms
        var date = 0L         // file last modified
        var hidden = false    // "Masquer du feed"
        var root: Uri? = null // scanned folder it was found in

        // User tags plus automatic ones from the video itself (ratio, resolution)
        fun allTags(): Set<String> {
            if (width <= 0 || height <= 0) return tags
            val ratio = RATIOS.minBy { abs(ln(width.toDouble() / height / it.second)) }.first
            val short = min(width, height)
            val res = when { short >= 2160 -> "4K"; short >= 1080 -> "1080p"; short >= 720 -> "720p"; else -> "SD" }
            return tags + ratio + res
        }

        fun toJson(): JSONObject = JSONObject().put("uri", uri.toString()).put("title", title).put("folder", folder)
            .put("favorite", favorite).put("tags", JSONArray(tags))
            .put("notes", JSONArray(notes.map { JSONObject().put("t", it.t).put("text", it.text) }))
            .put("width", width).put("height", height).put("duration", duration).put("size", size).put("lastPlayed", lastPlayed)
            .put("pos", pos).put("date", date).put("hidden", hidden)
    }

    private var library = listOf<Clip>()          // everything found in the folders
    private var clips = listOf<Clip>()            // what the feed shows, after filters/order
    private val folders = mutableListOf<Uri>()   // folder trees scanned for videos, subfolders included
    private val off = mutableSetOf<String>()     // folders kept in the library but left out of the feed
    private val excluded = mutableListOf<Uri>()  // subfolders skipped while scanning
    private val known = mutableMapOf<Uri, Clip>() // state + probed info, kept across rescans
    // Folders, settings and per-video state as JSON; AtomicFile so a crash mid-write can't corrupt it
    private val store by lazy { AtomicFile(File(filesDir, "feed.json")) }

    private var favoritesOnly = false
    private var scope: Scope? = null
    private var order = "smart"
    private var start = "smart"     // order at launch: smart, random, or keep (last order, last video)
    private var autoNext = true
    private var loop = false
    private var remember = true
    private var mutedStart = false
    private var muted = false
    private var rate = 1f
    private val recentQ = mutableListOf<String>()
    private var scanned = false
    private var resumeShown = false
    private var page = "feed"
    private var paused = false
    private var dragging = false

    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var pager: ViewPager2
    private lateinit var scrub: SeekBar
    private var fd: ParcelFileDescriptor? = null
    private var playingClip: Clip? = null
    private var attachedTo: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        @Suppress("DEPRECATION") window.statusBarColor = 0xff000000.toInt()
        @Suppress("DEPRECATION") window.navigationBarColor = 0xff000000.toInt()
        vlc = LibVLC(this)
        player = MediaPlayer(vlc).apply { videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN }
        load()
        if (start != "keep") order = start
        muted = mutedStart

        pager = findViewById(R.id.pager)
        pager.adapter = Feed()
        // No change animation: rebinding the page on screen must keep the same view, or the video restarts
        (pager.getChildAt(0) as RecyclerView).itemAnimator = null
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position > 0 && !resumeShown) { resumeShown = true; renderCtx() }
                pager.post { play(position) }
            }
        })
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.TimeChanged -> if (!dragging && player.length > 0) scrub.progress = (player.time * 1000 / player.length).toInt()
                MediaPlayer.Event.EndReached -> {
                    playingClip?.pos = 0
                    if (autoNext && pager.currentItem < clips.size - 1) pager.currentItem += 1 else setPaused(true)
                }
            }
        }

        scrub = findViewById(R.id.scrub)
        scrub.thumb.mutate().alpha = 0
        val scrubTime = findViewById<TextView>(R.id.scrubtime)
        scrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user) scrubTime.text = "${time(p * player.length / 1000)} / ${time(player.length)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true; s.thumb.alpha = 255; scrubTime.visibility = View.VISIBLE }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false; s.thumb.alpha = 0; scrubTime.visibility = View.GONE
                if (player.length > 0) player.setTime(s.progress * player.length / 1000)
            }
        })

        findViewById<View>(R.id.all).setOnClickListener { setFavoritesOnly(false) }
        findViewById<View>(R.id.favorites).setOnClickListener { setFavoritesOnly(true) }
        findViewById<View>(R.id.order).setOnClickListener { orderSheet() }
        findViewById<View>(R.id.search).setOnClickListener { searchSheet() }
        findViewById<View>(R.id.nav_home).setOnClickListener { if (page == "feed") { if (clips.isNotEmpty()) pager.currentItem = 0 } else goPage("feed") }
        findViewById<View>(R.id.nav_explore).setOnClickListener { goPage("explore") }
        findViewById<View>(R.id.nav_create).setOnClickListener { pick(ADD_FOLDER) }
        findViewById<View>(R.id.nav_recent).setOnClickListener { goPage("history") }
        findViewById<View>(R.id.nav_library).setOnClickListener { goPage("library") }
        renderTabs()
        renderOrder()
        renderNav()
        scan()
    }

    // ---- Feed: filters and order ----

    private fun visible(c: Clip) = !c.hidden && c.root?.toString() !in off

    private fun matches(c: Clip) = visible(c) && (!favoritesOnly || c.favorite) && scope?.test?.invoke(c) != false

    private fun ordered(list: List<Clip>): List<Clip> = when (order) {
        "random" -> list.shuffled()
        "unseen" -> list.filter { it.lastPlayed == 0L }.shuffled()
        "newest" -> list.sortedByDescending { it.date }
        "oldest" -> list.sortedBy { it.date }
        "name" -> list.sortedBy { it.title.lowercase() }
        else -> {
            // 3 unseen videos for 1 seen again; the seen ones least recently first, favorites a little sooner
            val unseen = list.filter { it.lastPlayed == 0L }.shuffled()
            val seen = list.filter { it.lastPlayed > 0 }
                .sortedBy { it.lastPlayed - (if (it.favorite) 6 * HOUR else 0) + Random.nextLong(12 * HOUR) }
            val out = mutableListOf<Clip>()
            var i = 0
            var j = 0
            while (i < unseen.size || j < seen.size) {
                repeat(3) { if (i < unseen.size) out += unseen[i++] }
                if (j < seen.size) out += seen[j++]
            }
            // Never the video from last time first
            if (out.size > 1 && out[0] === lastClip()) out.add(out.removeAt(0))
            out
        }
    }

    private fun lastClip() = library.maxByOrNull { it.lastPlayed }?.takeIf { it.lastPlayed > 0 }

    // Rebuilds the feed. reorder = false keeps the current order (after a rescan, a hidden video…), only appending new videos.
    private fun applyFilter(reorder: Boolean = true, at: Clip? = null) {
        val matching = library.filter(::matches)
        clips = if (reorder || clips.isEmpty()) ordered(matching) else {
            val set = matching.toSet()
            val old = clips.toSet()
            clips.filter { it in set } + ordered(matching.filter { it !in old })
        }
        findViewById<TextView>(R.id.empty).apply {
            text = when {
                !scanned -> ""
                library.isEmpty() -> "Aucune vidéo\nTouche + pour ajouter un dossier"
                favoritesOnly -> "Aucun favori ici\nDouble-tape une vidéo pour l'ajouter aux favoris."
                else -> "Aucune vidéo\nChange de filtre ou ajoute un dossier."
            }
            visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        }
        scrub.visibility = if (clips.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (clips.isEmpty()) { savePos(); player.stop(); player.detachViews(); playingClip = null; attachedTo = null }
        pager.adapter!!.notifyDataSetChanged()
        pager.setCurrentItem(clips.indexOf(at).coerceAtLeast(0), false)
        pager.post { play(pager.currentItem) }
        renderCtx()
    }

    private fun setFavoritesOnly(on: Boolean) {
        favoritesOnly = on
        resumeShown = true
        renderTabs()
        applyFilter()
    }

    private fun setScope(s: Scope?, at: Clip? = null) {
        scope = s
        favoritesOnly = false
        resumeShown = true
        renderTabs()
        goPage("feed")
        applyFilter(at = at)
    }

    private fun folderScope(f: String) = Scope("folder:$f", f.substringAfterLast('/'), R.drawable.ic_folder) { it.folder == f }

    private fun tagScope(t: String) = Scope("tag:$t", "#$t", R.drawable.ic_tag) { t in it.allTags() }

    // Shows a video wherever it is: in the feed if it's there, else in the full library
    private fun jumpTo(c: Clip) {
        resumeShown = true
        goPage("feed")
        if (c in clips) pager.setCurrentItem(clips.indexOf(c), false)
        else { scope = null; favoritesOnly = false; renderTabs(); applyFilter(at = c) }
        renderCtx()
    }

    private fun renderTabs() {
        listOf(R.id.all to !favoritesOnly, R.id.favorites to favoritesOnly).forEach { (id, active) ->
            findViewById<TextView>(id).apply {
                setTextColor(if (active) WHITE else 0x9effffff.toInt())
                paint.isFakeBoldText = active
                setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, if (active) R.drawable.tab_underline else R.drawable.tab_underline_none)
            }
        }
    }

    private fun renderOrder() = findViewById<ImageView>(R.id.order).setImageResource(ORDERS[order]!!.icon)

    private fun resumeClip() = library.filter(::visible).maxByOrNull { it.lastPlayed }
        ?.takeIf { it.lastPlayed > 0 && it.pos > 5000 && (it.duration == 0L || it.pos < it.duration - 3000) }

    // Pill of the active filter, or at launch the card to resume the last video
    private fun renderCtx() {
        val box = findViewById<FrameLayout>(R.id.ctx)
        box.removeAllViews()
        scope?.let { s ->
            box.addView(hbox(
                icon(s.icon, WHITE, 16),
                tv("${s.label} · ${clips.size}", 13f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.lp(-2, -2, 0f, l = 8, r = 8),
                closeButton(26, "Retirer le filtre") { setScope(null) },
            ).apply { background = rounded(0x8c000000.toInt(), 17); setPadding(dp(12), 0, dp(4), 0) },
                FrameLayout.LayoutParams(-2, dp(34), Gravity.CENTER_HORIZONTAL))
            return
        }
        if (resumeShown) return
        val r = resumeClip()?.takeIf { it !== clips.firstOrNull() } ?: return
        box.addView(hbox(
            tile(r, 36, 52).lp(dp(36), dp(52), r = 10),
            vbox(
                tv("Reprendre où tu t'étais arrêté", 12f, MU),
                tv(r.title, 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                tv("${time(r.pos)} / ${time(r.duration)}", 12f, AC2, true),
            ).lp(0, -2, 1f, r = 8),
            button("Reprendre", AC, small = true) { jumpTo(r) },
            closeButton(34, "Ignorer", MU) { resumeShown = true; renderCtx() },
        ).apply { background = rounded(0xd1161618.toInt(), 16); setPadding(dp(8), dp(8), dp(8), dp(8)) }, FrameLayout.LayoutParams(-1, -2))
    }

    // ---- Pages ----

    private fun goPage(p: String) {
        page = p
        val pages = findViewById<ScrollView>(R.id.pages)
        if (p == "feed") {
            pages.visibility = View.GONE
            if (playingClip != null && !paused) player.play()
        } else {
            pages.visibility = View.VISIBLE
            player.pause()
            renderPage()
            pages.scrollTo(0, 0)
        }
        renderNav()
    }

    private fun renderNav() {
        listOf(R.id.nav_home to "feed", R.id.nav_explore to "explore", R.id.nav_recent to "history", R.id.nav_library to "library").forEach { (id, p) ->
            findViewById<TextView>(id).apply { alpha = if (page == p) 1f else .55f; paint.isFakeBoldText = page == p; invalidate() }
        }
    }

    private fun renderPage() {
        findViewById<LinearLayout>(R.id.pagebody).removeAllViews()
        when (page) { "explore" -> explore(); "history" -> history(); "library" -> libraryPage() }
    }

    // Keeps the margins a view already has (sec()) unless new ones are given
    private fun add(v: View, t: Int = 0, b: Int = 0) =
        findViewById<LinearLayout>(R.id.pagebody).addView(if (v.layoutParams != null && t == 0 && b == 0) v else v.lp(t = t, b = b))

    private fun title(s: String, extra: View? = null) = add(hbox(tv(s, 24f, bold = true).lp(0, -2, 1f)).apply { extra?.let(::addView) })

    private fun header(s: String, action: String, onClick: () -> Unit) = add(hbox(
        tv(s, 18f, bold = true).lp(0, -2, 1f),
        tv(action, 12f, PINK_TX, true).apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { onClick() } },
    ), t = 26, b = 10)

    private fun explore() {
        val lib = library.filter(::visible)
        title("Explorer")
        add(hbox(icon(R.drawable.ic_search, MU, 18), tv("Titre, dossier, tag ou note", 15f, MU).lp(l = 10))
            .apply { background = rounded(S1, 10); setPadding(dp(14), 0, dp(14), 0); minimumHeight = dp(44); setOnClickListener { searchSheet() } }, t = 14)
        fun preset(label: String, res: Int, color: Int, n: Int, go: () -> Unit) = hbox(
            icon(res, color), vbox(tv(label, 14f, bold = true), tv("$n vidéos", 12f, MU)).lp(0, -2, 1f, l = 10),
        ).apply { background = rounded(S1, 14); setPadding(dp(14), dp(14), dp(14), dp(14)); setOnClickListener { go() } }
        add(grid(listOf(
            preset("Non vues", R.drawable.ic_eye, AC2, lib.count { it.lastPlayed == 0L }) {
                setScope(Scope("unseen", "Non vues", R.drawable.ic_eye) { it.lastPlayed == 0L })
            },
            preset("Courtes (≤30 s)", R.drawable.ic_clock, 0xffffc94d.toInt(), lib.count { it.duration in 1..30_000 }) {
                setScope(Scope("short", "Courtes", R.drawable.ic_clock) { it.duration in 1..30_000 })
            },
            preset("Avec notes", R.drawable.ic_comment, 0xffb57bff.toInt(), lib.count { it.notes.isNotEmpty() }) {
                setScope(Scope("notes", "Avec notes", R.drawable.ic_comment) { it.notes.isNotEmpty() })
            },
            preset("Favoris", R.drawable.ic_heart, AC, lib.count { it.favorite }) { scope = null; goPage("feed"); setFavoritesOnly(true) },
        )), t = 16)

        header("Dossiers", "Gérer") { goPage("library") }
        add(grid(lib.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (f, vs) ->
            vbox(
                grid((0 until 4).map { k -> View(this).apply { background = vs.getOrNull(k)?.let(::gradient) ?: rounded(0xff222222.toInt(), 0) }.lp(-1, dp(59)) }, gap = 2),
                vbox(
                    hbox(icon(R.drawable.ic_folder, folderColor(f), 16), tv(f.substringAfterLast('/'), 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.lp(l = 6)),
                    tv("${vs.size} vidéos · $f", 12f, MU).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) },
                ).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) },
            ).apply { background = rounded(S1, 14); clipToOutline = true; setOnClickListener { setScope(folderScope(f)) } }
        }))

        header("Tags", "Combiner") { browseTags() }
        add(flow(tagCounts().map { (t, n) -> chip("#$t  $n") { setScope(tagScope(t)) } }), b = 12)
    }

    private fun history() {
        val hs = library.filter { visible(it) && it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(100)
        title("Récents", if (hs.isEmpty()) null else button("Effacer", S2, small = true) {
            AlertDialog.Builder(this).setMessage("Effacer l'historique et les positions de lecture ?")
                .setPositiveButton("Effacer") { _, _ -> hs.forEach { it.lastPlayed = 0; it.pos = 0 }; save(); renderPage() }
                .setNegativeButton("Annuler", null).show()
        })
        resumeClip()?.let { r ->
            add(hbox(
                tile(r, 64, 96).lp(dp(64), dp(96), r = 14),
                vbox(
                    tv("REPRENDRE", 12f, AC2, true),
                    tv(r.title, 15f, bold = true).apply { setPadding(0, dp(4), 0, 0) },
                    tv("${time(r.pos)} / ${time(r.duration)} · ${r.folder.substringAfterLast('/')}", 12f, MU).apply { setPadding(0, dp(4), 0, 0) },
                ).lp(0, -2, 1f, r = 10),
                ImageView(this).apply { setImageResource(R.drawable.ic_play); background = rounded(AC, 22); setPadding(dp(13), dp(13), dp(13), dp(13)) }.lp(dp(44), dp(44)),
            ).apply { background = rounded(S1, 14); setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { jumpTo(r) } }, t = 16)
        }
        if (hs.isEmpty()) {
            add(vbox(icon(R.drawable.ic_history, 0xff3a3a42.toInt(), 44), tv("Rien regardé pour l'instant.", 14f, MU).lp(t = 12))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(60), 0, dp(60)) })
            return
        }
        val now = System.currentTimeMillis()
        hs.groupBy { val d = (now - it.lastPlayed) / (24 * HOUR); if (d < 1) "Aujourd'hui" else if (d < 7) "Cette semaine" else "Plus ancien" }.forEach { (g, l) ->
            add(sec(g))
            l.forEach { c -> add(clipRow(c, listOfNotNull(c.folder, DateUtils.getRelativeTimeSpanString(c.lastPlayed, now, DateUtils.MINUTE_IN_MILLIS).toString())) { jumpTo(c) }) }
        }
    }

    private fun libraryPage() {
        val lib = library.filter(::visible)
        title("Bibliothèque")
        add(grid(listOf("${lib.size}" to "vidéos", "${folders.count { it.toString() !in off }}" to "dossiers",
            size(lib.sumOf { it.size }) to "stockage", "${lib.count { it.favorite }}" to "favoris").map { (n, l) ->
            vbox(tv(n, 17f, bold = true), tv(l, 12f, MU)).apply {
                for (i in 0 until childCount) (getChildAt(i) as TextView).gravity = Gravity.CENTER
                background = rounded(S1, 14); setPadding(dp(4), dp(12), dp(4), dp(12))
            }
        }, cols = 4, gap = 8), t = 16)

        add(hbox(tv("Dossiers suivis", 18f, bold = true).lp(0, -2, 1f), button("+ Ajouter", AC, small = true) { pick(ADD_FOLDER) }), t = 24, b = 10)
        add(card(
            *folders.map { f ->
                val on = f.toString() !in off
                val sw = switch(on) { checked ->
                    if (checked) off.remove(f.toString()) else off.add(f.toString())
                    save(); applyFilter(false, playingClip); renderPage()
                    toast(if (checked) "${label(f)} inclus dans le feed" else "${label(f)} exclu du feed")
                }
                lrow(icon(R.drawable.ic_folder, if (on) folderColor(label(f)) else 0xff55555f.toInt(), 24),
                    vbox(tv(label(f), 15f, if (on) WHITE else MU, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        tv("${library.count { it.root == f }} vidéos${if (on) "" else " · exclu du feed"}", 12f, MU)).lp(0, -2, 1f, l = 12, r = 8),
                    sw,
                    iconButton(R.drawable.ic_trash, "Retirer ${label(f)}", MU) {
                        runCatching { contentResolver.releasePersistableUriPermission(f, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        folders.remove(f); off.remove(f.toString()); save(); scan(); toast("${label(f)} retiré")
                    })
            }.toTypedArray(),
            *excluded.map { f ->
                lrow(icon(R.drawable.ic_ban, MU, 24), vbox(tv(label(f), 15f, bold = true), tv("Sous-dossier ignoré au scan", 12f, MU)).lp(0, -2, 1f, l = 12, r = 8),
                    iconButton(R.drawable.ic_trash, "Ne plus ignorer ${label(f)}", MU) { excluded.remove(f); save(); scan() })
            }.toTypedArray(),
            lrow(icon(R.drawable.ic_ban, WHITE, 20), tv("Ignorer un sous-dossier…", 15f, bold = true).lp(0, -2, 1f, l = 12)).apply { setOnClickListener { pick(EXCLUDE_FOLDER) } },
        ))
        add(tv("L'interrupteur exclut un dossier du feed sans l'oublier. La corbeille le retire de la bibliothèque (aucun fichier supprimé).", 12f, MU)
            .apply { setLineSpacing(0f, 1.3f) }, t = 8)

        val hidden = library.filter { it.hidden }
        if (hidden.isNotEmpty()) {
            add(sec("Vidéos masquées · ${hidden.size}"))
            add(card(*hidden.map { c ->
                lrow(tile(c, 36, 54).lp(dp(36), dp(54)), tv(c.title, 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.lp(0, -2, 1f, l = 12, r = 8),
                    button("Rétablir", S2, small = true) { c.hidden = false; save(); applyFilter(false, playingClip); renderPage(); toast("Vidéo rétablie") })
            }.toTypedArray()))
        }

        add(sec("Lecture"))
        add(card(
            lrow(vbox(tv("Au démarrage", 15f, bold = true), tv("Ordre du feed à l'ouverture", 12f, MU)).lp(0, -2, 1f, r = 8),
                seg(listOf("smart" to "Mix", "random" to "Aléa", "keep" to "Dernier"), start) { start = it; save(); renderPage() }),
            switchRow("Vidéo suivante automatique", "Enchaîne à la fin de la vidéo", autoNext) { autoNext = it },
            switchRow("Boucler la vidéo", "Prioritaire sur l'enchaînement", loop) { loop = it },
            switchRow("Mémoriser la position", "Reprend où tu t'étais arrêté", remember) { remember = it },
            switchRow("Démarrer en sourdine", null, mutedStart) { mutedStart = it },
        ))

        add(sec("Maintenance"))
        add(card(
            lrow(icon(R.drawable.ic_scan, WHITE, 20), vbox(tv("Rescanner", 15f, bold = true), tv("Détecte les nouvelles vidéos", 12f, MU)).lp(0, -2, 1f, l = 12))
                .apply { setOnClickListener { toast("Analyse…"); scan { toast("Scan terminé · ${library.size} vidéos") } } },
            lrow(icon(R.drawable.ic_export, WHITE, 20), vbox(tv("Exporter favoris, tags et notes", 15f, bold = true), tv("Fichier JSON", 12f, MU)).lp(0, -2, 1f, l = 12))
                .apply {
                    setOnClickListener {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "videoflows-export.json"), EXPORT)
                    }
                },
        ), b = 12)
    }

    private fun tagCounts() = library.filter(::visible).flatMap { it.allTags() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    private fun browseTags() {
        val counts = tagCounts().sortedBy { it.first.lowercase() }
        if (counts.isEmpty()) return toast("Pas encore de tags : tague une vidéo avec son bouton tag")
        val names = counts.map { it.first }
        val checked = BooleanArray(names.size)
        AlertDialog.Builder(this)
            .setTitle("Vidéos qui ont tous ces tags")
            .setMultiChoiceItems(counts.map { "#${it.first}  (${it.second})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Lire") { _, _ ->
                val sel = names.filterIndexed { i, _ -> checked[i] }
                if (sel.isNotEmpty()) setScope(Scope("tags", sel.joinToString(" + ") { "#$it" }, R.drawable.ic_tag) { it.allTags().containsAll(sel) })
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (page != "feed") goPage("feed") else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- Folders ----

    private fun label(tree: Uri) = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Stockage" }

    private fun pick(request: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), request)

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val tree = data.data ?: return
        when (requestCode) {
            EXPORT -> runCatching { contentResolver.openOutputStream(tree)!!.use { it.write(exportJson().toByteArray()) } }
                .onSuccess { toast("Export enregistré") }.onFailure { toast("Export impossible") }
            ADD_FOLDER -> {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (tree !in folders) folders += tree
                toast("${label(tree)} ajouté · scan en cours")
            }
            EXCLUDE_FOLDER -> if (tree !in excluded) excluded += tree
        }
        if (requestCode != EXPORT) { save(); scan() }
    }

    // ---- Scanning ----

    private class Found(val uri: Uri, val name: String, val folder: String, val size: Long, val date: Long, val root: Uri)

    // Walks the folders off the UI thread, swaps the feed in, then probes new videos for ratio/resolution
    private fun scan(done: (() -> Unit)? = null) {
        val trees = folders.toList()
        val skip = excluded.map { DocumentsContract.getTreeDocumentId(it) }.toSet()
        Thread {
            val found = mutableListOf<Found>()
            trees.forEach { walk(it, DocumentsContract.getTreeDocumentId(it), label(it), skip, found) }
            found.sortWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
            val result = found.map { f ->
                known.getOrPut(f.uri) { Clip(f.uri, f.name.substringBeforeLast('.'), f.folder) }
                    .also { it.folder = f.folder; it.size = f.size; it.date = f.date; it.root = f.root }
            }
            runOnUiThread {
                library = result
                // First scan of the session: fresh order, or back to the last video with "Dernier"
                if (!scanned) { scanned = true; applyFilter(true, if (start == "keep") lastClip() else null) } else applyFilter(false, playingClip)
                if (page != "feed") renderPage()
                save()
                done?.invoke()
            }

            val probed = result.filter { it.width == 0 }.mapNotNull { c -> probe(c.uri)?.let { c to it } }
            if (probed.isNotEmpty()) runOnUiThread {
                probed.forEach { (c, p) -> c.width = p[0].toInt(); c.height = p[1].toInt(); c.duration = p[2] }
                save()
                if (scope != null) applyFilter(false, playingClip) else pager.adapter!!.notifyItemRangeChanged(0, clips.size)
            }
        }.start()
    }

    private fun walk(tree: Uri, dir: String, path: String, skip: Set<String>, out: MutableList<Found>) {
        if (dir in skip) return
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        // A folder that vanished or lost its permission just yields nothing
        runCatching { contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir), cols, null, null, null) }
            .getOrNull()?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) walk(tree, id, "$path/${c.getString(1)}", skip, out)
                    else if (mime.startsWith("video/"))
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1), path, c.getLong(3), c.getLong(4), tree)
                }
            }
    }

    // [width, height, durationMs] as displayed (rotation applied), or null if the file can't be read
    private fun probe(uri: Uri): List<Long>? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            fun key(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotated = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 == 90L
            listOf(if (rotated) h else w, if (rotated) w else h, key(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } finally {
            r.release()
        }
    }.getOrNull()?.takeIf { it[0] > 0 }

    // ---- Persistence ----

    private fun exportJson() = JSONArray(library.map { it.toJson() }).toString(2)

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun save() {
        val inLibrary = library.toSet()
        val json = JSONObject()
            .put("folders", JSONArray(folders.map { it.toString() }))
            .put("off", JSONArray(off))
            .put("excluded", JSONArray(excluded.map { it.toString() }))
            .put("order", order).put("start", start)
            .put("autoNext", autoNext).put("loop", loop).put("remember", remember).put("mutedStart", mutedStart)
            .put("rate", rate.toDouble())
            .put("recentQ", JSONArray(recentQ))
            // Videos from removed folders are forgotten unless the user favorited, tagged or annotated them
            .put("clips", JSONArray(known.values.filter { it in inLibrary || it.favorite || it.tags.isNotEmpty() || it.notes.isNotEmpty() }
                .map { it.toJson() }))
        val out = store.startWrite()
        runCatching { out.write(json.toString().toByteArray()); store.finishWrite(out) }
            .onFailure { store.failWrite(out); toast("Enregistrement impossible") }
    }

    private fun load() {
        val json = runCatching { JSONObject(String(store.readFully())) }.getOrNull() ?: return
        json.optJSONArray("folders")?.strings()?.mapTo(folders, Uri::parse)
        json.optJSONArray("off")?.strings()?.let(off::addAll)
        json.optJSONArray("excluded")?.strings()?.mapTo(excluded, Uri::parse)
        order = json.optString("order", if (json.optBoolean("shuffle")) "random" else "smart").takeIf { it in ORDERS } ?: "smart"
        start = json.optString("start", "smart")
        autoNext = json.optBoolean("autoNext", true)
        loop = json.optBoolean("loop", false)
        remember = json.optBoolean("remember", true)
        mutedStart = json.optBoolean("mutedStart", false)
        rate = json.optDouble("rate", 1.0).toFloat()
        json.optJSONArray("recentQ")?.strings()?.let(recentQ::addAll)
        val saved = json.optJSONArray("clips") ?: return
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            val clip = Clip(Uri.parse(o.getString("uri")), o.getString("title"), o.optString("folder")).apply {
                favorite = o.optBoolean("favorite", o.optBoolean("liked")) // "liked"/"comments": V0.0.2 names
                o.optJSONArray("tags")?.strings()?.let(tags::addAll)
                (o.optJSONArray("notes") ?: o.optJSONArray("comments"))?.let { a ->
                    // Plain strings up to V0.0.4, {t, text} since
                    for (k in 0 until a.length()) notes += a.optJSONObject(k)?.let { Note(it.optLong("t", -1), it.optString("text")) } ?: Note(-1, a.getString(k))
                }
                width = o.optInt("width")
                height = o.optInt("height")
                duration = o.optLong("duration")
                size = o.optLong("size")
                lastPlayed = o.optLong("lastPlayed")
                pos = o.optLong("pos")
                date = o.optLong("date")
                hidden = o.optBoolean("hidden")
            }
            known[clip.uri] = clip
        }
    }

    // ---- Playback ----

    private fun savePos() {
        val c = playingClip ?: return
        val t = player.time
        if (t > 0) c.pos = if (player.length > 0 && t >= player.length - 1000) 0 else t
    }

    // One player for the whole feed: it is re-attached to whichever page is on screen
    private fun play(pos: Int) {
        val clip = clips.getOrNull(pos) ?: return
        val page = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pos)?.itemView ?: return
        if (clip === playingClip && page === attachedTo) return
        savePos()
        player.stop()
        player.detachViews()
        fd?.close()
        fd = runCatching { contentResolver.openFileDescriptor(clip.uri, "r") }.getOrNull()
            ?: return toast("Impossible d'ouvrir cette vidéo")
        playingClip = clip
        attachedTo = page
        player.attachViews(page.findViewById<VLCVideoLayout>(R.id.video), null, false, false)
        Media(vlc, fd!!.fileDescriptor).run {
            if (loop) addOption(":input-repeat=65535")
            if (remember && clip.pos > 0 && (clip.duration == 0L || clip.pos < clip.duration - 1000)) addOption(":start-time=${clip.pos / 1000.0}")
            player.media = this
            release()
        }
        scrub.progress = 0
        paused = false
        findViewById<View>(R.id.paused).visibility = View.GONE
        if (this.page == "feed") player.play()
        player.rate = rate
        player.setVolume(if (muted) 0 else 100)
        clip.lastPlayed = System.currentTimeMillis()
        save()
    }

    private fun setPaused(p: Boolean) {
        if (playingClip == null) return
        paused = p
        if (p) player.pause() else player.play()
        findViewById<View>(R.id.paused).visibility = if (p) View.VISIBLE else View.GONE
    }

    // Big heart where the user double-tapped
    private fun burst(e: MotionEvent) {
        val h = findViewById<ImageView>(R.id.burst)
        h.animate().cancel()
        h.x = e.x - dp(48); h.y = e.y - dp(45)
        h.rotation = Random.nextInt(-15, 15).toFloat()
        h.scaleX = .3f; h.scaleY = .3f; h.alpha = 0f; h.visibility = View.VISIBLE
        h.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(140).withEndAction {
            h.animate().translationYBy(-dp(70).toFloat()).scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(600).withEndAction { h.visibility = View.GONE }
        }
    }

    private fun renderCurrent() = pager.adapter!!.notifyItemChanged(pager.currentItem)

    override fun onResume() { super.onResume(); if (playingClip != null && !paused && page == "feed") player.play() }
    override fun onPause() { super.onPause(); savePos(); save(); player.pause() }
    override fun onDestroy() {
        player.release()
        vlc.release()
        fd?.close()
        super.onDestroy()
    }

    // ---- View helpers ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun size(bytes: Long) = if (bytes >= 1 shl 30) "%.1f Go".format(bytes / 1073741824.0) else "%.0f Mo".format(bytes / 1048576.0)

    private fun time(ms: Long) = (max(ms, 0) / 1000).let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color); if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun hue(s: String) = (abs(s.hashCode()) % 360).toFloat()

    private fun gradient(c: Clip) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
        Color.HSVToColor(floatArrayOf(hue(c.title), .65f, .42f)), Color.HSVToColor(floatArrayOf((hue(c.title) + 50) % 360, .6f, .14f))))

    private fun folderColor(f: String) = Color.HSVToColor(floatArrayOf(hue(f.substringAfterLast('/')), .6f, .95f))

    // Width/height raw (-1, -2 or already in px), margins in dp
    private fun <T : View> T.lp(w: Int = -1, h: Int = -2, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    }

    private fun tv(s: CharSequence, size: Float = 14f, color: Int = WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun icon(res: Int, color: Int, size: Int = 22) = ImageView(this).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(color) }.lp(dp(size), dp(size))

    private fun iconButton(res: Int, desc: String, color: Int = WHITE, onClick: () -> Unit) = icon(res, color, 36).apply {
        setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = desc; setOnClickListener { onClick() }
    }

    private fun closeButton(size: Int, desc: String, color: Int = WHITE, onClick: () -> Unit) = icon(R.drawable.ic_close, color, size).apply {
        val p = size / 4
        setPadding(dp(p), dp(p), dp(p), dp(p)); background = rounded(0x29ffffff, size / 2); contentDescription = desc; setOnClickListener { onClick() }
    }

    private fun vbox(vararg views: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; views.forEach(::addView) }

    private fun hbox(vararg views: View) = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; views.forEach(::addView) }

    private fun button(s: String, bg: Int, small: Boolean = false, onClick: () -> Unit) = tv(s, if (small) 13f else 14f, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = dp(if (small) 34 else 44); setPadding(dp(if (small) 12 else 18), 0, dp(if (small) 12 else 18), 0)
        background = rounded(bg, if (small) 8 else 10); setOnClickListener { onClick() }
    }

    private fun chip(s: String, on: Boolean = false, onClick: (() -> Unit)? = null) = tv(s, 13f, if (on) PINK_TX else WHITE, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(34); setPadding(dp(12), 0, dp(12), 0)
        background = if (on) rounded(0x29fe2c55, 17).apply { setStroke(dp(1), AC) } else rounded(S1, 17)
        onClick?.let { f -> setOnClickListener { f() } }
    }

    private fun flow(views: List<View>) = Flow(this, dp(8)).apply { views.forEach(::addView) }

    private fun sec(s: String) = tv(s.uppercase(), 13f, MU, true).apply { letterSpacing = .05f }.lp(t = 20, b = 8)

    private fun grid(views: List<View>, cols: Int = 2, gap: Int = 10) = vbox(*views.chunked(cols).mapIndexed { i, row ->
        LinearLayout(this).apply {
            row.forEachIndexed { j, v -> addView(v, LinearLayout.LayoutParams(0, v.layoutParams?.height ?: -2, 1f).apply { if (j > 0) marginStart = dp(gap) }) }
            repeat(cols - row.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(gap) }) }
        }.lp(t = if (i > 0) gap else 0)
    }.toTypedArray())

    private fun card(vararg rows: View) = vbox(*rows).apply {
        background = rounded(S1, 14)
        dividerDrawable = GradientDrawable().apply { setColor(LINE); setSize(1, dp(1)) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    private fun lrow(vararg views: View) = hbox(*views).apply { minimumHeight = dp(52); setPadding(dp(14), dp(10), dp(14), dp(10)) }

    private fun switch(on: Boolean, set: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(WHITE, 0xffc9c9d2.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(AC, 0xff55555f.toInt()))
        setOnCheckedChangeListener { _, c -> set(c) }
    }

    private fun switchRow(title: String, desc: String?, on: Boolean, set: (Boolean) -> Unit): View {
        val sw = switch(on) { set(it); save() }
        return lrow(vbox(tv(title, 15f, bold = true), *listOfNotNull(desc?.let { tv(it, 12f, MU) }).toTypedArray()).lp(0, -2, 1f, r = 8), sw)
            .apply { setOnClickListener { sw.toggle() } }
    }

    private fun seg(options: List<Pair<String, String>>, current: String, set: (String) -> Unit) = hbox(*options.map { (k, l) ->
        tv(l, 12f, if (k == current) 0xff000000.toInt() else MU, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(32); setPadding(dp(10), 0, dp(10), 0)
            background = if (k == current) rounded(WHITE, 8) else null
            setOnClickListener { set(k) }
        }
    }.toTypedArray()).apply { background = rounded(S2, 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }

    // Thumbnail stand-in: a color from the title, duration, how far it was watched
    private fun tile(c: Clip, w: Int, h: Int) = FrameLayout(this).apply {
        background = gradient(c).apply { cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        if (c.duration > 0) addView(tv(time(c.duration), 10f, bold = true).apply { background = rounded(0x99000000.toInt(), 4); setPadding(dp(4), 0, dp(4), 0) },
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        if (c.pos > 0 && c.duration > 0) addView(View(this@MainActivity).apply { setBackgroundColor(AC) },
            FrameLayout.LayoutParams((dp(w) * min(c.pos, c.duration) / c.duration).toInt(), dp(3), Gravity.BOTTOM))
    }

    private fun clipRow(c: Clip, lines: List<CharSequence>, onClick: () -> Unit) = hbox(
        tile(c, 54, 80).lp(dp(54), dp(80), r = 12),
        vbox(tv(c.title, 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            *lines.map { tv(it, 12f, MU).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) } }.toTypedArray()).lp(0, -2, 1f),
        *listOfNotNull(if (c.favorite) icon(R.drawable.ic_heart, AC, 16) else null).toTypedArray(),
    ).apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { onClick() } }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; setSingleLine(); textSize = 15f; setTextColor(WHITE); setHintTextColor(MU); background = null; setPadding(0, 0, 0, 0)
    }

    private fun EditText.onText(f: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(e: Editable) = f()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })

    // ---- Bottom sheets ----

    private inner class Sheet(val dialog: Dialog, val top: LinearLayout, val body: LinearLayout, val foot: LinearLayout) {
        fun footShown() { foot.visibility = if (foot.childCount > 0) View.VISIBLE else View.GONE }
    }

    private fun sheet(title: String?, full: Boolean = false, onClose: () -> Unit = {}, fill: Sheet.() -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val s = Sheet(d, vbox(), vbox().apply { setPadding(dp(16), 0, dp(16), dp(16)) }, hbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(14))
            // Top border only
            background = LayerDrawable(arrayOf(GradientDrawable().apply { setStroke(dp(1), LINE) })).apply { setLayerInset(0, -dp(2), 0, -dp(2), -dp(2)) }
        })
        if (title != null) s.top.addView(hbox(
            View(this).lp(dp(40), 1),
            tv(title, 17f, bold = true).apply { gravity = Gravity.CENTER }.lp(0, -2, 1f),
            iconButton(R.drawable.ic_close, "Fermer", MU) { d.dismiss() }.lp(dp(40), dp(40)),
        ).apply { setPadding(dp(16), dp(4), dp(16), dp(10)) })
        s.fill()
        s.footShown()
        val grab = View(this).apply { background = rounded(0xff45454f.toInt(), 2) }.lp(dp(38), dp(4), t = 10, b = 4)
        (grab.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        d.setContentView(vbox(grab, s.top,
            ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(s.body) }.lp(-1, if (full) 0 else -2, 1f), s.foot))
        d.setOnDismissListener { onClose() }
        d.window!!.apply {
            val r = dp(22).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(SH); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) })
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        d.window!!.setLayout(-1, if (full) (resources.displayMetrics.heightPixels * .92).toInt() else -2)
    }

    private fun orderSheet() = sheet("Ordre de lecture") {
        ORDERS.forEach { (k, o) ->
            val on = order == k
            body.addView(hbox(
                FrameLayout(this@MainActivity).apply {
                    background = rounded(if (on) 0x2efe2c55 else S1, 12)
                    addView(icon(o.icon, if (on) AC else WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }.lp(dp(40), dp(40), r = 14),
                vbox(tv(o.label, 15f, bold = true), tv(o.desc, 12f, MU).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(2), 0, 0) }).lp(0, -2, 1f, r = 12),
                tv(if (on) "●" else "", 12f, AC).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), if (on) AC else 0xff55555f.toInt()) } }.lp(dp(22), dp(22)),
            ).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = if (on) rounded(S1, 12) else null
                setOnClickListener { order = k; save(); dialog.dismiss(); renderOrder(); applyFilter(); toast("Ordre : ${o.label}") }
            })
        }
        body.addView(button("Remélanger maintenant", S2) { dialog.dismiss(); applyFilter(); toast("Feed remélangé") }.lp(t = 10))
    }

    private fun highlight(s: String, q: String): CharSequence {
        val i = s.lowercase().indexOf(q)
        if (q.isEmpty() || i < 0) return s
        return SpannableString(s).apply { setSpan(BackgroundColorSpan(0x4dfe2c55), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun searchSheet() = sheet(null, full = true) {
        val input = field("Titre, dossier, tag ou note").apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
        top.addView(hbox(
            hbox(icon(R.drawable.ic_search, MU, 18), input.lp(0, -2, 1f, l = 10)).apply { background = rounded(S1, 10); setPadding(dp(14), 0, dp(14), 0) }.lp(0, dp(44), 1f, r = 8),
            button("Annuler", S2, small = true) { dialog.dismiss() }.lp(-2, dp(44)),
        ).apply { setPadding(dp(16), dp(4), dp(16), dp(10)) })
        var found = listOf<Clip>()
        fun playAll(at: Clip?) {
            val q = input.text.trim().toString()
            recentQ.remove(q); recentQ.add(0, q); while (recentQ.size > 6) recentQ.removeAt(recentQ.size - 1)
            val set = found.toSet()
            dialog.dismiss()
            setScope(Scope("search", "« $q »", R.drawable.ic_search) { it in set }, at)
        }
        fun update() {
            body.removeAllViews(); foot.removeAllViews()
            val q = input.text.trim().toString().lowercase()
            if (q.isEmpty()) {
                found = emptyList()
                if (recentQ.isNotEmpty()) { body.addView(sec("Récentes")); body.addView(flow(recentQ.map { r -> chip("🕘 $r") { input.setText(r); input.setSelection(r.length) } })) }
                body.addView(sec("Dossiers"))
                body.addView(flow(library.filter(::visible).groupingBy { it.folder }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (f, n) -> chip("${f.substringAfterLast('/')}  $n") { dialog.dismiss(); setScope(folderScope(f)) } }))
                body.addView(sec("Tags"))
                body.addView(flow(tagCounts().take(10).map { (t, n) -> chip("#$t  $n") { dialog.dismiss(); setScope(tagScope(t)) } }))
            } else {
                val tq = q.trimStart('#')
                val res = library.filter(::visible).mapNotNull { c ->
                    when {
                        c.title.lowercase().contains(q) -> c to "title"
                        c.folder.lowercase().contains(q) -> c to "folder"
                        tq.isNotEmpty() && c.allTags().any { it.lowercase().contains(tq) } -> c to "tag"
                        c.notes.any { it.text.lowercase().contains(q) } -> c to "note"
                        else -> null
                    }
                }
                found = res.map { it.first }
                if (res.isEmpty()) body.addView(tv("Rien pour « ${input.text.trim()} »", 14f, MU).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(40)) })
                else {
                    body.addView(sec("${res.size} vidéo${if (res.size > 1) "s" else ""}"))
                    res.take(60).forEach { (c, why) ->
                        val lines = mutableListOf(if (why == "folder") highlight(c.folder, q) else c.folder)
                        if (why == "tag") lines += c.allTags().joinToString(" ") { "#$it" }.let { highlight(it, tq) }
                        if (why == "note") c.notes.first { it.text.lowercase().contains(q) }.let { n -> lines += (if (n.t >= 0) "${time(n.t)} · " else "").let { p -> p + n.text }.let { highlight(it, q) } }
                        body.addView(clipRow(c, lines) { playAll(c) }.apply { ((getChildAt(1) as LinearLayout).getChildAt(0) as TextView).text = highlight(c.title, q) })
                    }
                    foot.addView(button("▶  Lire les ${res.size} résultats", AC) { playAll(null) }.lp(0, -2, 1f))
                }
            }
            footShown()
        }
        input.onText(::update)
        input.setOnEditorActionListener { _, _, _ -> if (found.isNotEmpty()) playAll(null); true }
        update()
        input.requestFocus()
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun notesSheet(c: Clip) {
        val resume = !paused && c === playingClip
        if (resume) setPaused(true)
        val at = if (c === playingClip) max(player.time, 0) else c.pos
        sheet("Notes", onClose = { renderCurrent(); if (resume && c === playingClip && paused) setPaused(false) }) {
            fun fill() {
                body.removeAllViews()
                if (c.notes.isEmpty()) body.addView(vbox(tv("Pas encore de notes", 15f, bold = true),
                    tv("Une note est liée au moment de la vidéo :\ntape le minutage pour y revenir.", 12f, MU).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                    .apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(28), 0, dp(28)) })
                c.notes.sortedBy { it.t }.forEach { n ->
                    body.addView(hbox(
                        *listOfNotNull(if (n.t >= 0) tv("▶ ${time(n.t)}", 12f, AC2, true).apply {
                            gravity = Gravity.CENTER; minHeight = dp(26); setPadding(dp(8), 0, dp(8), 0); background = rounded(0x2425f4ee, 6)
                            setOnClickListener { if (c === playingClip) { player.setTime(n.t); setPaused(false) } else c.pos = n.t; dialog.dismiss() }
                        }.lp(-2, -2, r = 10) else null).toTypedArray(),
                        tv(n.text, 14f).apply { setLineSpacing(0f, 1.3f) }.lp(0, -2, 1f),
                        iconButton(R.drawable.ic_trash, "Supprimer la note", MU) { c.notes.remove(n); save(); fill() },
                    ).apply { setPadding(0, dp(8), 0, dp(8)) })
                }
            }
            fill()
            val input = field("Ajouter une note à ${time(at)}…").apply { imeOptions = EditorInfo.IME_ACTION_SEND }
            fun addNote() {
                val t = input.text.trim().toString()
                if (t.isEmpty()) return
                c.notes += Note(at, t); input.setText(""); save(); fill()
            }
            input.setOnEditorActionListener { _, _, _ -> addNote(); true }
            foot.addView(tv(time(at), 12f, AC2, true).apply { gravity = Gravity.CENTER; minHeight = dp(26); setPadding(dp(8), 0, dp(8), 0); background = rounded(0x2425f4ee, 6) }.lp(-2, -2, r = 10))
            foot.addView(input.apply { background = rounded(S1, 10); setPadding(dp(14), 0, dp(14), 0) }.lp(0, dp(44), 1f, r = 10))
            foot.addView(button("Ajouter", AC, small = true) { addNote() }.lp(-2, dp(44)))
        }
    }

    private fun tagsSheet(c: Clip) = sheet("Tags", onClose = { renderCurrent(); if (scope != null) applyFilter(false, playingClip) }) {
        fun fill() {
            body.removeAllViews()
            body.addView(sec("Automatiques").lp(t = 4, b = 8))
            body.addView(flow((c.allTags() - c.tags).map { tv("#$it", 13f, MU, true).apply { gravity = Gravity.CENTER; minHeight = dp(34); setPadding(dp(12), 0, dp(12), 0); background = rounded(S1, 17) } }))
            body.addView(sec("Les tiens"))
            body.addView(if (c.tags.isEmpty()) tv("Aucun tag perso.", 12f, MU) else flow(c.tags.map { t -> chip("#$t  ✕", on = true) { c.tags.remove(t); save(); fill() } }))
            val sugg = library.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.filter { it !in c.tags }
            if (sugg.isNotEmpty()) {
                body.addView(sec("Suggestions"))
                body.addView(flow(sugg.take(20).map { t -> chip("+ #$t") { c.tags += t; save(); fill() } }))
            }
        }
        fill()
        val input = field("nouveau-tag").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        fun addTag() {
            val t = input.text.trim().trimStart('#').replace(Regex("\\s+"), "-")
            if (t.isEmpty()) return
            c.tags += t; input.setText(""); save(); fill()
        }
        input.setOnEditorActionListener { _, _, _ -> addTag(); true }
        foot.addView(hbox(tv("#", 15f, MU), input.lp(0, -2, 1f, l = 6)).apply { background = rounded(S1, 10); setPadding(dp(14), 0, dp(14), 0) }.lp(0, dp(44), 1f, r = 10))
        foot.addView(button("Ajouter", AC, small = true) { addTag() }.lp(-2, dp(44)))
    }

    private fun infoSheet(c: Clip) = sheet("Infos") {
        // fps and codec come from libVLC, so only for the video being played
        val track = if (c === playingClip) player.currentVideoTrack else null
        val fps = track?.takeIf { it.frameRateDen > 0 }?.let { "%.2f i/s".format(it.frameRateNum.toDouble() / it.frameRateDen) }
        val ratio = c.allTags().firstOrNull { it.contains(':') }
        val cur = if (c === playingClip) max(player.time, 0) else c.pos
        body.addView(hbox(tile(c, 60, 90).lp(dp(60), dp(90), r = 12),
            vbox(tv(c.title, 16f, bold = true), tv("${time(cur)} / ${time(c.duration)}", 12f, MU).apply { setPadding(0, dp(4), 0, 0) }).lp(0, -2, 1f))
            .apply { setPadding(0, dp(4), 0, dp(14)) })
        val rows = listOfNotNull(
            "Dossier" to c.folder,
            if (c.width > 0) "Résolution" to "${c.width} × ${c.height}" + (ratio?.let { " · $it" } ?: "") else null,
            fps?.let { "Images" to it },
            track?.codec?.let { "Codec" to it },
            if (c.duration > 0) "Durée" to time(c.duration) else null,
            "Taille" to size(c.size),
            if (c.date > 0) "Date" to DateFormat.getDateInstance(DateFormat.MEDIUM).format(c.date) else null,
            "Vue" to if (c.lastPlayed > 0) DateUtils.getRelativeTimeSpanString(c.lastPlayed).toString() else "Jamais",
            "Lecteur" to "VLC (libVLC)",
        )
        body.addView(card(*rows.map { (k, v) ->
            lrow(tv(k, 14f, MU).lp(dp(92), -2), tv(v, 14f, bold = true).apply { gravity = Gravity.END }.lp(0, -2, 1f))
        }.toTypedArray()))
        val name = c.folder.substringAfterLast('/')
        body.addView(card(
            lrow(icon(R.drawable.ic_folder, folderColor(c.folder), 20), tv("Lire tout le dossier $name", 15f, bold = true).lp(0, -2, 1f, l = 12), icon(R.drawable.ic_chev, MU, 18))
                .apply { setOnClickListener { dialog.dismiss(); setScope(folderScope(c.folder), c) } },
            lrow(icon(R.drawable.ic_old, WHITE, 20), tv("Revoir depuis le début", 15f, bold = true).lp(0, -2, 1f, l = 12))
                .apply { setOnClickListener { dialog.dismiss(); if (c === playingClip) { player.setTime(0); setPaused(false) } else c.pos = 0 } },
            lrow(icon(R.drawable.ic_ban, RED, 20), tv("Masquer cette vidéo du feed", 15f, RED, true).lp(0, -2, 1f, l = 12))
                .apply {
                    setOnClickListener {
                        dialog.dismiss()
                        c.hidden = true
                        save()
                        val i = clips.indexOf(c)
                        applyFilter(false, clips.getOrNull(i + 1) ?: clips.getOrNull(i - 1))
                        toast("Vidéo masquée · à rétablir dans Biblio")
                    }
                },
        ).lp(t = 14))
    }

    // ---- Feed pages ----

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent) = true.also { setPaused(!paused) }
            override fun onDoubleTap(e: MotionEvent) = true.also {
                burst(e)
                playingClip?.takeIf { !it.favorite }?.let { it.favorite = true; save(); renderCurrent() }
            }
        })
    }

    inner class Feed : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = clips.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.page, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val c = clips[position]
            fun text(id: Int) = v.findViewById<TextView>(id)
            val inFolder = scope?.key == "folder:${c.folder}"
            val color = folderColor(c.folder)

            text(R.id.caption).text = c.title
            text(R.id.folder).apply {
                text = c.folder; compoundDrawableTintList = ColorStateList.valueOf(color)
                setOnClickListener { setScope(folderScope(c.folder), c) }
            }
            text(R.id.tags).apply {
                text = c.allTags().joinToString("  ") { "#$it" }
                visibility = if (c.allTags().isEmpty()) View.GONE else View.VISIBLE
            }
            text(R.id.folder_avatar).apply {
                text = c.folder.substringAfterLast('/').take(1).uppercase(); setTextColor(color)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color and 0x22ffffff or 0x22000000); setStroke(dp(2), WHITE) }
            }
            text(R.id.folder_badge).apply {
                text = if (inFolder) "✓" else "+"
                setTextColor(if (inFolder) 0xff111111.toInt() else WHITE)
                backgroundTintList = ColorStateList.valueOf(if (inFolder) WHITE else AC)
                contentDescription = if (inFolder) "Tous les dossiers" else "Lire ce dossier"
            }
            val toggleFolder = View.OnClickListener { setScope(if (inFolder) null else folderScope(c.folder), c) }
            text(R.id.folder_avatar).setOnClickListener(toggleFolder)
            text(R.id.folder_badge).setOnClickListener(toggleFolder)

            text(R.id.like).apply {
                text = if (c.favorite) "Favori" else "Fav"
                compoundDrawableTintList = ColorStateList.valueOf(if (c.favorite) AC else WHITE)
                setOnClickListener {
                    c.favorite = !c.favorite
                    save()
                    renderCurrent()
                    toast(if (c.favorite) "Ajoutée aux favoris" else "Retirée des favoris")
                }
            }
            text(R.id.notes).apply { text = "${c.notes.size}"; setOnClickListener { notesSheet(c) } }
            text(R.id.tag).apply { text = "${c.allTags().size}"; setOnClickListener { tagsSheet(c) } }
            text(R.id.info).setOnClickListener { infoSheet(c) }
            text(R.id.speed).apply {
                text = "▶ ${if (rate % 1 == 0f) rate.toInt() else rate}×"
                setOnClickListener {
                    rate = RATES[(RATES.indexOf(rate) + 1) % RATES.size]
                    player.rate = rate
                    save()
                    renderCurrent()
                }
            }
            v.findViewById<ImageView>(R.id.mute).apply {
                setImageResource(if (muted) R.drawable.ic_vol_off else R.drawable.ic_vol)
                contentDescription = if (muted) "Activer le son" else "Couper le son"
                setOnClickListener {
                    muted = !muted
                    player.setVolume(if (muted) 0 else 100)
                    renderCurrent()
                    toast(if (muted) "Son coupé" else "Son activé")
                }
            }
            text(R.id.count).text = "${position + 1} / ${clips.size}"
            v.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

            // A recycled page may be the one on screen: make sure the player follows it
            if (position == pager.currentItem) pager.post { play(position) }
        }
    }
}
