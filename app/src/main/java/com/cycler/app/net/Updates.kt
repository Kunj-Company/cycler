package com.cycler.app.net

import org.json.JSONArray
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL

/** One published GitHub release of Cycler. */
data class Release(
    val version: String,
    val tag: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val publishedAt: String?,
    val body: String?,
)

sealed class UpdateCheck {
    data class UpToDate(val version: String) : UpdateCheck()
    data class Available(val release: Release) : UpdateCheck()
    data class Error(val message: String) : UpdateCheck()
}

/** Looks for a newer Cycler release on GitHub. Only runs when the rider asks. */
object Updates {
    const val RELEASES_API = "https://api.github.com/repos/Kunj-Company/cycler/releases?per_page=10"
    const val REPO_URL = "https://github.com/Kunj-Company/cycler"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val NETWORK_ERROR = "Couldn't reach GitHub. Check your connection and try again."

    /** "v0.1.10-beta" -> [0, 1, 10]. Null when there are no numbers at all. */
    fun parseVersion(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        return parts.ifEmpty { null }
    }

    /** Numeric, per component; missing components count as 0. */
    fun compare(a: String, b: String): Int {
        val x = parseVersion(a) ?: emptyList()
        val y = parseVersion(b) ?: emptyList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Releases from the API's JSON array. Drafts and tags that aren't versions are skipped. */
    fun parseReleases(json: String): List<Release> {
        val arr = JSONArray(json)
        val out = ArrayList<Release>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft", false)) continue
            val tag = o.optString("tag_name")
            val parsed = parseVersion(tag) ?: continue
            var apk: String? = null
            o.optJSONArray("assets")?.let { assets ->
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url").ifBlank { null }
                        if (apk != null) break
                    }
                }
            }
            out.add(
                Release(
                    version = parsed.joinToString("."), tag = tag,
                    htmlUrl = o.optString("html_url").ifBlank { RELEASES_URL },
                    apkUrl = apk,
                    publishedAt = o.optString("published_at").ifBlank { null },
                    body = o.optString("body").ifBlank { null },
                ),
            )
        }
        return out
    }

    /** Pure decision: the highest release, compared with the installed [current] version. */
    fun decide(current: String, json: String): UpdateCheck {
        val releases = try {
            parseReleases(json)
        } catch (e: JSONException) {
            return UpdateCheck.Error("GitHub sent something unexpected. Try again later.")
        }
        val best = releases.maxWithOrNull { a, b -> compare(a.version, b.version) }
            ?: return UpdateCheck.UpToDate(current)
        return if (compare(best.version, current) > 0) UpdateCheck.Available(best) else UpdateCheck.UpToDate(current)
    }

    /** "2026-11-23T10:00:00Z" -> "23 Nov 2026", or null. */
    fun displayDate(iso: String?): String? {
        val m = iso?.let { Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(it) } ?: return null
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val (y, mo, d) = m.destructured
        val name = months.getOrNull(mo.toInt() - 1) ?: return null
        return "${d.toInt()} $name $y"
    }

    /** Blocking; call off the main thread. */
    fun check(current: String): UpdateCheck {
        val body = try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Cycler/0.1 (personal Android app)")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code !in 200..299) return UpdateCheck.Error(NETWORK_ERROR)
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return UpdateCheck.Error(NETWORK_ERROR)
        }
        return decide(current, body)
    }
}
