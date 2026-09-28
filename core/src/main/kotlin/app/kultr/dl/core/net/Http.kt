package app.kultr.dl.core.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

/** Plain HTTP for the catalogue APIs, cancelled with the coroutine that asked. */
class Http(val client: OkHttpClient) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        text(Request.Builder().url(url).headers(headers).get().build())

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        text(Request.Builder().url(url).headers(headers).post(json.toRequestBody(JSON)).build())

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val body: RequestBody = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return text(Request.Builder().url(url).headers(headers).post(body).build())
    }

    /** Where [url] ends up after redirects (short links such as spotify.link). */
    suspend fun finalUrl(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).get().build()
        return execute(request).use { it.request.url.toString() }
    }

    private suspend fun text(request: Request): String = execute(request).use { response ->
        val body = withContext(Dispatchers.IO) { response.body.string() }
        if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code} from ${request.url.host}")
        body
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun Request.Builder.headers(map: Map<String, String>): Request.Builder {
        header("User-Agent", BROWSER_UA)
        map.forEach { (k, v) -> header(k, v) }
        return this
    }

    companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
