package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors

/**
 * Fingerprint of everything the remote view exchanges (frames, screen state, intents). An unpacked extension built
 * from other sources than the app speaks another schema; the app refuses its view instead of misreading it.
 */
object StudioViewSchema {
    /** FNV-1a 64 of the serial descriptors, as 16 hex digits. */
    val fingerprint: String by lazy {
        fingerprintOf(
            StudioViewFrame.serializer().descriptor,
            StudioViewPacket.serializer().descriptor,
            StudioViewLimit.serializer().descriptor,
            AiStudioScreenState.serializer().descriptor,
            AiStudioScreenIntent.serializer().descriptor,
        )
    }
}

/** Hashes the shape of [descriptors]: serial names, kinds, nullability and element names, recursively. */
@OptIn(ExperimentalSerializationApi::class)
fun fingerprintOf(vararg descriptors: SerialDescriptor): String {
    val active = mutableListOf<SerialDescriptor>()
    val shape = StringBuilder()
    fun walk(descriptor: SerialDescriptor) {
        shape.append(descriptor.serialName).append('|').append(descriptor.kind).append('|')
            .append(descriptor.isNullable).append('|')
        if (active.any { it === descriptor }) return
        active.add(descriptor)
        for (index in 0 until descriptor.elementsCount) shape.append(descriptor.getElementName(index)).append(',')
        shape.append(';')
        descriptor.elementDescriptors.forEach(::walk)
        active.removeAt(active.lastIndex)
    }
    descriptors.forEach(::walk)
    var hash = FNV_OFFSET
    for (byte in shape.toString().encodeToByteArray()) {
        hash = (hash xor (byte.toLong() and BYTE_MASK)) * FNV_PRIME
    }
    return hash.toULong().toString(HEX).padStart(HEX_DIGITS, '0')
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val BYTE_MASK = 0xffL
private const val HEX = 16
private const val HEX_DIGITS = 16
