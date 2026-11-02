workflow { input ->
    val issueValue = input["issue_number"]
    val target = if (issueValue == null) {
        "автоматический выбор следующей подходящей открытой issue"
    } else {
        require(issueValue is JsonPrimitive && issueValue.isString) {
            "issue_number must be a string containing a positive issue number"
        }
        val issueNumber = issueValue.content.trim()
        require(issueNumber.matches(Regex("[1-9][0-9]*")) && issueNumber.toLongOrNull() != null) {
            "issue_number must be a positive issue number; omit it for automatic selection"
        }
        "issue #$issueNumber"
    }
    val brief = skill(ItemName("process_issue")) + "\n\n" +
        prompt(ItemName("process_next"), mapOf("run_mode" to target))
    val report = agent(brief, AgentOptions("Heartbeat issue + e2e: $target"))
    buildJsonObject {
        put("target", target)
        put("report", report)
    }
}
