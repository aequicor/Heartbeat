package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLimits
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection

/** Units of a tree and the files no section claimed. */
internal data class GroupedTree(val units: List<ConfigUnit>, val unclaimed: List<ConfigProblem>) {
    /** The unit [ref], or null. */
    operator fun get(ref: ConfigUnitRef): ConfigUnit? = units.firstOrNull { it.ref == ref }
}

/**
 * Groups [tree] into the units [sections] claim. Root files, files of unknown sections and files a section does not
 * recognize are reported as unsupported; a unit with more than [ConfigLimits.UNIT_FILES] files is reported instead
 * of being read.
 */
internal fun groupUnits(tree: ConfigTree, sections: Collection<ConfigSection>): GroupedTree {
    val bySection = sections.associateBy { it.id.value }
    val unclaimed = mutableListOf<ConfigProblem>()
    val grouped = mutableMapOf<ConfigUnitRef, MutableList<ConfigFile>>()
    for (file in tree.files) {
        val section = bySection[file.path.section]
        val name = section?.takeIf { file.path.inSection.isNotEmpty() }?.unitOf(file.path)
        val ref = name?.takeIf(ConfigPath::isValid)?.let { ConfigUnitRef(section.id, it) }
        if (ref == null) {
            unclaimed += ConfigProblem(ConfigProblemCode.Unsupported, file.path)
        } else {
            grouped.getOrPut(ref) { mutableListOf() } += file
        }
    }
    val units = grouped.mapNotNull { (ref, files) ->
        if (files.size > ConfigLimits.UNIT_FILES) {
            unclaimed += ConfigProblem(ConfigProblemCode.Limit, files.first().path, "too many files in one unit")
            null
        } else {
            ConfigUnit.of(ref, files)
        }
    }
    return GroupedTree(units.sortedBy { it.ref.toString() }, unclaimed + tree.ignored)
}

/** Sections by id. */
internal fun Collection<ConfigSection>.byId(): Map<ConfigSectionId, ConfigSection> = associateBy { it.id }
