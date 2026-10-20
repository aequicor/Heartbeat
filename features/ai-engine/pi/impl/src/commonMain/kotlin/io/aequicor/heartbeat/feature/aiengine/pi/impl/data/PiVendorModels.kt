package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pins a vendor endpoint the Pi catalog serves away from the origin root. A provider-level override keeps
 * Pi's native model ids, capabilities and limits, including models added by a later runtime update: Heartbeat
 * declares no models of its own, so context windows, thinking levels and input modalities stay the verified ones.
 * The auth scope stays origin-only so existing saved connections of the vendor use the corrected endpoint too.
 * Credentials are resolved by Pi from the isolated process environment, never written to models.json.
 */
internal fun piVendorModelsJson(provider: PiProvider): String? {
    val baseUrl = provider.pinnedBaseUrl ?: return null
    return buildJsonObject {
        put(
            "providers",
            buildJsonObject {
                put(
                    provider.id,
                    buildJsonObject {
                        put("baseUrl", baseUrl)
                        put("apiKey", "\$${provider.variable}")
                    },
                )
            },
        )
    }.toString()
}
