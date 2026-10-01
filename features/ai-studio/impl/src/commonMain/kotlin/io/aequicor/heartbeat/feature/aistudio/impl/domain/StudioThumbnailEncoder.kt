package io.aequicor.heartbeat.feature.aistudio.impl.domain

/** Platform pixel decoder isolated from feature data and presentation through a pure CPU port. */
fun interface StudioThumbnailEncoder {
    /** Produces bounded preview PNG bytes without retaining the original input. */
    fun encode(bytes: ByteArray): ByteArray
}
