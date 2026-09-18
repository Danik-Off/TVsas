package com.tvsas.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.ApiException
import com.tvsas.app.data.Prefs
import kotlinx.coroutines.launch

class LoginActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, LoginFragment(), android.R.id.content)
        }
    }
}

/** Username / password form built with Leanback's GuidedStep so it works with a D-pad. */
class LoginFragment : GuidedStepSupportFragment() {

    private var busy = false

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.login_title),
            getString(R.string.login_description),
            getString(R.string.app_name),
            null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        actions += GuidedAction.Builder(ctx)
            .id(ID_USERNAME)
            .title(R.string.login_username)
            .editTitle("")
            .editable(true)
            .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            .build()
        actions += GuidedAction.Builder(ctx)
            .id(ID_PASSWORD)
            .title(R.string.login_password)
            .editTitle("")
            .editable(true)
            .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .build()
        actions += GuidedAction.Builder(ctx).id(ID_SUBMIT).title(R.string.login_submit).build()
        actions += GuidedAction.Builder(ctx).id(ID_CANCEL).title(R.string.login_cancel).build()
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        // Keep the typed value visible as the title, then jump to the next field.
        val typed = action.editTitle?.toString().orEmpty()
        if (action.id == ID_PASSWORD) {
            action.title = if (typed.isEmpty()) getString(R.string.login_password) else "•".repeat(typed.length)
            notifyActionChanged(findActionPositionById(ID_PASSWORD))
            return ID_SUBMIT
        }
        if (action.id == ID_USERNAME) {
            action.title = typed.ifEmpty { getString(R.string.login_username) }
            notifyActionChanged(findActionPositionById(ID_USERNAME))
            return ID_PASSWORD
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_SUBMIT -> submit()
            ID_CANCEL -> requireActivity().finish()
        }
    }

    private fun submit() {
        if (busy) return
        val username = findActionById(ID_USERNAME)?.editTitle?.toString()?.trim().orEmpty()
        val password = findActionById(ID_PASSWORD)?.editTitle?.toString().orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(context, R.string.login_empty, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        setSubmitLabel(getString(R.string.login_progress))
        lifecycleScope.launch {
            try {
                val token = Api.login(username, password)
                Prefs.token = token
                Api.clearCache()
                val user = runCatching { Api.profile() }.getOrNull()
                Prefs.username = user?.fullname?.ifEmpty { null } ?: user?.username ?: username
                Prefs.subscription = user?.subscriptionLevel
                context?.let { Toast.makeText(it, it.getString(R.string.login_success, Prefs.username), Toast.LENGTH_SHORT).show() }
                activity?.finish()
            } catch (e: Exception) {
                val reason = when ((e as? ApiException)?.message) {
                    "errors.login.wrong" -> getString(R.string.error_login_wrong)
                    "errors.user.inactive" -> getString(R.string.error_user_inactive)
                    "errors.user.blocked" -> getString(R.string.error_user_blocked)
                    else -> e.message ?: getString(R.string.error_network)
                }
                context?.let { Toast.makeText(it, it.getString(R.string.login_failed, reason), Toast.LENGTH_LONG).show() }
                setSubmitLabel(getString(R.string.login_submit))
            } finally {
                busy = false
            }
        }
    }

    private fun setSubmitLabel(label: String) {
        findActionById(ID_SUBMIT)?.title = label
        notifyActionChanged(findActionPositionById(ID_SUBMIT))
    }

    private companion object {
        const val ID_USERNAME = 1L
        const val ID_PASSWORD = 2L
        const val ID_SUBMIT = 3L
        const val ID_CANCEL = 4L
    }
}
