package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentImportPurpose
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEffect
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentStorage
import kotlinx.coroutines.CancellationException

@ContributesBinding(ProfileScope::class)
@Inject
internal class AttachmentEffects(private val storage: AttachmentStorage, private val toggles: FeatureToggles) :
    EffectHandler<AttachmentsEffect, AttachmentsIntent> {
    private val log = Log.tag("AttachmentEffects")

    override suspend fun handle(effect: AttachmentsEffect, machine: EffectScope<AttachmentsIntent>) {
        val feedback = when (effect) {
            AttachmentsEffect.Prepare -> prepare()
            is AttachmentsEffect.Import -> import(effect)
        }
        machine.send(feedback)
    }

    private suspend fun prepare(): AttachmentsIntent = try {
        storage.prepare()
        AttachmentsIntent.Internal.Prepared
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Attachment storage unavailable" }
        AttachmentsIntent.Internal.PrepareFailed
    }

    private suspend fun import(effect: AttachmentsEffect.Import): AttachmentsIntent {
        if (effect.purpose == AttachmentImportPurpose.User && !toggles.get(AttachmentsEnabled)) {
            return AttachmentsIntent.Internal.Failed(effect.requestId, AttachmentFailure.Disabled)
        }
        return try {
            val attachments = storage.import(effect.inputs, effect.support, effect.deduplicationKey)
            AttachmentsIntent.Internal.Imported(effect.requestId, attachments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AttachmentValidationException) {
            log.w(error) { "Attachment input rejected: ${error.failure}" }
            AttachmentsIntent.Internal.Failed(effect.requestId, error.failure)
        } catch (error: Exception) {
            log.w(IllegalStateException(error::class.simpleName.orEmpty())) { "Attachment import unavailable" }
            AttachmentsIntent.Internal.Failed(effect.requestId, AttachmentFailure.Unavailable)
        }
    }
}
