package com.tvsas.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.Topic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SearchFragment())
                .commit()
        }
        // Voice search needs the mic; the keyboard keeps working if the user declines.
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
}

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { shadowEnabled = false },
    )
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(
            OnItemViewClickedListener { _, item, _, _ ->
                if (item is Topic) startActivity(DetailsActivity.intent(requireContext(), item))
            },
        )
        title = getString(R.string.search_hint)
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        search(newQuery, debounceMs = 500)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        search(query, debounceMs = 0)
        return true
    }

    private fun search(query: String, debounceMs: Long) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            rowsAdapter.clear()
            return
        }
        searchJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                val page = Api.search(q)
                rowsAdapter.clear()
                if (page.rows.isEmpty()) {
                    context?.let { Toast.makeText(it, R.string.search_nothing, Toast.LENGTH_SHORT).show() }
                } else {
                    val a = ArrayObjectAdapter(CardPresenter())
                    a.addAll(0, page.rows)
                    rowsAdapter.add(ListRow(HeaderItem(0, getString(R.string.search_results) + " (${page.total})"), a))
                }
            } catch (e: Exception) {
                context?.let { Toast.makeText(it, it.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
            }
        }
    }
}
