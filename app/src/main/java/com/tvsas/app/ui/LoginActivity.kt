package com.tvsas.app.ui

import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tvsas.app.R
import com.tvsas.app.data.Api
import com.tvsas.app.data.ApiException
import com.tvsas.app.data.Prefs
import kotlinx.coroutines.launch

/**
 * Username / password form with a built-in D-pad keyboard.
 *
 * Many cheap TV boxes ship without a usable on-screen IME (or with a "remote app" IME that never
 * shows up), so the form never depends on the system keyboard: every character can be entered with
 * the remote via the key grid on the right. A system IME, when present, still works on the fields.
 */
class LoginActivity : FragmentActivity() {

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var submit: Button
    private lateinit var status: TextView
    private lateinit var keyboard: LinearLayout

    /** Field that receives characters from the built-in keyboard. */
    private var target: EditText? = null
    private var page = Page.LATIN
    private var shift = false
    private var passwordVisible = false
    private var busy = false

    private enum class Page { LATIN, CYRILLIC, SYMBOLS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        submit = findViewById(R.id.submit)
        status = findViewById(R.id.status)
        keyboard = findViewById(R.id.keyboard)

        val focusTracker = View.OnFocusChangeListener { v, hasFocus -> if (hasFocus) setTarget(v as EditText) }
        username.onFocusChangeListener = focusTracker
        password.onFocusChangeListener = focusTracker
        username.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false }
        password.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        submit.setOnClickListener { submit() }

        // Some remotes send ENTER on OK; the system may still open its IME — harmless.
        val enterSubmits = View.OnKeyListener { v, code, e ->
            if (e.action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_ENTER && v === password) { submit(); true } else false
        }
        password.setOnKeyListener(enterSubmits)

        buildKeyboard()
        setTarget(username)
        username.requestFocus()
    }

    private fun setTarget(field: EditText) {
        target = field
        username.isActivated = field === username
        password.isActivated = field === password
    }

    // ---- Built-in keyboard ----

    private fun buildKeyboard() {
        // Remember which key had focus so toggling Shift/layout doesn't throw focus away.
        val focused = keyboard.findFocus()
        val focusedRow = (focused?.parent as? View)?.let { keyboard.indexOfChild(it) } ?: -1
        val focusedCol = (focused?.parent as? LinearLayout)?.indexOfChild(focused) ?: -1
        keyboard.removeAllViews()
        val rows: List<List<String>> = when (page) {
            Page.LATIN -> listOf(
                "1 2 3 4 5 6 7 8 9 0".split(" "),
                "q w e r t y u i o p".split(" "),
                "a s d f g h j k l @".split(" "),
                "z x c v b n m . _ -".split(" "),
            )
            Page.CYRILLIC -> listOf(
                "1 2 3 4 5 6 7 8 9 0".split(" "),
                "й ц у к е н г ш щ з х ъ".split(" "),
                "ф ы в а п р о л д ж э ё".split(" "),
                "я ч с м и т ь б ю . @ -".split(" "),
            )
            Page.SYMBOLS -> listOf(
                "1 2 3 4 5 6 7 8 9 0".split(" "),
                "! \" # $ % & ' ( ) *".split(" "),
                "+ , - . / : ; < = >".split(" "),
                "? @ [ \\ ] ^ _ ` { |".split(" "),
                "} ~ € £ § № ° «".split(" "),
            )
        }
        rows.forEach { chars ->
            keyboard.addView(row(chars.map { c ->
                key(if (shift) c.uppercase() else c, weight = 1f) { type(if (shift) c.uppercase() else c) }
            }))
        }
        keyboard.addView(row(listOf(
            key(getString(R.string.key_shift), 1.4f, selected = shift) { shift = !shift; buildKeyboard() },
            key(
                when (page) { Page.LATIN -> getString(R.string.key_rus); Page.CYRILLIC -> getString(R.string.key_symbols); Page.SYMBOLS -> getString(R.string.key_abc) },
                1.4f,
            ) {
                page = when (page) { Page.LATIN -> Page.CYRILLIC; Page.CYRILLIC -> Page.SYMBOLS; Page.SYMBOLS -> Page.LATIN }
                buildKeyboard()
            },
            key(getString(R.string.key_space), 2.4f) { type(" ") },
            key(getString(R.string.key_backspace), 1.4f) { backspace() },
            key(getString(R.string.key_clear), 1.6f) { target?.setText("") },
        )))
        keyboard.addView(row(listOf(
            key(if (passwordVisible) getString(R.string.key_hide_password) else getString(R.string.key_show_password), 2f, selected = passwordVisible) {
                passwordVisible = !passwordVisible
                password.transformationMethod = if (passwordVisible) null else PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
                buildKeyboard()
            },
            key(getString(R.string.key_next), 2f) {
                if (target === username) password.requestFocus() else submit.requestFocus()
            },
        )))
        if (focusedRow >= 0) {
            val row = keyboard.getChildAt(focusedRow) as? LinearLayout
            (row?.getChildAt(focusedCol.coerceIn(0, (row?.childCount ?: 1) - 1)))?.requestFocus()
        }
    }

    private fun row(keys: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        keys.forEach { addView(it) }
    }

    private fun key(label: String, weight: Float, selected: Boolean = false, onClick: () -> Unit): View {
        val m = dp(3)
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = false
            isSelected = selected
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = if (label.length > 2) 13f else 17f
            setBackgroundResource(R.drawable.key_bg)
            minHeight = dp(40)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(m, m, m, m) }
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun type(s: String) {
        val f = target ?: return
        val pos = f.selectionEnd.takeIf { it >= 0 } ?: f.text.length
        f.text.insert(pos, s)
        if (shift && page != Page.SYMBOLS) { shift = false; buildKeyboard() } // one-shot shift like a phone keyboard
    }

    private fun backspace() {
        val f = target ?: return
        val pos = f.selectionEnd.takeIf { it > 0 } ?: f.text.length
        if (pos > 0) f.text.delete(pos - 1, pos)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- Submit ----

    private fun submit() {
        if (busy) return
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            status.text = getString(R.string.login_empty)
            return
        }
        busy = true
        submit.isEnabled = false
        status.text = getString(R.string.login_progress)
        lifecycleScope.launch {
            try {
                val token = Api.login(user, pass)
                Prefs.token = token
                Api.clearCache()
                val profile = runCatching { Api.profile() }.getOrNull()
                Prefs.username = profile?.fullname?.ifEmpty { null } ?: profile?.username ?: user
                Prefs.subscription = profile?.subscriptionLevel
                Toast.makeText(this@LoginActivity, getString(R.string.login_success, Prefs.username), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                val reason = when ((e as? ApiException)?.message) {
                    "errors.login.wrong" -> getString(R.string.error_login_wrong)
                    "errors.user.inactive" -> getString(R.string.error_user_inactive)
                    "errors.user.blocked" -> getString(R.string.error_user_blocked)
                    else -> e.message ?: getString(R.string.error_network)
                }
                status.text = getString(R.string.login_failed, reason)
                submit.isEnabled = true
                busy = false
            }
        }
    }
}
