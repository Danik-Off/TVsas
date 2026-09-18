package com.tvsas.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.App
import com.tvsas.app.BuildConfig
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.Prefs
import kotlinx.coroutines.launch

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }
}

class SettingsFragment : GuidedStepSupportFragment() {

    private val qualities = intArrayOf(Prefs.QUALITY_AUTO, 2160, 1440, 1080, 720, 480)

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.settings_description),
            getString(R.string.app_name),
            null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        val subActions = qualities.map { q ->
            GuidedAction.Builder(ctx)
                .id(ID_QUALITY_BASE + q)
                .title(qualityLabel(q))
                .checkSetId(1)
                .checked(q == Prefs.maxQuality)
                .build()
        }
        actions += GuidedAction.Builder(ctx)
            .id(ID_QUALITY)
            .title(R.string.settings_quality)
            .description(qualityLabel(Prefs.maxQuality) + " — " + getString(R.string.settings_quality_desc))
            .subActions(subActions)
            .build()

        actions += GuidedAction.Builder(ctx)
            .id(ID_ACCOUNT)
            .title(if (Prefs.isLoggedIn) Prefs.username ?: getString(R.string.settings_account) else getString(R.string.settings_account_none))
            .description(
                when {
                    !Prefs.isLoggedIn -> getString(R.string.menu_login)
                    Prefs.subscription != null -> getString(R.string.settings_account_subscription, Prefs.subscription)
                    else -> getString(R.string.settings_account_no_subscription)
                },
            )
            .build()

        if (Prefs.isLoggedIn) {
            actions += GuidedAction.Builder(ctx).id(ID_LOGOUT).title(R.string.settings_logout).build()
        }
        actions += GuidedAction.Builder(ctx).id(ID_CLEAR_HISTORY).title(R.string.settings_clear_history).build()
        actions += GuidedAction.Builder(ctx)
            .id(ID_ABOUT)
            .title(R.string.settings_about)
            .description(getString(R.string.settings_about_desc, BuildConfig.VERSION_NAME))
            .infoOnly(true)
            .build()
        actions += GuidedAction.Builder(ctx).id(ID_BACK).title(R.string.settings_back).build()
    }

    override fun onResume() {
        super.onResume()
        // Account state may have changed after the login screen.
        if ((findActionById(ID_LOGOUT) != null) != Prefs.isLoggedIn) {
            val fresh = ArrayList<GuidedAction>()
            onCreateActions(fresh, null)
            actions = fresh
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val q = (action.id - ID_QUALITY_BASE).toInt()
        if (q in qualities) {
            Prefs.maxQuality = q
            findActionById(ID_QUALITY)?.description = qualityLabel(q) + " — " + getString(R.string.settings_quality_desc)
            notifyActionChanged(findActionPositionById(ID_QUALITY))
        }
        return true
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val ctx = requireContext()
        when (action.id) {
            ID_ACCOUNT -> if (!Prefs.isLoggedIn) startActivity(Intent(ctx, LoginActivity::class.java))
            ID_LOGOUT -> {
                val token = Prefs.token
                Prefs.clearSession()
                Api.clearCache()
                if (token != null) App.ioScope.launch { Api.logout() }
                requireActivity().finish()
            }
            ID_CLEAR_HISTORY -> {
                Prefs.clearHistory()
                Toast.makeText(ctx, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show()
            }
            ID_BACK -> requireActivity().finish()
        }
    }

    private fun qualityLabel(q: Int): String =
        if (q == Prefs.QUALITY_AUTO) getString(R.string.settings_quality_auto) else "${q}p"

    private companion object {
        const val ID_QUALITY = 1L
        const val ID_ACCOUNT = 2L
        const val ID_LOGOUT = 3L
        const val ID_CLEAR_HISTORY = 4L
        const val ID_ABOUT = 5L
        const val ID_BACK = 6L
        const val ID_QUALITY_BASE = 1000L
    }
}
