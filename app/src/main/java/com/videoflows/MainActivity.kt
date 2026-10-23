package com.videoflows

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

private const val EXPORT = 2
private const val ADD_FOLDER = 3
private const val EXCLUDE_FOLDER = 4
private const val PINK = 0xffff315c.toInt()
private const val WHITE = 0xffffffff.toInt()
private val RATES = listOf(1f, 1.5f, 2f, 0.5f)
private val RATIOS = listOf("9:16" to 9 / 16.0, "3:4" to 3 / 4.0, "1:1" to 1.0, "4:3" to 4 / 3.0, "16:9" to 16 / 9.0, "21:9" to 21 / 9.0)

class MainActivity : Activity() {
    class Clip(val uri: Uri, val title: String, var folder: String) {
        var favorite = false
        val tags = sortedSetOf<String>()
        val notes = mutableListOf<String>()
        // Probed once and cached in feed.json, so auto tags work without playing the video
        var width = 0
        var height = 0
        var duration = 0L
        var size = 0L
        var lastPlayed = 0L

        // User tags plus automatic ones from the video itself (ratio, resolution)
        fun allTags(): Set<String> {
            if (width <= 0 || height <= 0) return tags
            val ratio = RATIOS.minBy { abs(ln(width.toDouble() / height / it.second)) }.first
            val short = min(width, height)
            val res = when { short >= 2160 -> "4K"; short >= 1080 -> "1080p"; short >= 720 -> "720p"; else -> "SD" }
            return tags + ratio + res
        }

        fun toJson(): JSONObject = JSONObject().put("uri", uri.toString()).put("title", title).put("folder", folder)
            .put("favorite", favorite).put("tags", JSONArray(tags)).put("notes", JSONArray(notes))
            .put("width", width).put("height", height).put("duration", duration).put("size", size).put("lastPlayed", lastPlayed)
    }

    private var library = listOf<Clip>()          // everything found in the folders
    private var clips = listOf<Clip>()            // what the feed shows, after filters/shuffle
    private val folders = mutableListOf<Uri>()   // folder trees scanned for videos, subfolders included
    private val excluded = mutableListOf<Uri>()  // subfolders skipped while scanning
    private val known = mutableMapOf<Uri, Clip>() // state + probed info, kept across rescans
    // Folders, settings and per-video state as JSON; AtomicFile so a crash mid-write can't corrupt it
    private val store by lazy { AtomicFile(File(filesDir, "feed.json")) }

    private var favoritesOnly = false
    private var folderFilter: String? = null
    private val tagFilter = sortedSetOf<String>()
    private var query = ""
    private var shuffle = false
    private var rate = 1f

    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var pager: ViewPager2
    private var fd: ParcelFileDescriptor? = null
    private var playingClip: Clip? = null
    private var attachedTo: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        vlc = LibVLC(this)
        player = MediaPlayer(vlc).apply { videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN }

