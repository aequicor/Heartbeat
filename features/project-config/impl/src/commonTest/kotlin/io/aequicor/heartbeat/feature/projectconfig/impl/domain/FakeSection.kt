package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntity
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSummary
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A section whose units are `<section>/<name>.md` holding one text; an entity is a name and a text. A text `bad`
 * is invalid. Applied units become entities, so tests can follow a whole round trip.
 */
internal class FakeSection(
    override val id: ConfigSectionId = ConfigSectionId.Harnesses,
    override val isProjectScoped: Boolean = false,
    override val parent: ConfigSectionId? = null,
) : ConfigSection {
    val state = MutableStateFlow<List<FakeEntity>?>(emptyList())
    val applied = mutableListOf<Pair<ConfigUnit, ConfigApplyContext>>()
    var rejection: ConfigRejection? = null

    override val reviewPolicy: String = "fake policy"

    override val entities: Flow<List<ConfigEntity>?> = state.map { entities ->
        entities?.map {
            ConfigEntity(
                ConfigEntityKey(it.key),
                it.name,
                it.name,
                it.name,
                "${it.revision}:${it.text.hashCode()}",
                project = it.project,
                parent = it.parent?.let(::ConfigEntityKey),
            )
        }
    }

    override fun unitOf(path: ConfigPath): String? =
        path.segments.takeIf { it.size == 2 && it[1].endsWith(".md") }?.get(1)?.removeSuffix(".md")

    override fun parentOf(name: String): String = name.substringBefore('-')

    override suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit? =
        state.value?.firstOrNull { it.key == key.value }?.let { unit(name, it.text) }

    override suspend fun check(unit: ConfigUnit): ConfigCheck {
        val text = unit.files.single().text
        return if (text.trim() == "bad") {
            ConfigCheck.Invalid(listOf(ConfigProblem(ConfigProblemCode.Syntax, unit.files.single().path)))
        } else {
            ConfigCheck.Valid(ConfigSummary(unit.ref.name), unit.ref.name, unit(unit.ref.name, text.trim()))
        }
    }

    override suspend fun apply(
        unit: ConfigUnit,
        target: ConfigEntityKey?,
        context: ConfigApplyContext,
    ): ConfigApplyResult {
        rejection?.let { return ConfigApplyResult.Rejected(it) }
        applied += unit to context
        val key = target?.value ?: "k-${unit.ref.name}"
        val entity = FakeEntity(key, unit.ref.name, unit.files.single().text.trim(), context.project)
        state.value = state.value.orEmpty().filterNot { it.key == key } + entity
        return ConfigApplyResult.Applied(ConfigEntityKey(key))
    }

    override suspend fun remove(key: ConfigEntityKey): ConfigApplyResult {
        state.value = state.value.orEmpty().filterNot { it.key == key.value }
        return ConfigApplyResult.Applied(key)
    }

    fun unit(name: String, text: String) = ConfigUnit.of(
        ConfigUnitRef(id, name),
        listOf(ConfigFile(ConfigPath.of(id.value, "$name.md"), text + "\n")),
    )
}

/** An entity of [FakeSection]. */
internal data class FakeEntity(
    val key: String,
    val name: String,
    val text: String,
    val project: WorkspaceRef? = null,
    val revision: Int = 0,
    val parent: String? = null,
)
