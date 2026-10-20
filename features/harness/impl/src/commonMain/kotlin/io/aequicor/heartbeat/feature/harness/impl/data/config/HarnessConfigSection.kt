package io.aequicor.heartbeat.feature.harness.impl.data.config

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessDraft
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.storageBytes
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.CodeCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessConfigCodec
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessContent
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessDecoded
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Harnesses as the `harnesses` section of project folders and bundles. A unit is applied as a user edit after the
 * user trusted it: a new harness through `Create` in the source project of its folder (shared for a profile bundle),
 * a bound one through `Update(Content)` in one revision, keeping its stored scope and
 * the identities of items with the same names. Code is compiled when a unit is checked, before it is put to the
 * user. Unavailable while harnesses are off, loading, failed or suspended.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessConfigSection(
    private val machine: Lazy<HarnessMachine>,
    private val library: HarnessLibraryClient,
    private val checks: Lazy<HarnessCodeChecks>,
    private val toggles: FeatureToggles,
    private val creationScopes: HarnessCreationScope,
) : ConfigSection {
    private val log = Log.tag("HarnessConfigSection")

    override val id: ConfigSectionId = HarnessConfigCodec.section

    override val entities: Flow<List<ConfigEntity>?>
        get() = combine(toggles.observe(HarnessEnabled), machine.value.state) { isEnabled, state ->
            // A suspended or reloading library is not a settled list: its harnesses are unknown, not removed.
            val ready = (state as? HarnessState.Ready)?.takeIf { isEnabled && !it.isSuspended && !it.isReloading }
            ready?.harnesses?.map { entry ->
                val harness = entry.harness
                ConfigEntity(
                    key = ConfigEntityKey(harness.id.value),
                    name = harness.name.value,
                    identity = harness.name.value,
                    title = harness.title,
                    revision = harness.revision.toString(),
                    description = harness.description,
                    projects = (harness.scope as? HarnessScope.Projects)?.projects.orEmpty(),
                )
            }
        }.distinctUntilChanged()

    override fun unitOf(path: ConfigPath): String? = HarnessConfigCodec.unitOf(path)

    override suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit? =
        current()?.harnesses?.firstOrNull { it.harness.id.value == key.value }?.harness?.let(HarnessConfigCodec::encode)

    override suspend fun check(unit: ConfigUnit): ConfigCheck {
        val content = when (val decoded = HarnessConfigCodec.decode(unit)) {
            is HarnessDecoded.Invalid -> return ConfigCheck.Invalid(decoded.problems)
            is HarnessDecoded.Valid -> decoded.content
        }
        val problems = compileProblems(content)
        val candidate = candidate(content)
        val sizeProblem = ConfigProblem(ConfigProblemCode.Limit, metadataPath(unit.ref.name), "harness is too large")
            .takeIf { candidate.storageBytes() > HarnessLimits.BYTES_PER_HARNESS }
        val all = problems + listOfNotNull(sizeProblem)
        log.v { "Checked harness unit problems=${all.size}" }
        return if (all.isNotEmpty()) {
            ConfigCheck.Invalid(all)
        } else {
            ConfigCheck.Valid(
                HarnessConfigCodec.summary(content),
                content.name.value,
                HarnessConfigCodec.encode(candidate),
            )
        }
    }

    override suspend fun apply(
        unit: ConfigUnit,
        target: ConfigEntityKey?,
        context: ConfigApplyContext,
    ): ConfigApplyResult {
        val content = (HarnessConfigCodec.decode(unit) as? HarnessDecoded.Valid)?.content
            ?: return ConfigApplyResult.Rejected(ConfigRejection.Invalid)
        val ready = current() ?: return ConfigApplyResult.Rejected(ConfigRejection.Unavailable)
        val now = Clock.System.now()
        val request = RequestId("config-" + Uuid.random().toHexString())
        val (id, intent) = if (target == null) {
            val scope = creationScopes.resolve(context.project)
                ?: return ConfigApplyResult.Rejected(ConfigRejection.Unavailable)
            val id = HarnessId("h_" + Uuid.random().toHexString())
            val draft = HarnessDraft(
                content.name,
                content.title,
                content.description,
                scope,
                true,
                content.items.withIds(emptyList()),
                content.tools,
            )
            id to HarnessIntent.Internal.Create(request, id, draft, null, now)
        } else {
            val harness = ready.harnesses.firstOrNull { it.harness.id.value == target.value }?.harness
                ?: return ConfigApplyResult.Rejected(ConfigRejection.NotFound)
            if (harness.name != content.name) return ConfigApplyResult.Rejected(ConfigRejection.Invalid)
            val change = HarnessChange.Content(
                content.title,
                content.description,
                content.items.withIds(harness.items),
                content.tools,
            )
            harness.id to HarnessIntent.Internal.Update(
                request,
                harness.id,
                change,
                harness.revision,
                HarnessAuthor.User,
                maxOf(now, harness.updatedAt),
            )
        }
        val outcome = library.submit(intent)
        log.i { "Applied harness unit created=${target == null} outcome=${outcome::class.simpleName.orEmpty()}" }
        return outcome.result(ConfigEntityKey(id.value))
    }

    override suspend fun remove(key: ConfigEntityKey): ConfigApplyResult {
        val harness = current()?.harnesses?.firstOrNull { it.harness.id.value == key.value }?.harness
            ?: return ConfigApplyResult.Rejected(ConfigRejection.NotFound)
        val outcome = library.submit(
            HarnessIntent.Internal.Delete(
                RequestId("config-" + Uuid.random().toHexString()),
                harness.id,
                harness.revision,
            ),
        )
        log.i { "Removed harness unit outcome=${outcome::class.simpleName.orEmpty()}" }
        return outcome.result(key)
    }

    private suspend fun current(): HarnessState.Ready? =
        if (toggles.get(HarnessEnabled)) library.current ?: library.ready() else null

    private suspend fun compileProblems(content: HarnessContent): List<ConfigProblem> =
        content.items.mapNotNull { item ->
            val (kind, source) = when (item) {
                is HarnessItem.Script -> HarnessCodeKind.Script to item.source
                is HarnessItem.Workflow -> HarnessCodeKind.Workflow to item.source
                is HarnessItem.Skill, is HarnessItem.Instruction, is HarnessItem.Template -> return@mapNotNull null
            }
            val path = ConfigPath.of(HarnessConfigCodec.section.value, content.name.value, "${item.name.value}.kts")
            when (val check = checks.value.check(CHECK_HARNESS, item.id, kind, source, isAgent = false)) {
                is CodeCheck.Compiled -> null

                is CodeCheck.Failed -> {
                    val error = check.diagnostics.firstOrNull { it.severity == HarnessDiagnosticSeverity.Error }
                    val place = listOfNotNull(error?.line, error?.column).joinToString(":")
                    ConfigProblem(ConfigProblemCode.Compile, path, place)
                }

                CodeCheck.TimedOut -> ConfigProblem(ConfigProblemCode.Compile, path, "compilation timed out")

                CodeCheck.Unsupported -> ConfigProblem(ConfigProblemCode.Compile, path, "code is unsupported here")
            }
        }

    private fun candidate(content: HarnessContent): Harness = Harness(
        CHECK_HARNESS, content.name, content.title, content.description, HarnessScope.Attached, true,
        content.items, content.tools, null, 0, Instant.DISTANT_PAST, Instant.DISTANT_PAST,
    )

    private fun metadataPath(name: String) = ConfigPath.of(HarnessConfigCodec.section.value, name, "harness.json")

    private fun List<HarnessItem>.withIds(existing: List<HarnessItem>): List<HarnessItem> = map { item ->
        val id = existing.firstOrNull { it.name == item.name }?.id ?: ItemId("it_" + Uuid.random().toHexString())
        item.withId(id)
    }

    private fun HarnessItem.withId(id: ItemId): HarnessItem = when (this) {
        is HarnessItem.Skill -> copy(id = id)
        is HarnessItem.Instruction -> copy(id = id)
        is HarnessItem.Template -> copy(id = id)
        is HarnessItem.Script -> copy(id = id)
        is HarnessItem.Workflow -> copy(id = id)
    }

    private fun LibraryOutcome.result(key: ConfigEntityKey): ConfigApplyResult = when (this) {
        is LibraryOutcome.Committed -> ConfigApplyResult.Applied(key)
        is LibraryOutcome.Rejected -> ConfigApplyResult.Rejected(reason.rejection())
        LibraryOutcome.StorageFailed, LibraryOutcome.Unconfirmed -> ConfigApplyResult.Rejected(ConfigRejection.Failed)
        LibraryOutcome.NotTaken -> ConfigApplyResult.Rejected(ConfigRejection.Unavailable)
    }

    private fun HarnessRejection.rejection(): ConfigRejection = when (this) {
        HarnessRejection.Unavailable, HarnessRejection.Busy -> ConfigRejection.Unavailable
        HarnessRejection.Conflict -> ConfigRejection.Conflict
        HarnessRejection.NotFound -> ConfigRejection.NotFound
        HarnessRejection.Duplicate -> ConfigRejection.Duplicate
        HarnessRejection.Limit -> ConfigRejection.Limit
        HarnessRejection.Invalid -> ConfigRejection.Invalid
    }

    private companion object {
        /** Identity under which units are compiled before they belong to a harness. */
        val CHECK_HARNESS = HarnessId("config-check")
    }
}
