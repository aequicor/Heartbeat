package io.aequicor.heartbeat.feature.harness.api.workflow

/** Live requests of organism cells and the user's answers to them; see [HarnessRunsMachineSpec] for the table. */
internal fun RunsTransitions.organismRequests() {
    on<HarnessRunsIntent.Internal.OrganismPermissionsChanged>(guard = {
        state.executing(intent.run, intent.generation) != null
    }) {
        stay {
            state.replace(
                checkNotNull(state.current(intent.run, intent.generation)).copy(organismRequests = intent.requests),
            )
        }
    }
    on<HarnessRunsIntent.Internal.DecideOrganismPermission> {
        effect {
            if (state.organismDecisionRejection(intent) == null) {
                HarnessRunsEffect.AnswerOrganism(
                    intent.run,
                    state.runs.first { it.id == intent.run }.driverGeneration,
                    intent.step,
                    intent.cell,
                    intent.decision,
                )
            } else {
                null
            }
        }
        output {
            state.organismDecisionRejection(intent)?.let { HarnessRunsOutput.Rejected(intent.requestId, it) }
        }
    }
}
