package io.github.crkarthik11.ottplayer

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The whole app: a home page (live preview, language tabs, tiles), full-screen live
 * playback, the bottom channel panel, and a Settings screen for languages and genres.
 *
 * Home: Up/Down between the preview, the tabs and the tile rows; Left/Right switch
 * tab or move along a row; OK opens full screen, a channel, a category or Settings;
 * CH +/- zap the preview; Back returns to the tabs, then asks before exiting.
 * Watching: OK or Down opens the panel, CH +/- zap (running on into the next or
 * previous category at the ends), Left goes back to the last channel, Up/Right/Info
 * show the info banner, Back returns home.
 * Panel: Up/Down channels, Left/Right categories, OK plays.
 * Anywhere: digits jump to a channel number; holding OK (or Menu while watching)
 * toggles a favourite.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private enum class Key { UP, DOWN, LEFT, RIGHT, OK, BACK, CH_UP, CH_DOWN, INFO, GUIDE, MENU }

    /** Something on a home-page tile. */
    private sealed class Item {
        class Chan(val ch: Channel) : Item()
        class Cat(val group: Int) : Item()
        object Settings : Item()
    }

    private class HomeRow(val label: String, val items: List<Item>)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var io: ExecutorService
    private lateinit var store: Store
    private lateinit var prefs: SharedPreferences
    private lateinit var catalog: Catalog

    private var playlist: Playlist? = null
    @Volatile private var loadFailures = 0
    @Volatile private var guide: Guide? = null
    private var groups: List<Group> = emptyList()
    private var genreIndex: Map<String, Int> = emptyMap()
    private val favourites = LinkedHashSet<String>()
    private val recent = ArrayList<String>()

    private var player: ExoPlayer? = null
    private var playing: Channel? = null
    private var playGroup = 0
    private var previous: Channel? = null
    private var previousGroup = 0
    private var retries = 0
    private var lastZapAt = 0L

    // Manifest URLs after JioTV's redirect, so a channel switch can skip that hop.
    private val resolved = HashMap<String, Pair<Uri, Long>>()
    private var loadedFromCache = false

    // Timing of the current channel start, for the "zap" log line.
    private var zapChannel = 0
    private var zapStart = 0L
    private var zapManifest = 0L
    private var zapKeys = 0L

    private var home = true
    private var hr = 1 // home focus: 0 = preview, 1 = tabs, 2.. = tile rows
    private var hc = 0 // column in a tile row
    private var lt = 0 // selected tab
    private var hintsShown = false

    private var panelOpen = false
    private var gi = 0
    private var ci = 0
    private var okDownAt = 0L
    private var okLong = false
    private var digits = ""
    private var backAt = 0L

    // Settings screen state; changes apply when it is closed.
    private var settingsOpen = false
    private var sCol = 0
    private var sLi = 0
    private var sGi = 0
    private var sLangs = mutableSetOf<String>()
    private var sGenresOff = mutableSetOf<String>()
    private var langOrder: List<String> = emptyList()
    private var genreOrder: List<String> = emptyList()
    private var pairCounts: Map<Pair<String, String>, Int> = emptyMap()

    private lateinit var homeBg: View
    private lateinit var homeView: View
    private lateinit var playerView: PlayerView
    private lateinit var heroRing: View
    private lateinit var clockView: TextView
    private lateinit var hWhere: TextView
    private lateinit var hName: TextView
    private lateinit var hNow: TextView
    private lateinit var hTime: TextView
    private lateinit var hProg: ProgressBar
    private lateinit var hNext: TextView
    private lateinit var tabHint: TextView
    private lateinit var tuning: View
    private lateinit var tTile: LogoTile
    private lateinit var tName: TextView
    private lateinit var tWhere: TextView
    private lateinit var settingsView: View
    private lateinit var sTotal: TextView
    private lateinit var sCats: TextView
    private lateinit var sLangCard: View
    private lateinit var sGenreCard: View
    private lateinit var sLangTitle: TextView
    private lateinit var sGenreTitle: TextView
    private lateinit var sLangPos: TextView
    private lateinit var sGenrePos: TextView
    private lateinit var sPreview: TextView
    private lateinit var status: TextView
    private lateinit var hints: View
    private lateinit var note: TextView
    private lateinit var digitsView: TextView
    private lateinit var banner: View
    private lateinit var panel: View
    private lateinit var bTile: LogoTile
    private lateinit var bNum: TextView
    private lateinit var bName: TextView
    private lateinit var bGroup: TextView
    private lateinit var bNow: TextView
    private lateinit var bProg: ProgressBar
    private lateinit var bNextLabel: TextView
    private lateinit var bNext: TextView
    private lateinit var barLang: TextView
    private lateinit var barLangPos: TextView
    private lateinit var barPos: TextView
    private lateinit var scrollTrack: View
    private lateinit var scrollThumb: View
    private lateinit var dTile: LogoTile
    private lateinit var dName: TextView
    private lateinit var dNowLabel: TextView
    private lateinit var dNow: TextView
    private lateinit var dProg: ProgressBar
    private lateinit var dNextLabel: TextView
    private lateinit var dNext: TextView
    private lateinit var dHint: TextView

    private class Tile(val root: View, val name: TextView, val count: TextView, val chips: List<LogoTile>, val prog: ProgressBar)
    private class RowView(val label: TextView, val tiles: List<Tile>)
    private class ChanRow(
        val root: View, val num: TextView, val tile: LogoTile, val name: TextView,
        val now: TextView, val prog: ProgressBar, val dot: View,
    )
    private class ToggleRow(val root: View, val name: TextView, val count: TextView, val track: View, val knob: View)

    private val rowViews = ArrayList<RowView>()
    private val langTabs = ArrayList<TextView>()
    private val chanRows = ArrayList<ChanRow>()
    private val tabs = ArrayList<TextView>()
    private val langRows = ArrayList<ToggleRow>()
    private val genreRows = ArrayList<ToggleRow>()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val dateClock = SimpleDateFormat("EEE d MMM   HH:mm", Locale.UK)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        Logos.init(this)
        io = Executors.newSingleThreadExecutor()
        store = Store(this)
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        catalog = Catalog(prefs)
        prefs.getString("favourites", "")!!.split(',').filterTo(favourites) { it.isNotEmpty() }
        prefs.getString("recent", "")!!.split(',').filterTo(recent) { it.isNotEmpty() }
        bindViews()
        showHome()
        hr = 1
        loadData()
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        playing?.let { load(it) }
        main.post(tick)
        main.postDelayed(refreshData, REFRESH_MS)
    }

    override fun onStop() {
        super.onStop()
        for (r in listOf(startRun, retryRun, stallRun, loadingRun, tick, refreshData)) main.removeCallbacks(r)
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- data

    /**
     * Runs [work] on the io thread. Results posted back to the main thread can arrive
     * after onDestroy has shut the executor down; those are dropped instead of crashing.
     */
    private fun onIo(work: () -> Unit) {
        if (!io.isShutdown) io.execute(work)
    }

    /** Runs on the io thread: shows the cached playlist straight away, then refreshes it. */
    private fun loadData() {
        onIo {
            fun parse() = store.cached(FULL_FILE)
                ?.let { f -> runCatching { M3u.parse(f.readText()) }.getOrNull() }
                ?.takeIf { it.channels.isNotEmpty() }

            var full = parse()
            full?.let { f -> main.post { setPlaylist(f) } }
            try {
                val changed = store.refresh(BuildConfig.FULL_PLAYLIST_URL, FULL_FILE) { M3u.parse(it.readText()).channels.isNotEmpty() }
                if (changed || full == null) {
                    full = parse()
                    full?.let { f -> main.post { setPlaylist(f) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "playlist download failed (attempt ${loadFailures + 1})", e)
                if (full == null) {
                    // Nothing cached yet: retry soon, since this is usually the network still coming up.
                    val delay = when (loadFailures++) { 0 -> 2_000L; 1 -> 5_000L; else -> 10_000L }
                    main.post { showStatus("Can’t reach the server. Retrying…") }
                    main.postDelayed({ loadData() }, delay)
                    return@onIo
                }
            }
            loadFailures = 0
            main.post { requestGuide() }
        }
    }

    /** Trims the guide to the channels currently shown, on the io thread. */
    private fun requestGuide() {
        val ids = HashSet<String>()
        groups.forEach { g -> g.channels.forEach { ids.add(it.id) } }
        if (ids.isNotEmpty()) onIo { loadGuide(ids) }
    }

    /**
     * Runs on the io thread. JioTV's full guide (about 2 MB gzipped, rebuilt once a
     * day) is only downloaded when it changed; it's then trimmed to the shown
     * channels and saved small, so normal starts never touch the big file.
     */
    private fun loadGuide(ids: Set<String>) {
        val trimmed = File(filesDir, TRIMMED_GUIDE_FILE)
        // [guide] is only set once the main thread gets to setGuide, so track here
        // whether a guide exists; otherwise a cold start re-parses the full file.
        var have = guide != null
        if (!have) Guide.load(trimmed)?.let { g -> have = true; main.post { setGuide(g) } }
        val changed = try {
            store.refresh(BuildConfig.GUIDE_URL, GUIDE_FILE) { f -> f.inputStream().use { it.read() == 0x1f && it.read() == 0x8b } }
        } catch (_: Exception) {
            false // Keep whatever guide we have; the next refresh tries again.
        }
        // Re-trim when the guide or the set of shown channels changed.
        val idsKey = ids.sorted().joinToString(",").hashCode()
        if (!changed && have && prefs.getInt("guideIds", 0) == idsKey) return
        val raw = store.cached(GUIDE_FILE) ?: return
        val now = System.currentTimeMillis()
        val g = runCatching { raw.inputStream().use { Guide.parse(it, ids, now - 3 * HOUR, now + 36 * HOUR) } }.getOrNull() ?: return
        runCatching { g.save(trimmed) }
        prefs.edit().putInt("guideIds", idsKey).apply()
        main.post { setGuide(g) }
    }

    private val refreshData = object : Runnable {
        override fun run() {
            loadData()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun setPlaylist(full: Playlist) {
        playlist = full
        genreIndex = Catalog.genreOrder(full).withIndex().associate { (i, g) -> g to i }
        status.visibility = View.GONE
        rebuildGroups()
        if (groups.isEmpty()) return
        val current = playing
        if (current == null) {
            val saved = prefs.getString("last", null)?.let { full.byId[it] }
            val lastGroup = prefs.getString("lastGroup", null)
            var group = groups.indexOfFirst { g -> g.name == lastGroup && g.channels.any { it.id == saved?.id } }
            if (group < 0) group = groupOf(saved)
            if (saved != null && group >= 0) play(saved, group, immediate = true)
            else play(groups[0].channels[0], 0, immediate = true)
        } else {
            val fresh = full.byId[current.id]
            if (fresh != null && fresh.mpd != current.mpd) play(fresh, max(0, groupOf(fresh)), immediate = true)
            else if (fresh != null) playing = fresh
        }
        render()
    }

    private fun setGuide(g: Guide) {
        guide = g
        render()
    }

    private fun rebuildGroups() {
        val pl = playlist ?: return
        val keepPlay = groups.getOrNull(playGroup)?.name
        val keepFocus = groups.getOrNull(gi)?.name
        val keepTab = homeTabs().getOrNull(lt)
        groups = catalog.build(pl, favourites)
        if (groups.isEmpty()) return
        playGroup = groups.indexOfFirst { it.name == keepPlay }.takeIf { it >= 0 } ?: max(0, groupOf(playing))
        gi = groups.indexOfFirst { it.name == keepFocus }.coerceAtLeast(0)
        ci = ci.coerceIn(0, max(0, groups[gi].channels.size - 1))
        lt = homeTabs().indexOf(keepTab).takeIf { it >= 0 } ?: 0
        hr = hr.coerceIn(0, 1 + homeRows().size)
        clampHomeColumn()
    }

    /**
     * The category to play a channel in: its own language/genre category first,
     * then the All lists, then Favourites or the music mix. -1 if it isn't shown at all.
     */
    private fun groupOf(ch: Channel?): Int {
        if (ch == null) return -1
        fun find(test: (Group) -> Boolean) = groups.indexOfFirst { g -> test(g) && g.channels.any { it.id == ch.id } }
        return find { it.language != Catalog.ALL && it.language != Catalog.FOR_YOU }.takeIf { it >= 0 }
            ?: find { it.language == Catalog.ALL }.takeIf { it >= 0 }
            ?: find { true }
    }

    private fun indexIn(group: Int): Int =
        groups[group].channels.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0)

    // ---------------------------------------------------------------- playback

    private fun createPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("OTTPlayer/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
        val drm = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }
        val sources = DefaultMediaSourceFactory(http)
            .setDrmSessionManagerProvider(drm)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        // Start once half a second is buffered: on a LAN, switching speed matters
        // more than a deep buffer, and rebuffers refill from the next segment quickly.
        val buffering = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 500, 1_500)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setLoadControl(buffering)
            .build()
        p.addListener(listener)
        p.addAnalyticsListener(timing)
        p.playWhenReady = true
        playerView.player = p
        player = p
    }

    private fun mediaItem(ch: Channel): MediaItem {
        val cached = resolved[ch.id]?.takeIf { SystemClock.elapsedRealtime() - it.second < RESOLVED_TTL_MS }?.first
        loadedFromCache = cached != null
        // JioTV serves most channels as DASH with Widevine, but about a fifth as plain
        // HLS (".m3u8", no DRM). Reading HLS as DASH fails with "manifest malformed".
        val hls = ch.mpd.substringBefore('?').endsWith(".m3u8")
        val b = MediaItem.Builder().setMediaId(ch.id).setUri(cached ?: Uri.parse(ch.mpd))
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
        if (!hls) ch.license?.let { b.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(it).build()) }
        return b.build()
    }

    private fun load(ch: Channel) {
        val p = player ?: return
        main.removeCallbacks(retryRun)
        zapChannel = ch.num
        zapStart = SystemClock.elapsedRealtime()
        zapManifest = 0
        zapKeys = 0
        p.setMediaItem(mediaItem(ch))
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Switches channel. A single press starts at once; presses in quick succession
     * (holding CH+) wait for a pause so the streams in between aren't started.
     */
    private fun play(ch: Channel, group: Int, immediate: Boolean) {
        val before = playing
        if (before != null && before.id != ch.id) {
            previous = before
            previousGroup = playGroup
        }
        playing = ch
        playGroup = group
        recent.remove(ch.id)
        recent.add(0, ch.id)
        while (recent.size > COLS) recent.removeAt(recent.size - 1)
        prefs.edit()
            .putString("last", ch.id)
            .putString("lastGroup", groups.getOrNull(group)?.name)
            .putString("recent", recent.joinToString(","))
            .apply()
        if (home) renderHome() else {
            if (before?.id != ch.id) showTuning(ch)
            showBanner()
        }
        main.removeCallbacks(startRun)
        main.removeCallbacks(retryRun)
        val now = SystemClock.elapsedRealtime()
        val rapid = !immediate && now - lastZapAt < 400
        lastZapAt = now
        if (rapid) main.postDelayed(startRun, 300) else startRun.run()
    }

    /**
     * CH +/-: the next or previous channel in the current category. Past either end
     * it carries on into the neighbouring category (its first channel going up, its
     * last going down), wrapping from the last category back to the first.
     */
    private fun zap(step: Int) {
        if (groups.isEmpty()) return
        var g = playGroup.coerceIn(0, groups.size - 1)
        val list = groups[g].channels
        var idx = list.indexOfFirst { it.id == playing?.id }
        if (idx < 0) idx = if (step > 0) -1 else list.size
        idx += step
        while (idx !in groups[g].channels.indices) {
            g = (g + step + groups.size) % groups.size
            idx = if (step > 0) 0 else groups[g].channels.size - 1
        }
        play(groups[g].channels[idx], g, immediate = false)
    }

    /** Left while watching: back to the channel before this one. */
    private fun lastChannel() {
        val ch = previous ?: run { showNote("No previous channel yet"); return }
        val g = if (groups.getOrNull(previousGroup)?.channels?.any { it.id == ch.id } == true) previousGroup else groupOf(ch)
        if (g < 0) return
        play(ch, g, immediate = true)
    }

    private val startRun = Runnable { playing?.let { retries = 0; load(it) } }
    private val retryRun = Runnable { playing?.let { load(it) } }
    private val loadingRun = Runnable { if (tuning.visibility != View.VISIBLE) showStatus("Loading…") }
    private val stallRun = Runnable { if (player?.playbackState == Player.STATE_BUFFERING) retry("stalled") }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            main.removeCallbacks(stallRun)
            main.removeCallbacks(loadingRun)
            when (state) {
                Player.STATE_READY -> { retries = 0; status.visibility = View.GONE; hideTuning() }
                Player.STATE_BUFFERING -> {
                    main.postDelayed(loadingRun, 1_200)
                    main.postDelayed(stallRun, STALL_MS)
                }
                Player.STATE_ENDED -> retry("stream ended")
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            hideTuning()
            if (zapStart == 0L) return
            val total = SystemClock.elapsedRealtime() - zapStart
            Log.i(TAG, "zap ch=$zapChannel manifest=${zapManifest}ms keys=${zapKeys}ms firstFrame=${total}ms cachedUrl=$loadedFromCache")
            zapStart = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            // The live window is only a minute long; after a hiccup just jump back to live.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.let { it.seekToDefaultPosition(); it.prepare() }
                return
            }
            // A remembered manifest URL may have expired: forget it and retry straight away.
            val ch = playing
            if (loadedFromCache && ch != null) {
                resolved.remove(ch.id)
                load(ch)
                return
            }
            Log.w(TAG, "playback error on ch=${ch?.num}: ${error.errorCodeName}", error)
            retry(error.errorCodeName)
        }
    }

    /** Records how long each step of a channel start took, and remembers redirects. */
    private val timing = object : AnalyticsListener {
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
            if (data.dataType != C.DATA_TYPE_MANIFEST) return
            if (zapStart != 0L && zapManifest == 0L) zapManifest = SystemClock.elapsedRealtime() - zapStart
            // While CH+ is held, [playing] already names the next channel but the old
            // stream keeps refreshing its manifest; take the channel from the event itself.
            if (eventTime.timeline.isEmpty) return
            val id = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
            val ch = playlist?.byId?.get(id) ?: return
            if (info.uri.toString() != ch.mpd) resolved[ch.id] = info.uri to SystemClock.elapsedRealtime()
        }

        override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
            if (zapStart != 0L && zapKeys == 0L) zapKeys = SystemClock.elapsedRealtime() - zapStart
        }
    }

    /**
     * Reloads the channel with back-off: 1s, 2s, 4s, 8s, then every 15s. After a few
     * failures it says plainly that the channel isn't working, but keeps trying.
     */
    private fun retry(reason: String) {
        retries++
        val delay = min(15_000L, 1_000L shl min(retries - 1, 4))
        if (retries >= 3) {
            hideTuning()
            showStatus("${playing?.name ?: "This channel"} isn’t available right now · CH + to try the next one")
        } else if (tuning.visibility != View.VISIBLE) {
            showStatus("Reconnecting…")
        }
        Log.w(TAG, "retry $retries for ch=${playing?.num}: $reason")
        main.removeCallbacks(retryRun)
        main.postDelayed(retryRun, delay)
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && !settingsOpen) addDigit(digit)
            return true
        }
        val key = keyOf(code) ?: return super.dispatchKeyEvent(e)
        if (key == Key.OK && !settingsOpen && !home) {
            // Short press acts on release. Holding it 0.6 s toggles a favourite: timed
            // here, because some remotes don't repeat a held OK key.
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) {
                    okDownAt = SystemClock.elapsedRealtime()
                    okLong = false
                    main.postDelayed(okHeld, LONG_PRESS_MS)
                }
            } else if (e.action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(okHeld)
                if (!okLong) {
                    if (SystemClock.elapsedRealtime() - okDownAt >= LONG_PRESS_MS) okHeld.run() else press(Key.OK)
                }
            }
            return true
        }
        if (key == Key.OK) {
            if (e.action == KeyEvent.ACTION_UP) press(Key.OK)
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) press(key)
        return true
    }

    private val okHeld = Runnable {
        okLong = true
        toggleFavourite(focusedOrPlaying())
    }

    private fun keyOf(code: Int): Key? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> Key.OK
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> Key.BACK
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> Key.CH_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Key.CH_DOWN
        KeyEvent.KEYCODE_INFO -> Key.INFO
        KeyEvent.KEYCODE_GUIDE -> Key.GUIDE
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_SETTINGS -> Key.MENU
        else -> null
    }

    private fun press(key: Key) {
        if (settingsOpen) { pressSettings(key); return }
        if (groups.isEmpty()) {
            if (key == Key.BACK) exitOrWarn()
            return
        }
        when {
            home -> pressHome(key)
            panelOpen -> pressPanel(key)
            else -> pressWatching(key)
        }
    }

    private fun pressHome(key: Key) {
        val rows = homeRows()
        when (key) {
            Key.UP -> hr = max(0, hr - 1)
            Key.DOWN -> hr = min(1 + rows.size, hr + 1)
            Key.LEFT -> if (hr == 1) selectTab(lt - 1) else if (hr >= 2) hc = max(0, hc - 1)
            Key.RIGHT -> if (hr == 1) selectTab(lt + 1) else if (hr >= 2) hc += 1
            Key.CH_UP -> { zap(1); return }
            Key.CH_DOWN -> { zap(-1); return }
            Key.OK -> { homeOk(rows); return }
            Key.MENU -> { openSettings(); return }
            Key.BACK -> if (hr >= 2) hr = 1 else { exitOrWarn(); return }
            Key.INFO, Key.GUIDE -> Unit
        }
        clampHomeColumn()
        renderHome()
    }

    private fun selectTab(index: Int) {
        lt = index.coerceIn(0, homeTabs().size - 1)
        hc = 0
    }

    private fun homeOk(rows: List<HomeRow>) {
        when {
            hr == 0 -> { showWatching(); showBanner() }
            hr == 1 -> {
                if (homeTabs()[lt] == TAB_SETTINGS) openSettings()
                else if (rows.isNotEmpty()) { hr = 2; hc = 0; renderHome() }
            }
            else -> when (val item = rows.getOrNull(hr - 2)?.items?.getOrNull(hc)) {
                is Item.Chan -> {
                    val g = groupOf(item.ch)
                    if (g >= 0) { showWatching(); play(item.ch, g, immediate = true) }
                }
                is Item.Cat -> { showWatching(); openPanel(item.group) }
                Item.Settings -> openSettings()
                null -> Unit
            }
        }
    }

    private fun pressWatching(key: Key) {
        when (key) {
            Key.OK, Key.DOWN, Key.GUIDE -> openPanel(playGroup)
            Key.CH_UP -> zap(1)
            Key.CH_DOWN -> zap(-1)
            Key.LEFT -> lastChannel()
            Key.UP, Key.RIGHT, Key.INFO -> showBanner()
            Key.MENU -> toggleFavourite(playing)
            Key.BACK -> showHome()
        }
    }

    private fun pressPanel(key: Key) {
        val list = groups[gi].channels
        val n = max(1, list.size)
        when (key) {
            Key.UP -> ci = (ci - 1 + n) % n
            Key.DOWN -> ci = (ci + 1) % n
            Key.LEFT -> { gi = (gi - 1 + groups.size) % groups.size; ci = indexIn(gi) }
            Key.RIGHT -> { gi = (gi + 1) % groups.size; ci = indexIn(gi) }
            Key.CH_UP -> ci = min(n - 1, ci + 5)
            Key.CH_DOWN -> ci = max(0, ci - 5)
            Key.OK -> {
                closePanel()
                list.getOrNull(ci)?.let { play(it, gi, immediate = true) }
                return
            }
            Key.BACK, Key.GUIDE -> { closePanel(); return }
            Key.MENU -> { toggleFavourite(list.getOrNull(ci)); return }
            Key.INFO -> Unit
        }
        renderPanel()
    }

    private fun pressSettings(key: Key) {
        when (key) {
            Key.UP -> if (sCol == 0) sLi = max(0, sLi - 1) else sGi = max(0, sGi - 1)
            Key.DOWN -> if (sCol == 0) sLi = min(langOrder.size - 1, sLi + 1) else sGi = min(genreOrder.size - 1, sGi + 1)
            Key.LEFT -> sCol = 0
            Key.RIGHT -> sCol = 1
            Key.OK -> if (sCol == 0) toggle(sLangs, langOrder[sLi]) else toggle(sGenresOff, genreOrder[sGi])
            Key.BACK -> { closeSettings(); return }
            Key.CH_UP, Key.CH_DOWN, Key.INFO, Key.GUIDE, Key.MENU -> Unit
        }
        renderSettings()
    }

    private fun <T> toggle(set: MutableSet<T>, item: T) {
        if (!set.remove(item)) set.add(item)
    }

    private fun focusedOrPlaying(): Channel? =
        if (panelOpen) groups.getOrNull(gi)?.channels?.getOrNull(ci) else playing

    private fun toggleFavourite(ch: Channel?) {
        ch ?: return
        val added = favourites.add(ch.id)
        if (!added) favourites.remove(ch.id)
        prefs.edit().putString("favourites", favourites.joinToString(",")).apply()
        showNote(if (added) "★ Added ${ch.name} to Favourites" else "Removed ${ch.name} from Favourites")
        rebuildGroups()
        if (panelOpen) {
            val idx = groups[gi].channels.indexOfFirst { it.id == ch.id }
            if (idx >= 0) ci = idx
        }
        render()
    }

    /** Typing a number shows which channel it is before switching to it. */
    private fun addDigit(d: Int) {
        if (digits.length >= 4) digits = ""
        digits += d
        val ch = digits.toIntOrNull()?.let { n -> playlist?.channels?.firstOrNull { it.num == n } }
        digitsView.text = SpannableStringBuilder(digits).also { sb ->
            if (ch != null) {
                val from = sb.length
                sb.append("   ${ch.name}")
                sb.setSpan(ForegroundColorSpan(Color.WHITE), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        digitsView.visibility = View.VISIBLE
        main.removeCallbacks(commitDigits)
        main.postDelayed(commitDigits, 1_500)
    }

    private val commitDigits = Runnable {
        val num = digits.toIntOrNull()
        digits = ""
        digitsView.visibility = View.GONE
        val ch = num?.let { n -> playlist?.channels?.firstOrNull { it.num == n } }
        val group = groupOf(ch)
        when {
            ch == null -> showNote("No channel $num")
            group < 0 -> showNote("${ch.name} is hidden. Turn on ${ch.language} ${ch.genre} in Settings.")
            else -> {
                closePanel()
                if (home) showWatching()
                play(ch, group, immediate = true)
            }
        }
    }

    private fun exitOrWarn() {
        val now = System.currentTimeMillis()
        if (now - backAt < 2_500) finish()
        else { backAt = now; showNote("Press Back again to exit") }
    }

    // ---------------------------------------------------------------- screens

    private fun showHome() {
        home = true
        hr = 0
        closePanel()
        hideTuning()
        main.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        hints.visibility = View.GONE
        homeBg.visibility = View.VISIBLE
        homeView.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        playerView.layoutParams = FrameLayout.LayoutParams((280 * d).toInt(), (158 * d).toInt(), Gravity.TOP or Gravity.START).apply {
            leftMargin = (42 * d).toInt()
            topMargin = (54 * d).toInt()
        }
        renderHome()
    }

    private fun showWatching() {
        home = false
        homeBg.visibility = View.GONE
        homeView.visibility = View.GONE
        playerView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (!hintsShown) {
            hintsShown = true
            hints.visibility = View.VISIBLE
            main.postDelayed({ hints.visibility = View.GONE }, 6_000)
        }
    }

    private fun showTuning(ch: Channel) {
        bindTile(tTile, ch, 18f)
        tName.text = SpannableStringBuilder("${ch.num}").also { sb ->
            sb.setSpan(ForegroundColorSpan(AMBER), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("   ${ch.name}")
        }
        tWhere.text = where(groups.getOrNull(playGroup), ch)
        status.visibility = View.GONE
        tuning.visibility = View.VISIBLE
    }

    private fun hideTuning() {
        tuning.visibility = View.GONE
    }

    private fun openPanel(group: Int) {
        gi = group.coerceIn(0, groups.size - 1)
        ci = indexIn(gi)
        panelOpen = true
        main.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        hints.visibility = View.GONE
        renderPanel()
        panel.visibility = View.VISIBLE
    }

    private fun closePanel() {
        panelOpen = false
        panel.visibility = View.GONE
    }

    private fun openSettings() {
        val pl = playlist ?: return
        val usable = Catalog.usable(pl)
        langOrder = Catalog.languageOrder(pl)
        genreOrder = Catalog.genreOrder(pl)
        pairCounts = usable.groupingBy { it.language to it.genre }.eachCount()
        sLangs = catalog.languages.toMutableSet()
        sGenresOff = catalog.genresOff.toMutableSet()
        sCol = 0
        sLi = 0
        sGi = 0
        settingsOpen = true
        renderSettings()
        settingsView.visibility = View.VISIBLE
    }

    /** Saves the choices and rebuilds the categories and the trimmed guide. */
    private fun closeSettings() {
        settingsOpen = false
        settingsView.visibility = View.GONE
        val changed = sLangs != catalog.languages || sGenresOff != catalog.genresOff
        if (!changed) return
        catalog.save(sLangs, sGenresOff)
        hr = 1
        hc = 0
        rebuildGroups()
        render()
        requestGuide()
        val plain = groups.filter { it.language != Catalog.ALL && it.language != Catalog.FOR_YOU }
        showNote("Saved · ${plain.sumOf { it.channels.size }} channels in ${plain.size} categories")
    }

    private fun showBanner() {
        if (home || panelOpen || playing == null) return
        renderBanner()
        banner.visibility = View.VISIBLE
        hints.visibility = View.GONE
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, 4_500)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun showNote(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, 3_000)
    }

    private val hideNote = Runnable { note.visibility = View.GONE }

    /** Keeps the clock, progress bars and now/next current. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun render() {
        if (home) renderHome()
        if (panelOpen) renderPanel()
        if (banner.visibility == View.VISIBLE) renderBanner()
    }

    // ---------------------------------------------------------------- home model

    /** Tab keys: For you, All channels, each shown language, Settings. */
    private fun homeTabs(): List<String> {
        val langs = groups.map { it.language }.distinct()
        val order = listOf(Catalog.FOR_YOU, Catalog.ALL)
        return order.filter { it in langs } + langs.filter { it !in order } + TAB_SETTINGS
    }

    private fun tabLabel(tab: String) = when (tab) {
        Catalog.FOR_YOU -> "★ For you"
        TAB_SETTINGS -> "⚙ Settings"
        else -> tab
    }

    private fun homeRows(): List<HomeRow> {
        val tab = homeTabs().getOrNull(lt) ?: return emptyList()
        if (tab == TAB_SETTINGS) return listOf(HomeRow("SETTINGS", listOf(Item.Settings)))
        val cats = groups.indices.filter { groups[it].language == tab }.map { Item.Cat(it) }
        if (tab == Catalog.FOR_YOU) {
            val pl = playlist
            val recentItems = recent.mapNotNull { id -> pl?.byId?.get(id) }.filter { groupOf(it) >= 0 }.map { Item.Chan(it) }
            val rows = ArrayList<HomeRow>()
            if (recentItems.isNotEmpty()) rows.add(HomeRow("RECENT", recentItems))
            if (cats.isNotEmpty()) rows.add(HomeRow("YOUR LISTS", cats))
            return rows
        }
        val channels = groups.filter { it.language == tab }.let { gs -> if (tab == Catalog.ALL) gs.first().channels.size else gs.sumOf { it.channels.size } }
        return cats.chunked(COLS).mapIndexed { i, chunk ->
            val label = when {
                i > 0 -> ""
                tab == Catalog.ALL -> "ALL CHANNELS  ·  $channels"
                else -> "${tab.uppercase(Locale.ROOT)}  ·  $channels CHANNELS IN ${cats.size} GENRES"
            }
            HomeRow(label, chunk)
        }
    }

    private fun clampHomeColumn() {
        if (hr < 2) return
        val row = homeRows().getOrNull(hr - 2) ?: return
        hc = hc.coerceIn(0, max(0, row.items.size - 1))
    }

    /** "Telugu · Movies", or just "Favourites" for the For-you lists. */
    private fun where(g: Group?, ch: Channel): String = when {
        g == null -> "${ch.language} · ${ch.genre}"
        g.language == Catalog.FOR_YOU -> g.label.removePrefix("★ ")
        g.language == Catalog.ALL -> "All channels · ${g.label}"
        else -> "${g.language} · ${g.label}"
    }

    // ---------------------------------------------------------------- rendering

    private fun renderHome() {
        clockView.text = dateClock.format(Date())
        heroRing.visibility = if (hr == 0) View.VISIBLE else View.INVISIBLE
        val ch = playing
        if (ch != null) {
            val (now, next) = nowNext(ch)
            hWhere.text = "CONTINUE WATCHING  ·  ${where(groups.getOrNull(playGroup), ch).uppercase(Locale.ROOT)}"
            hName.text = ch.name
            hNow.text = now?.title ?: "No guide information"
            hTime.text = now?.let { "${time(it.start)} – ${time(it.stop)}" } ?: ""
            setProgress(hProg, now)
            hNext.text = next?.let { "Next ${time(it.start)}   ${it.title}" } ?: ""
        }

        val tabsList = homeTabs()
        lt = lt.coerceIn(0, tabsList.size - 1)
        val tStart = max(0, min(lt - 3, tabsList.size - langTabs.size))
        val d = resources.displayMetrics.density
        langTabs.forEachIndexed { j, view ->
            val tab = tabsList.getOrNull(tStart + j)
            if (tab == null) { view.visibility = View.GONE; return@forEachIndexed }
            val sel = tStart + j == lt
            val focus = sel && hr == 1
            view.visibility = View.VISIBLE
            view.text = tabLabel(tab)
            view.setTextColor(if (focus) INK else if (sel) Color.WHITE else MUTED)
            view.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (focus) FOCUS else if (sel) GROUP_FOCUS else TAB_BG)
                if (sel && !focus) setStroke((2 * d).toInt(), AMBER)
            }
        }
        tabHint.text = when {
            hr == 1 -> "◀ ▶ switch   ·   OK or ▼ open"
            hr == 0 -> "OK full screen"
            else -> ""
        }

        val rows = homeRows()
        val focusRow = hr - 2
        val start = if (focusRow < 0) 0 else max(0, min(focusRow - 1, rows.size - rowViews.size))
        rowViews.forEachIndexed { r, rv ->
            val row = rows.getOrNull(start + r)
            rv.label.visibility = if (row != null && row.label.isNotEmpty()) View.VISIBLE else View.GONE
            rv.label.text = row?.label ?: ""
            rv.tiles.forEachIndexed { j, tile ->
                val item = row?.items?.getOrNull(j)
                if (item == null) { tile.root.visibility = if (row == null) View.GONE else View.INVISIBLE; return@forEachIndexed }
                val focus = start + r == focusRow && j == hc
                tile.root.visibility = View.VISIBLE
                tile.root.background = rounded(if (focus) FOCUS else CARD, 11f)
                tile.name.setTextColor(if (focus) INK else TEXT)
                tile.count.setTextColor(if (focus) INK_SUB else MUTED)
                tile.prog.visibility = View.GONE
                when (item) {
                    is Item.Chan -> {
                        val now = nowNext(item.ch).first
                        tile.name.text = item.ch.name
                        tile.count.text = "${item.ch.num} · ${now?.title ?: item.ch.genre}"
                        showChips(tile, listOf(item.ch))
                        setProgress(tile.prog, now)
                        if (now == null) tile.prog.visibility = View.GONE
                    }
                    is Item.Cat -> {
                        val g = groups[item.group]
                        tile.name.text = g.label
                        tile.count.text = "${g.channels.size} channels"
                        showChips(tile, g.channels.take(4))
                    }
                    Item.Settings -> {
                        tile.name.text = "Languages & genres"
                        tile.count.text = "Choose which channels appear"
                        showChips(tile, emptyList())
                    }
                }
            }
        }
    }

    private fun showChips(tile: Tile, channels: List<Channel>) {
        tile.chips.forEachIndexed { i, chip ->
            val c = channels.getOrNull(i)
            if (c == null) chip.visibility = View.GONE
            else { chip.visibility = View.VISIBLE; bindTile(chip, c, 5f) }
        }
    }

    private fun renderBanner() {
        val ch = playing ?: return
        val (now, next) = nowNext(ch)
        val g = groups.getOrNull(playGroup)
        bindTile(bTile, ch, 12f)
        bNum.text = ch.num.toString()
        bName.text = starred(ch)
        val pos = g?.channels?.indexOfFirst { it.id == ch.id } ?: -1
        bGroup.text = where(g, ch) + if (pos >= 0) "  ·  ${pos + 1} / ${g!!.channels.size}" else ""
        bNow.text = if (now == null) "No guide information" else
            SpannableStringBuilder(now.title).append("   ").also { sb ->
                val from = sb.length
                sb.append("${time(now.start)} – ${time(now.stop)}")
                sb.setSpan(ForegroundColorSpan(MUTED), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        setProgress(bProg, now)
        bNextLabel.text = next?.let { "NEXT  ${time(it.start)}" } ?: "NEXT"
        bNext.text = next?.title ?: "—"
    }

    private fun renderPanel() {
        if (groups.isEmpty()) return
        val cur = groups[gi]
        // The bar shows the current language as a label and only its categories as chips.
        val siblings = groups.indices.filter { groups[it].language == cur.language }
        val pos = siblings.indexOf(gi)
        barLang.text = tabLabel(cur.language).uppercase(Locale.ROOT)
        barLangPos.text = "${pos + 1} / ${siblings.size}"
        val tShown = min(TABS, siblings.size)
        val tStart = max(0, min(pos - 2, siblings.size - tShown))
        val d = resources.displayMetrics.density
        for (j in 0 until TABS) {
            val tab = tabs[j]
            if (j >= tShown) { tab.visibility = View.GONE; continue }
            val idx = siblings[tStart + j]
            val current = idx == gi
            tab.visibility = View.VISIBLE
            tab.text = groups[idx].label
            tab.setTextColor(if (current) Color.WHITE else TAB_DIM)
            tab.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (current) GROUP_FOCUS else TAB_BG)
                if (current) setStroke((2 * d).toInt(), AMBER)
            }
        }

        val list = cur.channels
        barPos.text = SpannableStringBuilder("${list.size} CHANNELS").also { sb ->
            sb.setSpan(ForegroundColorSpan(AMBER), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("   ·   ${if (list.isEmpty()) 0 else ci + 1} / ${list.size}")
        }
        val shown = min(ROWS, list.size)
        val start = max(0, min(ci - 2, list.size - shown))
        for (j in 0 until ROWS) {
            val row = chanRows[j]
            if (j >= shown) { row.root.visibility = View.INVISIBLE; continue }
            val idx = start + j
            val c = list[idx]
            val focus = idx == ci
            val now = nowNext(c).first
            row.root.visibility = View.VISIBLE
            row.root.background = rounded(if (focus) FOCUS else Color.TRANSPARENT, 10f)
            row.num.text = c.num.toString()
            row.num.setTextColor(if (focus) INK_SUB else MUTED)
            bindTile(row.tile, c, 7f)
            row.name.text = starred(c)
            row.name.setTextColor(if (focus) INK else TEXT)
            row.now.text = now?.title ?: ""
            row.now.setTextColor(if (focus) INK_SUB else MUTED)
            setProgress(row.prog, now)
            row.dot.visibility = if (c.id == playing?.id) View.VISIBLE else View.GONE
        }

        updateScrollBar(list.size, shown)

        val f = list.getOrNull(ci) ?: return
        val (now, next) = nowNext(f)
        bindTile(dTile, f, 11f)
        dName.text = starred(f)
        dNowLabel.text = now?.let { "NOW  ${time(it.start)} – ${time(it.stop)}" } ?: "NOW"
        dNow.text = now?.title ?: "No guide information"
        setProgress(dProg, now)
        dNextLabel.text = next?.let { "NEXT  ${time(it.start)}" } ?: "NEXT"
        dNext.text = next?.title ?: "—"
        dHint.text = if (f.id in favourites) "★ In Favourites · Hold OK to remove"
        else "OK watch · Hold OK to add to Favourites"
    }

    /**
     * The panel's scroll bar: the thumb's length is the share of the list on screen,
     * its position follows the focused channel. Hidden when the whole list fits.
     */
    private fun updateScrollBar(total: Int, shown: Int) {
        if (total <= shown) { scrollTrack.visibility = View.INVISIBLE; return }
        scrollTrack.visibility = View.VISIBLE
        scrollTrack.post {
            val trackH = scrollTrack.height
            if (trackH == 0) return@post
            val minThumb = (18 * resources.displayMetrics.density).toInt()
            val thumbH = max(minThumb, trackH * shown / total)
            scrollThumb.layoutParams = scrollThumb.layoutParams.apply { height = thumbH }
            scrollThumb.translationY = (trackH - thumbH) * ci.toFloat() / (total - 1)
        }
    }

    private fun renderSettings() {
        fun langCount(l: String) = genreOrder.sumOf { g -> if (g in sGenresOff) 0 else pairCounts[l to g] ?: 0 }
        fun genreCount(g: String) = langOrder.sumOf { l -> if (l in sLangs) pairCounts[l to g] ?: 0 else 0 }
        val cats = ArrayList<String>()
        var shown = 0
        for (l in langOrder) {
            if (l !in sLangs) continue
            for (g in genreOrder) {
                val n = pairCounts[l to g] ?: 0
                if (g !in sGenresOff && n > 0) { shown += n; cats.add("$l $g") }
            }
        }
        val total = pairCounts.values.sum()
        sTotal.text = SpannableStringBuilder("Showing ").also { sb ->
            val from = sb.length
            sb.append("$shown")
            sb.setSpan(ForegroundColorSpan(AMBER), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" of $total channels")
        }
        sCats.text = "in ${cats.size} categories, plus All channels and your lists"
        sPreview.text = SpannableStringBuilder("HOME PAGE   ").also { sb ->
            sb.setSpan(ForegroundColorSpan(MUTED), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(TextUtils.join("  ·  ", cats.take(6)))
            if (cats.size > 6) sb.append("   +${cats.size - 6} more")
        }

        val d = resources.displayMetrics.density
        listOf(sLangCard to 0, sGenreCard to 1).forEach { (card, col) ->
            card.background = GradientDrawable().apply {
                cornerRadius = 15 * d
                setColor(SETTINGS_CARD)
                setStroke((2 * d).toInt(), if (sCol == col) AMBER else Color.TRANSPARENT)
            }
        }
        sLangTitle.setTextColor(if (sCol == 0) AMBER else MUTED)
        sGenreTitle.setTextColor(if (sCol == 1) AMBER else MUTED)
        sLangPos.text = "${sLangs.count { it in langOrder }} on"
        sGenrePos.text = "${genreOrder.count { it !in sGenresOff }} on"
        fillToggles(langRows, langOrder, sLi, sCol == 0, { it in sLangs }, ::langCount)
        fillToggles(genreRows, genreOrder, sGi, sCol == 1, { it !in sGenresOff }, ::genreCount)
    }

    private fun fillToggles(rows: List<ToggleRow>, items: List<String>, focus: Int, active: Boolean, on: (String) -> Boolean, count: (String) -> Int) {
        val d = resources.displayMetrics.density
        val start = max(0, min(focus - 3, items.size - rows.size))
        rows.forEachIndexed { j, row ->
            val idx = start + j
            val item = items.getOrNull(idx)
            if (item == null) { row.root.visibility = View.INVISIBLE; return@forEachIndexed }
            val isFocus = idx == focus
            val isActive = isFocus && active
            val isOn = on(item)
            row.root.visibility = View.VISIBLE
            row.root.background = rounded(if (isActive) FOCUS else if (isFocus) SETTINGS_FOCUS else Color.TRANSPARENT, 9f)
            row.name.text = item
            row.name.setTextColor(if (isActive) INK else if (isOn) TEXT else TAB_DIM)
            row.count.text = count(item).toString()
            row.count.setTextColor(if (isActive) INK_SUB else MUTED)
            row.track.background = rounded(if (isOn) AMBER else if (isActive) TRACK_ACTIVE else GROUP_FOCUS, 11f)
            // Slide the knob with a translation: margin changes after layout were ignored.
            row.knob.translationX = if (isOn) 16 * d else 0f
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The channel's name, with an amber star in front if it's a favourite. */
    private fun starred(ch: Channel): CharSequence {
        if (ch.id !in favourites) return ch.name
        return SpannableStringBuilder("★ ").also { sb ->
            sb.setSpan(ForegroundColorSpan(AMBER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(ch.name)
        }
    }

    private fun nowNext(ch: Channel): Pair<Programme?, Programme?> =
        guide?.nowNext(ch.id, System.currentTimeMillis()) ?: (null to null)

    private fun setProgress(bar: ProgressBar, p: Programme?) {
        if (p == null) { bar.visibility = View.INVISIBLE; return }
        val now = System.currentTimeMillis()
        bar.visibility = View.VISIBLE
        bar.progress = ((now - p.start) * 100 / max(1L, p.stop - p.start)).toInt().coerceIn(0, 100)
    }

    private fun time(ms: Long): String = clock.format(Date(ms))

    private fun bindTile(view: LogoTile, ch: Channel, radiusDp: Float) {
        view.bind(ch, TINTS[(genreIndex[ch.genre] ?: 0) % TINTS.size], radiusDp)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
    }

    private fun bindViews() {
        homeBg = findViewById(R.id.homeBg)
        homeView = findViewById(R.id.home)
        playerView = findViewById(R.id.player)
        heroRing = findViewById(R.id.heroRing)
        clockView = findViewById(R.id.clock)
        hWhere = findViewById(R.id.hWhere)
        hName = findViewById(R.id.hName)
        hNow = findViewById(R.id.hNow)
        hTime = findViewById(R.id.hTime)
        hProg = findViewById(R.id.hProg)
        hNext = findViewById(R.id.hNext)
        tabHint = findViewById(R.id.tabHint)
        tuning = findViewById(R.id.tuning)
        tTile = findViewById(R.id.tTile)
        tName = findViewById(R.id.tName)
        tWhere = findViewById(R.id.tWhere)
        settingsView = findViewById(R.id.settings)
        sTotal = findViewById(R.id.sTotal)
        sCats = findViewById(R.id.sCats)
        sLangCard = findViewById(R.id.sLangCard)
        sGenreCard = findViewById(R.id.sGenreCard)
        sLangTitle = findViewById(R.id.sLangTitle)
        sGenreTitle = findViewById(R.id.sGenreTitle)
        sLangPos = findViewById(R.id.sLangPos)
        sGenrePos = findViewById(R.id.sGenrePos)
        sPreview = findViewById(R.id.sPreview)
        status = findViewById(R.id.status)
        hints = findViewById(R.id.hints)
        note = findViewById(R.id.note)
        digitsView = findViewById(R.id.digits)
        banner = findViewById(R.id.banner)
        panel = findViewById(R.id.panel)
        bTile = findViewById(R.id.bTile)
        bNum = findViewById(R.id.bNum)
        bName = findViewById(R.id.bName)
        bGroup = findViewById(R.id.bGroup)
        bNow = findViewById(R.id.bNow)
        bProg = findViewById(R.id.bProg)
        bNextLabel = findViewById(R.id.bNextLabel)
        bNext = findViewById(R.id.bNext)
        barLang = findViewById(R.id.barLang)
        barLangPos = findViewById(R.id.barLangPos)
        barPos = findViewById(R.id.barPos)
        scrollTrack = findViewById(R.id.scrollTrack)
        scrollThumb = findViewById(R.id.scrollThumb)
        dTile = findViewById(R.id.dTile)
        dName = findViewById(R.id.dName)
        dNowLabel = findViewById(R.id.dNowLabel)
        dNow = findViewById(R.id.dNow)
        dProg = findViewById(R.id.dProg)
        dNextLabel = findViewById(R.id.dNextLabel)
        dNext = findViewById(R.id.dNext)
        dHint = findViewById(R.id.dHint)

        val inflater = layoutInflater
        val d = resources.displayMetrics.density
        fun chip(textSp: Float, k: Int, padH: Int) = TextView(this).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            textSize = textSp
            paint.isFakeBoldText = true
            setPadding((padH * d).toInt(), 0, (padH * d).toInt(), 0)
            tag = k
        }

        val tabBox = findViewById<LinearLayout>(R.id.langTabs)
        repeat(HOME_TABS) { k ->
            val v = chip(14f, k, 15)
            tabBox.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * d).toInt()).apply {
                if (k > 0) leftMargin = (7 * d).toInt()
            })
            langTabs.add(v)
        }

        val rowsBox = findViewById<LinearLayout>(R.id.homeRows)
        repeat(GRID_ROWS) { r ->
            val label = TextView(this).apply {
                textSize = 12f
                letterSpacing = 0.12f
                paint.isFakeBoldText = true
                setTextColor(MUTED)
            }
            rowsBox.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = (8 * d).toInt()
                bottomMargin = (5 * d).toInt()
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowsBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = (2 * d).toInt()
            })
            val rowTiles = ArrayList<Tile>()
            repeat(COLS) { c ->
                val v = inflater.inflate(R.layout.item_tile, row, false)
                (v.layoutParams as LinearLayout.LayoutParams).apply { if (c > 0) leftMargin = (9 * d).toInt() }
                row.addView(v)
                val chipBox = v.findViewById<LinearLayout>(R.id.chips)
                val chips = List(4) { k ->
                    LogoTile(this).also { t ->
                        chipBox.addView(t, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply {
                            if (k > 0) leftMargin = (4 * d).toInt()
                        })
                    }
                }
                rowTiles.add(Tile(v, v.findViewById(R.id.name), v.findViewById(R.id.count), chips, v.findViewById(R.id.prog)))
            }
            rowViews.add(RowView(label, rowTiles))
        }

        val barTabs = findViewById<LinearLayout>(R.id.tabs)
        repeat(TABS) { k ->
            val v = chip(13f, k, 12)
            barTabs.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (30 * d).toInt()).apply {
                if (k > 0) leftMargin = (5 * d).toInt()
            })
            tabs.add(v)
        }

        val chanBox = findViewById<LinearLayout>(R.id.chanRows)
        repeat(ROWS) {
            val c = inflater.inflate(R.layout.item_channel, chanBox, false)
            chanBox.addView(c)
            chanRows.add(
                ChanRow(
                    c, c.findViewById(R.id.num), c.findViewById(R.id.tile), c.findViewById(R.id.name),
                    c.findViewById(R.id.now), c.findViewById(R.id.prog), c.findViewById(R.id.dot),
                ),
            )
        }
        for ((box, rows) in listOf(R.id.sLangRows to langRows, R.id.sGenreRows to genreRows)) {
            val parent = findViewById<LinearLayout>(box)
            repeat(TOGGLE_ROWS) {
                val v = inflater.inflate(R.layout.item_toggle, parent, false)
                parent.addView(v)
                rows.add(ToggleRow(v, v.findViewById(R.id.name), v.findViewById(R.id.count), v.findViewById(R.id.track), v.findViewById(R.id.knob)))
            }
        }
    }

    private companion object {
        const val TAG = "OTTPlayer"
        const val FULL_FILE = "full.m3u"
        const val GUIDE_FILE = "guide.xml.gz"
        const val TRIMMED_GUIDE_FILE = "guide.bin"
        const val TAB_SETTINGS = "settings"
        const val ROWS = 5
        const val COLS = 6
        const val GRID_ROWS = 2
        const val TABS = 6
        const val HOME_TABS = 7
        const val TOGGLE_ROWS = 8
        const val LONG_PRESS_MS = 600L
        const val HOUR = 3_600_000L
        const val TICK_MS = 30_000L
        const val REFRESH_MS = 3 * HOUR
        const val STALL_MS = 20_000L
        const val RESOLVED_TTL_MS = 10 * 60_000L

        val AMBER = Color.parseColor("#F2A93B")
        val FOCUS = Color.parseColor("#F4F1EA")
        val CARD = Color.parseColor("#171B22")
        val SETTINGS_CARD = Color.parseColor("#12161C")
        val SETTINGS_FOCUS = Color.parseColor("#232831")
        val TRACK_ACTIVE = Color.parseColor("#C9C4B9")
        val GROUP_FOCUS = Color.parseColor("#2E3540")
        val TAB_BG = Color.parseColor("#151920")
        val TEXT = Color.parseColor("#EDEFF2")
        val MUTED = Color.parseColor("#A3ACB8")
        val TAB_DIM = Color.parseColor("#8A939F")
        val INK = Color.parseColor("#141413")
        val INK_SUB = Color.parseColor("#4A4F57")
        val TINTS = intArrayOf(
            Color.parseColor("#8A5A2B"), Color.parseColor("#8A3B2E"), Color.parseColor("#5E4A8A"),
            Color.parseColor("#2E5E7A"), Color.parseColor("#7A2E5A"), Color.parseColor("#2E6B55"),
            Color.parseColor("#4A5A86"), Color.parseColor("#2E4A7A"), Color.parseColor("#6E5E22"),
        )
    }
}
