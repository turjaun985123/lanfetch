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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared OkHttp client configured for high-throughput segmented transfers and
 * fast concurrent LAN directory browsing without exhausting connection pools.
 */
fun defaultHttpClient(): OkHttpClient {
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
