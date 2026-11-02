workflow { input ->
    val target = if ("issue_number" in input) {
        val value = input["issue_number"]
        require(value is JsonPrimitive && value.isString) { "issue_number must be a positive integer string" }
        val issueNumber = value.content.trim()
        require(issueNumber.matches(Regex("[1-9][0-9]*"))) { "issue_number must be a positive integer string" }
        "issue #$issueNumber"
    } else {
        "автоматический выбор следующей подходящей открытой issue"
    }
    val brief = skill(ItemName("process_issue")) + "\n\n" +
        prompt(ItemName("process_next"), mapOf("run_mode" to target))
    val report = agent(brief, AgentOptions("Heartbeat issue + e2e: $target"))
    buildJsonObject {
        put("target", target)
        put("report", report)
    }
}
