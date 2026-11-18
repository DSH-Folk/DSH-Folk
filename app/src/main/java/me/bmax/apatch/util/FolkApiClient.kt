package me.bmax.apatch.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.bmax.apatch.apApp
import okhttp3.Request
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

object FolkApiClient {
    private const val TAG = "FolkApiClient"
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_MAX_RETRIES = 2
    private const val INITIAL_BACKOFF_MS = 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class CacheEntry(
        val data: String,
        val timestamp: Long
    )

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String>>()

    /**
     * 在途请求 → 发起它的链路标签。
     *
     * 存在的理由只有一个：**取消要能分得开**。
     *
     * `cancelInFlightRequests()` 取消的是全部在途请求，而冷启动时应用更新与运行时更新是
     * **并行**跑的（两条都走 [fetchJson]）。用户在这期间点「手动检查应用更新」，原来会把
     * 运行时那条也一起取消 —— 它拿到 null 后静默当作"没查到"，于是运行时更新提示时有时无，
     * 且不报任何错。这类"偶发、无报错"的行为最难查。
     *
     * 加标签之后「检查应用更新」只取消它自己那条链，互不干扰。
     */
    private val inFlightTags = ConcurrentHashMap<String, String>()

    suspend fun fetchJson(
        url: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        forceRefresh: Boolean = false,
        /** 发起这条请求的链路标签，用于按链路取消（见 [cancelTagged]）。 */
        tag: String? = null,
    ): Result<String> {
        // 安全校验：拒绝非 HTTP/HTTPS 协议的 URL
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.e(TAG, "Rejected unsafe URL scheme: $url")
            return Result.failure(IOException("Unsafe URL scheme"))
        }

        if (!forceRefresh) {
            val cached = memoryCache[url]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < ttlMs) {
                Log.d(TAG, "Cache hit: $url")
                return Result.success(cached.data)
            }
        }

        val existing = inFlightRequests[url]
        if (existing != null) {
            Log.d(TAG, "Deduplicating in-flight request: $url")
            return try {
                Result.success(existing.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val deferred = scope.async {
            fetchWithRetry(url, maxRetries)
        }

        inFlightRequests[url] = deferred
        if (tag != null) inFlightTags[url] = tag

        return try {
            val result = deferred.await()
            memoryCache[url] = CacheEntry(result, System.currentTimeMillis())
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            inFlightRequests.remove(url)
            inFlightTags.remove(url)
        }
    }

    private suspend fun fetchWithRetry(url: String, maxRetries: Int): String {
        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val request = Request.Builder().url(url).build()
                val response = apApp.okhttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return response.body?.string() ?: ""
                }
                if (response.code in 400..499) {
                    throw IOException("HTTP ${response.code}")
                }
                lastException = IOException("HTTP ${response.code}")
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Socket timeout for $url (attempt ${attempt + 1}/${maxRetries + 1})")
                lastException = e
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown host for $url: ${e.message}")
                throw e
            } catch (e: SocketException) {
                lastException = e
            } catch (e: IOException) {
                lastException = e
            }

            if (attempt < maxRetries) {
                val backoff = INITIAL_BACKOFF_MS * (1L shl attempt)
                Log.d(TAG, "Retry $attempt/$maxRetries for $url, waiting ${backoff}ms")
                delay(backoff)
            }
        }
        throw lastException ?: IOException("Unknown error")
    }

    fun cancelInFlightRequests() {
        inFlightRequests.values.forEach { it.cancel() }
    }

    /**
     * 只取消**某条链路**发起的在途请求。
     *
     * 没有标签的请求（历史上没传 tag 的调用方）不会被这里取消 —— 那是有意的：
     * 宁可少取消一条，也不要在用户点「检查应用更新」时把运行时更新一起打断。
     */
    fun cancelTagged(tag: String) {
        inFlightTags.forEach { (url, t) ->
            if (t == tag) inFlightRequests[url]?.cancel()
        }
    }

    fun clearCache() {
        memoryCache.clear()
    }

    fun clearCacheForUrl(url: String) {
        memoryCache.remove(url)
    }

    fun prefetch(vararg urls: String) {
        urls.forEach { url ->
            scope.launch {
                try {
                    fetchJson(url, forceRefresh = false)
                    Log.d(TAG, "Prefetch success: $url")
                } catch (_: Exception) {
                }
            }
        }
    }
}
