package com.tvsas.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.tvsas.app.App
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.Chapter
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.data.Storyboard
import com.tvsas.app.data.Prefs
import com.tvsas.app.data.Topic
import com.tvsas.app.util.Format
import kotlinx.coroutines.launch

class PlayerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screensaver / display sleep while the player is in front, even without remote input.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlayerFragment().apply { arguments = intent.extras })
                .commit()
        }
    }

    companion object {
        fun intent(ctx: Context, topic: Topic, videoUuid: String, durationSec: Int, startSec: Int): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(PlayerFragment.ARG_TOPIC, topic)
                .putExtra(PlayerFragment.ARG_VIDEO, videoUuid)
                .putExtra(PlayerFragment.ARG_DURATION, durationSec)
                .putExtra(PlayerFragment.ARG_START, startSec)
    }
}

/**
 * HLS playback with Leanback transport controls.
 * Tuned for old TV boxes: small buffers, capped resolution (default 1080p), no tunneling tricks.
 */
@OptIn(UnstableApi::class)
class PlayerFragment : VideoSupportFragment() {

    private lateinit var topic: Topic
    private lateinit var videoUuid: String
    private var durationSec = 0
    private var startSec = 0

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var glue: Glue? = null
    private var seekProvider: SeekProvider? = null
    private var chapters: List<Chapter> = emptyList()
    private var storyboard: Storyboard? = null
    private var extrasLoaded = false

    /** Where to continue when the player is re-created after onStop (screen off, HDMI switch, …). */
    private var resumePositionMs = C.TIME_UNSET

