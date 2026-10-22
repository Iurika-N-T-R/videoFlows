package com.videoflows

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PICK = 1
private const val EXPORT = 2
private const val PINK = 0xffff315c.toInt()
private const val GOLD = 0xffffd96a.toInt()
private const val WHITE = 0xffffffff.toInt()

class MainActivity : Activity() {
    class Clip(val uri: Uri, val title: String) {
        var liked = false
        var saved = false
        var following = false
        var reposted = false
        val comments = mutableListOf<String>()

        fun toJson(): JSONObject = JSONObject().put("uri", uri.toString()).put("title", title)
            .put("liked", liked).put("saved", saved).put("following", following).put("reposted", reposted)
            .put("comments", JSONArray(comments))
    }

    private val clips = mutableListOf<Clip>()
    // The whole feed (videos + likes/saves/comments) as JSON; AtomicFile so a crash mid-write can't corrupt it
    private val store by lazy { AtomicFile(File(filesDir, "feed.json")) }
    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var pager: ViewPager2
    private var fd: ParcelFileDescriptor? = null
    private var playing = -1

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
        refresh()

        val following = findViewById<TextView>(R.id.following)
        val forYou = findViewById<TextView>(R.id.foryou)
        fun selectTab(active: TextView, other: TextView, msg: String) {
            active.setTextColor(WHITE); active.paint.isFakeBoldText = true
            active.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, R.drawable.tab_underline)
            other.setTextColor(0xffd4d4d4.toInt()); other.paint.isFakeBoldText = false
            other.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, R.drawable.tab_underline_none)
            toast(msg)
        }
        selectTab(forYou, following, "")
        following.setOnClickListener { selectTab(following, forYou, "Follow creators to fill your feed") }
        forYou.setOnClickListener { selectTab(forYou, following, "For You feed") }

        findViewById<View>(R.id.nav_create).setOnClickListener { pick() }
        findViewById<View>(R.id.nav_home).setOnClickListener { if (clips.isNotEmpty()) pager.currentItem = 0 }
        findViewById<View>(R.id.nav_profile).setOnClickListener { showProfile() }
        mapOf(R.id.live to "LIVE", R.id.search to "Search", R.id.nav_discover to "Discover", R.id.nav_inbox to "Inbox")
            .forEach { (id, name) -> findViewById<View>(id).setOnClickListener { toast("$name — coming soon") } }
    }

    private fun pick() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK)

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == EXPORT) {
            runCatching { contentResolver.openOutputStream(data.data!!)!!.use { it.write(feedJson().toByteArray()) } }
                .onSuccess { toast("Exported") }.onFailure { toast("Export failed") }
            return
        }
        if (requestCode != PICK) return
        val uris = data.clipData?.let { cd -> (0 until cd.itemCount).map { cd.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        uris.forEach {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            add(it)
        }
        save()
        refresh()
    }

    private fun add(uri: Uri): Clip? {
        if (clips.any { it.uri == uri }) return null
        // Dropped silently if the document was deleted or its permission revoked since last launch
        val title = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
                .use { it.moveToFirst(); it.getString(0) }
        }.getOrNull() ?: return null
        return Clip(uri, title.substringBeforeLast('.')).also { clips += it }
    }

    private fun feedJson() = JSONArray(clips.map { it.toJson() }).toString(2)

    private fun save() {
        val out = store.startWrite()
        runCatching { out.write(feedJson().toByteArray()); store.finishWrite(out) }
            .onFailure { store.failWrite(out); toast("Couldn't save") }
    }

    private fun load() {
        val feed = runCatching { JSONArray(String(store.readFully())) }.getOrNull() ?: return
        for (i in 0 until feed.length()) {
            val o = feed.getJSONObject(i)
            add(Uri.parse(o.getString("uri")))?.apply {
                liked = o.optBoolean("liked")
                saved = o.optBoolean("saved")
                following = o.optBoolean("following")
                reposted = o.optBoolean("reposted")
                o.optJSONArray("comments")?.let { a -> (0 until a.length()).mapTo(comments) { a.getString(it) } }
            }
        }
    }

    private fun showProfile() {
        AlertDialog.Builder(this)
            .setTitle("Profile")
            .setMessage("${clips.size} videos · ${clips.count { it.liked }} liked · ${clips.count { it.saved }} saved · " +
                "${clips.sumOf { it.comments.size }} comments")
            .setPositiveButton("Export") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "videoflows-export.json"), EXPORT)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refresh() {
        pager.adapter!!.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
    }

    // One player for the whole feed: it is re-attached to whichever page is on screen
    private fun play(pos: Int) {
        if (pos == playing || pos !in clips.indices) return
        val page = (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pos)?.itemView ?: return
        player.stop()
        player.detachViews()
        fd?.close()
        fd = runCatching { contentResolver.openFileDescriptor(clips[pos].uri, "r") }.getOrNull()
            ?: return toast("Can't open this video")
        playing = pos
        player.attachViews(page.findViewById<VLCVideoLayout>(R.id.video), null, false, false)
        Media(vlc, fd!!.fileDescriptor).run {
            addOption(":input-repeat=65535")
            player.media = this
            release()
        }
        player.play()
    }

    override fun onResume() { super.onResume(); if (playing >= 0) player.play() }
    override fun onPause() { super.onPause(); player.pause() }
    override fun onDestroy() {
        player.release()
        vlc.release()
        fd?.close()
        super.onDestroy()
    }

    private fun toast(msg: String) { if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun showComments(clip: Clip, count: TextView) {
        val input = EditText(this).apply { hint = "Add a comment…" }
        AlertDialog.Builder(this)
            .setTitle("${clip.comments.size} comments")
            .setMessage(clip.comments.joinToString("\n") { "you: $it" }.ifEmpty { "No comments yet." })
            .setView(input)
            .setPositiveButton("Post") { _, _ ->
                input.text.trim().takeIf { it.isNotEmpty() }?.let { clip.comments.add(0, it.toString()) }
                count.text = "${clip.comments.size}"
                save()
            }
            .setNegativeButton("Close", null)
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
            val save = text(R.id.save)
            val follow = text(R.id.follow)
            val repost = text(R.id.repost)
            val comments = text(R.id.comments)

            fun render() {
                like.text = if (c.liked) "1" else "0"
                like.compoundDrawableTintList = ColorStateList.valueOf(if (c.liked) PINK else WHITE)
                save.text = if (c.saved) "1" else "0"
                save.compoundDrawableTintList = ColorStateList.valueOf(if (c.saved) GOLD else WHITE)
                follow.text = if (c.following) "✓" else "+"
                follow.setTextColor(if (c.following) 0xff111111.toInt() else WHITE)
                follow.backgroundTintList = ColorStateList.valueOf(if (c.following) WHITE else PINK)
                follow.contentDescription = if (c.following) "Unfollow creator" else "Follow creator"
                repost.text = if (c.reposted) "✓ Reposted" else "↻ Repost"
                comments.text = "${c.comments.size}"
            }
            render()

            text(R.id.caption).text = c.title
            text(R.id.music).text = "original sound - ${c.title}"
            like.setOnClickListener { c.liked = !c.liked; render(); save(); toast(if (c.liked) "Added to liked videos" else "Like removed") }
            save.setOnClickListener { c.saved = !c.saved; render(); save(); toast(if (c.saved) "Added to Favorites" else "Removed from Favorites") }
            follow.setOnClickListener { c.following = !c.following; render(); save(); toast(if (c.following) "Following creator" else "Unfollowed creator") }
            repost.setOnClickListener { c.reposted = !c.reposted; render(); save(); toast(if (c.reposted) "Video reposted" else "Repost removed") }
            comments.setOnClickListener { showComments(c, comments) }
            v.findViewById<View>(R.id.creator).setOnClickListener { toast("Creator profile — coming soon") }
            v.findViewById<View>(R.id.disc).setOnClickListener { toast("Original sound — coming soon") }
            v.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }

            // A recycled page may be the one on screen: make sure the player follows it
            if (position == pager.currentItem) { playing = -1; pager.post { play(position) } }
        }
    }
}
