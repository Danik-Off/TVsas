package com.tvsas.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.ApiException
import com.tvsas.app.data.HistoryEntry
import com.tvsas.app.data.Prefs
import com.tvsas.app.data.Topic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Home screen: "continue watching", latest posts, one row per site category and a menu row.
 *
 * Loading is staged for slow boxes: the "Новое" row is shown as soon as it arrives, then the
 * category rows are fetched one after another (no request storm) and inserted in a single batch
 * so the headers panel animates once instead of eight times.
 */
class MainFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { shadowEnabled = false },
    )
    private val cardSelector = ClassPresenterSelector().apply {
        val cards = CardPresenter()
        val icons = IconCardPresenter()
        addClassPresenter(Topic::class.java, cards)
        addClassPresenter(HistoryEntry::class.java, cards)
        addClassPresenter(MoreItem::class.java, icons)
        addClassPresenter(MenuItem::class.java, icons)
    }

    private var continueRow: ListRow? = null
    private var menuRow: ListRow? = null
    private var loadJob: Job? = null
    private var loadedForToken: String? = "unset"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.bg_brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_orb_bright)
        setOnSearchClickedListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> onItemClicked(item) }
        adapter = rowsAdapter
    }

    override fun onResume() {
        super.onResume()
        if (loadedForToken != Prefs.token) {
            loadedForToken = Prefs.token
            reload()
        } else {
            refreshContinueRow()
        }
    }

    private fun reload() {
        loadJob?.cancel()
        rowsAdapter.clear()
        continueRow = null
        menuRow = null

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val pageSize = resources.getInteger(R.integer.row_page_size)
            val menu = menuRow()
            menuRow = menu
            try {
                val latest = Api.topics(limit = pageSize)
                refreshContinueRow()
                rowsAdapter.add(topicRow(ROW_LATEST, getString(R.string.row_latest), latest.rows, MoreItem(0, getString(R.string.row_latest))))
                rowsAdapter.add(menu)
            } catch (e: Exception) {
                showError(e)
                refreshContinueRow()
                rowsAdapter.add(menu)
                return@launch
            }

            val categoryRows = ArrayList<ListRow>()
            try {
                for (c in Api.categories()) {
                    try {
                        val page = Api.topics(categoryId = c.id, limit = pageSize)
                        if (page.rows.isNotEmpty()) {
                            categoryRows += topicRow(ROW_CATEGORY_BASE + c.id, c.title, page.rows, MoreItem(c.id, c.title))
                        }
                    } catch (_: IOException) {
                        // A single failed category should not kill the whole home screen.
                    }
                }
            } catch (e: Exception) {
                showError(e)
            }
            if (categoryRows.isNotEmpty()) {
                val at = rowsAdapter.indexOf(menu).takeIf { it >= 0 } ?: rowsAdapter.size()
                rowsAdapter.addAll(at, categoryRows)
            }
        }
    }

    private fun topicRow(id: Long, name: String, topics: List<Topic>, more: MoreItem): ListRow {
        val adapter = ArrayObjectAdapter(cardSelector)
        adapter.addAll(0, topics)
        adapter.add(more)
        return ListRow(HeaderItem(id, name), adapter)
    }

    private fun menuRow(): ListRow {
        val adapter = ArrayObjectAdapter(cardSelector)
        adapter.add(MenuItem(MenuItem.SEARCH, getString(R.string.menu_search), R.drawable.ic_search))
        adapter.add(
            if (Prefs.isLoggedIn) MenuItem(MenuItem.ACCOUNT, Prefs.username ?: getString(R.string.menu_profile), R.drawable.ic_person)
            else MenuItem(MenuItem.ACCOUNT, getString(R.string.menu_login), R.drawable.ic_login),
        )
        adapter.add(MenuItem(MenuItem.SETTINGS, getString(R.string.menu_settings), R.drawable.ic_settings))
        adapter.add(MenuItem(MenuItem.REFRESH, getString(R.string.menu_refresh), R.drawable.ic_refresh))
        return ListRow(HeaderItem(ROW_MENU, getString(R.string.row_menu)), adapter)
    }

    /** Rebuilds the local "continue watching" row without touching the network. */
    private fun refreshContinueRow() {
        val history = Prefs.history().filter { it.percent in 1..95 }
        val existing = continueRow
        if (history.isEmpty()) {
            if (existing != null) {
                rowsAdapter.remove(existing)
                continueRow = null
            }
            return
        }
        val adapter = (existing?.adapter as? ArrayObjectAdapter) ?: ArrayObjectAdapter(cardSelector)
        adapter.setItems(history, null)
        if (existing == null) {
            val row = ListRow(HeaderItem(ROW_CONTINUE, getString(R.string.row_continue)), adapter)
            continueRow = row
            rowsAdapter.add(0, row)
        }
    }

    private fun onItemClicked(item: Any) {
        val ctx = requireContext()
        when (item) {
            is Topic -> startActivity(DetailsActivity.intent(ctx, item))
            is HistoryEntry -> startActivity(DetailsActivity.intent(ctx, item.topic))
            is MoreItem -> startActivity(GridActivity.intent(ctx, item.categoryId, item.title))
            is MenuItem -> when (item.id) {
                MenuItem.SEARCH -> startActivity(Intent(ctx, SearchActivity::class.java))
                MenuItem.ACCOUNT -> startActivity(Intent(ctx, if (Prefs.isLoggedIn) SettingsActivity::class.java else LoginActivity::class.java))
                MenuItem.SETTINGS -> startActivity(Intent(ctx, SettingsActivity::class.java))
                MenuItem.REFRESH -> {
                    Api.clearCache()
                    reload()
                }
            }
        }
    }

    private fun showError(e: Exception) {
        val ctx = context ?: return
        val msg = if (e is IOException && e !is ApiException) ctx.getString(R.string.error_network)
        else ctx.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val ROW_CONTINUE = 1L
        const val ROW_LATEST = 2L
        const val ROW_MENU = 3L
        const val ROW_CATEGORY_BASE = 100L
    }
}
