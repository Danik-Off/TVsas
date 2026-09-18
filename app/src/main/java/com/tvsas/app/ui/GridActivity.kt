package com.tvsas.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.Topic
import kotlinx.coroutines.launch

class GridActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, GridFragment().apply { arguments = intent.extras })
                .commit()
        }
    }

    companion object {
        fun intent(ctx: Context, categoryId: Int, title: String): Intent =
            Intent(ctx, GridActivity::class.java)
                .putExtra(GridFragment.ARG_CATEGORY, categoryId)
                .putExtra(GridFragment.ARG_TITLE, title)
    }
}

/** Paginated grid of a category (or of the whole feed when [ARG_CATEGORY] is 0). */
class GridFragment : VerticalGridSupportFragment() {

    private val gridAdapter = ArrayObjectAdapter(CardPresenter())
    private var page = 0
    private var total = Int.MAX_VALUE
    private var loading = false
    private val categoryId: Int get() = arguments?.getInt(ARG_CATEGORY) ?: 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = arguments?.getString(ARG_TITLE).orEmpty()
        setGridPresenter(VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false).apply {
            numberOfColumns = resources.getInteger(R.integer.grid_columns)
            shadowEnabled = false
        })
        adapter = gridAdapter
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Topic) startActivity(DetailsActivity.intent(requireContext(), item))
        }
        setOnItemViewSelectedListener { _, item, _, _ ->
            val index = gridAdapter.indexOf(item)
            if (index >= 0 && index >= gridAdapter.size() - numberOfColumnsTimesTwo()) loadNextPage()
        }
        loadNextPage()
    }

    private fun numberOfColumnsTimesTwo() = resources.getInteger(R.integer.grid_columns) * 2

    private fun loadNextPage() {
        if (loading || gridAdapter.size() >= total) return
        loading = true
        val next = page + 1
        val limit = resources.getInteger(R.integer.grid_page_size)
        lifecycleScope.launch {
            try {
                val result = Api.topics(categoryId = categoryId.takeIf { it > 0 }, page = next, limit = limit)
                total = result.total
                page = next
                gridAdapter.addAll(gridAdapter.size(), result.rows)
                if (gridAdapter.size() == 0) {
                    Toast.makeText(context, R.string.empty_list, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, it.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
            } finally {
                loading = false
            }
        }
    }

    companion object {
        const val ARG_CATEGORY = "category"
        const val ARG_TITLE = "title"
    }
}
