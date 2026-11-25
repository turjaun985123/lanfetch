package com.lanfetch.app.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared OkHttp client configured for high-throughput segmented transfers and
 * fast concurrent LAN directory browsing without exhausting connection pools.
 */
class CleartextHostPolicy {
    @Volatile private var configuredHosts: Set<String> = emptySet()

    fun updateSources(rootUrls: List<String>) {
        configuredHosts = rootUrls.mapNotNull { url ->
            runCatching { url.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrNull()
        }.toSet()
    }

    fun allows(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        return normalized in configuredHosts || isPrivateLiteral(normalized)
    }

    private fun isPrivateLiteral(host: String): Boolean {
        if (host == "localhost") return true
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
        if (!host.contains(':')) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }
}

fun defaultHttpClient(hostPolicy: CleartextHostPolicy = CleartextHostPolicy()): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = 48
        maxRequestsPerHost = 14
    }
    return OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(Interceptor { chain ->
            val request = chain.request()
            val origin = chain.call().request().url
            val target = request.url
            if (origin.host != target.host) {
                throw IOException("Server redirected to another host")
            }
            if (origin.isHttps && target.scheme == "http") {
                throw IOException("Server redirected to an unencrypted connection")
            }
            if (target.scheme == "http" && !hostPolicy.allows(target.host)) {
                throw IOException("Unencrypted connections are allowed only to private or added servers")
            }
            chain.proceed(request)
        })
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Runs an OkHttp call scoped to the current coroutine. Cancelling the coroutine
 * cancels [Call] immediately—even after response headers arrive and [block] is
 * blocked inside `InputStream.read`—closing the socket without waiting for the
 * read timeout.
 */
suspend fun <T> OkHttpClient.withCall(
    request: Request,
    block: suspend (Response) -> T,
): T = coroutineScope {
    val call: Call = newCall(request)
    val finished = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) call.cancel()
        }
    }
    try {
        val response = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
        response.use { block(it) }
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
