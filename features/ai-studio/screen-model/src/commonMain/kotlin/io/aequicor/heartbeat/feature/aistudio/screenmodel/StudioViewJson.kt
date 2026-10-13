package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.serialization.json.Json

/**
 * JSON of the remote studio view: screen state and intents between the app and the browser side panel. Both ends
 * are built from the same sources, so decoding is strict; the discriminator name is not a property of any screen
 * type, and maps with structured keys are allowed.
 */
val StudioViewJson: Json = Json {
    encodeDefaults = true
    classDiscriminator = "@type"
    allowStructuredMapKeys = true
}
