package io.aequicor.heartbeat.feature.checklist.screenmodel

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** [ImmutableList] as a JSON list. */
@OptIn(ExperimentalSerializationApi::class)
internal class ImmutableListSerializer<T>(element: KSerializer<T>) : KSerializer<ImmutableList<T>> {
    private val list = ListSerializer(element)
    override val descriptor: SerialDescriptor = SerialDescriptor("ImmutableList", list.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableList<T>): Unit = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableList<T> = list.deserialize(decoder).toImmutableList()
}

/** [ImmutableSet] as a JSON list. */
@OptIn(ExperimentalSerializationApi::class)
internal class ImmutableSetSerializer<T>(element: KSerializer<T>) : KSerializer<ImmutableSet<T>> {
    private val set = SetSerializer(element)
    override val descriptor: SerialDescriptor = SerialDescriptor("ImmutableSet", set.descriptor)

    override fun serialize(encoder: Encoder, value: ImmutableSet<T>): Unit = set.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableSet<T> = set.deserialize(decoder).toImmutableSet()
}
