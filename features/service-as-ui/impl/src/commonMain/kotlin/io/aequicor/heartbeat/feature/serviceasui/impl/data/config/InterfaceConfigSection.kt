package io.aequicor.heartbeat.feature.serviceasui.impl.data.config

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.WriteOutcome
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.config.InterfaceConfigCodec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.config.InterfaceContent
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.config.InterfaceDecoded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.submit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Interfaces of harnesses as the `interfaces` section of project folders and bundles. Only surfaces owned by a known
 * harness are portable: the folder names the harness, and the owner is found by that name, or given by the harness
 * entity just applied. Units are written as user edits of the library after the user trusted them; the spec is
 * checked as a library spec, calls of owner methods only warn until the harness publishes them.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class InterfaceConfigSection(private val machine: ServiceUiMachine, private val toggles: FeatureToggles) :
    ConfigSection {
    private val log = Log.tag("InterfaceConfigSection")

    override val id: ConfigSectionId = InterfaceConfigCodec.section

    override val parent: ConfigSectionId = ConfigSectionId.Harnesses

    override val reviewPolicy: String = """
A unit is a declarative interface of a harness shown in the chat: native nodes, and optional web nodes whose HTML runs
in an isolated document without network unless the user turned Safe Mode off; its buttons call methods of the owning
harness. Return risk for texts or HTML that phish for credentials or payment data, imitate system dialogs or other
products, load external resources to leak data, or trick the user into dangerous actions. Ordinary dashboards, lists,
forms and charts are safe.
"""

    /**
     * Portable surfaces, or null while the feature is off or the harnesses' owner snapshot is incomplete (loading,
     * reloading): a surface of an owner missing from an incomplete snapshot is unknown, not removed.
     */
    override val entities: Flow<List<ConfigEntity>?> =
        combine(toggles.observe(ServiceUiEnabled), machine.state) { isEnabled, state ->
            (state as? ServiceUiState.Ready)
                ?.takeIf { isEnabled && it.owners[HARNESS_OWNER]?.isComplete == true }
                ?.let(::entitiesOf)
        }.distinctUntilChanged()

    override fun unitOf(path: ConfigPath): String? = InterfaceConfigCodec.unitOf(path)

    override fun parentOf(name: String): String = InterfaceConfigCodec.parentOf(name)

    override suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit? {
        val ready = ready() ?: return null
        val surface = ready.library[UiSurfaceId(key.value)] ?: return null
        val harness = ready.harnesses().firstOrNull { it.id == surface.content.owner.id } ?: return null
        return InterfaceConfigCodec.encode(surface.content, harness.name)
    }

    override suspend fun check(unit: ConfigUnit): ConfigCheck = when (val decoded = InterfaceConfigCodec.decode(unit)) {
        is InterfaceDecoded.Invalid -> ConfigCheck.Invalid(decoded.problems)

        is InterfaceDecoded.Valid -> {
            val content = decoded.content
            val draft = content.surfaceContent(UiOwner(HARNESS_OWNER, "check"))
            val canonical = InterfaceConfigCodec.encode(draft, content.harness)
            ConfigCheck.Valid(InterfaceConfigCodec.summary(content), unit.ref.name, canonical)
        }
    }

    override suspend fun apply(
        unit: ConfigUnit,
        target: ConfigEntityKey?,
        context: ConfigApplyContext,
    ): ConfigApplyResult {
        val content = (InterfaceConfigCodec.decode(unit) as? InterfaceDecoded.Valid)?.content
            ?: return ConfigApplyResult.Rejected(ConfigRejection.Invalid)
        val ready = ready() ?: return ConfigApplyResult.Rejected(ConfigRejection.Unavailable)
        val ownerId = context.parent?.value ?: ready.harnesses().firstOrNull { it.name == content.harness }?.id
            ?: return ConfigApplyResult.Rejected(ConfigRejection.MissingParent)
        // A surface written before the machine knows its new owner could be purged by an older owner snapshot.
        if (!awaitOwner(ownerId)) return ConfigApplyResult.Rejected(ConfigRejection.MissingParent)
        val surface = content.surfaceContent(UiOwner(HARNESS_OWNER, ownerId))
        val existing = target?.let { ready.library[UiSurfaceId(it.value)] }
        if (target != null && (existing == null || existing.id != surface.id)) {
            return ConfigApplyResult.Rejected(
                if (existing == null) ConfigRejection.NotFound else ConfigRejection.Invalid,
            )
        }
        val op = UiWriteOp.Put(surface, existing?.revision ?: 0, null, Clock.System.now())
        val outcome = machine.submit(UiWrite("config.${Uuid.random().toHexString()}", UiWriteOrigin.User, op))
        log.i { "Applied interface unit created=${target == null} outcome=${outcome::class.simpleName.orEmpty()}" }
        return outcome.result(ConfigEntityKey(surface.id.value))
    }

    override suspend fun remove(key: ConfigEntityKey): ConfigApplyResult {
        val surface = ready()?.library?.get(UiSurfaceId(key.value))
            ?: return ConfigApplyResult.Rejected(ConfigRejection.NotFound)
        val op = UiWriteOp.Delete(surface.id, surface.revision)
        val outcome = machine.submit(UiWrite("config.${Uuid.random().toHexString()}", UiWriteOrigin.User, op))
        log.i { "Removed interface unit outcome=${outcome::class.simpleName.orEmpty()}" }
        return outcome.result(key)
    }

    private suspend fun ready(): ServiceUiState.Ready? {
        if (!toggles.get(ServiceUiEnabled)) return null
        return withTimeoutOrNull(TIMEOUT_MILLIS) {
            machine.state.first { it is ServiceUiState.Ready || it is ServiceUiState.Failed }
        } as? ServiceUiState.Ready
    }

    private suspend fun awaitOwner(id: String): Boolean = withTimeoutOrNull(TIMEOUT_MILLIS) {
        machine.state.first { state -> (state as? ServiceUiState.Ready)?.harnesses()?.any { it.id == id } == true }
    } != null

    private fun entitiesOf(ready: ServiceUiState.Ready): List<ConfigEntity> {
        val harnesses = ready.harnesses().associateBy { it.id }
        return ready.library.surfaces.mapNotNull { surface ->
            val owner = surface.content.owner
            val harness = harnesses[owner.id]?.takeIf { owner.kind == HARNESS_OWNER } ?: return@mapNotNull null
            val name = "${harness.name}/${surface.content.name.value}"
            ConfigEntity(
                key = ConfigEntityKey(surface.id.value),
                name = name,
                identity = name,
                title = surface.content.title,
                revision = surface.revision.toString(),
                description = surface.content.description,
                parent = ConfigEntityKey(owner.id),
            )
        }
    }

    private fun ServiceUiState.Ready.harnesses(): List<UiOwnerInfo> = owners[HARNESS_OWNER]?.owners.orEmpty()

    private fun InterfaceContent.surfaceContent(owner: UiOwner) =
        UiSurfaceContent(owner, UiSurfaceName(surface), placement, title, description, launchers, spec)

    private fun WriteOutcome.result(key: ConfigEntityKey): ConfigApplyResult = when (this) {
        is WriteOutcome.Applied -> ConfigApplyResult.Applied(key)
        is WriteOutcome.Rejected -> ConfigApplyResult.Rejected(reason.rejection())
        WriteOutcome.NotTaken -> ConfigApplyResult.Rejected(ConfigRejection.Unavailable)
        WriteOutcome.Unconfirmed -> ConfigApplyResult.Rejected(ConfigRejection.Failed)
    }

    private fun UiEditRejection.rejection(): ConfigRejection = when (this) {
        UiEditRejection.Conflict -> ConfigRejection.Conflict
        UiEditRejection.Missing -> ConfigRejection.NotFound
        UiEditRejection.Limit -> ConfigRejection.Limit
        UiEditRejection.Busy, UiEditRejection.Unavailable -> ConfigRejection.Unavailable
        UiEditRejection.Storage -> ConfigRejection.Failed
    }

    private companion object {
        /** Kind harnesses register as surface owners (`ServiceUiProvider.kind`); stored, never renamed. */
        const val HARNESS_OWNER = "harness"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
