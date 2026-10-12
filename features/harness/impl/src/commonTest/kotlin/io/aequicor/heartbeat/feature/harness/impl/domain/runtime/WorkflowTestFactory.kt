package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflows
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflowsFactory

/** Real staging checks with no expected host launch in callback-only fixtures. */
internal fun workflowTestFactory(origins: HarnessCallOrigins): HarnessScriptWorkflowsFactory =
    HarnessScriptWorkflowsFactory { request, access ->
        HarnessScriptWorkflows(HarnessInstanceTarget(request, access), origins) { _, _, _, _ ->
            error("No workflow launch expected")
        }
    }
