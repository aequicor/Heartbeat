package io.aequicor.heartbeat.feature.projectconfig.api.spi

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import kotlinx.coroutines.flow.Flow

/**
 * One section of the configuration tree, implemented by the feature that owns its entities (harnesses, interfaces,
 * learned instructions) and bound into a profile multibinding. Only implementers and `platform-main:di-bundle` use
 * this package (checked on review).
 *
 * The owner encodes and applies its own entities through its own machine, so `project-config` never writes another
 * feature's data and never sees its internal intents. The owner decides only the format of its files and how a unit
 * maps onto its entities; review, trust, bindings to projects and file IO belong to `project-config`, which calls
 * [apply] and [remove] only after the user trusted the exact unit. Encoding is deterministic: the same entity always
 * produces the same files, so unchanged entities are recognized by their content.
 *
 * Every function is main-safe and never throws except cancellation; texts and code are never logged.
 */
public interface ConfigSection {
    /** Directory of the section; never renamed. */
    public val id: ConfigSectionId

    /** Section whose units the units of this one belong to (interfaces → harnesses); parents are applied first. */
    public val parent: ConfigSectionId? get() = null

    /**
     * Profile entities of the section and their changes; null while the owner is disabled, loading or failed, so an
     * unknown library is never mistaken for an empty one.
     */
    public val entities: Flow<List<ConfigEntity>?>

    /** Unit name of [path], a file of this section, or null when the file is not part of the format. */
    public fun unitOf(path: ConfigPath): String?

    /** Unit name in [parent] that unit [name] belongs to, e.g. interface `review/board` → harness `review`. */
    public fun parentOf(name: String): String? = null

    /**
     * Portable files of entity [key], or null when it no longer exists. [name] is the unit the files are written to:
     * sections whose entities have their own immutable identity (a harness name) ignore it.
     */
    public suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit?

    /** Parses and validates [unit] without applying it; code is compiled when the platform can compile it. */
    public suspend fun check(unit: ConfigUnit): ConfigCheck

    /**
     * Creates an entity from [unit], or replaces the content of [target] keeping its local switches (a disabled
     * entity stays disabled). New entities are enabled: the user trusted them. Fails without partial changes.
     */
    public suspend fun apply(unit: ConfigUnit, target: ConfigEntityKey?, context: ConfigApplyContext): ConfigApplyResult

    /** Removes entity [key] and everything the owner keeps for it. */
    public suspend fun remove(key: ConfigEntityKey): ConfigApplyResult
}
