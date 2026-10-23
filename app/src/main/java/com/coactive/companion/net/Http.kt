package com.coactive.companion.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
}

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Menjalankan Call OkHttp sebagai suspend function yang bisa dibatalkan. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Membaca body; melempar [ApiException] jika status bukan 2xx. */
fun Response.bodyOrThrow(): String = use {
    val text = body?.string().orEmpty()
    if (!isSuccessful) throw ApiException(code, text.take(500))
    text
}

fun joinUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')
