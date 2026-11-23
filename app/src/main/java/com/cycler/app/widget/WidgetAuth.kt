package com.cycler.app.widget

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A random per-install secret attached to every widget tap that opens the exported
 * [com.cycler.app.MainActivity]. Other apps can send the same extras but cannot know the token, so they
 * cannot start a ride or close the page the rider is on. Kept in its own private prefs file (not the
 * main one, which is backed up and restored) and generated on first use.
 */
object WidgetAuth {
    private const val FILE = "widget_auth"
    private const val KEY = "token"
    private val lock = Any()

    /** The install's token. A newly made one means old widget taps are stale, so widgets are rebuilt. */
    fun token(ctx: Context): String {
        val app = ctx.applicationContext ?: ctx
        var created = false
        val t = synchronized(lock) {
            val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs.getString(KEY, null)?.takeIf { it.length >= 16 } ?: run {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val fresh = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                prefs.edit().putString(KEY, fresh).commit()
                created = true
                fresh
            }
        }
        if (created) Widgets.updateAll(app)
        return t
    }

    /** Constant-time check of a presented token. */
    fun valid(ctx: Context, presented: String?): Boolean {
        if (presented == null) return false
        val expected = token(ctx)
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }
}
