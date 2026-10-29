package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** HTTP-транспорт provisioning на Ktor WinHttp: HTTPS, Range для resume, потоковая запись артефактов. */
class WinHttpTransport : HttpTransport {
    private val client = HttpClient(WinHttp) {
        expectSuccess = false
        followRedirects = true
    }

    override suspend fun getBytes(url: String, limit: Long): HttpBytesResponse {
        val response = client.get(url)
        return HttpBytesResponse(response.status.value, response.body<ByteArray>())
    }

    override suspend fun download(
        url: String,
        offset: Long,
        onStart: (isResumed: Boolean) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): HttpDownloadResult = client.prepareGet(url) {
        if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
    }.execute { response ->
        val isResumed = response.status == HttpStatusCode.PartialContent
        onStart(isResumed)
        val channel = response.body<ByteReadChannel>()
        val buffer = ByteArray(CHUNK_BYTES)
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer)
            if (count > 0) onChunk(buffer.copyOf(count))
        }
        HttpDownloadResult(response.status.value, isResumed)
    }

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
    }
}