    private val handler = Handler(Looper.getMainLooper())
    private var lastServerSaveMs = 0L
    private val progressTicker = object : Runnable {
        override fun run() {
            saveProgress(force = false)
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        topic = BundleCompat.getParcelable(args, ARG_TOPIC, Topic::class.java) ?: run { requireActivity().finish(); return }
        videoUuid = args.getString(ARG_VIDEO) ?: run { requireActivity().finish(); return }
        durationSec = args.getInt(ARG_DURATION).takeIf { it > 0 } ?: topic.durationSec
        startSec = args.getInt(ARG_START)
    }

    /** Leanback shows the thumbnail strip only while the user is scrubbing. */
    private fun isSeeking(): Boolean =
        view?.findViewById<View>(androidx.leanback.R.id.thumbs_row)?.visibility == View.VISIBLE

    override fun onStart() {
        super.onStart()
        if (player == null) initPlayer()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initPlayer() {
        val ctx = requireContext()

        // Resolution cap: user preference or the physical display. Old SoCs choke on 4K H.264.
        val selector = DefaultTrackSelector(ctx).also { ts ->
            ts.parameters = ts.buildUponParameters()
                .applyQualityCap(Prefs.maxQuality)
                .setForceHighestSupportedBitrate(false)
                // Old decoders stall for a second on every rendition switch; only allow switches
                // the codec can do seamlessly, and pin the rendition when a fixed quality is chosen.
                .setAllowVideoNonSeamlessAdaptiveness(false)
                .build()
        }
        trackSelector = selector

        // Enough buffer to ride out Wi-Fi hiccups on cheap boxes, still far below the RAM of a 4K
        // default (min 20 s / max 60 s, start after 2.5 s, resume after a stall with 5 s).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val http: HttpDataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // Playlists come from sasflix.ru and need the auth token; segments come from presigned S3
        // links that reject any Authorization header — so add it per request, by host.
        val dataSource = ResolvingDataSource.Factory(http) { spec ->
            val token = Prefs.token
            // Exact host: segments live on reflector.sasflix.ru (S3) and must NOT get the header.
            if (token != null && spec.uri.host == Api.HOST) {
                spec.buildUpon().setHttpRequestHeaders(mapOf("Authorization" to "Bearer $token")).build()
            } else spec
        }

        val exo = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true))
            .setSeekBackIncrementMs(Prefs.seekStepSec * 1000L)
            .setSeekForwardIncrementMs(Prefs.seekStepSec * 1000L)
            .setWakeMode(C.WAKE_MODE_NETWORK) // keep CPU + Wi-Fi awake while playing
            .build()
        player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val msg = if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                    getString(R.string.player_access_denied)
                } else {
                    getString(R.string.player_error, error.errorCodeName)
                }
                context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> setupSeek()
                    Player.STATE_ENDED -> {
                        saveProgress(force = true)
                        activity?.finish()
                    }
                }
            }
        })

        val g = Glue(ctx, LeanbackPlayerAdapter(ctx, exo, 500))
        g.host = VideoSupportFragmentGlueHost(this)
        g.title = topic.title
        g.subtitle = baseSubtitle()
        g.isSeekEnabled = true
        g.isControlsOverlayAutoHideEnabled = true
        glue = g
        if (!extrasLoaded) loadExtras()

        val item = MediaItem.Builder()
            .setUri(Api.videoUrl(videoUuid))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val startMs = when {
            resumePositionMs != C.TIME_UNSET -> resumePositionMs
            startSec > 0 -> startSec * 1000L
            else -> C.TIME_UNSET
        }
        exo.setMediaItem(item, startMs)
        exo.prepare()
        exo.playWhenReady = true

        // Logged-in users may have a newer server-side position than the local one.
        if (Prefs.isLoggedIn && startSec == 0 && resumePositionMs == C.TIME_UNSET) {
            viewLifecycleOwner.lifecycleScope.launch {
                val remote = Api.progress(videoUuid) ?: return@launch
                val p = player ?: return@launch
                if (remote.timeSec > 30 && p.currentPosition < 15_000 && durationSec > 0 &&
                    remote.timeSec * 100 / durationSec < 96
                ) {
                    p.seekTo(remote.timeSec * 1000L)
                }
            }
        }

        lastServerSaveMs = System.currentTimeMillis()
        handler.postDelayed(progressTicker, PROGRESS_INTERVAL_MS)
    }

    private fun baseSubtitle(): String =
        listOfNotNull(topic.categoryTitle, topic.levelTitle?.takeIf { topic.paid }).joinToString(" · ")

    /** Chapters and the storyboard sprite are optional extras; playback never waits for them. */
    private fun loadExtras() {
        extrasLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            chapters = Api.chapters(videoUuid)
            storyboard = Api.storyboard(videoUuid)
            if (chapters.isNotEmpty()) glue?.addChapterActions()
            setupSeek()
        }
    }

    /** (Re)creates the seek positions once the duration is known; re-run when the storyboard arrives. */
    private fun setupSeek() {
        val p = player ?: return
        val g = glue ?: return
        val duration = p.duration.takeIf { it > 0 } ?: (durationSec * 1000L).takeIf { it > 0 } ?: return
        val existing = seekProvider
        if (existing != null && existing.hasStoryboard == (storyboard != null)) return
        existing?.release()
        val provider = SeekProvider(requireContext(), viewLifecycleOwner.lifecycleScope, duration, Prefs.seekStepSec * 1000L, storyboard)
        seekProvider = provider
        g.seekProvider = provider
    }

    private fun currentChapter(posMs: Long): Chapter? {
        val sec = posMs / 1000
        var best: Chapter? = null
        for (c in chapters) { if (c.startSec <= sec) best = c else break }
        return best
    }

    private fun seekToChapter(forward: Boolean) {
        val p = player ?: return
        val sec = p.currentPosition / 1000
        val target = if (forward) chapters.firstOrNull { it.startSec > sec }
        else chapters.lastOrNull { it.startSec < sec - 3 } ?: chapters.firstOrNull()
        target?.let { p.seekTo(it.startSec * 1000L) }
    }

    private fun applyQuality(maxHeight: Int) {
        val ts = trackSelector ?: return
        ts.parameters = ts.buildUponParameters().applyQualityCap(maxHeight).build()
    }

    /** Auto = adaptive up to the display size; a fixed value pins that rendition (no ABR switching). */
    private fun DefaultTrackSelector.Parameters.Builder.applyQualityCap(maxHeight: Int): DefaultTrackSelector.Parameters.Builder =
        if (maxHeight == Prefs.QUALITY_AUTO) {
            clearVideoSizeConstraints().setViewportSizeToPhysicalDisplaySize(true)
        } else {
            setMaxVideoSize(Int.MAX_VALUE, maxHeight).setMinVideoSize(0, maxHeight).setExceedVideoConstraintsIfNecessary(true)
        }

    private fun saveProgress(force: Boolean) {
        val p = player ?: return
        val posSec = (p.currentPosition / 1000).toInt()
        val dur = (if (p.duration > 0) p.duration / 1000 else durationSec.toLong()).toInt()
        if (posSec <= 0 || dur <= 0) return
        val entry = HistoryEntry(topic, videoUuid, posSec, dur, System.currentTimeMillis())
        Prefs.saveHistory(entry)

        val now = System.currentTimeMillis()
        if (force || now - lastServerSaveMs >= SERVER_SAVE_INTERVAL_MS) {
            lastServerSaveMs = now
            val ctx = context ?: return
            // Launcher "Watch Next" row: keep while in progress, drop once (almost) finished.
            if (entry.percent >= 96) WatchNext.remove(ctx, videoUuid) else WatchNext.update(ctx, entry)
            if (Prefs.isLoggedIn) {
                val quality = p.videoFormat?.height?.takeIf { it > 0 }?.toString() ?: "auto"
                // Fire-and-forget on the app scope; the fragment may be going away.
                App.ioScope.launch { Api.saveProgress(videoUuid, posSec, quality) }
            }
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressTicker)
        player?.let { p ->
            if (p.playbackState != Player.STATE_ENDED && p.currentPosition > 0) resumePositionMs = p.currentPosition
        }
        glue?.host = null
        glue = null
        seekProvider?.release()
        seekProvider = null
        player?.release()
        player = null
        trackSelector = null
    }

    /** Transport controls + quality switch + chapter navigation + "current chapter" subtitle. */
    private inner class Glue(ctx: Context, adapter: LeanbackPlayerAdapter) :
        PlaybackTransportControlGlue<LeanbackPlayerAdapter>(ctx, adapter) {

        private val prevChapter = PlaybackControlsRow.SkipPreviousAction(ctx)
        private val nextChapter = PlaybackControlsRow.SkipNextAction(ctx)
        private var lastChapter: Chapter? = null
        private var upOnSeekBar = false

        /**
         * The glue registers itself as the fragment's key interceptor (for media keys), so this
         * is the only place to add key handling. UP on the seek bar — the top-most control — hides
         * the overlay. Leanback calls tickle() (= show overlay) after this on every ACTION_DOWN,
         * hence the hide happens on ACTION_UP.
         */
        override fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        upOnSeekBar = isControlsOverlayVisible && !isSeeking() &&
                            view?.findFocus()?.id == androidx.leanback.R.id.playback_progress
                        if (upOnSeekBar) return true
                    }
                    KeyEvent.ACTION_UP -> if (upOnSeekBar) {
                        upOnSeekBar = false
                        hideControlsOverlay(true)
                        return true
                    }
                }
            }
            return super.onKey(v, keyCode, event)
        }

        override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
            super.onCreateSecondaryActions(adapter)
            adapter.add(QualityAction(context))
        }

        fun addChapterActions() {
            val primary = controlsRow?.primaryActionsAdapter as? ArrayObjectAdapter ?: return
            if (primary.indexOf(prevChapter) >= 0) return
            primary.add(0, prevChapter)
            primary.add(nextChapter)
        }

        override fun onActionClicked(action: Action) {
            when (action) {
                is QualityAction -> {
                    action.nextIndex()
                    Prefs.maxQuality = action.currentHeight()
                    applyQuality(action.currentHeight())
                    notifySecondaryChanged(action)
                }
                prevChapter -> seekToChapter(forward = false)
                nextChapter -> seekToChapter(forward = true)
                else -> super.onActionClicked(action)
            }
        }

        override fun onUpdateProgress() {
            super.onUpdateProgress()
            if (chapters.isEmpty()) return
            val c = currentChapter(currentPosition)
            if (c !== lastChapter) {
                lastChapter = c
                subtitle = if (c != null) Format.duration(c.startSec) + "  " + c.title else baseSubtitle()
            }
        }

        private fun notifySecondaryChanged(action: Action) {
            val secondary = controlsRow?.secondaryActionsAdapter as? ArrayObjectAdapter ?: return
            val idx = secondary.indexOf(action)
            if (idx >= 0) secondary.notifyArrayItemRangeChanged(idx, 1)
        }
    }

    /** Secondary control that cycles the resolution cap. */
    private class QualityAction(ctx: Context) : PlaybackControlsRow.MultiAction(ID) {
        private val heights = intArrayOf(Prefs.QUALITY_AUTO, 2160, 1440, 1080, 720, 480)

        init {
            val labels = heights.map { if (it == Prefs.QUALITY_AUTO) ctx.getString(R.string.player_quality_auto) else "${it}p" }
            setLabels(labels.toTypedArray())
            setDrawables(Array(heights.size) { ContextCompat.getDrawable(ctx, R.drawable.ic_quality) })
            index = heights.indexOf(Prefs.maxQuality).coerceAtLeast(0)
        }

        fun currentHeight(): Int = heights[index]

        companion object {
            const val ID = 0x1000
        }
    }

    companion object {
        const val ARG_TOPIC = "topic"
        const val ARG_VIDEO = "video"
        const val ARG_DURATION = "duration"
        const val ARG_START = "start"
        private const val PROGRESS_INTERVAL_MS = 15_000L
        private const val SERVER_SAVE_INTERVAL_MS = 60_000L
    }
}
