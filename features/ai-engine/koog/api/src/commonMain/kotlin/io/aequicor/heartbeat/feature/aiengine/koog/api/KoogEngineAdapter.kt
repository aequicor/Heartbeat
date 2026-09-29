package io.aequicor.heartbeat.feature.aiengine.koog.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource

/**
 * Profile-owned Koog SPI entry point, normally consumed through the engine facade registration.
 *
 * The adapter supports text/resource prompts and streamed text responses, local history discovery and explicit resume.
 * Runtime identity is fixed to the source revision; a new revision retires its old runtime before replacement.
 * Closing a session releases its observation lease, while accepted work continues in the profile.
 * Closing a stream cannot prove remote cancellation, so interruption records TurnOutcome.Unknown.
 *
 * Transcripts and connection metadata survive profile reopening. Runtime loss recovers unfinished turns as
 * Unknown with partial history; it never repeats a prompt. Fixed provider origins are listed in KoogProvider;
 * credentials remain in SecretStore.
 *
 * Image references accept HTTPS URLs on cloud providers and `data:<mediaType>;base64,<content>` on all providers;
 * supported media types are image/png, image/jpeg, image/webp and image/gif. Resource references accept inline
 * UTF-8 text/plain or text/markdown documents, and inline application/pdf on cloud providers. Inline base64 is
 * limited to 16 MiB encoded per resource. The selected provider/model must support the requested modality.
 * Local paths, opaque resource ids, PDF URLs, Ollama image URLs/PDFs and reasoning input are rejected before
 * acceptance. Text document contents remain user source material and cannot supply system instructions.
 *
 * On Desktop, a session opened on a local workspace with [KoogCodingTools] on is a coding session: the model gets
 * file tools and a shell command tool confined to the project directory. Writes and commands run automatically
 * while [KoogAutoApprove] is on, otherwise each waits for a `session.permissions` decision (allow once, allow the
 * tool for the session, deny). Mobile platforms keep the plain chat.
 */
public interface KoogEngineAdapter :
    EngineFactory,
    EngineSessionSource,
    ListsSessions
