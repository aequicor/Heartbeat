package io.aequicor.heartbeat.feature.harness.api

/** Stable hosted tool names shared by context delivery, authoring and the chat host. */
public object HarnessTools {
    public const val CONTEXT: String = "harness_context"
    public const val SKILL_LOAD: String = "harness_skill_load"
    public const val PROMPT_GET: String = "harness_prompt_get"

    public const val CREATE: String = "harness_create"
    public const val UPDATE: String = "harness_update"
    public const val DELETE: String = "harness_delete"
    public const val ATTACH: String = "harness_attach"
    public const val DETACH: String = "harness_detach"
    public const val ITEM_PUT: String = "harness_item_put"
    public const val ITEM_DELETE: String = "harness_item_delete"
    public const val TOOLS_SET: String = "harness_tools_set"

    public const val LIST: String = "harness_list"
    public const val GET: String = "harness_get"
    public const val TOOLS_CATALOG: String = "harness_tools_catalog"
    public const val API_REFERENCE: String = "harness_api_reference"

    public const val WORKFLOW_START: String = "harness_workflow_start"
    public const val WORKFLOW_STATUS: String = "harness_workflow_status"
    public const val WORKFLOW_CANCEL: String = "harness_workflow_cancel"

    /** Name prefix of script-owned tools: hs_<harness slug>_<tool>. */
    public const val SCRIPT_PREFIX: String = "hs_"
}
