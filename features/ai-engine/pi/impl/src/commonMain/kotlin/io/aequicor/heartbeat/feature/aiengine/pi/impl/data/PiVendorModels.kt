package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pins the Coding Plan endpoint independently of the installed Pi catalog. A provider-level override keeps
 * Pi's native model ids, capabilities and limits, including models added by a later runtime update.
 * The auth scope stays origin-only so existing saved Z.ai connections use the corrected endpoint too.
 * Credentials are resolved by Pi from the isolated process environment, never written to models.json.
 */
internal fun piVendorModelsJson(provider: PiProvider): String? {
    if (provider != PiProvider.ZaiCodingPlan) return null
    return buildJsonObject {
        put(
            "providers",
            buildJsonObject {
                put(
                    provider.id,
                    buildJsonObject {
                        put("baseUrl", "https://api.z.ai/api/coding/paas/v4")
                        put("apiKey", "\$${provider.variable}")
                    },
                )
            },
        )
    }.toString()
}
