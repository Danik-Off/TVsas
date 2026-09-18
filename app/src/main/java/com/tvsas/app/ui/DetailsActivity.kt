package com.tvsas.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.Prefs
import com.tvsas.app.data.Topic
import com.tvsas.app.data.TopicDetail
import com.tvsas.app.util.Format
import kotlinx.coroutines.launch

class DetailsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, DetailsFragment().apply { arguments = intent.extras })
                .commit()
        }
    }

    companion object {
        fun intent(ctx: Context, topic: Topic): Intent =
            Intent(ctx, DetailsActivity::class.java).putExtra(DetailsFragment.ARG_TOPIC, topic)
    }
}

class DetailsFragment : DetailsSupportFragment() {

    private lateinit var topic: Topic
    private var detail: TopicDetail? = null
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var overviewRow: DetailsOverviewRow
    private lateinit var actionsAdapter: ArrayObjectAdapter
    private var loadedForToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topic = BundleCompat.getParcelable(requireArguments(), ARG_TOPIC, Topic::class.java)
            ?: run { requireActivity().finish(); return }

        val ctx = requireContext()
        val overviewPresenter = FullWidthDetailsOverviewRowPresenter(DescriptionPresenter()).apply {
            backgroundColor = ContextCompat.getColor(ctx, R.color.bg_brand)
            actionsBackgroundColor = ContextCompat.getColor(ctx, R.color.bg)
            initialState = FullWidthDetailsOverviewRowPresenter.STATE_HALF
            setOnActionClickedListener { onAction(it) }
        }
        val selector = ClassPresenterSelector().apply {
            addClassPresenter(DetailsOverviewRow::class.java, overviewPresenter)
            addClassPresenter(ListRow::class.java, ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { shadowEnabled = false })
        }
        rowsAdapter = ArrayObjectAdapter(selector)
        adapter = rowsAdapter

        // Show what we already know immediately; the network fills in the rest.
        val placeholder = TopicDetail(topic, topic.teaser, emptyList())
        overviewRow = DetailsOverviewRow(placeholder)
        overviewRow.imageDrawable = ContextCompat.getDrawable(ctx, R.drawable.card_placeholder)
        actionsAdapter = ArrayObjectAdapter()
        overviewRow.actionsAdapter = actionsAdapter
        rowsAdapter.add(overviewRow)

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Topic) startActivity(DetailsActivity.intent(requireContext(), item))
        }

        loadCover()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (detail != null && loadedForToken != Prefs.token) {
            load() // access flags depend on the account; user just logged in or out
        } else {
            detail?.let { buildActions(it) } // position may have changed after playback
        }
    }

    private fun loadCover() {
        val cover = topic.coverUuid ?: return
        val ctx = requireContext()
        val w = resources.getDimensionPixelSize(R.dimen.details_poster_width)
        val h = resources.getDimensionPixelSize(R.dimen.details_poster_height)
        val request = ImageRequest.Builder(ctx)
            .data(Api.imageUrl(cover, 640, 360))
            .size(w, h)
            .target { drawable ->
                overviewRow.imageDrawable = drawable
                rowsAdapter.notifyArrayItemRangeChanged(0, 1)
            }
            .build()
        ctx.imageLoader.enqueue(request)
    }

    private fun load() {
        loadedForToken = Prefs.token
        lifecycleScope.launch {
            try {
                val d = Api.topic(topic.uuid)
                detail = d
                topic = d.topic
                overviewRow.item = d
                buildActions(d)
                rowsAdapter.notifyArrayItemRangeChanged(0, 1)
                loadRelated(d)
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, it.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
                buildActions(TopicDetail(topic, topic.teaser, emptyList()).also { detail = it })
            }
        }
    }

    private fun buildActions(d: TopicDetail) {
        val ctx = context ?: return
        val t = d.topic
        actionsAdapter.clear()
        when {
            !t.hasVideo && d.videos.isEmpty() ->
                actionsAdapter.add(Action(ACTION_NONE, ctx.getString(R.string.details_no_video)))

            t.locked -> {
                actionsAdapter.add(Action(ACTION_SUBSCRIBE, ctx.getString(R.string.action_subscription_required, t.levelTitle ?: "")))
                if (!Prefs.isLoggedIn) actionsAdapter.add(Action(ACTION_LOGIN, ctx.getString(R.string.action_login_required)))
            }

            else -> {
                val main = d.videos.firstOrNull()
                val resumeSec = main?.let { resumePosition(it.uuid) } ?: 0
                if (resumeSec > 0) {
                    actionsAdapter.add(Action(ACTION_PLAY, ctx.getString(R.string.action_resume, Format.duration(resumeSec))))
                    actionsAdapter.add(Action(ACTION_RESTART, ctx.getString(R.string.action_restart)))
                } else {
                    actionsAdapter.add(Action(ACTION_PLAY, ctx.getString(R.string.action_play)))
                }
                if (d.videos.size > 1) {
                    d.videos.drop(1).forEachIndexed { i, _ ->
                        actionsAdapter.add(Action(ACTION_PART_BASE + i + 1, ctx.getString(R.string.action_play_part, i + 2)))
                    }
                }
            }
        }
    }

    /** Local history first, then the server-reported position for logged-in users. */
    private fun resumePosition(videoUuid: String): Int {
        val local = Prefs.historyFor(videoUuid)
        if (local != null) return if (local.percent >= 96) 0 else local.positionSec
        val server = topic.serverTimeSec ?: 0
        return if (topic.durationSec > 0 && server * 100 / topic.durationSec >= 96) 0 else server
    }

    private fun onAction(action: Action) {
        val ctx = requireContext()
        val d = detail ?: return
        when (action.id) {
            ACTION_PLAY -> play(d, 0, resume = true)
            ACTION_RESTART -> play(d, 0, resume = false)
            ACTION_SUBSCRIBE -> Toast.makeText(ctx, R.string.subscription_hint, Toast.LENGTH_LONG).show()
            ACTION_LOGIN -> startActivity(Intent(ctx, LoginActivity::class.java))
            in ACTION_PART_BASE..ACTION_PART_BASE + 50 -> play(d, (action.id - ACTION_PART_BASE).toInt(), resume = true)
        }
    }

    private fun play(d: TopicDetail, index: Int, resume: Boolean) {
        val video = d.videos.getOrNull(index) ?: return
        val start = if (resume) resumePosition(video.uuid) else 0
        startActivity(PlayerActivity.intent(requireContext(), d.topic, video.uuid, video.durationSec, start))
    }

    private fun loadRelated(d: TopicDetail) {
        if (rowsAdapter.size() > 1) return // already shown
        val categoryTitle = d.topic.categoryTitle ?: return
        val categoryId = d.topic.categoryId ?: return
        lifecycleScope.launch {
            runCatching {
                val page = Api.topics(categoryId = categoryId, limit = 12)
                val rows = page.rows.filter { it.uuid != d.topic.uuid }
                if (rows.isNotEmpty()) {
                    val a = ArrayObjectAdapter(CardPresenter())
                    a.addAll(0, rows)
                    rowsAdapter.add(ListRow(HeaderItem(1, categoryTitle), a))
                }
            }
        }
    }

    companion object {
        const val ARG_TOPIC = "topic"
        private const val ACTION_PLAY = 1L
        private const val ACTION_RESTART = 2L
        private const val ACTION_SUBSCRIBE = 3L
        private const val ACTION_LOGIN = 4L
        private const val ACTION_NONE = 5L
        private const val ACTION_PART_BASE = 100L
    }
}
