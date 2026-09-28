package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine

/**
 * Profile-scoped Pi adapter: the SPI [EngineFactory] consumed only through its registration, plus the public
 * [PiEngine] configuration. One instance per profile serves both roles.
 */
internal interface PiAdapter :
    PiEngine,
    EngineFactory
