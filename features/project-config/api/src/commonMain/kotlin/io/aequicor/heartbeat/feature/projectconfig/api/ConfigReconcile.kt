package io.aequicor.heartbeat.feature.projectconfig.api

/**
 * Reconciles the folder read by [snapshot] with the [bindings] of its project (bindings of other projects are not
 * returned), keeping the review state of [previous] proposals whose unit, kind, target and digest did not change.
 *
 * | Folder unit | Binding / entity | Result |
 * |---|---|---|
 * | files as bound | entity as bound | nothing |
 * | files as bound | entity changed | write-back of the entity |
 * | files as bound | entity removed | write-back deleting the files |
 * | files changed, canonically equal to the entity | | binding takes the new file digest |
 * | files changed | entity as bound | Update proposal |
 * | files changed | entity changed | Conflict proposal |
 * | files changed | entity removed | binding dropped, unit handled as unbound |
 * | unbound, entity with its identity equal | | silent binding |
 * | unbound, entity with its identity differs | | Update proposal for that entity |
 * | unbound, no such entity | | Create proposal |
 * | missing | entity exists | Remove proposal |
 * | missing | entity removed | binding dropped |
 * | absent and unbound | project-owned entity | automatic write-back with an absent-source precondition |
 * | explicitly excluded | entity exists | exclusion retained without writing |
 *
 * Invalid units become proposals with their problems: they are shown and never trusted. Units and bindings of
 * [ProjectSnapshot.unavailable] sections are left untouched. Pure: digests and checks were computed by the effect
 * that read the folder.
 */
public fun reconcile(
    bindings: List<ConfigBinding>,
    snapshot: ProjectSnapshot,
    previous: List<ConfigProposal> = emptyList(),
): Reconciled {
    val reconciliation = Reconciliation(snapshot)
    val (paused, own) = bindings.filter { it.project == snapshot.project }
        .partition { it.unit.section in snapshot.unavailable }
    own.filterNot { it.isExcluded }.forEach(reconciliation::bound)
    reconciliation.bindings += own.filter { binding ->
        binding.isExcluded && snapshot.units.none { it.ref == binding.unit } && snapshot.entities.any { entity ->
            entity.section == binding.unit.section && entity.key == binding.key
        }
    }
    snapshot.units.filter { unit ->
        unit.ref.section !in snapshot.unavailable && own.none { !it.isExcluded && it.unit == unit.ref }
    }
        .forEach(reconciliation::unbound)
    reconciliation.released.forEach(reconciliation::unbound)
    snapshot.entities.filter { it.section !in snapshot.unavailable }.forEach { entity ->
        val unit = entity.automaticUnit
        val isBound = (paused + reconciliation.bindings).any {
            it.unit == unit || (it.unit.section == entity.section && it.key == entity.key)
        }
        val hasProposal = reconciliation.proposals.any { it.unit.section == entity.section && it.target == entity.key }
        val isMissing = unit != null && snapshot.units.none { it.ref == unit }
        if (isMissing && !isBound && !hasProposal) {
            reconciliation.writeBacks += ConfigWriteBack(snapshot.project, unit, entity.key, null)
        }
    }
    return Reconciled(
        paused + reconciliation.bindings,
        reconciliation.proposals.map { it.keeping(previous) },
        reconciliation.writeBacks,
    )
}

/** Accumulates [reconcile] of one project: bound units first, then units without a binding. */
private class Reconciliation(private val snapshot: ProjectSnapshot) {
    private val source = ConfigSource.Project(snapshot.project)
    private val entities = snapshot.entities.associateBy { it.section to it.key }
    private val units = snapshot.units.associateBy { it.ref }
    val bindings = mutableListOf<ConfigBinding>()
    val proposals = mutableListOf<ConfigProposal>()
    val writeBacks = mutableListOf<ConfigWriteBack>()

    /** Units whose bound entity was removed in the app while their files changed. */
    val released = mutableListOf<ScannedUnit>()

    fun bound(binding: ConfigBinding) {
        val unit = units[binding.unit]
        val entity = entities[binding.unit.section to binding.key]
        when {
            unit == null && entity == null -> Unit

            unit == null -> {
                bindings += binding
                proposals += ConfigProposal(source, binding.unit, ProposalKind.Remove, binding.key, null)
            }

            unit.digest == binding.fileDigest -> {
                bindings += binding
                if (entity?.digest != binding.entityDigest) {
                    writeBacks += ConfigWriteBack(snapshot.project, binding.unit, entity?.key, unit.digest)
                }
            }

            entity == null -> released += unit

            else -> changed(binding, unit, entity)
        }
    }

    fun unbound(unit: ScannedUnit) {
        val check = unit.check as? UnitCheck.Valid
        val taken = bindings.map { it.unit.section to it.key }.toSet()
        val match = check?.let { valid ->
            snapshot.entities.firstOrNull {
                it.section == unit.ref.section && it.identity == valid.identity && (it.section to it.key) !in taken
            }
        }
        when {
            match != null && match.digest == check.canonical ->
                bindings += ConfigBinding(snapshot.project, unit.ref, match.key, unit.digest, match.digest)

            match != null -> proposals += proposal(source, unit, ProposalKind.Update, match.key)

            else -> proposals += proposal(source, unit, ProposalKind.Create, null)
        }
    }

    private fun changed(binding: ConfigBinding, unit: ScannedUnit, entity: EntityDigest) {
        val check = unit.check
        if (check is UnitCheck.Valid && check.canonical == entity.digest) {
            bindings += binding.copy(fileDigest = unit.digest, entityDigest = entity.digest)
            return
        }
        bindings += binding
        val kind = if (entity.digest == binding.entityDigest) ProposalKind.Update else ProposalKind.Conflict
        proposals += proposal(source, unit, kind, binding.key)
    }
}

/**
 * Proposals of a bundle for the profile: a unit creates an entity, replaces the entity with its identity, or is left
 * out when that entity already equals it. [entities] are the profile entities the units' identities match.
 */
public fun bundleProposals(id: String, units: List<ScannedUnit>, entities: List<EntityDigest>): List<ConfigProposal> {
    val source = ConfigSource.Bundle(id)
    return units.mapNotNull { unit ->
        val check = unit.check
        val match = (check as? UnitCheck.Valid)?.let { valid ->
            entities.firstOrNull { it.section == unit.ref.section && it.identity == valid.identity }
        }
        when {
            match != null && match.digest == (check as UnitCheck.Valid).canonical -> null
            match != null -> proposal(source, unit, ProposalKind.Update, match.key)
            else -> proposal(source, unit, ProposalKind.Create, null)
        }
    }
}

private fun proposal(source: ConfigSource, unit: ScannedUnit, kind: ProposalKind, target: ConfigEntityKey?) =
    when (val check = unit.check) {
        is UnitCheck.Valid -> ConfigProposal(source, unit.ref, kind, target, unit.digest, check.summary)
        is UnitCheck.Invalid -> ConfigProposal(source, unit.ref, kind, target, unit.digest, problems = check.problems)
        UnitCheck.Unchanged -> ConfigProposal(source, unit.ref, kind, target, unit.digest)
    }

/** Keeps a previous decision only while both the files and their latest validation describe the same proposal. */
private fun ConfigProposal.keeping(previous: List<ConfigProposal>): ConfigProposal = previous.firstOrNull {
    it.id == id && it.kind == kind && it.target == target && it.digest == digest &&
        it.problems == problems && it.summary == summary
} ?: this
