package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import okio.Buffer

/** Bounded manifest reads and streamed artifacts; each platform supplies its native HTTPS engine. */
internal class KtorBootstrapTransport(private val client: HttpClient) : HttpTransport {
    override suspend fun getBytes(url: String, limit: Long): HttpBytesResponse =
        client.prepareGet(url).execute { response ->
            val body = Buffer()
            readChunks(response) { chunk ->
                require(body.size + chunk.size <= limit) { "Bootstrap response exceeds the size limit" }
                body.write(chunk)
            }
            HttpBytesResponse(response.status.value, body.readByteArray())
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
        if (response.status != HttpStatusCode.OK && !isResumed) {
            return@execute HttpDownloadResult(response.status.value, false)
        }
        validateResume(response, offset, isResumed)
        onStart(isResumed)
        readChunks(response, onChunk)
        HttpDownloadResult(response.status.value, isResumed)
    }

    private fun validateResume(response: HttpResponse, offset: Long, isResumed: Boolean) {
        if (isResumed && offset > 0) {
            require(response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $offset-") == true) {
                "Unexpected bootstrap Content-Range"
            }
        }
    }

    private suspend fun readChunks(response: HttpResponse, onChunk: (ByteArray) -> Unit) {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count > 0) onChunk(buffer.copyOf(count))
        }
    }

    override fun close() = client.close()

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
    }
}
