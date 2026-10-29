package io.aequicor.heartbeat.platform.nativelauncher.provisioning

/**
 * HTTP-транспорт provisioning: маленькие ответы (манифест, подпись) в память, артефакты — потоком в обработчик.
 * При offset > 0 реализация отправляет `Range: bytes=<offset>-`. [onStart] вызывается один раз до первого чанка
 * и сообщает, вернул ли сервер 206 (частичное содержимое): тогда поток дописывается в `.part`, иначе — с нуля.
 */
interface HttpTransport {
    suspend fun getBytes(url: String, limit: Long): HttpBytesResponse

    suspend fun download(
        url: String,
        offset: Long,
        onStart: (isResumed: Boolean) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): HttpDownloadResult
}

data class HttpBytesResponse(val status: Int, val body: ByteArray)

data class HttpDownloadResult(val status: Int, val isResumed: Boolean)
