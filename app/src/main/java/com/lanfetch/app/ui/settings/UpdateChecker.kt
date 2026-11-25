package com.lanfetch.app.ui.settings

import com.lanfetch.app.BuildConfig
import com.lanfetch.app.net.withCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class UpdateStatus { NOT_CHECKED, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

data class UpdateCheckState(
    val status: UpdateStatus = UpdateStatus.NOT_CHECKED,
    val latestVersion: String? = null,
    val releaseUrl: String? = null,
)

class UpdateChecker(private val client: OkHttpClient) {
    suspend fun check(): UpdateCheckState = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("User-Agent", "LanFetch/${BuildConfig.VERSION_NAME}")
            .build()

        client.withCall(request) { response ->
            response.use { res ->
                if (!res.isSuccessful) error("Release request failed")
                // GitHub redirects /releases/latest to /releases/tag/<tag>.
                // Read the final URL instead of the Releases API, which has a
                // comparatively low unauthenticated rate limit.
                val finalUrl = res.request.url
                val segments = finalUrl.pathSegments
                val tagIndex = segments.indexOf("tag")
                val tag = segments.getOrNull(tagIndex + 1)
                    ?.takeIf { tagIndex >= 0 && it.isNotBlank() }
                    ?: error("No published release was found")
                val comparison = ReleaseVersionComparator.compare(BuildConfig.VERSION_NAME, tag)
                    ?: error("Release version is not comparable")
                UpdateCheckState(
                    status = if (comparison < 0) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE,
                    latestVersion = tag,
                    releaseUrl = finalUrl.toString(),
                )
            }
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://github.com/turjaun985123/lanfetch/releases/latest"
    }
}

internal object ReleaseVersionComparator {
    private val versionPattern = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+].*)?$""")

    fun compare(current: String, latest: String): Int? {
        val currentParts = numericParts(current) ?: return null
        val latestParts = numericParts(latest) ?: return null
        for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
            val left = currentParts.getOrElse(i) { "0" }
            val right = latestParts.getOrElse(i) { "0" }
            val lengthOrder = left.length.compareTo(right.length)
            if (lengthOrder != 0) return lengthOrder
            val digitOrder = left.compareTo(right)
            if (digitOrder != 0) return digitOrder
        }
        return 0
    }

    private fun numericParts(value: String): List<String>? {
        val core = versionPattern.matchEntire(value.trim())?.groupValues?.get(1) ?: return null
        return core.split('.').map { it.trimStart('0').ifEmpty { "0" } }
    }
}
