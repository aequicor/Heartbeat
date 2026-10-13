package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/*
 * Serializers of the screen model for the remote studio view (the browser side panel): immutable collections
 * travel as plain JSON lists, sets and maps; bytes travel as Base64.
 */

/** [ImmutableList] as a JSON list. */
@OptIn(ExperimentalSerializationApi::class)
class ImmutableListSerializer<T>(element: KSerializer<T>) : KSerializer<ImmutableList<T>> {
    private val list = ListSerializer(element)
    override val descriptor: SerialDescriptor = SerialDescriptor("ImmutableList", list.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableList<T>): Unit = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableList<T> = list.deserialize(decoder).toImmutableList()
}

/** [ImmutableSet] as a JSON list. */
@OptIn(ExperimentalSerializationApi::class)
class ImmutableSetSerializer<T>(element: KSerializer<T>) : KSerializer<ImmutableSet<T>> {
    private val set = SetSerializer(element)
    override val descriptor: SerialDescriptor = SerialDescriptor("ImmutableSet", set.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableSet<T>): Unit = set.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableSet<T> = set.deserialize(decoder).toImmutableSet()
}

/** [ImmutableMap] as a JSON object (or a list of pairs for structured keys). */
@OptIn(ExperimentalSerializationApi::class)
class ImmutableMapSerializer<K, V>(key: KSerializer<K>, value: KSerializer<V>) : KSerializer<ImmutableMap<K, V>> {
    private val map = MapSerializer(key, value)
    override val descriptor: SerialDescriptor = SerialDescriptor("ImmutableMap", map.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableMap<K, V>): Unit = map.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableMap<K, V> = map.deserialize(decoder).toImmutableMap()
}

/** Bytes (attachment images and previews) as one Base64 string instead of a list of numbers. */
object Base64BytesSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray): Unit = encoder.encodeString(Base64.encode(value))

    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}
