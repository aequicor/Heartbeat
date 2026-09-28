package io.aequicor.heartbeat.feature.aiengine.koog.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource

/**
 * Profile-owned Koog SPI entry point, normally consumed through the engine facade registration.
 *
 * The adapter supports text prompts and streamed text responses, local history discovery and explicit resume.
 * Runtime identity is fixed to the source revision; a new revision retires its old runtime before replacement.
 * Closing a session releases its observation lease, while accepted work continues in the profile.
 * Closing a stream cannot prove remote cancellation, so interruption records TurnOutcome.Unknown.
 *
 * Transcripts and connection metadata survive profile reopening. Runtime loss recovers unfinished turns as
 * Unknown with partial history; it never repeats a prompt. Tools, permissions and multimodal input are not
 * advertised. Fixed provider origins are listed in KoogProvider; credentials remain in SecretStore.
 */
public interface KoogEngineAdapter :
    EngineFactory,
    EngineSessionSource,
    ListsSessions
