package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration

internal const val HELPER_DIRECTIVE: String =
    "You are a helper working on one assigned step. Return the result to your caller. " +
        "Do not call scheduler_sleep: the host owns waiting, cancellation and recovery for this helper."

/** Applies the helper ceiling after saved configuration has selected the requested trust for this turn. */
@Inject
internal class StudioHelperPolicy(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val log = Log.tag("StudioHelperPolicy")
    private val store = stores.keyValue(ChatSpec)

    // Detekt cannot resolve the cross-module generic store.get; Kotlin requires suspend here.
    @Suppress("RedundantSuspendModifier")
    suspend fun trust(
        record: StudioChatRecord,
        requested: TrustLevel?,
        configurations: () -> Map<String, StudioSessionConfiguration>,
    ): TrustLevel? {
        if (record.helper == null) return requested
        log.v { "Apply helper trust ceiling" }
        val records = store.get(ChatsKey).orEmpty()
        val current = configurations()
        val latest = records.singleOrNull { it.id == record.id } ?: return TrustLevel.Ask
        return constrainedHelperTrust(latest, requested, records, current)
    }
}

/** Unknown, missing or cyclic ancestry never grants trust. An ancestor helper also contributes its own ceiling. */
internal fun constrainedHelperTrust(
    record: StudioChatRecord,
    requested: TrustLevel?,
    records: List<StudioChatRecord>,
    configurations: Map<String, StudioSessionConfiguration>,
): TrustLevel {
    val current = (configurations[record.id]?.applied ?: record.configuration)?.approval?.toTrust()
    var effective = lessTrusted(requested ?: TrustLevel.Ask, current ?: requested ?: TrustLevel.Ask)
    var ancestor = record
    val visited = mutableSetOf<String>()
    while (ancestor.helper != null) {
        val identity = ancestor.helper
        val parent = records.singleOrNull { it.id == identity.parentChatId }
        if (!visited.add(ancestor.id) || parent == null) return TrustLevel.Ask
        effective = lessTrusted(effective, identity.trustCap)
        val parentTrust = (configurations[parent.id]?.applied ?: parent.configuration)?.approval?.toTrust()
            ?: TrustLevel.Ask
        effective = lessTrusted(effective, parentTrust)
        ancestor = parent
    }
    return effective
}

private fun lessTrusted(first: TrustLevel, second: TrustLevel): TrustLevel = when {
    first == TrustLevel.Ask || second == TrustLevel.Ask -> TrustLevel.Ask
    first == TrustLevel.AutoEdits || second == TrustLevel.AutoEdits -> TrustLevel.AutoEdits
    else -> TrustLevel.Full
}