        pager = findViewById(R.id.pager)
        pager.adapter = Feed()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { pager.post { play(position) } }
        })
        load()
        scan()

        findViewById<View>(R.id.favorites).setOnClickListener { setFavoritesOnly(true) }
        findViewById<View>(R.id.all).setOnClickListener { setFavoritesOnly(false) }
        setFavoritesOnly(false)
        findViewById<View>(R.id.shuffle).setOnClickListener {
            shuffle = !shuffle
            save()
            applyFilter()
            toast(if (shuffle) "Shuffle on" else "Shuffle off")
        }
        renderShuffle()
        findViewById<View>(R.id.search).setOnClickListener { showSearch() }
        findViewById<View>(R.id.filter).setOnClickListener { folderFilter = null; tagFilter.clear(); query = ""; applyFilter() }

        findViewById<View>(R.id.nav_home).setOnClickListener { if (clips.isNotEmpty()) pager.currentItem = 0 }
        findViewById<View>(R.id.nav_tags).setOnClickListener { browseTags() }
        findViewById<View>(R.id.nav_create).setOnClickListener { showFolders() }
        findViewById<View>(R.id.nav_recent).setOnClickListener { showRecent() }
        findViewById<View>(R.id.nav_library).setOnClickListener { showLibrary() }
    }

    // ---- Filters ----

    private fun setFavoritesOnly(on: Boolean) {
        favoritesOnly = on
        listOf(R.id.favorites to on, R.id.all to !on).forEach { (id, active) ->
            findViewById<TextView>(id).apply {
                setTextColor(if (active) WHITE else 0xffd4d4d4.toInt())
                paint.isFakeBoldText = active
                setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, if (active) R.drawable.tab_underline else R.drawable.tab_underline_none)
            }
        }
        applyFilter()
    }

    private fun renderShuffle() {
        findViewById<ImageView>(R.id.shuffle).imageTintList = ColorStateList.valueOf(if (shuffle) PINK else WHITE)
    }

    private fun matches(c: Clip): Boolean {
        val q = query.lowercase()
        return (!favoritesOnly || c.favorite) && (folderFilter == null || c.folder == folderFilter) &&
            c.allTags().containsAll(tagFilter) &&
            (q.isEmpty() || c.title.lowercase().contains(q) || c.folder.lowercase().contains(q) ||
                c.allTags().any { it.lowercase().contains(q) } || c.notes.any { it.lowercase().contains(q) })
    }

    // Rebuilds the feed and keeps the current video on screen when it still matches
    private fun applyFilter() {
        val current = playingClip
        clips = library.filter(::matches).let { if (shuffle) it.shuffled() else it }
        renderShuffle()
        val parts = listOfNotNull(folderFilter?.let { "📁 $it" }, tagFilter.takeIf { it.isNotEmpty() }?.joinToString(" + ") { "#$it" },
            query.takeIf { it.isNotEmpty() }?.let { "“$it”" })
        findViewById<TextView>(R.id.filter).apply {
            text = parts.joinToString("  ·  ") + "  ✕"
            visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.empty).apply {
            text = if (library.isEmpty()) "Tap + to add folders" else "No videos match"
            visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        }
        if (clips.isEmpty()) { player.stop(); player.detachViews(); playingClip = null; attachedTo = null }
        pager.adapter!!.notifyDataSetChanged()
        pager.setCurrentItem(clips.indexOf(current).coerceAtLeast(0), false)
        pager.post { play(pager.currentItem) }
    }

    private fun showSearch() {
        val input = EditText(this).apply { setText(query); hint = "Title, folder, tag or note"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ -> query = input.text.trim().toString(); applyFilter() }
            .setNeutralButton("Clear") { _, _ -> query = ""; applyFilter() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun browseTags() {
        val counts = library.flatMap { it.allTags() }.groupingBy { it }.eachCount().toSortedMap()
        if (counts.isEmpty()) return toast("No tags yet — tag a video with its tag button")
        val names = counts.keys.toList()
        val checked = names.map { it in tagFilter }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Show videos with all of…")
            .setMultiChoiceItems(names.map { "#$it  (${counts[it]})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Show") { _, _ ->
                tagFilter.clear()
                names.filterIndexedTo(tagFilter) { i, _ -> checked[i] }
                applyFilter()
            }
            .setNeutralButton("Clear") { _, _ -> tagFilter.clear(); applyFilter() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Library: folders, recent, stats ----

    private fun label(tree: Uri) = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Storage" }

    private fun showFolders() {
        val rows = folders.map { "📁  ${label(it)}" } + excluded.map { "⛔  ${label(it)}" }
        AlertDialog.Builder(this)
            .setTitle(if (rows.isEmpty()) "Add a folder to scan" else "Folders — tap to remove")
            .setItems(rows.toTypedArray()) { _, i ->
                if (i < folders.size) runCatching {
                    contentResolver.releasePersistableUriPermission(folders.removeAt(i), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else excluded.removeAt(i - folders.size)
                save()
                scan()
            }
            .setPositiveButton("Add") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), ADD_FOLDER) }
            .setNeutralButton("Exclude") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), EXCLUDE_FOLDER) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRecent() {
        val recent = library.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(30)
        if (recent.isEmpty()) return toast("Nothing watched yet")
        AlertDialog.Builder(this)
            .setTitle("Recently watched")
            .setItems(recent.map { it.title }.toTypedArray()) { _, i ->
                val c = recent[i]
                if (c !in clips) { folderFilter = null; tagFilter.clear(); query = ""; setFavoritesOnly(false) }
                pager.setCurrentItem(clips.indexOf(c), false)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLibrary() {
        AlertDialog.Builder(this)
            .setTitle("Library")
            .setMessage("${library.size} videos in ${library.map { it.folder }.distinct().size} folders\n" +
                "${library.count { it.favorite }} favorites · ${library.count { it.tags.isNotEmpty() }} tagged · " +
                "${library.sumOf { it.notes.size }} notes\n${mb(library.sumOf { it.size })} total")
            .setPositiveButton("Export") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "videoflows-export.json"), EXPORT)
            }
            .setNeutralButton("Rescan") { _, _ -> scan() }
            .setNegativeButton("Close", null)
            .show()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val tree = data.data ?: return
        when (requestCode) {
            EXPORT -> runCatching { contentResolver.openOutputStream(tree)!!.use { it.write(exportJson().toByteArray()) } }
                .onSuccess { toast("Exported") }.onFailure { toast("Export failed") }
            ADD_FOLDER -> {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (tree !in folders) folders += tree
            }
            EXCLUDE_FOLDER -> if (tree !in excluded) excluded += tree
        }
        if (requestCode != EXPORT) { save(); scan() }
    }

    // ---- Scanning ----

    private class Found(val uri: Uri, val name: String, val folder: String, val size: Long)

    // Walks the folders off the UI thread, swaps the feed in, then probes new videos for ratio/resolution
    private fun scan() {
        val trees = folders.toList()
        val skip = excluded.map { DocumentsContract.getTreeDocumentId(it) }.toSet()
        Thread {
            val found = mutableListOf<Found>()
            trees.forEach { walk(it, DocumentsContract.getTreeDocumentId(it), label(it), skip, found) }
            found.sortWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
            val scanned = found.map { f ->
                known.getOrPut(f.uri) { Clip(f.uri, f.name.substringBeforeLast('.'), f.folder) }.also { it.folder = f.folder; it.size = f.size }
            }
            runOnUiThread { library = scanned; applyFilter(); save() }

            val probed = scanned.filter { it.width == 0 }.mapNotNull { c -> probe(c.uri)?.let { c to it } }
            if (probed.isNotEmpty()) runOnUiThread {
                probed.forEach { (c, p) -> c.width = p[0].toInt(); c.height = p[1].toInt(); c.duration = p[2] }
                save()
                if (tagFilter.isNotEmpty() || query.isNotEmpty()) applyFilter() else pager.adapter!!.notifyDataSetChanged()
            }
        }.start()
    }

    private fun walk(tree: Uri, dir: String, path: String, skip: Set<String>, out: MutableList<Found>) {
        if (dir in skip) return
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        // A folder that vanished or lost its permission just yields nothing
        runCatching { contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir), cols, null, null, null) }
            .getOrNull()?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) walk(tree, id, "$path/${c.getString(1)}", skip, out)
                    else if (mime.startsWith("video/"))
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1), path, c.getLong(3))
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
        val json = JSONObject()
            .put("folders", JSONArray(folders.map { it.toString() }))
            .put("excluded", JSONArray(excluded.map { it.toString() }))
            .put("shuffle", shuffle)
            .put("rate", rate.toDouble())
            // Videos from removed folders are forgotten unless the user favorited, tagged or annotated them
            .put("clips", JSONArray(known.values.filter { it in library || it.favorite || it.tags.isNotEmpty() || it.notes.isNotEmpty() }
                .map { it.toJson() }))
        val out = store.startWrite()
        runCatching { out.write(json.toString().toByteArray()); store.finishWrite(out) }
            .onFailure { store.failWrite(out); toast("Couldn't save") }
    }

    private fun load() {
        val json = runCatching { JSONObject(String(store.readFully())) }.getOrNull() ?: return
        json.optJSONArray("folders")?.strings()?.mapTo(folders, Uri::parse)
        json.optJSONArray("excluded")?.strings()?.mapTo(excluded, Uri::parse)
        shuffle = json.optBoolean("shuffle")
        rate = json.optDouble("rate", 1.0).toFloat()
        val saved = json.optJSONArray("clips") ?: return
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            val clip = Clip(Uri.parse(o.getString("uri")), o.getString("title"), o.optString("folder")).apply {
                favorite = o.optBoolean("favorite", o.optBoolean("liked")) // "liked"/"comments": V0.0.2 names
                o.optJSONArray("tags")?.strings()?.let(tags::addAll)
                (o.optJSONArray("notes") ?: o.optJSONArray("comments"))?.strings()?.let(notes::addAll)
                width = o.optInt("width")
                height = o.optInt("height")
                duration = o.optLong("duration")
                size = o.optLong("size")
                lastPlayed = o.optLong("lastPlayed")
            }
            known[clip.uri] = clip
        }
    }

    // ---- Playback ----

    // One player for the whole feed: it is re-attached to whichever page is on screen
    private fun play(pos: Int) {
        val clip = clips.getOrNull(pos) ?: return
        val page = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pos)?.itemView ?: return
        if (clip === playingClip && page === attachedTo) return
        player.stop()
        player.detachViews()
        fd?.close()
        fd = runCatching { contentResolver.openFileDescriptor(clip.uri, "r") }.getOrNull()
            ?: return toast("Can't open this video")
        playingClip = clip
        attachedTo = page
        player.attachViews(page.findViewById<VLCVideoLayout>(R.id.video), null, false, false)
        Media(vlc, fd!!.fileDescriptor).run {
            addOption(":input-repeat=65535")
            player.media = this
            release()
        }
        player.play()
        player.rate = rate
        clip.lastPlayed = System.currentTimeMillis()
        save()
    }

    override fun onResume() { super.onResume(); if (playingClip != null) player.play() }
    override fun onPause() { super.onPause(); player.pause() }
    override fun onDestroy() {
        player.release()
        vlc.release()
        fd?.close()
        super.onDestroy()
    }

    // ---- Per-video dialogs ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun mb(bytes: Long) = if (bytes >= 1 shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.1f MB".format(bytes / 1048576.0)

    private fun time(ms: Long) = (ms / 1000).let { if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60) }

    private fun showInfo(c: Clip) {
        // fps and codec come from libVLC, so only for the video being played
        val track = if (c === playingClip) player.currentVideoTrack else null
        val fps = track?.takeIf { it.frameRateDen > 0 }?.let { "%.2f".format(it.frameRateNum.toDouble() / it.frameRateDen) }
        val ratio = c.allTags().firstOrNull { it.contains(':') }
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(listOfNotNull(
                "Folder: ${c.folder}",
                if (c.width > 0) "Resolution: ${c.width} × ${c.height}" + (ratio?.let { " ($it)" } ?: "") else null,
                fps?.let { "Frame rate: $it fps" },
                track?.codec?.let { "Codec: $it" },
                if (c.duration > 0) "Duration: ${time(c.duration)}" else null,
                "Size: ${mb(c.size)}",
            ).joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNotes(c: Clip, count: TextView) {
        val input = EditText(this).apply { hint = "Add a note…" }
        AlertDialog.Builder(this)
            .setTitle("Notes")
            .setMessage(c.notes.joinToString("\n\n").ifEmpty { "No notes yet." })
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                input.text.trim().takeIf { it.isNotEmpty() }?.let { c.notes.add(0, it.toString()) }
                count.text = "${c.notes.size}"
                save()
            }
            .setNeutralButton("Clear all") { _, _ -> c.notes.clear(); count.text = "0"; save() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editTags(c: Clip, onDone: () -> Unit) {
        val existing = library.flatMap { it.tags }.toSortedSet().toList()
        val checked = existing.map { it in c.tags }.toBooleanArray()
        val input = EditText(this).apply { hint = "New tags, comma-separated"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Tags")
            .setMultiChoiceItems(existing.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                c.tags.clear()
                existing.filterIndexedTo(c.tags) { i, _ -> checked[i] }
                input.text.split(',').map { it.trim().removePrefix("#") }.filterTo(c.tags) { it.isNotEmpty() }
                save()
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class Feed : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = clips.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.page, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val c = clips[position]
            fun text(id: Int) = v.findViewById<TextView>(id)
            val like = text(R.id.like)
            val tag = text(R.id.tag)
            val notes = text(R.id.notes)
            val speed = text(R.id.speed)
            val badge = text(R.id.folder_badge)
            val inFolder = folderFilter == c.folder

            fun render() {
                like.text = if (c.favorite) "Fav" else ""
                like.compoundDrawableTintList = ColorStateList.valueOf(if (c.favorite) PINK else WHITE)
                tag.text = "${c.tags.size}"
                notes.text = "${c.notes.size}"
                speed.text = "▶ ${if (rate % 1 == 0f) rate.toInt() else rate}×"
                text(R.id.tags).text = c.allTags().joinToString(" ") { "#$it" }
                text(R.id.tags).visibility = if (c.allTags().isEmpty()) View.GONE else View.VISIBLE
            }
            render()

            text(R.id.caption).text = c.title
            text(R.id.folder).text = c.folder
            text(R.id.folder_avatar).text = c.folder.substringAfterLast('/').take(1).uppercase()
            badge.text = if (inFolder) "✓" else "+"
            badge.setTextColor(if (inFolder) 0xff111111.toInt() else WHITE)
            badge.backgroundTintList = ColorStateList.valueOf(if (inFolder) WHITE else PINK)
            val toggleFolder = View.OnClickListener {
                folderFilter = if (inFolder) null else c.folder
                applyFilter()
                toast(if (inFolder) "All folders" else "Only ${c.folder}")
            }
            text(R.id.folder_avatar).setOnClickListener(toggleFolder)
            badge.setOnClickListener(toggleFolder)
            badge.contentDescription = if (inFolder) "Show all folders" else "Show only this folder"

            like.setOnClickListener {
                c.favorite = !c.favorite
                render()
                save()
                toast(if (c.favorite) "Added to favorites" else "Removed from favorites")
            }
            tag.setOnClickListener { editTags(c) { render(); if (tagFilter.isNotEmpty()) applyFilter() } }
            notes.setOnClickListener { showNotes(c, notes) }
            speed.setOnClickListener {
                rate = RATES[(RATES.indexOf(rate) + 1) % RATES.size]
                player.rate = rate
                render()
                save()
            }
            v.findViewById<View>(R.id.info).setOnClickListener { showInfo(c) }
            v.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }

            // A recycled page may be the one on screen: make sure the player follows it
            if (position == pager.currentItem) pager.post { play(position) }
        }
    }
}
