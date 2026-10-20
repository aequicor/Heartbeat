package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Preserves ownership, receipt exclusion and independent review across helper route changes. */
@Inject
internal class StudioEngineSwitchHelpers(
    private val attempts: StudioHelperAttempts,
    private val routes: StudioHelperRoutes,
    private val facade: EngineFacade,
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val chats = stores.keyValue(ChatSpec)

    suspend fun <T> withIdleSource(record: StudioChatRecord, body: suspend () -> T): T =
        if (record.helper == null) body() else routes.withIdleRoute(HelperId(record.id), attempts, body)

    fun validate(record: StudioChatRecord, target: EngineTarget) {
        if (record.helper != null) {
            val declared = facade.engines.state.value.firstOrNull { it.descriptor.id == target.engine }
                ?.descriptor?.declaredFeatures.orEmpty()
            refuseUnless(AppliesTrustLevels.id in declared, StudioEngineSwitchFailure.Unsupported)
        }
    }

    suspend fun identity(record: StudioChatRecord, target: EngineTarget): StudioHelperIdentity? {
        val helper = record.helper ?: return null
        if (helper.model.reviewTarget != null) return helper
        val parent = helper.parentChatId?.let { id ->
            checkNotNull(chats.get(ChatsKey).orEmpty().firstOrNull { it.id == id }?.target) {
                "The helper parent review model is unavailable"
            }
        }
        return helper.forEngineSwitch(parent, target)
    }
}

/** An explicit reviewer always wins; an inherited helper keeps parent review when moving away. */
internal fun StudioHelperIdentity.forEngineSwitch(parent: EngineTarget?, target: EngineTarget): StudioHelperIdentity =
    if (model.reviewTarget != null || parent == null || parent == target) {
        this
    } else {
        copy(model = model.copy(reviewTarget = parent))
    }
