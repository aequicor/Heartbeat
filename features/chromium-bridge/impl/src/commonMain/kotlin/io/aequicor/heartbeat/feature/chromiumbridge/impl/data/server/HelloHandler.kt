package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloApp
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloLimits
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HelloResult

/**
 * The handshake: agrees on the protocol version, records the client and tells the extension which features the app
 * offers. Until it succeeds every other method answers `not_ready`.
 */
internal class HelloHandler(private val appVersion: String, private val features: suspend () -> BridgeFeatureSet) :
    BridgeRequestHandler {
    private val log = Log.tag("BridgeHello")

    override val methods: Set<String> = setOf(METHOD)

    override val isHandshake: Boolean = true

    override suspend fun handle(call: BridgeCall): BridgeReply {
        val params = BridgeProtocol.json.decodeFromJsonElement(HelloParams.serializer(), call.params)
        require(params.capabilities.size <= MAX_CAPABILITIES && params.capabilities.all { it.length <= MAX_NAME }) {
            "Invalid capabilities"
        }
        if (BridgeProtocol.VERSION !in params.protocol) {
            log.w { "Bridge client speaks an unsupported protocol id=${call.session.id}" }
            return BridgeReply.Refused(
                BridgeErrorCode.UNSUPPORTED_PROTOCOL,
                "Supported protocol: ${BridgeProtocol.VERSION}",
            )
        }
        val client = BridgeClient(
            origin = call.session.origin,
            extensionVersion = params.extension.version.take(MAX_NAME),
            browserBrand = params.browser.brand.take(MAX_NAME),
            browserVersion = params.browser.version.take(MAX_NAME),
            capabilities = params.capabilities.toSet(),
        )
        call.session.accept(client)
        val result = HelloResult(
            protocol = BridgeProtocol.VERSION,
            app = HelloApp(appVersion),
            features = features(),
            limits = HelloLimits(maxFrameBytes = BridgeProtocol.MAX_FROM_BROWSER_BYTES, maxWatches = MAX_WATCHES),
        )
        return BridgeReply.Ok(BridgeProtocol.json.encodeToJsonElement(HelloResult.serializer(), result))
    }

    companion object {
        /** Method name. */
        const val METHOD = "hello"

        /** Live watches a connection may hold. */
        const val MAX_WATCHES = 8
        private const val MAX_CAPABILITIES = 16
        private const val MAX_NAME = 64
    }
}
