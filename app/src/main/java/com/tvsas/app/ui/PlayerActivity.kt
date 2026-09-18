package com.tvsas.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.tvsas.app.App
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.data.Prefs
import com.tvsas.app.data.Topic
import kotlinx.coroutines.launch

class PlayerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    private var glue: PlaybackTransportControlGlue<LeanbackPlayerAdapter>? = null

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
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .build()
        }
        trackSelector = selector

        // Less RAM than defaults (50 s/50 s) while still riding out short network hiccups.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
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

        val exo = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(HlsMediaSource.Factory(http).setAllowChunklessPreparation(true))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
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
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(force = true)
                    activity?.finish()
                }
            }
        })

        val adapter = LeanbackPlayerAdapter(ctx, exo, 500)
        val g = object : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(ctx, adapter) {
            override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
                super.onCreateSecondaryActions(adapter)
                adapter.add(QualityAction(ctx))
            }

            override fun onActionClicked(action: Action) {
                if (action is QualityAction) {
                    action.nextIndex()
                    Prefs.maxQuality = action.currentHeight()
                    applyQuality(action.currentHeight())
                    notifyActionChanged(action)
                } else {
                    super.onActionClicked(action)
                }
            }

            private fun notifyActionChanged(action: Action) {
                val secondary = controlsRow?.secondaryActionsAdapter as? ArrayObjectAdapter ?: return
                val idx = secondary.indexOf(action)
                if (idx >= 0) secondary.notifyArrayItemRangeChanged(idx, 1)
            }
        }
        g.host = VideoSupportFragmentGlueHost(this)
        g.title = topic.title
        g.subtitle = listOfNotNull(topic.categoryTitle, topic.levelTitle?.takeIf { topic.paid }).joinToString(" · ")
        g.isSeekEnabled = true
        g.isControlsOverlayAutoHideEnabled = true
        glue = g

        val item = MediaItem.Builder()
            .setUri(Api.videoUrl(videoUuid))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        exo.setMediaItem(item, if (startSec > 0) startSec * 1000L else C.TIME_UNSET)
        exo.prepare()
        exo.playWhenReady = true

        // Logged-in users may have a newer server-side position than the local one.
        if (Prefs.isLoggedIn && startSec == 0) {
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

    private fun applyQuality(maxHeight: Int) {
        val ts = trackSelector ?: return
        ts.parameters = ts.buildUponParameters().applyQualityCap(maxHeight).build()
    }

    private fun DefaultTrackSelector.Parameters.Builder.applyQualityCap(maxHeight: Int): DefaultTrackSelector.Parameters.Builder =
        if (maxHeight == Prefs.QUALITY_AUTO) {
            clearVideoSizeConstraints().setViewportSizeToPhysicalDisplaySize(true)
        } else {
            setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        }

    private fun saveProgress(force: Boolean) {
        val p = player ?: return
        val posSec = (p.currentPosition / 1000).toInt()
        val dur = (if (p.duration > 0) p.duration / 1000 else durationSec.toLong()).toInt()
        if (posSec <= 0 || dur <= 0) return
        Prefs.saveHistory(HistoryEntry(topic, videoUuid, posSec, dur, System.currentTimeMillis()))

        if (!Prefs.isLoggedIn) return
        val now = System.currentTimeMillis()
        if (force || now - lastServerSaveMs >= SERVER_SAVE_INTERVAL_MS) {
            lastServerSaveMs = now
            val quality = p.videoFormat?.height?.takeIf { it > 0 }?.toString() ?: "auto"
            // Fire-and-forget on the app scope; the fragment may be going away.
            App.ioScope.launch { Api.saveProgress(videoUuid, posSec, quality) }
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressTicker)
        glue?.host = null
        glue = null
        player?.release()
        player = null
        trackSelector = null
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
