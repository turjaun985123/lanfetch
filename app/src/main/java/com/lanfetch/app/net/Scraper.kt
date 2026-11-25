package com.lanfetch.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses individual HTTP autoindex directory listings.
 */
class Scraper(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val USER_AGENT = "LanFetch/1.3"
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /**
         * Best poster candidate in a folder listing.
         */
        fun pickPoster(entries: List<DirEntry>): String? {
            fun score(name: String): Int {
                val n = name.lowercase()
                return when {
                    "poster" in n -> 5
                    "folder" in n -> 4
                    "cover" in n -> 4
                    "fanart" in n -> 3
                    "backdrop" in n -> 2
                    else -> 1
                }
            }
            return entries
                .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                .maxByOrNull { score(it.name) }
                ?.url
        }

        fun defaultClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    suspend fun fetchListing(url: String): List<DirEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.withCall(req) { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body?.string() ?: throw IllegalStateException("Empty response")
            AutoindexParser.parse(body, url)
        }
    }
}
