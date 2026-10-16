package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.EntityDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ScannedUnit
import io.aequicor.heartbeat.feature.projectconfig.api.UnitCheck
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.GroupedTree
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sections of one moment: the entities of each available section; a missing section is off or not loaded. */
internal class SectionsSnapshot(
    val sections: List<ConfigSection>,
    val entities: Map<ConfigSectionId, List<ConfigEntity>>,
) {
    /** Sections whose owner is off or not loaded. */
    val unavailable: Set<ConfigSectionId> get() = sections.map { it.id }.toSet() - entities.keys

    /** The section [id], or null. */
    operator fun get(id: ConfigSectionId): ConfigSection? = sections.firstOrNull { it.id == id }

    /** The entity [key] of section [id], or null. */
    fun entity(id: ConfigSectionId, key: ConfigEntityKey): ConfigEntity? = entities[id]?.firstOrNull { it.key == key }

    /** The entity of section [id] with [identity], or null. */
    fun withIdentity(id: ConfigSectionId, identity: String): ConfigEntity? =
        entities[id]?.firstOrNull { it.identity == identity }
}

/**
 * Turns trees into scanned units and profile entities into digests for [reconcile][io.aequicor.heartbeat.feature
 * .projectconfig.api.reconcile]. Entity encodings are cached by key, revision and unit name, so an unchanged entity
 * is encoded once. Texts are never logged.
 */
internal class ConfigReader(private val provider: () -> Collection<ConfigSection>) {
    private val log = Log.tag("ConfigReader")
    private val cache = Mutex()
    private val digests = mutableMapOf<String, ConfigDigest>()

    /** Current entities of every section. */
    suspend fun sections(): SectionsSnapshot {
        val all = provider().toList()
        val entities = all.mapNotNull { section -> section.entities.first()?.let { section.id to it } }.toMap()
        return SectionsSnapshot(all, entities)
    }

    /**
     * Units of [tree]; a unit equal to its binding in [bindings] is not checked again, units of [unavailable] sections
     * are left out, or reported invalid when [isUnavailableReported].
     */
    suspend fun scan(
        tree: GroupedTree,
        snapshot: SectionsSnapshot,
        bindings: List<ConfigBinding> = emptyList(),
        isUnavailableReported: Boolean = false,
    ): List<ScannedUnit> = tree.units.mapNotNull { unit ->
        val section = snapshot[unit.ref.section]
        val digest = unit.digest
        when {
            section == null || unit.ref.section in snapshot.unavailable -> if (isUnavailableReported) {
                ScannedUnit(unit.ref, digest, UnitCheck.Invalid(listOf(offProblem(unit))))
            } else {
                null
            }

            bindings.any { it.unit == unit.ref && it.fileDigest == digest } ->
                ScannedUnit(unit.ref, digest, UnitCheck.Unchanged)

            else -> ScannedUnit(unit.ref, digest, check(section, unit))
        }
    }.also { log.v { "Scanned ${it.size} configuration units" } }

    /**
     * Digests of entities the units of [project] (null for a bundle) may relate to: those bound in [bindings] and, of
     * project-scoped sections, only the entities of [project].
     */
    suspend fun entities(
        snapshot: SectionsSnapshot,
        project: WorkspaceRef?,
        bindings: List<ConfigBinding> = emptyList(),
    ): List<EntityDigest> = snapshot.sections.flatMap { section ->
        val entities = snapshot.entities[section.id].orEmpty()
        entities.mapNotNull { entity ->
            val bound = bindings.firstOrNull { it.unit.section == section.id && it.key == entity.key }
            val isCandidate = !section.isProjectScoped || entity.project == project
            if (bound == null && !isCandidate) return@mapNotNull null
            digest(section, entity, bound?.unit?.name ?: entity.name)
                ?.let { EntityDigest(section.id, entity.key, entity.identity, it) }
        }
    }

    /** Encoding of [entity] under unit [name], or null when it no longer exists. */
    suspend fun encode(section: ConfigSection, entity: ConfigEntity, name: String): ConfigUnit? =
        section.encode(entity.key, name)

    private suspend fun digest(section: ConfigSection, entity: ConfigEntity, name: String): ConfigDigest? {
        val key = "${section.id}|${entity.key}|${entity.revision}|$name"
        val cached = cache.withLock { digests[key] }
        val digest = cached ?: section.encode(entity.key, name)?.digest
        if (cached == null && digest != null) {
            cache.withLock {
                if (digests.size >= CACHE_ENTRIES) digests.clear()
                digests[key] = digest
            }
        }
        return digest
    }

    private suspend fun check(section: ConfigSection, unit: ConfigUnit): UnitCheck =
        when (val check = section.check(unit)) {
            is ConfigCheck.Valid -> UnitCheck.Valid(check.summary, check.identity, check.canonical.digest)
            is ConfigCheck.Invalid -> UnitCheck.Invalid(check.problems)
        }

    private fun offProblem(unit: ConfigUnit) =
        ConfigProblem(ConfigProblemCode.Unsupported, unit.files.first().path, "the feature of this section is off")

    private companion object {
        const val CACHE_ENTRIES = 1024
    }
}
