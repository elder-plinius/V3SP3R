package com.vesper.flipper.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public, explicit checks only: no provider key, GitHub token, downloads or installation. */
internal class GithubUpdateClient(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetch(repository: String): ReleaseCatalog {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}").matches(repository)) {
            "Invalid update repository."
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Vesper-Update-Check")
            .get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("GitHub check failed. Check connectivity and retry."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val catalog = response.use {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                403, 429 -> "GitHub denied or rate-limited this check. Try again later."
                                404 -> "Update repository is unavailable or private. No update offered."
                                else -> "GitHub check failed (HTTP ${it.code}). No update offered."
                            })
                            if (it.headers.values("Link").any { link -> link.contains("rel=\"next\"") }) {
                                throw IOException("More than 100 releases exist. This check cannot safely determine the newest compatible build.")
                            }
                            val body = it.body ?: throw IOException("GitHub returned an empty response.")
                            val source = body.source()
                            source.request(1_048_577)
                            if (source.buffer.size > 1_048_576) throw IOException("GitHub release response is too large.")
                            GithubReleaseCatalog.parse(source.readUtf8(), repository)
                        }
                        if (continuation.isActive) continuation.resume(catalog)
                    } catch (e: Exception) {
                        val error = if (e is IOException) e else IOException("GitHub release metadata could not be read. No update offered.")
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
